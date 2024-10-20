package com.zhzc0x.bluetooth

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.widget.Toast
import androidx.annotation.CallSuper
import androidx.annotation.WorkerThread
import com.zhzc0x.bluetooth.client.BleClient
import com.zhzc0x.bluetooth.client.ClassicClient
import com.zhzc0x.bluetooth.client.Client
import com.zhzc0x.bluetooth.client.ClientState
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState

import com.zhzc0x.bluetooth.client.ConnectStateCallback
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.client.DataResultCallback
import com.zhzc0x.bluetooth.client.ScanDeviceCallback
import com.zhzc0x.bluetooth.client.Service
import timber.log.Timber
import java.lang.IllegalArgumentException
import java.util.UUID

open class BluetoothClient(private val context: Context, type: ClientType, serviceUUID: UUID?) {

    protected val logTag: String = this::class.java.simpleName
    private val client: Client
    private val bluetoothAdapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private val clientHandler: Handler by lazy {
        val ht = HandlerThread("clientHandler")
        ht.start()
        Handler(ht.looper)
    }
    private var turnOn: (() -> Unit)? = null
    private var turnOff: (() -> Unit)? = null
    private var onEndScan: (() -> Unit)? = null
    @Volatile
    protected var drivingDisconnect = false//是否主动断开
    protected var curReconnectCount = 0

    init {
        client = when(type) {
            ClientType.CLASSIC -> ClassicClient(context, bluetoothAdapter, serviceUUID, logTag)
            ClientType.BLE -> BleClient(context, bluetoothAdapter, serviceUUID, logTag)
        }
        BluetoothHelper.logTag = logTag
        BluetoothHelper.registerSwitchReceiver(context, turnOn = {
            turnOn?.invoke()
        }, turnOff={
            disconnect()
            turnOff?.invoke()
        })
    }

    /**
     * 检查设备蓝牙状态
     * @param isNext: true 如无蓝牙权限则继续请求权限，如设备蓝牙未开启则继续请求打开，如未开启定位开关（Android12以下需要）则前往设置；
     *                false 无操作
     * @return ClientState： NOT_SUPPORT, NO_PERMISSIONS, LOCATION_DISABLE, ENABLE, DISABLE
     * @see com.zhzc0x.bluetooth.client.ClientState
     * */
    fun checkState(isNext: Boolean = true): ClientState {
        val state = BluetoothHelper.checkState(context, bluetoothAdapter, false)
        if (!isNext) {
            return state
        }
        when (state) {
            ClientState.NO_PERMISSIONS -> {
                clientHandler.post { BluetoothHelper.requestPermissions(context) }
            }
            ClientState.DISABLE -> {
                clientHandler.post { switch(true) }
            }
            ClientState.LOCATION_DISABLE -> {
                (context as Activity).startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                Toast.makeText(context, "当前设备蓝牙服务需要开启定位服务！", Toast.LENGTH_SHORT).show()
            } 
            else -> {}
        }
        return state
    }
    
    private fun checkValid(): Boolean {
        val state = checkState(true)
        Timber.d("${BluetoothHelper.logTag} --> checkState: $state")
        return state == ClientState.ENABLE
    }

    /** 设置蓝牙开关状态通知 */
    fun setSwitchReceive(turnOn: () -> Unit, turnOff: () -> Unit) {
        this.turnOn = turnOn
        this.turnOff = turnOff
    }
    
    /**
     * 开关蓝牙
     * 此系统方法在 API 级别 33 中已弃用。从 Build.VERSION_CODES.TIRAMISU 开始，不允许应用程序启用/禁用蓝牙并总是返回false
     * */
    fun switch(enable: Boolean): Boolean {
        return if (bluetoothAdapter != null) {
            BluetoothHelper.switchBluetooth(context, bluetoothAdapter, enable,
                checkPermission = true)
        } else {
            false
        }
    }

    /**
     * 开始扫描设备
     * @param timeMillis：扫描时长
     * @param onEndScan：扫描结束回调
     * @param deviceCallback：ScanDeviceCallback.call(Device):
     * @See com.zhzc0x.bluetooth.client.Device
     * @See com.zhzc0x.bluetooth.client.ScanDeviceCallback
     *
     * */
    @JvmOverloads
    fun startScan(timeMillis: Long, onEndScan: (() -> Unit)? = null, deviceCallback: ScanDeviceCallback): Boolean {
        if (!checkValid()) {
            return false
        }
        this.onEndScan = onEndScan
        Timber.d("$logTag --> 开始扫描设备")
        client.startScan { device ->
            Timber.d("$logTag --> Scan: $device")
            clientHandler.post { deviceCallback.call(device) }
        }
        if (timeMillis > 0) {
            clientHandler.postDelayed(::stopScan, timeMillis)
        }
        return true
    }

