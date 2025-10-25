package com.polaralias.ytfocus.util

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object SafeServiceStarter {
    fun startFg(context: Context, intent: Intent) {
        try {
            Logx.d("SafeServiceStarter.startFg action=${intent.action}")
            ContextCompat.startForegroundService(context, intent)
            Logx.d("SafeServiceStarter.startFg started action=${intent.action}")
        } catch (error: Throwable) {
            Logx.e("SafeServiceStarter.startFg failed action=${intent.action} message=${error.message}", error)
        }
    }
}
