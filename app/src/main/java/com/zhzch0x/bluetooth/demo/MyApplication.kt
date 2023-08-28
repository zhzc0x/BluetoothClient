package com.zhzch0x.bluetooth.demo

import android.app.Application
import com.zhzc0x.bluetooth.demo.BuildConfig
import timber.log.Timber

class MyApplication: Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

}