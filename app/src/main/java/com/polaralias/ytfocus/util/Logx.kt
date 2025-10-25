package com.polaralias.ytfocus.util

import android.util.Log

object Logx {
    private const val TAG = "YTFocus"

    private fun prefix(message: String) = "[${Thread.currentThread().name}] $message"

    fun d(message: String) {
        Log.d(TAG, prefix(message))
    }

    fun i(message: String) {
        Log.i(TAG, prefix(message))
    }

    fun w(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG, prefix(message), throwable)
        } else {
            Log.w(TAG, prefix(message))
        }
    }

    fun e(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, prefix(message), throwable)
        } else {
            Log.e(TAG, prefix(message))
        }
    }
}
