package com.polaralias.ytfocus

import android.app.Application
import android.os.StrictMode
import com.polaralias.ytfocus.util.Logx
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant

class AudioFocusApp : Application() {
    override fun onCreate() {
        super.onCreate()
        processStartTimestamp = Instant.now()
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().detectAll().penaltyLog().build())
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeCrash(throwable)
            Logx.e("Uncaught exception thread=${thread.name} message=${throwable.message}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
        Logx.i("AudioFocusApp.onCreate start processStartTimestamp=$processStartTimestamp")
    }

    private fun writeCrash(throwable: Throwable) {
        try {
            val crashDir = File(filesDir, "crash")
            if (!crashDir.exists()) {
                crashDir.mkdirs()
            }
            val file = File(crashDir, "latest_crash.txt")
            val writer = StringWriter()
            throwable.printStackTrace(PrintWriter(writer))
            file.writeText(writer.toString())
        } catch (error: Throwable) {
            Logx.e("Failed to write crash message=${error.message}", error)
        }
    }

    companion object {
        var processStartTimestamp: Instant? = null
            private set
    }
}
