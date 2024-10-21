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
                             override val bluetoothAdapter: BluetoothAdapter?,
                             override var serviceUUID: UUID?,
                             override val logTag: String) : Client {

    override var writeType: Int = -1

    private lateinit var scanDeviceCallback: ScanDeviceCallback
    private lateinit var connectStateCallback: ConnectStateCallback
    private var receiverFilter: IntentFilter? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var mtu = 0
    private var realDevice: BluetoothDevice? = null
    private val timer = Timer()
    private var timeoutTask: TimerTask? = null
    @Volatile
    private var receiveData = false

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
                    if (device == null) {
                        return
                    }
                    scanDeviceCallback.call(Device(device, device.bondState == BluetoothDevice.BOND_BONDED))
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
        bluetoothAdapter!!.startDiscovery()
        bluetoothAdapter.bondedDevices.forEach {
            Timber.d("$logTag --> 已配对设备：$it, uuids=${it.uuids?.contentToString()}")
            scanDeviceCallback.call(Device(it, true))
        }

    }

    override fun stopScan() {
        if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
    }

    private fun registerStateReceiver() {
        if (receiverFilter == null) {
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
        scheduleTimeoutTask(timeoutMillis) {
            callConnectState(ConnectState.CONNECT_TIMEOUT)
        }
        connect(bluetoothAdapter!!.getRemoteDevice(device.address))
    }

    private fun connect(realDevice: BluetoothDevice) {
        if (realDevice.bondState == BluetoothDevice.BOND_NONE) {
            Timber.d("$logTag --> 请求配对")
            realDevice.createBond()
            return
        }
        this.realDevice = realDevice
        if (serviceUUID != null) {
            if (createRfcommSocketToConnect()) {
                callConnectState(ConnectState.CONNECTED)
            } else {
                callConnectState(ConnectState.CONNECT_ERROR)
            }
        } else {
            callConnectState(ConnectState.CONNECTED)
        }
    }

    private fun createRfcommSocketToConnect(): Boolean {
        return try {
            bluetoothSocket?.safeClose()
            bluetoothSocket = realDevice!!.createRfcommSocketToServiceRecord(serviceUUID)
            bluetoothSocket?.connect()
            true
        } catch (iex: IOException) {
            Timber.e(iex,"$logTag --> createRfcommSocketToConnect failed")
            false
        }
    }

    private fun callConnectState(state: ConnectState) {
        cancelTimeoutTask()
        if (state == ConnectState.CONNECT_TIMEOUT || state == ConnectState.CONNECT_ERROR) {
            bluetoothSocket?.safeClose()
        }
        connectStateCallback.call(state)
    }

    override fun changeMtu(mtu: Int): Boolean {
        this.mtu = mtu
        return true
    }

    override fun supportedServices(): List<Service>? {
        return realDevice?.uuids?.map { Service(it.uuid, Service.Type.UNKNOWN, null, null) }
    }

    override fun assignService(service: Service) {
        serviceUUID = service.uuid
        if (realDevice != null) {
            createRfcommSocketToConnect()
        }
    }

    private fun checkClientValid(): Boolean {
        if (realDevice == null) {
            Timber.e("$logTag --> 设备未连接!")
            return false
        }
        if (serviceUUID == null) {
            Timber.e("$logTag --> 未设置serviceUUID!")
            return false
        }
        if (bluetoothSocket == null) {
            Timber.e("$logTag -->bluetoothSocket Not connected")
            return false
        }
        return true
    }

    override fun receiveData(uuid: UUID?, onReceive: (ByteArray) -> Unit): Boolean {
        if (!checkClientValid()) {
            return false
        }
        receiveData = true
        thread {
            var readFailedCount = 0
            val buffer = ByteArray(if(mtu > 0) mtu else 1024)
            while (receiveData && bluetoothSocket?.isConnected == true && readFailedCount < 3) {
                try {
                    val len = bluetoothSocket!!.inputStream.read(buffer)
                    val readData = ByteArray(len)
                    System.arraycopy(buffer, 0, readData, 0, len)
                    onReceive(readData)
                } catch (iex: IOException){
                    Timber.e(iex,"$logTag --> receiveData failed ${++readFailedCount}")
                }
            }
            if (readFailedCount >= 3) {
                connectStateCallback.call(ConnectState.CONNECT_ERROR)
            }
        }
        return true
    }

    override fun cancelReceive(uuid: UUID?): Boolean {
        receiveData = false
        return true
    }

    override fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, callback: DataResultCallback) {
        if (!checkClientValid()) {
            callback.call(false, data)
            return
        }
        scheduleTimeoutTask(timeoutMillis) {
            Timber.e("$logTag --> sendData timeout")
            callback.call(false, data)
        }
        try {
            bluetoothSocket!!.outputStream.write(data)
            cancelTimeoutTask()
            callback.call(true, data)
        } catch (iex: IOException) {
            Timber.e(iex,"$logTag --> sendData failed")
            cancelTimeoutTask()
            callback.call(false, data)
        }
    }

    override fun readData(uuid: UUID?, timeoutMillis: Long, callback: DataResultCallback) {
        if (!checkClientValid()) {
            callback.call(false, null)
            return
        }
        scheduleTimeoutTask(timeoutMillis) {
            Timber.e("$logTag --> readData timeout")
            callback.call(false, null)
        }
        thread {
            try {
                val buffer = ByteArray(if(mtu > 0) mtu else 1024)
                val len = bluetoothSocket!!.inputStream.read(buffer)
                val readData = ByteArray(len)
                System.arraycopy(buffer, 0, readData, 0, len)
                cancelTimeoutTask()
                callback.call(true, readData)
            } catch (iex: IOException) {
                Timber.e(iex,"$logTag --> readData failed")
                cancelTimeoutTask()
                callback.call(false, null)
            }
        }
    }

    private fun scheduleTimeoutTask(timeoutMillis: Long, onTask: () -> Unit) {
        cancelTimeoutTask()
        timeoutTask = object: TimerTask() {
            override fun run() {
                onTask()
            }
        }
        timer.schedule(timeoutTask, timeoutMillis)
    }

    private fun cancelTimeoutTask() {
        timeoutTask?.cancel()
        timeoutTask = null
    }

    override fun disconnect() {
        if (bluetoothSocket != null) {
            bluetoothSocket!!.safeClose()
            bluetoothSocket = null
            realDevice = null
            callConnectState(ConnectState.DISCONNECTED)
        }
    }

    private fun BluetoothSocket.safeClose() = try {
        close()
    } catch (_: IOException){

    }

    override fun release() {
        disconnect()
        timer.cancel()
        if (receiverFilter != null) {
            context.unregisterReceiver(stateReceiver)
            receiverFilter = null
        }
    }
}
