package com.noobdev.orbit

import android.app.Application
import repository.initSettings

class OrbitApp : Application() {
    companion object {
        lateinit var instance: OrbitApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        initSettings(this)
    }
}