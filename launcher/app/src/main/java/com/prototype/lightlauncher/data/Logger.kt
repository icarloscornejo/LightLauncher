package com.prototype.lightlauncher.data

import android.util.Log

/** Logging behind an interface, so code that logs can run in plain JVM tests. */
interface Logger {
    fun info(message: String)
    fun warn(message: String)
}

/** The real logger, tag "LightLauncher" unless told otherwise. */
class AndroidLogger(private val tag: String = "LightLauncher") : Logger {
    override fun info(message: String) {
        Log.i(tag, message)
    }

    override fun warn(message: String) {
        Log.w(tag, message)
    }
}