    /**
     * 停止扫描设备
     *
     * */
    @CallSuper open fun stopScan() {
        if (onEndScan != null) {
            Timber.d("$logTag --> 停止扫描设备, ${Thread.currentThread().name}")
            client.stopScan()
            onEndScan?.invoke()
            onEndScan = null
        }
    }

    /**
     * 连接蓝牙设备
     * @param device: startScan返回的Device
     * @param mtu: IntRange(23..512)
     * @param timeoutMillis: 连接超时时间，默认6000ms，超时后回调ConnectState.CONNECT_TIMEOUT
     * @param reconnectCount: 失败重连次数，默认3次，0不重连
     * @param stateCallback: 回调ConnectState
     *
     * */
    @JvmOverloads
    fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 6000, reconnectCount: Int = 3,
                stateCallback: ConnectStateCallback): Boolean {
        if (!checkValid()) {
            return false
        }
        return clientHandler.post {
            client.connect(device, mtu, timeoutMillis) { state ->
                Timber.d("$logTag --> connectState: $state")
                if (state == ConnectState.DISCONNECTED && !drivingDisconnect) {
                    Timber.d("$logTag --> 被动 disconnect，可以尝试重连")
                    checkReconnect(device, mtu, timeoutMillis, reconnectCount, stateCallback, state)
                } else if (state == ConnectState.CONNECT_ERROR || state == ConnectState.CONNECT_TIMEOUT) {
                    checkReconnect(device, mtu, timeoutMillis, reconnectCount, stateCallback, state)
                } else if (state == ConnectState.CONNECTED) {
                    drivingDisconnect = false
                    curReconnectCount = 0
                    callConnectState(stateCallback, state)
                } else {
                    callConnectState(stateCallback, state)
                }
            }
        }
    }

    private fun checkReconnect(device: Device, mtu: Int, timeoutMillis: Long,
                               reconnectMaxCount: Int, stateCallback: ConnectStateCallback,
                               state: ConnectState) {
        if (reconnectMaxCount > 0) {
            if (curReconnectCount < reconnectMaxCount) {
                Timber.d("$logTag --> 开始重连count=${++curReconnectCount}")
                callConnectState(stateCallback, ConnectState.RECONNECT)
                connect(device, mtu, timeoutMillis, reconnectMaxCount, stateCallback)
            } else {
                Timber.d("$logTag --> 超过最大重连次数，停止重连！")
                callConnectState(stateCallback, state)
                disconnect()
            }
        } else {
            callConnectState(stateCallback, state)
        }
    }

    private fun callConnectState(stateCallback: ConnectStateCallback, state: ConnectState) =
        clientHandler.post { stateCallback.call(state) }

    /**
     * 修改mtu
     * @param mtu: IntRange(23..512)
     *
     * @return Boolean: true修改成功， false修改失败
     *
     * @throws IllegalArgumentException("The mtu value must be in the 23..512 range")
     * */
    fun changeMtu(mtu: Int): Boolean {
        BluetoothHelper.checkMtuRange(mtu)
        return if (client.changeMtu(mtu)) {
            Timber.d("$logTag --> mtu修改成功")
            true
        } else {
            Timber.d("$logTag --> mtu修改失败")
            false
        }
    }

    /**
     * 获取支持的 services
     *
     * @return List<Service>
     * @see com.zhzc0x.bluetooth.client.Service
     * */
    fun supportedServices() = client.supportedServices()

    /**
     * 指定 Service
     * @param service 通过supportedServices()方法返回的Service
     * @see com.zhzc0x.bluetooth.client.Service
     *
     * */
    fun assignService(service: Service) = client.assignService(service)

    /**
     * 设置写特征类型
     * @param type：默认-1不设置，其他值同 WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE, WRITE_TYPE_SIGNED
     * @see android.bluetooth.BluetoothGattCharacteristic
     *
     * */
    fun setWriteType(type: Int) {
        client.writeType = type
    }

    /**
     * 设置数据接收
     * @param uuid：低功耗蓝牙传入包含notify特征的uuid，经典蓝牙不需要传
     * @param onReceive(ByteArray)
     *
     * @return Boolean：true设置成功，false设置失败
     * */
    @JvmOverloads
    fun receiveData(uuid: UUID? = null, @WorkerThread onReceive: (ByteArray) -> Unit): Boolean {
        return client.receiveData(uuid) { readData ->
            clientHandler.post { onReceive(readData) }
        }
    }

    /**
     * 发送数据
     * @param uuid：低功耗蓝牙传入包含write特征的uuid，经典蓝牙不需要传
     * @param data: ByteArray
     * @param timeoutMillis: 发送超时时间，默认3000ms
     * @param resendCount: 失败重发次数，默认3次，0不重发
     * @param callback: 回调发送结果DataResultCallback.call(Boolean,ByteArray)
     * @see com.zhzc0x.bluetooth.client.DataResultCallback
     *
     * */
    @JvmOverloads
    fun sendData(uuid: UUID? = null, data: ByteArray, timeoutMillis: Long = 3000,
                 resendCount: Int = 3, callback: DataResultCallback) {
        sendData(uuid, data, timeoutMillis, 0, resendCount, callback)
    }

    private fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, resendCount: Int,
                         resendMaxCount: Int, callback: DataResultCallback) = clientHandler.post {
        client.sendData(uuid, data, timeoutMillis) { success, _ ->
            if (!success) {
                Timber.d("$logTag --> sendData failed: ${String(data)}")
                checkResend(uuid, data, timeoutMillis, resendCount, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> sendData success: ${String(data)}")
                callback.call(true, data)
            }
        }
    }

    private fun checkResend(uuid: UUID?, data: ByteArray, timeoutMillis: Long, resendCount: Int,
                              resendMaxCount: Int, callback: DataResultCallback) {
        if (resendMaxCount > 0) {
            if (resendCount < resendMaxCount) {
                Timber.d("$logTag --> 开始重发count=${resendCount + 1}")
                sendData(uuid, data, timeoutMillis, resendCount + 1, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> 超过最大重发次数，停止重发！")
                callback.call(false, data)
            }
        } else {
            callback.call(false, data)
        }
    }

    /**
     * 读取数据
     * @param uuid：低功耗蓝牙传入包含read特征的uuid，经典蓝牙不需要传
     * @param timeoutMillis: 读取超时时间，默认3000ms
     * @param rereadCount: 失败重读次数，默认3次，0不重读
     * @param callback: 回调读取结果DataResultCallback.call(Boolean,ByteArray)
     * @see com.zhzc0x.bluetooth.client.DataResultCallback
     *
     * */
    @JvmOverloads
    fun readData(uuid: UUID? = null, timeoutMillis: Long = 3000, rereadCount: Int = 3,
                 callback: DataResultCallback) {
        readData(uuid, timeoutMillis, 0, rereadCount, callback)
    }

    private fun readData(uuid: UUID?, timeoutMillis: Long, rereadCount: Int,
                         resendMaxCount: Int, callback: DataResultCallback) = clientHandler.post {
        client.readData(uuid, timeoutMillis) { success, data ->
            if (!success) {
                Timber.d("$logTag --> readData failed")
                checkReread(uuid, timeoutMillis, rereadCount, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> readData success: ${String(data!!)}")
                callback.call(true, data)
            }
        }
    }

    private fun checkReread(uuid: UUID?, timeoutMillis: Long, rereadCount: Int,
                              resendMaxCount: Int, callback: DataResultCallback) {
        if (resendMaxCount > 0) {
            if (rereadCount < resendMaxCount) {
                Timber.d("$logTag --> 开始重读count=${rereadCount + 1}")
                readData(uuid, timeoutMillis, rereadCount + 1, resendMaxCount, callback)
            } else {
                Timber.d("$logTag --> 超过最大重读次数，停止重读！")
                callback.call(false, null)
            }
        } else {
            callback.call(false, null)
        }
    }

    /**
     * 断开蓝牙设备
     * */
    @CallSuper open fun disconnect() {
        stopScan()
        drivingDisconnect = true
        clientHandler.removeCallbacksAndMessages(null)
        client.disconnect()
        Timber.d("$logTag --> 主动 disconnect")
    }

    /**
     * 释放资源
     * */
    fun release() {
        disconnect()
        BluetoothHelper.unregisterSwitchReceiver(context)
        client.release()
    }
}
