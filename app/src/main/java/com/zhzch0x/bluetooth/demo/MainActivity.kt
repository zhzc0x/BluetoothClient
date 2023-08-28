package com.zhzch0x.bluetooth.demo

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Toast
import com.zhzc0x.bluetooth.BluetoothClient
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.demo.databinding.ActivityMainBinding
import timber.log.Timber
import java.util.UUID

val BLE_UUID_SERVICE2: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
val BLE_UUID_CHARACTERISTIC_READ2: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
val BLE_UUID_CHARACTERISTIC_WRITE2: UUID = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")

class MainActivity : AppCompatActivity() {

    private lateinit var bluetoothClient: BluetoothClient
    private lateinit var scanDeviceDialog: ScanDeviceDialog

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnScanDevice.setOnClickListener {
            scanDeviceDialog.show()
        }
        binding.btnSendData.setOnClickListener {
            bluetoothClient.sendData(BLE_UUID_CHARACTERISTIC_WRITE2, "b".toByteArray()){
                Timber.d("BluetoothClient --> sendData: $it")
            }
        }
        scanDeviceDialog = ScanDeviceDialog(this, ::startScanDevice, ::stopScanDevice){ it ->
            bluetoothClient.connect(it){ connectState ->
                Timber.d("MainActivity --> connectState: $connectState")
                if(connectState == ConnectState.CONNECTED){
                    Toast.makeText(this, "连接成功！", Toast.LENGTH_SHORT).show()
                    bluetoothClient.receiveData(BLE_UUID_CHARACTERISTIC_READ2) { data ->
                        Timber.d("BluetoothClient --> receiveData: ${data.toHex()}")
                    }
                }
            }
        }
        bluetoothClient = BluetoothClient(this, ClientType.CLASSIC, BLE_UUID_SERVICE2)

    }

    private fun startScanDevice(){
        bluetoothClient.startScan(10000, onStopScan={
            runOnUiThread(scanDeviceDialog::stopScan)
        }){
            runOnUiThread{
                scanDeviceDialog.add(it)
            }
        }
    }

    private fun stopScanDevice(){
        bluetoothClient.stopScan()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothClient.release()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun ByteArray.toHex(): String = asUByteArray().joinToString("") { it.toString(radix = 16).padStart(2, '0') }
}