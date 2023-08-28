package com.zhzc0x.bluetooth.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.text.TextUtils
import timber.log.Timber
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.UUID
import kotlin.concurrent.thread

/**
 * 经典蓝牙
 * */
@SuppressLint("MissingPermission")
internal class ClassicClient(override val context: Context,
                             override val bluetoothAdapter: BluetoothAdapter,
                             override var serviceUUID: UUID?,
                             override val logTag: String) : Client {

    private lateinit var scanDeviceCallback: ScanDeviceCallback
    private lateinit var connectStateCallback: ConnectStateCallback
    private var receiverFilter: IntentFilter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var mtu = 0
    private var realDevice: BluetoothDevice? = null
    private val timer = Timer()
    private var timeoutTask: TimerTask? = null

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Timber.d("$logTag --> stateReceiver: ${intent.action}")
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if(device == null){
                        return
                    }
                    scanDeviceCallback.call(Device(device.address, device.name, Device.typeOf(device.type)))
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    disconnect()
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                    Timber.d("$logTag --> stateReceiver, bondState: $bondState")
                    when (bondState) {
                        BluetoothDevice.BOND_BONDED -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            } ?: return
                            connect(device)
                        }
                        BluetoothDevice.BOND_NONE -> {
                            Timber.d("$logTag --> 配对取消")
                        }
                    }
                }
            }
        }
    }

    override fun startScan(callback: ScanDeviceCallback) {
        scanDeviceCallback = callback
        registerStateReceiver()
        bluetoothAdapter.startDiscovery()
        bluetoothAdapter.bondedDevices.forEach {
            Timber.d("$logTag --> 已配对设备：$it, ${it.uuids.size}, ${it.fetchUuidsWithSdp()}")
            scanDeviceCallback.call(Device(it.address, it.name, Device.typeOf(it.type)))
        }

    }

    override fun stopScan() {
        if(bluetoothAdapter.isDiscovering){
            bluetoothAdapter.cancelDiscovery()
        }
    }

    private fun registerStateReceiver(){
        if(receiverFilter == null){
            receiverFilter = IntentFilter()
            receiverFilter!!.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            receiverFilter!!.addAction(BluetoothDevice.ACTION_FOUND)
            receiverFilter!!.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            receiverFilter!!.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            receiverFilter!!.addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            receiverFilter!!.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            context.registerReceiver(stateReceiver, receiverFilter)
        }
    }

    override fun connect(device: Device, mtu: Int, timeoutMillis: Long, stateCallback: ConnectStateCallback) {
        this.mtu = mtu
        connectStateCallback = stateCallback
        connectStateCallback.call(ConnectState.CONNECTING)
        scheduleTimeoutTask(timeoutMillis){
            connectStateCallback.call(ConnectState.CONNECT_TIMEOUT)
        }
        connect(bluetoothAdapter.getRemoteDevice(device.address))
    }

    override fun supportedServices(): List<Service>? {
        return realDevice?.uuids?.map { Service(it.uuid, Service.Type.UNKNOWN, null, null) }
    }

    override fun assignService(service: Service) {
        serviceUUID = service.uuid
        if(realDevice != null){
            createRfcommSocket()
        }
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

    private fun connect(realDevice: BluetoothDevice){
        if(realDevice.bondState == BluetoothDevice.BOND_NONE){
            Timber.d("$logTag --> 请求配对")
            realDevice.createBond()
            return
        }
        this.realDevice = realDevice
        if(serviceUUID != null){
            if(createRfcommSocket()){
                connectStateCallback.call(ConnectState.CONNECTED)
            } else {
                connectStateCallback.call(ConnectState.CONNECT_ERROR)
            }
        } else {
            connectStateCallback.call(ConnectState.CONNECTED)
        }
        cancelTimeoutTask()
    }

    private fun createRfcommSocket(): Boolean{
        return try {
            bluetoothSocket?.safeClose()
            bluetoothSocket = realDevice!!.createRfcommSocketToServiceRecord(serviceUUID)
            bluetoothSocket?.connect()
            true
        } catch (iex: IOException){
            Timber.e(iex,"$logTag --> createRfcommSocket failed")
            false
        }
    }

    private fun checkClientValid(): Boolean{
        if(realDevice == null){
            Timber.e("$logTag --> 设备未连接!")
            return false
        }
        if(serviceUUID == null){
            Timber.e("$logTag --> 未设置serviceUUID!")
            return false
        }
        if(bluetoothSocket == null){
            Timber.e("$logTag -->bluetoothSocket Not connected")
            return false
        }
        return true
    }

    override fun receiveData(uuid: UUID?, onReceive: (ByteArray) -> Unit): Boolean {
        if(!checkClientValid()){
            return false
        }
        thread {
            var readFailedCount = 0
            val buffer = ByteArray(if(mtu > 0) mtu else 102)
            while (bluetoothSocket?.isConnected == true && readFailedCount < 3){
                try {
                    val len = bluetoothSocket!!.inputStream.read(buffer)
                    val readData = ByteArray(len)
                    System.arraycopy(buffer, 0, readData, 0, len)
                    onReceive(readData)
                } catch (iex: IOException){
                    Timber.e(iex,"$logTag --> read failed ${++readFailedCount}")
                }
            }
            if(readFailedCount >= 3){
                connectStateCallback.call(ConnectState.CONNECT_ERROR)
            }
        }
        return true
    }

    override fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, callback: ResultCallback) {
        if(!checkClientValid()){
            callback.call(false)
            return
        }
        scheduleTimeoutTask(timeoutMillis){
            Timber.e("$logTag --> sendData timeout")
            callback.call(false)
        }
        try {
            bluetoothSocket!!.outputStream.write(data)
            callback.call(true)
        } catch (iex: IOException){
            Timber.e(iex,"$logTag --> write failed")
            callback.call(false)
        }
        cancelTimeoutTask()
    }

    override fun disconnect() {
        if(bluetoothSocket != null){
            bluetoothSocket!!.safeClose()
            bluetoothSocket = null
            realDevice = null
            cancelTimeoutTask()
            connectStateCallback.call(ConnectState.DISCONNECTED)
        }
    }

    private fun BluetoothSocket.safeClose() = try {
        close()
    } catch (_: Exception){

    }

    override fun release(){
        disconnect()
        timer.cancel()
        if(receiverFilter != null){
            context.unregisterReceiver(stateReceiver)
            receiverFilter = null
        }
    }

}