package com.zhzc0x.bluetooth

import android.content.Context
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

class CoroutineClient(
    context: Context,
    type: ClientType,
    serviceUUID: UUID? = null
): BluetoothClient(context, type, serviceUUID) {

    private var scanDeviceChannel: SendChannel<Device>? = null
    private var connectStateChannel: SendChannel<ConnectState>? = null

    fun startScan(timeMillis: Long, onEndScan: (() -> Unit)? = null): Flow<Device> {
        return channelFlow {
            if (startScan(0, onEndScan, ::trySend)) {
                scanDeviceChannel = channel
                launch {
                    delay(timeMillis)
                    withContext(Dispatchers.Main) {
                        stopScan()
                    }
                }
                awaitClose {
                    stopScan()
                    Timber.d("$logTag --> scanFlow closed")
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun stopScan() {
        super.stopScan()
        if (scanDeviceChannel != null) {
            scanDeviceChannel?.close()
            scanDeviceChannel = null
        }
    }

    fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 6000,
                reconnectCount: Int = 3): Flow<ConnectState> {
        curReconnectCount = 0
        val connectFlow = channelFlow {
            if (connect(device, mtu, timeoutMillis, 0) { state ->
                    if (reconnectCount > 0) {
                        checkReconnect(state, reconnectCount)
                    } else {
                        trySend(state)
                    }
                }) {
                connectStateChannel = channel
                awaitClose {
                    Timber.d("$logTag --> connectFlow closed")
                }
            }
        }.flowOn(Dispatchers.IO)
        return if (reconnectCount > 0) {
            connectFlow.retryWhen { _, _ ->
                Timber.d("$logTag --> 开始重连count=$curReconnectCount")
                true
            }
        } else {
            connectFlow
        }
    }

    private fun ProducerScope<ConnectState>.checkReconnect(state: ConnectState, reconnectCount: Int) {
        if ((state == ConnectState.DISCONNECTED && !drivingDisconnect) ||
            state == ConnectState.CONNECT_ERROR || state == ConnectState.CONNECT_TIMEOUT) {
            if (curReconnectCount >= reconnectCount) {
                Timber.d("$logTag --> 超过最大重连次数，停止重连！")
                trySend(state)
                disconnect()
            } else {
                trySend(ConnectState.RECONNECT)
                close(RuntimeException("设备连接异常: $state, 尝试重连${++curReconnectCount}"))
            }
        } else if (state == ConnectState.CONNECTED) {
            curReconnectCount = 0
            trySend(state)
        } else if (state == ConnectState.DISCONNECTED) {
            trySend(state)
            connectStateChannel?.close()
        } else {
            trySend(state)
        }
    }

    fun receiveData(uuid: UUID? = null): Flow<ByteArray> {
        return channelFlow {
            if (receiveData(uuid, ::trySend)) {
                awaitClose {
                    Timber.d("$logTag --> receiveDataFlow closed")
                    cancelReceive(uuid)
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    suspend fun sendData(uuid: UUID? = null, data: ByteArray,
                         timeoutMillis: Long = 3000): Boolean = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            sendData(uuid, data, timeoutMillis, 0) { success, _ ->
                if (!continuation.isCompleted) {
                    continuation.resume(success)
                }
            }
            continuation.invokeOnCancellation {
                continuation.resume(false)
            }
        }
    }

    suspend fun readData(uuid: UUID? = null,
                         timeoutMillis: Long = 3000): ByteArray? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            readData(uuid, timeoutMillis, 0) { _, data ->
                if(!continuation.isCompleted) {
                    continuation.resume(data)
                }
            }
            continuation.invokeOnCancellation {
                continuation.resume(null)
            }
        }
    }
}
