package com.axis.translate

import android.app.Application
import com.axis.translate.di.AppContainer
import com.axis.translate.di.DefaultAppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class AxisApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
        // Warm up the TTS engine once — speak() is fire-and-forget and needs the
        // engine initialized before the first Speak tap.
        appScope.launch {
            runCatching { container.textSpeaker.initialize() }
        }
    }

    override fun onTerminate() {
        appScope.cancel()
        container.textSpeaker.close()
        super.onTerminate()
    }
}
