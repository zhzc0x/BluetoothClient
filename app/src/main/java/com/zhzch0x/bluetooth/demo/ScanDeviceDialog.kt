package com.zhzch0x.bluetooth.demo

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.zhzc0x.bluetooth.client.Device
import com.zhzc0x.bluetooth.demo.databinding.DialogScanDeviceBinding
import com.zhzc0x.bluetooth.demo.databinding.ItemScanDeviceBinding

class ScanDeviceDialog(uiContext: Context,
                       private val onStartScan: () -> Unit,
                       private val onCancel: () -> Unit,
                       private val onSelectDevice: (Device) -> Unit): AlertDialog(uiContext) {

    private val deviceListAdapter = DeviceListAdapter()
    private val viewBinding = DialogScanDeviceBinding.inflate(LayoutInflater.from(uiContext))

    init {
        setView(viewBinding.root)
        val layoutManager = LinearLayoutManager(context)
        layoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.rvDeviceList.layoutManager = layoutManager
        viewBinding.rvDeviceList.adapter = deviceListAdapter
        setButton(DialogInterface.BUTTON_NEGATIVE, "取消"){ _, _ ->
            onCancel()
        }
        viewBinding.ivRefresh.setOnClickListener {
            deviceListAdapter.reset()
            viewBinding.ivRefresh.visibility = View.GONE
            viewBinding.progressBar.visibility = View.VISIBLE
            onStartScan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setCanceledOnTouchOutside(false)
    }

    override fun show() {
        if (!isShowing) {
            super.show()
            deviceListAdapter.reset()
            viewBinding.ivRefresh.visibility = View.GONE
            viewBinding.progressBar.visibility = View.VISIBLE
            onStartScan()
        }

    }

    override fun dismiss() {
        if (isShowing) {
            super.dismiss()
            stopScan()
        }
    }

    fun stopScan(){
        viewBinding.progressBar.visibility = View.GONE
        viewBinding.ivRefresh.visibility = View.VISIBLE
    }

    fun add(deviceInfo: Device){
        deviceListAdapter.add(deviceInfo)
        viewBinding.rvDeviceList.scrollToPosition(0)
        if(viewBinding.rvDeviceList.visibility == View.GONE){
            viewBinding.tvNoDevice.visibility = View.GONE
            viewBinding.rvDeviceList.visibility = View.VISIBLE
        }
    }

    inner class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

        private var deviceInfoList: ArrayList<Device>? = null
        private var selectedDevice: Device? = null

        @SuppressLint("NotifyDataSetChanged")
        fun update(deviceInfoList: ArrayList<Device>?){
            this.deviceInfoList = deviceInfoList
            notifyDataSetChanged()
        }

        fun add(deviceInfo: Device){
            if(deviceInfoList == null){
                deviceInfoList = ArrayList()
            }
            if(!deviceInfoList!!.contains(deviceInfo)){
                deviceInfoList!!.add(0, deviceInfo)
                notifyItemInserted(0)
            }
        }

        fun remove(position: Int){
            deviceInfoList!!.removeAt(position)
            notifyItemRemoved(position)
        }

        fun reset(){
            deviceInfoList?.clear()
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun selectDevice(device: Device){
            selectedDevice = device
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemScanDeviceBinding.inflate(LayoutInflater.from(context)))
        }

        override fun getItemCount(): Int {

            return deviceInfoList?.size ?: 0
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(deviceInfoList!![position])
        }

        inner class ViewHolder(private val itemBinding: ItemScanDeviceBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            
            fun bind(deviceInfo: Device) {
                itemBinding.tvName.text = deviceInfo.name ?: deviceInfo.address
                itemBinding.rbSelected.isChecked = deviceInfo == selectedDevice
                itemBinding.root.setOnClickListener {
                    selectDevice(deviceInfo)
                    onSelectDevice(deviceInfo)
                }
            }
        }

    }
}
