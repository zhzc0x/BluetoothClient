# BluetoothClient

Android蓝牙客户端，支持经典蓝牙和低功耗蓝牙BLE，增加协程Flow扩展版本CoroutineClient，代码简洁，易扩展、集成

- 支持蓝牙权限自动检测、申请
- 支持失败自动重连、重发、重读
- 支持设置重试次数（协程版本暂不支持）
- 支持设置超时时间
- 支持连接设备后指定serviceUid，修改mtu
- 支持BLE设置多个notifyUid监听数据
- 支持ChannelFlow

# 使用

添加gradle依赖

```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation("com.zhzc0x.bluetooth:client-android:1.0.0")
}
```

代码示例

```kotlin
val bluetoothClient = BluetoothClient(context, ClientType.BLE\ClientType.CLASSIC, serviceUid)
bluetoothClient.startScan(30000, onEndScan={
	//子线程
	......
}){ device ->
	//子线程
	......
}
bluetoothClient.connect(device, mtu){ connectState ->
    //子线程
	if(connectState == ConnectState.CONNECTED){
    	......
    }  
}
//协程版本
val bluetoothClient = CoroutineClient(context, ClientType.BLE\ClientType.CLASSIC, serviceUid)
lifecycleScope.launch {
     bluetoothClient.startScan(30000, onEndScan={
         //主线程
     	......
     }).collect{ device ->
        //此处线程取决于FLow.collect所在的线程
        ......
	}
}
lifecycleScope.launch {
    bluetoothClient.connect(device, 85, 15000).collect{ connectState ->
         //此处线程取决于FLow.collect所在的线程
    	if(connectState == ConnectState.CONNECTED){
            ......
        }                                                   
    }          
}

```

API说明

```kotlin
/**                                                          
 * 检查设备蓝牙状态                                                  
 * @param toNext: true 如何无蓝牙权限则继续请求权限，如果设备蓝牙未开启则继续请求打开；false 无操作
 * @return ClientState                                       
 * @see com.zhzc0x.bluetooth.client.ClientState              
 * */                                                        
fun checkState(toNext: Boolean = true): ClientState

/** 设置蓝牙开关状态通知 */                                             
fun setSwitchReceiver(turnOn: () -> Unit, turnOff: () -> Unit)

/**                                                                                
 * 开关蓝牙                                                                            
 * 此系统方法在 API 级别 33 中已弃用。从 Build.VERSION_CODES.TIRAMISU 开始，不允许应用程序启用/禁用蓝牙并总是返回false
 * */                                                                              
fun switch(enable: Boolean): Boolean

/**                                                                                                 
 * 开始扫描设备                                                                                           
 * @param timeMillis：扫描时长                                                                           
 * @param onEndScan：扫描结束回调                                                                          
 * @param deviceCallback：ScanDeviceCallback.call(Device):                                           
 * @See com.zhzc0x.bluetooth.client.Device                                                          
 * @See com.zhzc0x.bluetooth.client.ScanDeviceCallback                                              
 *                                                                                                  
 * */                                                                                               
@JvmOverloads                                                                                       
fun startScan(timeMillis: Long, onEndScan: (() -> Unit)? = null, deviceCallback: ScanDeviceCallback)

/** 停止扫描设备 */         
fun stopScan()

/**                                                                                           
 * 连接蓝牙设备                                                                                     
 * @param device: startScan返回的Device                                                          
 * @param mtu: IntRange(23..512)                                                              
 * @param timeoutMillis: 连接超时时间，默认6000ms，超时后回调ConnectState.CONNECT_TIMEOUT                    
 * @param reconnectCount: 失败重连次数，默认3次，0不重连                                                    
 * @param stateCallback: 回调ConnectState                                                       
 *                                                                                            
 * @throws IllegalArgumentException("The mtu value must be in the 23..512 range")          
 * */                                                                                         
@JvmOverloads                                                                                 
fun connect(device: Device, mtu: Int = 0, timeoutMillis: Long = 6000, reconnectCount: Int = 3, stateCallback: 		ConnectStateCallback)

/**                                    
 * 修改mtu                               
 * @param mtu: IntRange(23..512)       
 *                                     
 * @return Boolean: true修改成功， false修改失败
 *                                     
 * @throws IllegalArgumentException("The mtu value must be in the 23..512 range")    
 * */                                  
fun changeMtu(mtu: Int): Boolean

/**                    
 * 获取支持的 services      
 *                     
 * @return List<Service
 * @seecom.zhzc0x.bluetooth.client.Service  
 * */                  
fun supportedServices(): List<Service>

/**                                
 * 指定 Service                      
 * @param service 通过supportedServices()方法返回的Service
 * @see com.zhzc0x.bluetooth.client.Service
 *                                 
 * */                              
fun assignService(service: Service)

/**                                                                                      
 * 设置写特征类型                                                                               
 * @param type：默认-1不设置，其他值同 WRITE_TYPE_DEFAULT, WRITE_TYPE_NO_RESPONSE, WRITE_TYPE_SIGNED
 * @see android.bluetooth.BluetoothGattCharacteristic                                    
 *                                                                                       
 * */                                                                                    
fun setWriteType(type: Int)

/**                                                                                       
 * 设置数据接收                                                                                 
 * @param uuid：低功耗蓝牙传入包含notify特征的uuid，经典蓝牙不需要传                                            
 * @param onReceive(ByteArray)                                                            
 *                                                                                        
 * @return Boolean：true设置成功，false设置失败                                                     
 * */                                                                                     
@JvmOverloads                                                                             
fun receiveData(uuid: UUID? = null, @WorkerThread onReceive: (ByteArray) -> Unit): Boolean

/**                                                                          
 * 发送数据                                                                      
 * @param uuid：低功耗蓝牙传入包含write特征的uuid，经典蓝牙不需要传                                
 * @param data: ByteArray                                                    
 * @param timeoutMillis: 发送超时时间，默认3000ms                                     
 * @param resendCount: 失败重发次数，默认3次，0不重发                                      
 * @param callback: 回调发送结果DataResultCallback.call(Boolean,ByteArray)         
 * @see com.zhzc0x.bluetooth.client.DataResultCallback                       
 *                                                                           
 * */                                                                        
@JvmOverloads                                                                
fun sendData(uuid: UUID? = null, data: ByteArray, timeoutMillis: Long = 3000, resendCount: Int = 3, callback: 		DataResultCallback)

/**                                                                               
 * 读取数据                                                                           
 * @param uuid：低功耗蓝牙传入包含read特征的uuid，经典蓝牙不需要传                                      
 * @param timeoutMillis: 读取超时时间，默认3000ms                                          
 * @param rereadCount: 失败重读次数，默认3次，0不重读                                           
 * @param callback: 回调读取结果DataResultCallback.call(Boolean,ByteArray)              
 * @see com.zhzc0x.bluetooth.client.DataResultCallback                            
 *                                                                                
 * */                                                                             
@JvmOverloads                                                                     
fun readData(uuid: UUID? = null, timeoutMillis: Long = 3000, rereadCount: Int = 3, callback: DataResultCallback)

/** 断开蓝牙设备 */           
fun disconnect()

/** 释放资源 */        
fun release()
```

# License

```

Copyright [2023] [zhzc0x]

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
```

