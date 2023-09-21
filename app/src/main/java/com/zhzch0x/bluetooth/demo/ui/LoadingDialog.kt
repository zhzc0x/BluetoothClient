package com.zhzch0x.bluetooth.demo.ui

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import com.zhzc0x.bluetooth.demo.R

class LoadingDialog(context: Context, cancelable: Boolean): Dialog(context) {

    init {
        setCancelable(cancelable)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_loading)
    }
}