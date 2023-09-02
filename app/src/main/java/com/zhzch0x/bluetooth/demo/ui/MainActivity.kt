package com.zhzch0x.bluetooth.demo.ui

import android.annotation.SuppressLint
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.zhzc0x.bluetooth.BluetoothClient
import com.zhzc0x.bluetooth.client.Characteristic
import com.zhzc0x.bluetooth.client.ClientType
import com.zhzc0x.bluetooth.client.ConnectState
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.client.Service
import com.zhzc0x.bluetooth.demo.R
import com.zhzch0x.bluetooth.demo.bean.LogoutInfo
import com.zhzch0x.bluetooth.demo.ui.widgets.ScanDeviceDialog
import com.zhzch0x.bluetooth.demo.ext.toHex
import com.zhzch0x.bluetooth.demo.ui.widgets.TopBar
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.Date

class MainActivity : ComposeBaseActivity() {

    private var bluetoothType by mutableStateOf(ClientType.BLE)
    private lateinit var bluetoothClient: BluetoothClient
    private lateinit var scanDeviceDialog: ScanDeviceDialog
    private var deviceName by mutableStateOf("")
    private val serviceList = ArrayList<Service>()
    private val receiveCharacteristicList = ArrayList<Characteristic>()
    private val sendCharacteristicList = ArrayList<Characteristic>()
    private val readCharacteristicList = ArrayList<Characteristic>()
    private var service: Service? by mutableStateOf(null)
    private var receiveCharacteristic: Characteristic? by mutableStateOf(null)
    private var sendCharacteristic: Characteristic? by mutableStateOf(null)
    private var readCharacteristic: Characteristic? by mutableStateOf(null)
    private var readDataStr: String by mutableStateOf("")
    @Volatile
    private var receivePackets = 0
    private var mtu by mutableStateOf(85)
    private val mtuRange = 23..512
    private var showChangeMtuDialog by mutableStateOf(false)
    private val dividerColor = Color(0xFFF1F1F1)

    @SuppressLint("SimpleDateFormat")
    private val simpleDateFormat = SimpleDateFormat("HH:mm:ss.SSS")
    private val logoutList = mutableStateListOf<LogoutInfo>()
    private var scrollToBottom by mutableStateOf(false)

