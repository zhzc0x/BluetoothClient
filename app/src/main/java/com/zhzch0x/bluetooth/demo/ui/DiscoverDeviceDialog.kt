package com.zhzch0x.bluetooth.demo.ui

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
import com.zhzc0x.bluetooth.demo.databinding.DialogDiscoverDeviceBinding
import com.zhzc0x.bluetooth.demo.databinding.ItemDiscoverDeviceBinding

class DiscoverDeviceDialog(uiContext: Context,
                           private val onStartDiscover: () -> Unit,
                           private val onStopDiscover: () -> Unit,
                           private val onSelectDevice: (Device) -> Unit): AlertDialog(uiContext) {

    private val deviceListAdapter = DeviceListAdapter()
    private val viewBinding = DialogDiscoverDeviceBinding.inflate(LayoutInflater.from(uiContext))

    init {
        setView(viewBinding.root)
        val layoutManager = LinearLayoutManager(context)
        layoutManager.orientation = LinearLayoutManager.VERTICAL
        viewBinding.rvDeviceList.layoutManager = layoutManager
        viewBinding.rvDeviceList.adapter = deviceListAdapter
        setButton(DialogInterface.BUTTON_NEGATIVE, "取消"){ _, _ ->

        }
        viewBinding.ivRefresh.setOnClickListener {
            start()
        }
        viewBinding.flLoading.setOnClickListener {
            if(viewBinding.progressBar.visibility == View.VISIBLE){
                stop()
            } else {
                start()
            }
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
            start()
        }
    }

    override fun dismiss() {
        if (isShowing) {
            super.dismiss()
            stop()
        }
    }

    private fun start(){
        onStartDiscover()
        deviceListAdapter.reset()
        viewBinding.ivRefresh.visibility = View.GONE
        viewBinding.progressBar.visibility = View.VISIBLE
    }

    fun stop(){
        onStopDiscover()
        viewBinding.progressBar.visibility = View.GONE
        viewBinding.ivRefresh.visibility = View.VISIBLE
    }

    fun add(device: Device){
        deviceListAdapter.add(device)
        if(viewBinding.rvDeviceList.visibility == View.GONE){
            viewBinding.tvNoDevice.visibility = View.GONE
            viewBinding.rvDeviceList.visibility = View.VISIBLE
        }
    }

    inner class DeviceListAdapter : RecyclerView.Adapter<DeviceListAdapter.ViewHolder>() {

        private var deviceList: ArrayList<Device>? = null
        private var selectedDevice: Device? = null

        @SuppressLint("NotifyDataSetChanged")
        fun update(deviceList: ArrayList<Device>?){
            this.deviceList = deviceList
            notifyDataSetChanged()
        }

        fun add(deviceInfo: Device){
            if(deviceList == null){
                deviceList = ArrayList()
            }
            if(!deviceList!!.contains(deviceInfo)){
                deviceList!!.add(0, deviceInfo)
                notifyItemInserted(0)
                viewBinding.rvDeviceList.scrollToPosition(0)
            }
        }

        fun remove(position: Int){
            deviceList!!.removeAt(position)
            notifyItemRemoved(position)
        }

        fun reset(){
            deviceList?.clear()
            selectedDevice = null
        }

        @SuppressLint("NotifyDataSetChanged")
        private fun selectDevice(device: Device){
            selectedDevice = device
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            return ViewHolder(ItemDiscoverDeviceBinding.inflate(LayoutInflater.from(context)))
        }

        override fun getItemCount(): Int {

            return deviceList?.size ?: 0
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(deviceList!![position])
        }

        inner class ViewHolder(private val itemBinding: ItemDiscoverDeviceBinding) : RecyclerView.ViewHolder(itemBinding.root) {
            
            fun bind(device: Device) {
                itemBinding.tvName.text = device.name ?: device.address
                itemBinding.rbSelected.isChecked = device == selectedDevice
                itemBinding.root.setOnClickListener {
                    selectDevice(device)
                    onSelectDevice(device)
                }
            }
        }

    }
}
