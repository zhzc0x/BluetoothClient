package com.zhzc0x.bluetooth.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.text.TextUtils
import timber.log.Timber
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

/**
 * 经典蓝牙
 * */
@SuppressLint("MissingPermission")
internal class BleClient(override val context: Context,
                         override val bluetoothAdapter: BluetoothAdapter?,
                         override var serviceUUID: UUID?,
                         override val logTag: String) : Client {

    override var writeType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    } else {
        -1
    }

    private lateinit var scanDeviceCallback: ScanDeviceCallback
    private lateinit var connectStateCallback: ConnectStateCallback
    private val receiveDataMap = HashMap<UUID, (ByteArray) -> Unit>()
    private val writeDataMap = HashMap<ByteArray, DataResultCallback>()
    private val readDataMap = HashMap<UUID, DataResultCallback>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var mtu = 0
    private val timer = Timer()
    private var timeoutTask: TimerTask? = null

    private val scanCallback = object: ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            if(TextUtils.isEmpty(device?.name)){
                return
            }
            scanDeviceCallback.call(Device(device.address, device.name, Device.typeOf(device.type)))
        }
    }

    override fun startScan(callback: ScanDeviceCallback) {
        scanDeviceCallback = callback
        bluetoothAdapter!!.bluetoothLeScanner!!.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    override fun stopScan() {
        if(bluetoothAdapter != null){
            bluetoothAdapter.bluetoothLeScanner?.stopScan(scanCallback)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        private var discoveredServices = false
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                discoveredServices = false
                var mtuResult = false
                if(mtu > 0){
                    mtuResult = changeMtu(mtu)
                }
                if(!mtuResult){
                    discoverServices()
                }
            } else if(newState == BluetoothProfile.STATE_DISCONNECTED) {
                disconnect()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if(status == BluetoothGatt.GATT_SUCCESS){
                discoveredServices = true
                if(serviceUUID == null){
                    callConnectState(ConnectState.CONNECTED)
                    return
                }
                val gattService = bluetoothGatt!!.getService(serviceUUID)
                if(gattService != null){
                    callConnectState(ConnectState.CONNECTED)
                } else {
                    Timber.e("$logTag --> onServicesDiscovered: getService($serviceUUID)=null")
                    callConnectState(ConnectState.CONNECT_ERROR)
                }
            } else {
                Timber.e("$logTag --> onServicesDiscovered: status=$status")
                callConnectState(ConnectState.CONNECT_ERROR)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            receiveDataMap[characteristic.uuid]?.invoke(characteristic.value)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic,
                                             value: ByteArray) {
            receiveDataMap[characteristic.uuid]?.invoke(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt,
                                           characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Timber.d("$logTag --> onCharacteristicWrite: value=${String(value)}, status=$status")
                callSendDataResult(value, status == BluetoothGatt.GATT_SUCCESS)
            } else {
                //api33及以上获取不到value
                Timber.d("$logTag --> onCharacteristicWrite: value=${value ?: null}, status=$status")
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(gatt: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            val value = characteristic.value
            Timber.d("$logTag --> onCharacteristicRead: value=${value.contentToString()}, status=$status")
            callReadDataResult(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS, value)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt,
                                          characteristic: BluetoothGattCharacteristic,
                                          value: ByteArray, status: Int) {
            Timber.d("$logTag --> onCharacteristicRead2: value=${value.contentToString()}, status=$status")
            callReadDataResult(characteristic.uuid, status == BluetoothGatt.GATT_SUCCESS, value)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.d("$logTag --> onMtuChanged: status=${status}, mtu=$mtu")
            if(!discoveredServices){
                discoverServices()
            }
        }
    }

    private fun discoverServices(){
        if(!bluetoothGatt!!.discoverServices()){
            Timber.e("$logTag --> discoverServices=false")
            callConnectState(ConnectState.CONNECT_ERROR)
        }
    }

    override fun connect(device: Device, mtu: Int, timeoutMillis: Long, stateCallback: ConnectStateCallback) {
        this.mtu = mtu
        connectStateCallback = stateCallback
        connectStateCallback.call(ConnectState.CONNECTING)
        scheduleTimeoutTask(timeoutMillis){
            callConnectState(ConnectState.CONNECT_TIMEOUT)
        }
        val realDevice = bluetoothAdapter!!.getRemoteDevice(device.address)
        bluetoothGatt?.safeClose()
        bluetoothGatt = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                realDevice.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                realDevice.connectGatt(context, false, gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            }
            else -> {
                realDevice.connectGatt(context, false, gattCallback)
            }
        }
    }

    private fun callConnectState(state: ConnectState){
        cancelTimeoutTask()
        if(state == ConnectState.CONNECT_TIMEOUT || state == ConnectState.CONNECT_ERROR){
            bluetoothGatt?.safeClose()
        }
        connectStateCallback.call(state)
    }

    override fun changeMtu(mtu: Int): Boolean {
        this.mtu = mtu
        val mtuResult = bluetoothGatt?.requestMtu(this.mtu) == true
        Timber.d("$logTag --> requestMtu($mtu)=$mtuResult")
        return mtuResult
    }

    override fun supportedServices(): List<Service>? {
        return bluetoothGatt?.services?.map { getGattService ->
            Service(getGattService.uuid, Service.typeOf(getGattService.type),
                getGattService.characteristics.map {
                    Characteristic(it.uuid, Characteristic.getProperties(it.properties), it.permissions)
                },getGattService.includedServices.map { includedService ->
                    Service(includedService.uuid, Service.typeOf(includedService.type),
                        includedService.characteristics.map {
                            Characteristic(it.uuid, Characteristic.getProperties(it.properties), it.permissions)
                        },null)
                })
        }
    }

    override fun assignService(service: Service){
        serviceUUID = service.uuid
    }

    private fun getGattService(): BluetoothGattService?{
        if(bluetoothGatt == null){
            Timber.e("$logTag --> 设备未连接!")
            return null
        }
        if(serviceUUID == null){
            Timber.e("$logTag --> 未设置serviceUUID!")
            return null
        }
        val gattService = bluetoothGatt!!.getService(serviceUUID)
        if(gattService == null){
            Timber.e("$logTag --> getService($serviceUUID)=null!")
            return null
        }
        return gattService
    }

    override fun receiveData(uuid: UUID?, onReceive: (ByteArray) -> Unit): Boolean{
        val gattService = getGattService() ?: return false
        receiveDataMap[uuid!!] = onReceive
        val readCharacteristic = gattService.getCharacteristic(uuid)
        if(readCharacteristic == null){
            Timber.e("$logTag --> receiveData: getCharacteristic($uuid)=null")
            return  false
        }
        val notificationResult = bluetoothGatt!!.setCharacteristicNotification(readCharacteristic, true)
        Timber.d("$logTag --> receiveData: notificationResult=$notificationResult")
        readCharacteristic.descriptors.forEach {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                bluetoothGatt!!.writeDescriptor(it)
            } else {
                bluetoothGatt!!.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            }
        }
        return notificationResult
    }

    override fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, callback: DataResultCallback) {
        val gattService = getGattService()
        if(gattService == null){
            callback.call(false, data)
            return
        }
        writeDataMap[data] = callback
        scheduleTimeoutTask(timeoutMillis){
            Timber.e("$logTag --> sendData timeout")
            callSendDataResult(data, false)
        }
        val writeCharacteristic = gattService.getCharacteristic(uuid)
        if(writeCharacteristic != null){
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                writeCharacteristic.value = data
                if(writeType != -1){
                    writeCharacteristic.writeType = writeType
                }
                @Suppress("DEPRECATION")
                if(!bluetoothGatt!!.writeCharacteristic(writeCharacteristic)){
                    Timber.e("$logTag --> sendData: writeCharacteristic=false")
                    callSendDataResult(data, false)
                }
            } else {
                val result = bluetoothGatt!!.writeCharacteristic(writeCharacteristic, data, writeType)
                Timber.e("$logTag --> sendData: writeCharacteristic=$result")
                if(result == BluetoothStatusCodes.SUCCESS){
                    callSendDataResult(data, true)
                } else {
                    callSendDataResult(data, false)
                }
            }
        } else {
            Timber.e("$logTag --> sendData: getCharacteristic($uuid)=null")
            callSendDataResult(data, false)
        }
    }

    private fun callSendDataResult(data: ByteArray, success: Boolean){
        cancelTimeoutTask()
        writeDataMap.remove(data)?.call(success, data)
    }

    override fun readData(uuid: UUID?, timeoutMillis: Long, callback: DataResultCallback) {
        val gattService = getGattService()
        if(gattService == null){
            callback.call(false, null)
            return
        }
        readDataMap[uuid!!] = callback
        scheduleTimeoutTask(timeoutMillis){
            Timber.e("$logTag --> readData timeout")
            callReadDataResult(uuid, false)
        }
        val readCharacteristic = gattService.getCharacteristic(uuid)
        if(readCharacteristic != null){
            if(!bluetoothGatt!!.readCharacteristic(readCharacteristic)){
                Timber.e("$logTag --> readData: readCharacteristic=false")
                callReadDataResult(uuid, false)
            }
        } else {
            Timber.e("$logTag --> readData: getCharacteristic($uuid)=null")
            callReadDataResult(uuid, false)
        }
    }

    private fun callReadDataResult(uuid: UUID, success: Boolean, data: ByteArray? = null){
        cancelTimeoutTask()
        readDataMap.remove(uuid)?.call(success, data)
    }

    private fun scheduleTimeoutTask(timeoutMillis: Long, onTask: () -> Unit){
        cancelTimeoutTask()
        timeoutTask = object: TimerTask(){
            override fun run() {
                onTask()
            }
        }
        timer.schedule(timeoutTask, timeoutMillis)
    }

    private fun cancelTimeoutTask(){
        timeoutTask?.cancel()
        timeoutTask = null
    }

    override fun disconnect() {
        if(bluetoothGatt != null){
            bluetoothGatt?.safeClose()
            bluetoothGatt = null
            receiveDataMap.clear()
            writeDataMap.clear()
            callConnectState(ConnectState.DISCONNECTED)
        }
    }

    private fun BluetoothGatt.safeClose() = try {
        disconnect()
        close()
    } catch (_: IOException){

    }

    override fun release() {
        disconnect()
        timer.cancel()
    }
}