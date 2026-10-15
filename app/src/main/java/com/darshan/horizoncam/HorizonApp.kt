package com.darshan.horizoncam

import android.app.Application
import com.darshan.horizoncam.util.CrashLogger

class HorizonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLogger.install(this)
    }
}
