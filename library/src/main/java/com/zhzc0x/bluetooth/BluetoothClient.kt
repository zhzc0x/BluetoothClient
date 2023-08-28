package com.zhzc0x.bluetooth

import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.WorkerThread
import com.zhzc0x.bluetooth.client.BleClient
import com.zhzc0x.bluetooth.client.ClassicClient
import com.zhzc0x.bluetooth.client.Client
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState

import com.zhzc0x.bluetooth.client.ConnectStateCallback
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.client.ResultCallback
import com.zhzc0x.bluetooth.client.ScanDeviceCallback
import com.zhzc0x.bluetooth.client.Service
import timber.log.Timber
import java.util.UUID

class BluetoothClient(private val context: Context, type: ClientType, serviceUUID: UUID?) {

    private val logTag = BluetoothClient::class.java.simpleName
    private val client: Client

    private val bluetoothAdapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        ?: throw RuntimeException("当前设备不支持蓝牙功能！")
    private val clientHandler: Handler by lazy {
        val ht = HandlerThread("clientHandler")
        ht.start()
        Handler(ht.looper)
    }
    private var scanDeviceCallback: ScanDeviceCallback? = null
    private var onStopScan: (() -> Unit)? = null
    private var scanTimeMillis: Long = 0
    @Volatile
    private var drivingDisconnect = false//是否主动断开

    init {
        client = when(type){
            ClientType.CLASSIC -> ClassicClient(context, bluetoothAdapter, serviceUUID, logTag)
            ClientType.BLE -> BleClient(context, bluetoothAdapter, serviceUUID, logTag)
        }
        BluetoothHelper.registerSwitchStateReceiver(context, stateOn = {
            if(scanDeviceCallback != null){
                startScan(scanTimeMillis, onStopScan, scanDeviceCallback!!)
            }
        }, stateOff=::disconnect)
    }

    fun startScan(timeMillis: Long, onStopScan: (() -> Unit)? = null, deviceCallback: ScanDeviceCallback){
        scanTimeMillis = timeMillis
        this.onStopScan = onStopScan
        scanDeviceCallback = deviceCallback
        if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
            return
        }
        Timber.d("$logTag --> 开始扫描设备")
        client.startScan{ device ->
            Timber.d("$logTag --> Scan: $device")
            clientHandler.post { scanDeviceCallback?.call(device) }
        }
        clientHandler.postDelayed(::stopScan, timeMillis)
    }

    fun stopScan(){
        if(scanDeviceCallback != null){
            Timber.d("$logTag --> 停止扫描设备")
            client.stopScan()
            onStopScan?.invoke()
            onStopScan = null
            scanDeviceCallback = null
        }
    }

    fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 10000, reconnectCount: Int = 3,
                stateCallback: ConnectStateCallback){
        if(!BluetoothHelper.checkBluetoothValid(context, bluetoothAdapter)){
            return
        }
        connect(device, mtu, timeoutMillis, 0, reconnectCount, stateCallback)
    }

    private fun connect(device: Device, mtu: Int, timeoutMillis: Long, reconnectCount: Int,
                        reconnectMaxCount: Int, stateCallback: ConnectStateCallback) = clientHandler.post {
        client.connect(device, mtu, timeoutMillis){ state ->
            Timber.d("$logTag --> connectState: $state")
            if(state == ConnectState.DISCONNECTED && !drivingDisconnect){
                Timber.d("$logTag --> 被动 disconnect，可以尝试重连")
                checkToReconnect(device, mtu, timeoutMillis, reconnectCount, reconnectMaxCount, stateCallback, state)
            } else if(state == ConnectState.CONNECT_ERROR || state == ConnectState.CONNECT_TIMEOUT){
                checkToReconnect(device, mtu, timeoutMillis, reconnectCount, reconnectMaxCount, stateCallback, state)
            } else if(state == ConnectState.CONNECTED){
                drivingDisconnect = false
                stateCallback.call(state)
            }
        }
    }

    private fun checkToReconnect(device: Device, mtu: Int, timeoutMillis: Long, reconnectCount: Int,
                                 reconnectMaxCount: Int, stateCallback: ConnectStateCallback, state: ConnectState){
        if(reconnectMaxCount > 0){
            if(reconnectCount < reconnectMaxCount){
                Timber.d("$logTag --> 开始重连reconnectCount=${reconnectCount + 1}")
                stateCallback.call(ConnectState.RECONNECT)
                connect(device, mtu, timeoutMillis, reconnectCount + 1, reconnectMaxCount, stateCallback)
            } else {
                Timber.d("$logTag --> 超过最大重连次数，停止重连！")
                stateCallback.call(state)
            }
        } else {
            stateCallback.call(state)
        }
    }

    fun supportedServices() = client.supportedServices()

    fun assignService(service: Service) = client.assignService(service)

    fun receiveData(uuid: UUID? = null, @WorkerThread onReceive: (ByteArray) -> Unit): Boolean {
        return client.receiveData(uuid) { readData ->
            clientHandler.post { onReceive(readData) }
        }
    }

    fun sendData(uuid: UUID? = null, data: ByteArray, timeoutMillis: Long = 3000,
                 resendCount: Int = 3, callback: ResultCallback) {
        sendData(uuid, data, timeoutMillis, 0, resendCount, callback)
    }

    private fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, resendCount: Int,
                         resendMaxCount: Int, callback: ResultCallback) = clientHandler.post {
        client.sendData(uuid, data, timeoutMillis){ success ->
            if(!success){
                Timber.d("$logTag --> sendData failed: ${String(data)}")
                checkToResend(uuid, data, timeoutMillis, resendCount, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> sendData success: ${String(data)}")
                callback.call(true)
            }
        }
    }

    private fun checkToResend(uuid: UUID?, data: ByteArray, timeoutMillis: Long, resendCount: Int,
                              resendMaxCount: Int, callback: ResultCallback){
        if(resendMaxCount > 0){
            if(resendCount < resendMaxCount){
                Timber.d("$logTag --> 开始重发resend=${resendCount + 1}")
                sendData(uuid, data, timeoutMillis, resendCount + 1, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> 超过最大重发次数，停止重发！")
                callback.call(false)
            }
        } else {
            callback.call(false)
        }
    }

    fun disconnect(){
        stopScan()
        drivingDisconnect = true
        client.disconnect()
        clientHandler.removeCallbacksAndMessages(null)
        Timber.d("$logTag --> 主动 disconnect")
    }

    fun release(){
        stopScan()
        client.disconnect()
        clientHandler.removeCallbacksAndMessages(null)
        BluetoothHelper.unregisterSwitchStateReceiver(context)
        client.release()
    }

}