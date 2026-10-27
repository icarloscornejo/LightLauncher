package com.prototype.lightlauncher

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode

/**
 * The process entry point. It runs on every process start, including the screensaver and the
 * accessibility services, so it must not do any IO: it only builds the [AppContainer] (lazily filled).
 */
class LightLauncherApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        val isDebuggable: Boolean = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebuggable) {
            // Debug builds only: log disk and network work on the main thread, never crash on it.
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        }
        container = AppContainer(this)
    }
}
