package com.polaralias.audiofocus.bus

import android.graphics.RectF
import android.os.SystemClock

object OverlayBus {
    @Volatile var hole: RectF? = null
    @Volatile var maskEnabled: Boolean = true
    @Volatile var pipRect: RectF? = null
    @Volatile private var lastUiDetectionTimestamp: Long = 0L

    private const val UI_DETECTION_WINDOW_MS = 5_000L

    fun markUiDetection() {
        lastUiDetectionTimestamp = SystemClock.elapsedRealtime()
    }

    fun hasRecentUiDetection(): Boolean {
        val ts = lastUiDetectionTimestamp
        if (ts == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - ts
        return elapsed in 0..UI_DETECTION_WINDOW_MS
    }

    fun clearUiDetection() {
        pipRect = null
        lastUiDetectionTimestamp = 0L
    }
}