    override fun initData() {
        scanDeviceDialog = ScanDeviceDialog(this, ::startScanDevice, ::stopScanDevice, ::connectDevice)
        val logFlow = channelFlow{
            Timber.plant(object: Timber.Tree(){
                private val date = Date()
                override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                    date.time = System.currentTimeMillis()
                    trySend(LogoutInfo(simpleDateFormat.format(date.time), message, priority))
                }
            })
            awaitClose{
                Timber.d("logChannelFLow closed")
            }
        }
        lifecycleScope.launch {
            logFlow.collect {
                println("collect=$it")
                while(logoutList.size >= 1000){
                    logoutList.removeAt(0)
                }
                logoutList.add(it)
                scrollToBottom = !scrollToBottom
            }
        }
    }

    @ExperimentalMaterial3Api
    @Composable
    override fun Content() {
        Scaffold(Modifier.fillMaxSize(), topBar = {
            TopBar(title = "BluetoothClient", showBackButton = false)
        }){ paddingValues ->
            Column(Modifier.padding(start = 14.dp, top=paddingValues.calculateTopPadding(), end=14.dp)) {
                Row(Modifier.padding(top = 12.dp).height(40.dp),
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
                var selectedTabIndex by remember {
                    mutableStateOf(0)
                }
                TabRow(selectedTabIndex, Modifier.padding(top = 12.dp)) {
                    Tab(selectedTabIndex == 0, onClick = {
                        selectedTabIndex = 0
                    }, Modifier.height(40.dp),  text = {
                        Text(text = "蓝牙服务", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }, unselectedContentColor = Color.Gray)
                    Tab(selectedTabIndex == 1, onClick = {
                        selectedTabIndex = 1
                    }, Modifier.height(40.dp), text = {
                        Text(text = "实时日志", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    }, unselectedContentColor = Color.Gray)
                }
                when(selectedTabIndex){
                    0 -> {
                        ServiceChoose(Modifier.fillMaxWidth().weight(1f))
                    }
                    1 -> {
                        RealtimeLogout(Modifier.fillMaxWidth().weight(1f))
                    }
                }
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
            bluetoothClient = BluetoothClient(this@MainActivity, bluetoothType, null)
        }
    }

    @Composable
    private fun ServiceChoose(modifier: Modifier) = Column(modifier){
        if(deviceName.isNotEmpty()){
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Text(text = "ServiceUid:", fontSize = 14.sp)
                Text(text = "${service?.uuid ?: ""}",
                    Modifier.weight(1f).clickable {
                        expanded = serviceList.isNotEmpty()
                    }.padding(11.dp), fontSize = 14.sp)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    offset = DpOffset(60.dp, 0.dp)) {
                    serviceList.forEach {
                        DropdownMenuItem(text = {
                            Column {
                                Text(text = "${it.uuid}", fontSize = 14.sp)
                                Text(text = "${it.type}", fontSize = 12.sp)
                            }
                        }, onClick = {
                            service = it
                            assignService(service)
                            expanded = false
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Text(text = "ReceiveUid:", fontSize = 14.sp)
                Text(text = "${receiveCharacteristic?.uuid ?: ""}",
                    Modifier.weight(1f).clickable {
                        expanded = receiveCharacteristicList.isNotEmpty()
                    }.padding(11.dp), fontSize = 14.sp)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    offset = DpOffset(60.dp, 0.dp)) {
                    receiveCharacteristicList.forEach {
                        DropdownMenuItem(text = {
                            Column {
                                Text(text = "${it.uuid}", fontSize = 14.sp)
                                Text(text = "${it.properties}", fontSize = 12.sp)
                            }
                        }, onClick = {
                            receiveCharacteristic = it
                            expanded = false
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                var ratePackets by remember{ mutableStateOf(0) }
                Text(text = "Receive每秒包数：", fontSize = 14.sp)
                Text(text = "$ratePackets", Modifier.defaultMinSize(32.dp), fontSize = 14.sp)
                LaunchedEffect(deviceName){
                    while(deviceName.isNotEmpty()){
                        receivePackets = 0
                        delay(1000)
                        ratePackets = receivePackets
                    }
                }
                Text(text = "修改mtu($mtu)", Modifier.padding(start=12.dp).clickable {
                    showChangeMtuDialog = true
                }.padding(8.dp), fontSize = 14.sp)
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Text(text = "SendUid:", fontSize = 14.sp)
                Text(text = "${sendCharacteristic?.uuid ?: ""}",
                    Modifier.weight(1f).clickable {
                        expanded = sendCharacteristicList.isNotEmpty()
                    }.padding(11.dp), fontSize = 14.sp)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    offset = DpOffset(60.dp, 0.dp)) {
                    sendCharacteristicList.forEach {
                        DropdownMenuItem(text = {
                            Column {
                                Text(text = "${it.uuid}", fontSize = 14.sp)
                                Text(text = "${it.properties}", fontSize = 12.sp)
                            }
                        }, onClick = {
                            sendCharacteristic = it
                            expanded = false
                        })
                    }
                }
            }
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
                var expanded by remember { mutableStateOf(false) }
                Text(text = "ReadUid:", fontSize = 14.sp)
                Text(text = "${readCharacteristic?.uuid ?: ""}",
                    Modifier.weight(1f).clickable {
                        expanded = readCharacteristicList.isNotEmpty()
                    }.padding(11.dp), fontSize = 14.sp)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    offset = DpOffset(60.dp, 0.dp)) {
                    readCharacteristicList.forEach {
                        DropdownMenuItem(text = {
                            Column {
                                Text(text = "${it.uuid}", fontSize = 14.sp)
                                Text(text = "${it.properties}", fontSize = 12.sp)
                            }
                        }, onClick = {
                            readCharacteristic = it
                            expanded = false
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    readData()
                }, Modifier.height(36.dp)) {
                    Text(text = "读取数据")
                }
                Text(text=readDataStr, Modifier.padding(start=6.dp))
            }
            if(showChangeMtuDialog){
                ChangeMtuDialog()
            }
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "请先连接设备", fontSize = 16.sp)
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ChangeMtuDialog(){
        Dialog({ showChangeMtuDialog = false }){
            Column(Modifier.fillMaxWidth().aspectRatio(1.6f)
                .background(Color.White, RoundedCornerShape(4.dp))){
                Box(Modifier.fillMaxWidth().height(50.dp), contentAlignment = Alignment.Center){
                    Text("修改mtu", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Divider(Modifier.fillMaxWidth().height(0.5.dp), color = dividerColor)
                var inputText by remember { mutableStateOf("") }
                var inputMtu by remember { mutableStateOf(0) }
                var inputError by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center){
                    TextField(inputText, { text ->
                        inputText = text
                        inputError = try {
                            inputMtu = inputText.toInt()
                            inputMtu !in mtuRange
                        } catch (_: NumberFormatException){
                            true
                        }
                    }, Modifier.padding(24.dp), isError = inputError, label={
                        Text("mtu范围23~512")
                    }, keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number))
                }
                Divider(Modifier.fillMaxWidth().height(0.5.dp), color = dividerColor)
                Box(Modifier.fillMaxWidth().height(44.dp).clickable {
                    if(inputMtu !in mtuRange){
                        showChangeMtuDialog = false
                        return@clickable
                    }
                    if(bluetoothClient.changeMtu(inputMtu)){
                        mtu = inputMtu
                        showToast("mtu修改成功！")
                    } else {
                        showToast("mtu修改失败！")
                    }
                    showChangeMtuDialog = false
                }, contentAlignment = Alignment.Center){
                    Text("确认", fontSize = 16.sp)
                }
            }
        }
    }

    @Composable
    private fun RealtimeLogout(modifier: Modifier){
        val state = rememberLazyListState()
        LaunchedEffect(scrollToBottom){
            if(logoutList.isNotEmpty()){
                state.scrollToItem(logoutList.size - 1)
            }
        }
        LazyColumn(modifier, state){
            items(logoutList) { logout ->
                Row(Modifier.padding(top=2.dp)){
                    Text(logout.times, color=Color.Gray, fontSize = 12.sp, lineHeight = 12.5.sp)
                    Text(text = logout.msg, Modifier.padding(start=4.dp),
                        color = if(logout.priority == Log.ERROR){
                            colorResource(id = R.color.logoutError)
                        } else {
                            colorResource(id = R.color.logoutDebug)
                        }, fontSize = 12.sp, lineHeight = 12.5.sp)
                }
            }
        }
    }

    private fun startScanDevice(){
        bluetoothClient.startScan(30000, onEndScan={
            runOnUiThread(scanDeviceDialog::stopScan)
        }){ device ->
            runOnUiThread{
                scanDeviceDialog.add(device)
            }
        }
    }

    private fun stopScanDevice(){
        bluetoothClient.stopScan()
    }

    private fun connectDevice(device: Device){
        stopScanDevice()
        bluetoothClient.connect(device, mtu){ connectState ->
            Timber.d("MainActivity --> connectState: $connectState")
            showLoading.value = connectState == ConnectState.CONNECTING
            if(connectState == ConnectState.CONNECTED){
                runOnUiThread(scanDeviceDialog::dismiss)
                stopScanDevice()
                deviceName = device.name ?: device.address
                showToast("连接成功！")
                supportedServices()
            } else if(connectState == ConnectState.DISCONNECTED){
                deviceName = ""
                assignService(null)
            }
        }
    }

    private fun supportedServices(){
        serviceList.clear()
        val services = bluetoothClient.supportedServices()
        if(services != null){
            serviceList.addAll(services)
        }
        val service = if(serviceList.isNotEmpty()){
            serviceList.first()
        } else {
            null
        }
        assignService(service)
    }

    private fun assignService(service: Service?){
        this.service = service
        receiveCharacteristicList.clear()
        sendCharacteristicList.clear()
        readCharacteristicList.clear()
        if(service != null){
            bluetoothClient.assignService(service)
            service.characteristics?.forEach { characteristic ->
                if(characteristic.properties.contains(Characteristic.Property.NOTIFY)){
                    receiveCharacteristicList.add(characteristic)
                } else if(characteristic.properties.contains(Characteristic.Property.WRITE)){
                    sendCharacteristicList.add(characteristic)
                } else if(characteristic.properties.contains(Characteristic.Property.READ)){
                    readCharacteristicList.add(characteristic)
                }
            }
            receiveCharacteristic = if(receiveCharacteristicList.isNotEmpty()){
                receiveCharacteristicList[0]
            } else {
                null
            }
            sendCharacteristic = if(sendCharacteristicList.isNotEmpty()){
                sendCharacteristicList[0]
            } else {
                null
            }
            readCharacteristic = if(readCharacteristicList.isNotEmpty()){
                readCharacteristicList[0]
            } else {
                null
            }
        } else {
            receiveCharacteristic = null
            sendCharacteristic = null
            readCharacteristic = null
            readDataStr = ""
        }
        if(receiveCharacteristic != null){
            receiveData()
        }
    }

    private fun receiveData(){
        if(bluetoothType == ClientType.BLE && receiveCharacteristic == null){
            return
        }
        bluetoothClient.receiveData(receiveCharacteristic?.uuid) { data ->
            receivePackets++
            Timber.d("receiveData: ${data.toHex()}")
        }
    }

    private fun sendData(data: ByteArray){
        if(bluetoothType == ClientType.BLE && sendCharacteristic == null){
            showToast("请选择SendUid!")
            return
        }
        bluetoothClient.sendData(sendCharacteristic?.uuid, data){ success, _ ->
            if(success){
                showToast("数据发送成功！")
            } else {
                showToast("数据发送失败！")
            }
        }
    }

    private fun readData(){
        if(bluetoothType == ClientType.BLE && readCharacteristic == null){
            showToast("请选择ReadUid!")
            return
        }
        bluetoothClient.readData(readCharacteristic?.uuid){ success, data ->
            if(success){
                readDataStr = String(data!!)
                showToast("数据读取成功！")
            } else {
                showToast("数据读取失败！")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothClient.release()
    }
}

