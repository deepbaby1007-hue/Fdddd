package com.sunsang24.autobooking

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }
}
