package com.zhzc0x.bluetooth.client

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import androidx.annotation.WorkerThread
import java.util.UUID

internal interface Client {

    val context: Context
    val bluetoothAdapter: BluetoothAdapter?
    val logTag: String
    var serviceUUID: UUID?
    var writeType: Int

    fun startScan(callback: ScanDeviceCallback)

    fun stopScan()

    fun connect(device: Device, mtu: Int, timeoutMillis: Long, stateCallback: ConnectStateCallback)

    fun changeMtu(mtu: Int): Boolean

    fun supportedServices(): List<Service>? = null

    fun assignService(service: Service){

    }

    fun receiveData(uuid: UUID?, onReceive: (ByteArray) -> Unit): Boolean

    fun sendData(uuid: UUID?, data: ByteArray, timeoutMillis: Long, callback: DataResultCallback)

    fun readData(uuid: UUID?, timeoutMillis: Long, callback: DataResultCallback)

    fun disconnect()

    fun release()

}

enum class ClientType {
    CLASSIC, BLE
}

enum class ClientState {
    NOT_SUPPORT, NO_PERMISSIONS, LOCATION_DISABLE, ENABLE, DISABLE
}

data class Device internal constructor(
    val address: String,
    val name: String?,
    val type: Type,
    val bonded: Boolean
) {

    @SuppressLint("MissingPermission")
    internal constructor(device: BluetoothDevice, bonded: Boolean) :
            this(device.address, device.name, typeOf(device.type), bonded)

    enum class Type(val value: Int) {
        CLASSIC(1), BLE(2), DUAL(3), UNKNOWN(-1);
    }

    companion object {
        private fun typeOf(value: Int): Type {
            Type.entries.forEach {
                if (it.value == value) {
                    return it
                }
            }
            return Type.UNKNOWN
        }
    }

}

fun interface ScanDeviceCallback {

    @WorkerThread
    fun call(device: Device)

}

data class Service(
    val uuid: UUID,
    val type: Type,
    val characteristics: List<Characteristic>?,
    val included: List<Service>?
){
    enum class Type(val value: Int) {
        PRIMARY(0), SECONDARY(1), UNKNOWN(-1);
    }

    companion object {
        fun typeOf(value: Int): Type {
            Service.Type.entries.forEach {
                if(it.value == value){
                    return it
                }
            }
            return Type.UNKNOWN
        }
    }

}

data class Characteristic(val uuid: UUID,
                          val properties: List<Property>,
                          val permissions: Int) {
    enum class Property(val value: Int) {
        BROADCAST(1), EXTENDED_PROPS(128), INDICATE(32), NOTIFY(16),
        READ(2), SIGNED_WRITE(64), WRITE(8), WRITE_NO_RESPONSE(4),
        UNKNOWN(-1);
    }

    companion object {

        fun getProperties(value: Int): List<Property> {
            return Property.entries.filter { property ->
                (value and property.value) == property.value
            }
        }

    }

}

enum class ConnectState(private val desc: String) {
    CONNECTING("连接中"),
    CONNECT_TIMEOUT("连接超时"),
    CONNECTED("已连接"),
    CONNECT_ERROR("连接异常"),
    DISCONNECTED( "连接断开"),
    RECONNECT("重新连接");
}

fun interface ConnectStateCallback {

    @WorkerThread
    fun call(state: ConnectState)

}

fun interface DataResultCallback {

    @WorkerThread
    fun call(success: Boolean, data: ByteArray?)

}
