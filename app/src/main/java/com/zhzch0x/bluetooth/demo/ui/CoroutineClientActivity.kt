package com.zhzch0x.bluetooth.demo.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.zhzc0x.bluetooth.CoroutineClient
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import com.zhzch0x.bluetooth.demo.ext.toHex
import com.zhzch0x.bluetooth.demo.ui.widgets.ScanDeviceDialog
import com.zhzch0x.bluetooth.demo.ui.widgets.TopBar
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID

class CoroutineClientActivity: ComposeBaseActivity() {

    private val serviceUid: UUID = UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb")
    private val receiveUid: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    private val sendUid: UUID = UUID.fromString("0000ffe3-0000-1000-8000-00805f9b34fb")

    private var bluetoothType by mutableStateOf(ClientType.BLE)
    private lateinit var bluetoothClient: CoroutineClient
    private lateinit var scanDeviceDialog: ScanDeviceDialog
    private var deviceName by mutableStateOf("")

    override fun initData() {
        scanDeviceDialog = ScanDeviceDialog(this, ::startScanDevice, ::stopScanDevice, ::connectDevice)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopBar(title = "BluetoothClient", showBackButton = false)
        }){ paddingValues ->
            Column(Modifier.padding(start = 14.dp, top=paddingValues.calculateTopPadding(), end=14.dp)) {
                Row(
                    Modifier.padding(top = 12.dp).height(40.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    var expanded by remember {
                        mutableStateOf(false)
                    }
                    Text(text = "蓝牙类型：$bluetoothType", Modifier.clickable {
                        expanded = true
                    }.padding(8.dp), fontSize = 16.sp, textAlign = TextAlign.Center)
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        ClientType.values().forEach { clientType ->
                            DropdownMenuItem(text = {
                                Text(text = "$clientType", fontSize = 16.sp)
                            }, onClick = {
                                bluetoothClient.release()
                                bluetoothType = clientType
                                expanded = false
                            })
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    if(deviceName.isEmpty()){
                        Button(onClick = {
                            if(bluetoothClient.supported()){
                                scanDeviceDialog.show()
                            } else {
                                showSnackBar("当前设备不支持蓝牙功能！")
                            }
                        }) {
                            Text(text = "扫描设备", fontSize = 16.sp)
                        }
                    } else {
                        Button(onClick = {
                            bluetoothClient.disconnect()
                        }) {
                            Text(text = "断开设备", fontSize = 16.sp)
                        }
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(text = deviceName)
                }
                Spacer(modifier = Modifier.height(12.dp))
                if(deviceName.isNotEmpty()){
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp).wrapContentHeight(),
                        verticalAlignment = Alignment.CenterVertically){
                        var sendText by remember { mutableStateOf("") }
                        var sendError by remember { mutableStateOf(false) }
                        TextField(sendText, onValueChange = { inputText ->
                            sendText = inputText
                            sendError = false
                        }, Modifier.fillMaxWidth().weight(1f), singleLine=true, label = {
                            Text(text = "数据格式：任意字符")
                        }, isError = sendError)
                        Spacer(modifier = Modifier.width(12.dp))
                        Button(onClick = {
                            if(sendText.isNotEmpty()){
                                sendData(sendText.toByteArray())
                            } else {
                                sendError = true
                            }

                        }) {
                            Text(text = "发送")
                        }
                    }
                }
            }
        }
        LaunchedEffect(bluetoothType){
            bluetoothClient = CoroutineClient(this@CoroutineClientActivity, bluetoothType,
                serviceUid)
        }
    }

    private fun startScanDevice(){
        lifecycleScope.launch {
            bluetoothClient.startScan(30000, scanDeviceDialog::stopScan).collect{ device ->
                scanDeviceDialog.add(device)
            }
        }
    }

    private fun stopScanDevice(){
        bluetoothClient.stopScan()
    }

    private fun connectDevice(device: Device){
        stopScanDevice()
        lifecycleScope.launch {
           bluetoothClient.connect(device, 85, 15000).collect{ connectState ->
               Timber.d("CoroutineClientActivity --> connectState: $connectState")
               showLoading.value = connectState == ConnectState.CONNECTING
               if(connectState == ConnectState.CONNECTED){
                   scanDeviceDialog.dismiss()
                   stopScanDevice()
                   deviceName = device.name ?: device.address
                   receiveData()
                   showToast("连接成功！")
               } else if(connectState == ConnectState.DISCONNECTED){
                   deviceName = ""
               } else if(connectState == ConnectState.CONNECT_ERROR) {
                   showToast("连接异常！")
               } else if(connectState == ConnectState.CONNECT_TIMEOUT) {
                   showToast("连接超时！")
               }
           }
       }
    }

    private fun receiveData(){
        lifecycleScope.launch {
            bluetoothClient.receiveData(receiveUid).collect{ data ->
                Timber.d("receiveData: ${data.toHex()}")
            }
        }
    }

    private fun sendData(data: ByteArray){
        lifecycleScope.launch {
            val success = bluetoothClient.sendData(sendUid, data)
            if(success){
                showToast("数据发送成功！")
            } else {
                showToast("数据发送失败！")
            }
        }
    }

}