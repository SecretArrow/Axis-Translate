package com.axis.translate

import android.app.Application
import com.axis.translate.di.AppContainer
import com.axis.translate.di.DefaultAppContainer

class AxisApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
