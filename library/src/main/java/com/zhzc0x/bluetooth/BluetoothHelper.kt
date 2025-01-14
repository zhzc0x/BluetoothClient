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
import androidx.core.location.LocationManagerCompat
import com.zhzc0x.bluetooth.client.ClientState
import timber.log.Timber
import java.lang.IllegalArgumentException

internal object BluetoothHelper {

    var logTag = "BluetoothHelper"
    private lateinit var turnOn: () -> Unit
    private lateinit var turnOff: () -> Unit
    private val bluetoothReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0)) {
                    BluetoothAdapter.STATE_TURNING_ON -> {
                        Timber.d("$logTag --> 蓝牙正在打开...")
                    }
                    BluetoothAdapter.STATE_ON -> {
                        Timber.d("$logTag --> 蓝牙已经打开。")
                        turnOn()
                    }
                    BluetoothAdapter.STATE_TURNING_OFF -> {
                        Timber.d("$logTag --> 蓝牙正在关闭...")
                        turnOff()
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

    fun checkState(context: Context, bluetoothAdapter: BluetoothAdapter?,
                   requestPermission: Boolean): ClientState {
        val state = when {
            bluetoothAdapter == null -> {
                ClientState.NOT_SUPPORT
            }
            !checkPermissions(context, requestPermission) -> {
                ClientState.NO_PERMISSIONS
            }
            //Android12之后就不需要定位权限了
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !LocationManagerCompat.isLocationEnabled(context
                        .getSystemService(Context.LOCATION_SERVICE) as LocationManager) -> {
                ClientState.LOCATION_DISABLE
            }
            bluetoothAdapter.isEnabled -> {
                ClientState.ENABLE
            }
            else -> {
                ClientState.DISABLE
            }
        }
        return state
    }

    @SuppressLint("MissingPermission")
    fun switchBluetooth(context: Context, bluetoothAdapter: BluetoothAdapter, enable: Boolean,
                        checkPermission: Boolean = false): Boolean {
        if (checkPermission && !checkPermissions(context)) {
            return false
        }
        //Android13及以上不允许App启用/关闭蓝牙
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (enable) {
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

    fun registerSwitchReceiver(context: Context, turnOn: () -> Unit, turnOff: () -> Unit) {
        this.turnOn = turnOn
        this.turnOff = turnOff
        context.registerReceiver(bluetoothReceiver, IntentFilter(
            BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    fun unregisterSwitchReceiver(context: Context) {
        context.unregisterReceiver(bluetoothReceiver)
    }

    private fun checkPermissions(context: Context, request: Boolean = true): Boolean {
        for (permission in permissions) {
            val grant = context.checkPermission(permission, Process.myPid(), Process.myUid())
            if (grant != PackageManager.PERMISSION_GRANTED) {
                Timber.d("$logTag --> 缺少权限$permission, 尝试申请...")
                if (request && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    requestPermissions(context)
                }
                return false
            }
        }
        return true
    }

    fun requestPermissions(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            (context as Activity).requestPermissions(permissions, 1200)
        }
    }

    fun checkMtuRange(mtu: Int) {
        if (mtu < 23 || mtu > 512) {
            throw IllegalArgumentException("The mtu value must be in the 23..512 range")
        }
    }
}
