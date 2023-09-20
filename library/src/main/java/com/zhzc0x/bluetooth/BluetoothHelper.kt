package com.zhzc0x.bluetooth

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Process
import android.widget.Toast
import androidx.core.location.LocationManagerCompat
import timber.log.Timber
import java.lang.IllegalArgumentException

internal object BluetoothHelper {

    var logTag = "BluetoothHelper"
    private lateinit var stateOn: () -> Unit
    private lateinit var stateOff: () -> Unit
    private val bluetoothReceiver = object: BroadcastReceiver(){
        override fun onReceive(context: Context, intent: Intent) {
            if(intent.action == BluetoothAdapter.ACTION_STATE_CHANGED){
                when(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)){
                    BluetoothAdapter.STATE_TURNING_ON -> {
                        Timber.d("$logTag --> 蓝牙正在打开...")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Timber.d("$logTag --> 蓝牙已经打开。")
                        stateOn()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        Timber.d("$logTag --> 蓝牙正在关闭...")
                        stateOff()
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Timber.d("$logTag --> 蓝牙已经关闭。")
                    }
                }
            }
        }
    }
    private val permissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
        arrayOf(android.Manifest.permission.BLUETOOTH, android.Manifest.permission.BLUETOOTH_ADMIN,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION)
    } else {
        arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    @SuppressLint("MissingPermission")
    fun checkBluetoothValid(context: Context, bluetoothAdapter: BluetoothAdapter?): Boolean{
        if(bluetoothAdapter == null){
            Timber.d("$logTag --> 当前设备不支持蓝牙功能！")
            return false
        }
        if(!checkPermissions(context)){
            return false
        }
        if (!bluetoothAdapter.isEnabled) {
            switchBluetooth(context, bluetoothAdapter, true)
            return false
        }
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S){
            if(!LocationManagerCompat.isLocationEnabled(context.getSystemService(Context.LOCATION_SERVICE) as LocationManager)){
                Toast.makeText(context, "当前设备蓝牙服务需要开启定位服务！", Toast.LENGTH_SHORT).show()
                return false
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun switchBluetooth(context: Context, bluetoothAdapter: BluetoothAdapter,
                        enabled: Boolean, checkPermission: Boolean = false): Boolean{
        if(checkPermission && !checkPermissions(context)){
            return false
        }
        //Android13及以上不允许App启用/关闭蓝牙
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU){
            if(enabled){
                Timber.d("$logTag --> 请求开启蓝牙")
                @Suppress("DEPRECATION")
                return bluetoothAdapter.enable()
            } else {
                Timber.d("$logTag --> 请求关闭蓝牙")
                @Suppress("DEPRECATION")
                return bluetoothAdapter.disable()
            }
        }
        return false
    }



    fun registerSwitchStateReceiver(context: Context, stateOn: () -> Unit, stateOff: () -> Unit){
        this.stateOn = stateOn
        this.stateOff = stateOff
        context.registerReceiver(bluetoothReceiver, IntentFilter(
            BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    fun unregisterSwitchStateReceiver(context: Context) {
        context.unregisterReceiver(bluetoothReceiver)
    }

    fun checkPermissions(context: Context): Boolean {
        for (permission in permissions) {
            val grant = context.checkPermission(permission, Process.myPid(), Process.myUid())
            if (grant != PackageManager.PERMISSION_GRANTED) {
                Timber.d("$logTag --> 缺少权限$permission, 尝试申请...")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    (context as Activity).requestPermissions(permissions, 1200)
                }
                return false
            }
        }
        return true
    }

    fun checkMtuRange(mtu: Int){
        if(mtu < 23 || mtu > 512){
            throw IllegalArgumentException("The mtu value must be in the 23..512 range")
        }
    }

}