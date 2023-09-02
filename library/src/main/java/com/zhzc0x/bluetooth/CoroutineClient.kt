package com.zhzc0x.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.zhzc0x.bluetooth.client.BleClient
import com.zhzc0x.bluetooth.client.ClassicClient
import com.zhzc0x.bluetooth.client.Client
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.client.Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

class CoroutineClient(private val context: Context, type: ClientType, serviceUUID: UUID?) {

    private val logTag = CoroutineClient::class.java.name
    private val client: Client
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanDeviceChannel: SendChannel<Device>? = null
    private var connectStateChannel: SendChannel<ConnectState>? = null
    private var receiveDataChannel: SendChannel<ByteArray>? = null
    private var onEndScan: (() -> Unit)? = null

    init {
        client = when(type){
            ClientType.CLASSIC -> ClassicClient(context, bluetoothAdapter, serviceUUID, logTag)
            ClientType.BLE -> BleClient(context, bluetoothAdapter, serviceUUID, logTag)
        }
        BluetoothHelper.registerSwitchStateReceiver(context, stateOn = {

        }, stateOff=::disconnect)
    }

    fun supported() = bluetoothAdapter != null

   suspend fun startScan(timeMillis: Long, onEndScan: (() -> Unit)? = null): Flow<Device>{
       if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
           return emptyFlow()
       }
       this.onEndScan = onEndScan
       return channelFlow {
           scanDeviceChannel = channel
           Timber.d("$logTag --> 开始扫描设备")
           client.startScan{ device ->
               coroutineScope.launch {
                   Timber.d("$logTag --> Scan: $device")
                   send(device)
               }
           }
           delay(timeMillis)
           withContext(Dispatchers.Main) {
               stopScan()
           }
       }.flowOn(Dispatchers.IO)
    }

    fun stopScan() {
        if(onEndScan != null){
            Timber.d("$logTag --> 停止扫描设备 ${Thread.currentThread().name}")
            scanDeviceChannel?.close()
            client.stopScan()
            onEndScan?.invoke()
            onEndScan = null
        }
    }

    suspend fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 6000): Flow<ConnectState>{
        BluetoothHelper.checkMtuRange(mtu)
        if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
            return emptyFlow()
        }
        return channelFlow{
            connectStateChannel = channel
            client.connect(device, mtu, timeoutMillis, ::trySend)
            awaitClose()
        }.flowOn(Dispatchers.IO)
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

    suspend fun receiveData(uuid: UUID? = null): Flow<ByteArray>{
        return channelFlow {
            receiveDataChannel = channel
            client.receiveData(uuid){ data ->
                coroutineScope.launch {
                    send(data)
                }
            }
            awaitClose()
        }.flowOn(Dispatchers.IO)
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

    suspend fun readData(uuid: UUID? = null,
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