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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.text.TextUtils
import timber.log.Timber
import java.util.Timer
import java.util.TimerTask
import java.util.UUID

/**
 * 经典蓝牙
 * */
@SuppressLint("MissingPermission")
internal class BleClient(override val context: Context,
                         override val bluetoothAdapter: BluetoothAdapter,
                         override var serviceUUID: UUID?,
                         override val logTag: String) : Client {

    private lateinit var scanDeviceCallback: ScanDeviceCallback
    private lateinit var connectStateCallback: ConnectStateCallback
    private val receiveDataMap = HashMap<UUID, (ByteArray) -> Unit>()
    private val sendDataMap = HashMap<ByteArray, ResultCallback>()
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
        bluetoothAdapter.bluetoothLeScanner.startScan(null, ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
    }

    override fun stopScan() {
        bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                var mtuResult = false
                if(mtu > 0){
                    mtuResult = bluetoothGatt!!.requestMtu(mtu)
                    Timber.d("$logTag --> requestMtu($mtu)=$mtuResult")
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
                if(serviceUUID == null){
                    cancelTimeoutTask()
                    connectStateCallback.call(ConnectState.CONNECTED)
                    return
                }
                val gattService = bluetoothGatt!!.getService(serviceUUID)
                cancelTimeoutTask()
                if(gattService != null){
                    connectStateCallback.call(ConnectState.CONNECTED)
                } else {
                    Timber.e("$logTag --> onServicesDiscovered: getService($serviceUUID)=null")
                    connectStateCallback.call(ConnectState.CONNECT_ERROR)
                }
            } else {
                Timber.e("$logTag --> onServicesDiscovered: status=$status")
                cancelTimeoutTask()
                connectStateCallback.call(ConnectState.CONNECT_ERROR)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt,
                                             characteristic: BluetoothGattCharacteristic) {
            receiveDataMap[characteristic.uuid]?.invoke(characteristic.value)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            Timber.d("$logTag --> onCharacteristicWrite: value=${String(characteristic.value)}, status=$status")
            sendDataMap.remove(characteristic.value)?.call(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            Timber.d("$logTag --> onMtuChanged: status=${status}, mtu=$mtu")
            discoverServices()
        }
    }

    private fun discoverServices(){
        if(!bluetoothGatt!!.discoverServices()){
            Timber.e("$logTag --> discoverServices=false")
            cancelTimeoutTask()
            connectStateCallback.call(ConnectState.CONNECT_ERROR)
        }
    }

    override fun connect(device: Device, mtu: Int, timeoutMillis: Long, stateCallback: ConnectStateCallback) {
        this.mtu = mtu
        connectStateCallback = stateCallback
        connectStateCallback.call(ConnectState.CONNECTING)
        scheduleTimeoutTask(timeoutMillis){
            connectStateCallback.call(ConnectState.CONNECT_TIMEOUT)
        }
        val realDevice = bluetoothAdapter.getRemoteDevice(device.address)
        bluetoothGatt!!.services
        realDevice.uuids
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

    override fun supportedServices(): List<Service>? {
        return bluetoothGatt?.services?.map { getGattService ->
            Service(getGattService.uuid, Service.typeOf(getGattService.type),
                getGattService.characteristics.map {
                    Characteristic(it.uuid, Characteristic.propertyOf(it.properties))
                },getGattService.includedServices.map { includedService ->
                    Service(includedService.uuid, Service.typeOf(includedService.type),
                        includedService.characteristics.map {
                            Characteristic(it.uuid, Characteristic.propertyOf(it.properties))
                        },null)
                })
        }
    }

    override fun assignService(service: Service){
        serviceUUID = service.uuid
    }

    private fun scheduleTimeoutTask(timeoutMillis: Long, onTask: () -> Unit){
        cancelTimeoutTask()
        timeoutTask = object: TimerTask(){
            override fun run() {
                cancelTimeoutTask()
                onTask()
            }
        }
        timer.schedule(timeoutTask, timeoutMillis)
    }

    private fun cancelTimeoutTask(){
        timeoutTask?.cancel()
        timeoutTask = null
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
            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            bluetoothGatt!!.writeDescriptor(it)
        }
        return notificationResult
    }

    override fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, callback: ResultCallback) {
        val gattService = getGattService()
        if(gattService == null){
            callback.call(false)
            return
        }
        sendDataMap[data] = callback
        scheduleTimeoutTask(timeoutMillis){
            Timber.e("$logTag --> sendData timeout")
            sendDataMap.remove(data)?.call(false)
        }
        val writeCharacteristic = gattService.getCharacteristic(uuid)
        if(writeCharacteristic != null){
            writeCharacteristic.value = data
            if(!bluetoothGatt!!.writeCharacteristic(writeCharacteristic)){
                Timber.e("$logTag --> sendData: writeCharacteristic=false")
                sendDataMap.remove(data)?.call(false)
            }
        } else {
            Timber.e("$logTag --> sendData: getCharacteristic($uuid)=null")
            sendDataMap.remove(data)?.call(false)
        }
    }

    override fun disconnect() {
        if(bluetoothGatt != null){
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            receiveDataMap.clear()
            sendDataMap.clear()
            cancelTimeoutTask()
            connectStateCallback.call(ConnectState.DISCONNECTED)
        }
    }

    override fun release() {
        disconnect()
        timer.cancel()
    }
}