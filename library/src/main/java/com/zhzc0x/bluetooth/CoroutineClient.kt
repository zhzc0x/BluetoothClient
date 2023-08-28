package com.zhzc0x.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import com.zhzc0x.bluetooth.client.BleClient
import com.zhzc0x.bluetooth.client.ClassicClient
import com.zhzc0x.bluetooth.client.Client
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
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
        return callbackFlow {
            client.startScan{ device ->
                trySend(device)
            }
            delay(timeMillis)
            close()
            stopScan()
        }
    }

    fun stopScan(){
        client.stopScan()
    }

    suspend fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 10000):
            Flow<ConnectState> = withContext(Dispatchers.IO){
        if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
            return@withContext emptyFlow()
        }
        flow{
            client.connect(device, mtu, timeoutMillis){ connectState ->
                this@withContext.launch {
                    emit(connectState)
                }
            }
        }
    }

    suspend fun receiveData(readUUID: UUID? = null): Flow<ByteArray> = withContext(Dispatchers.IO){
        flow {
            client.receiveData(readUUID) { readData ->
                this@withContext.launch {
                    emit(readData)
                }
            }
        }
    }

    suspend fun sendData(uuid: UUID? = null, data: ByteArray,
                         timeoutMillis: Long = 3000): Boolean = withContext(Dispatchers.IO){
        suspendCancellableCoroutine { continuation ->
            client.sendData(uuid, data, timeoutMillis){ success ->
                if(!continuation.isCompleted){
                    continuation.resume(success)
                }
            }
            continuation.invokeOnCancellation {
                continuation.resume(false)
            }
        }
    }

    fun disconnect(){
        stopScan()
        client.disconnect()
        Timber.d("$logTag --> 主动 disconnect")
    }

    fun release(){
        stopScan()
        client.disconnect()
        BluetoothHelper.unregisterSwitchStateReceiver(context)
        client.release()
    }

}