package com.noobdev.orbit

import android.app.Application
import repository.initSettings
import util.appContext

class OrbitApp : Application() {
    companion object {
        lateinit var instance: OrbitApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = this
        instance = this
        initSettings(this)
    }
}