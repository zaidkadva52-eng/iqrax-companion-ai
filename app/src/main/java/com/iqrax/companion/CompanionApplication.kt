package com.iqrax.companion

import android.app.Application
import android.util.Log

class CompanionApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("IqraXCompanion", "IqraX Companion Application Initialized")
    }
}
