package com.zhzc0x.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import com.zhzc0x.bluetooth.client.BleClient
import com.zhzc0x.bluetooth.client.ClassicClient
import com.zhzc0x.bluetooth.client.Client
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.client.Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

class CoroutineClient(private val context: Context, type: ClientType, serviceUUID: UUID) {

    private val logTag = CoroutineClient::class.java.name
    private val client: Client
    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ?: throw RuntimeException("当前设备不支持蓝牙功能！")
    private var scanDeviceChannel: SendChannel<Device>? = null
    private var connectStateChannel: SendChannel<ConnectState>? = null
    private var receiveDataChannel: SendChannel<ByteArray>? = null

    init {
        client = when(type){
            ClientType.CLASSIC -> ClassicClient(context, bluetoothAdapter, serviceUUID, logTag)
            ClientType.BLE -> BleClient(context, bluetoothAdapter, serviceUUID, logTag)
        }
        BluetoothHelper.registerSwitchStateReceiver(context, stateOn = {

        }, stateOff=::disconnect)
    }

   suspend fun startScan(timeMillis: Long): Flow<Device>{
       if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
           return emptyFlow()
       }
       return channelFlow {
           scanDeviceChannel = channel
           Timber.d("$logTag --> 开始扫描设备")
           client.startScan{ device ->
               Timber.d("$logTag --> Scan: $device")
               trySend(device)
           }
           delay(timeMillis)
           close()
           stopScan()
       }
    }

    fun stopScan() {
        scanDeviceChannel?.close()
        client.stopScan()
    }

    suspend fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 6000):
            Flow<ConnectState> = withContext(Dispatchers.IO){
        BluetoothHelper.checkMtuRange(mtu)
        if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
            return@withContext emptyFlow()
        }
        channelFlow{
            connectStateChannel = channel
            client.connect(device, mtu, timeoutMillis){ connectState ->
                this@withContext.launch {
                    send(connectState)
                }
            }
            awaitClose()
        }
    }

    suspend fun changeMtu(mtu: Int){
        BluetoothHelper.checkMtuRange(mtu)
        withContext(Dispatchers.IO){
            client.changeMtu(mtu)
        }
    }

    suspend fun supportedServices() = withContext(Dispatchers.IO){
        client.supportedServices()
    }

    suspend fun assignService(service: Service) = withContext(Dispatchers.IO){
        client.assignService(service)
    }

    suspend fun receiveData(uuid: UUID? = null): Flow<ByteArray> = withContext(Dispatchers.IO){
        channelFlow {
            receiveDataChannel = channel
            client.receiveData(uuid) { readData ->
                this@withContext.launch {
                    send(readData)
                }
            }
            awaitClose()
        }
    }

    suspend fun sendData(uuid: UUID? = null, data: ByteArray,
                         timeoutMillis: Long = 3000): Boolean = withContext(Dispatchers.IO){
        suspendCancellableCoroutine { continuation ->
            client.sendData(uuid, data, timeoutMillis){ success, _ ->
                if(!continuation.isCompleted){
                    continuation.resume(success)
                }
            }
            continuation.invokeOnCancellation {
                continuation.resume(false)
            }
        }
    }

    suspend fun readData(uuid: UUID? = null, data: ByteArray,
                         timeoutMillis: Long = 3000): ByteArray? = withContext(Dispatchers.IO){
        suspendCancellableCoroutine { continuation ->
            client.readData(uuid, timeoutMillis){ _, data ->
                if(!continuation.isCompleted){
                    continuation.resume(data)
                }
            }
            continuation.invokeOnCancellation {
                continuation.resume(null)
            }
        }
    }

    fun disconnect(){
        stopScan()
        client.disconnect()
        connectStateChannel?.close()
        receiveDataChannel?.close()
    }

    fun release(){
        disconnect()
        BluetoothHelper.unregisterSwitchStateReceiver(context)
        client.release()
    }

}