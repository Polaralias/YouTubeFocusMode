package com.polaralias.ytfocus.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

object ForegroundApp {
    private val FOREGROUND_EVENTS = setOf(
        UsageEvents.Event.MOVE_TO_FOREGROUND,
        UsageEvents.Event.ACTIVITY_RESUMED
    )
    private val DEFAULT_WINDOW_MS = TimeUnit.MINUTES.toMillis(5)
    private val FALLBACK_WINDOW_MS = TimeUnit.DAYS.toMillis(1)

    private var lastEventTimestamp = 0L
    private var cachedForegroundPackage: String? = null

    @Synchronized
    fun isForeground(context: Context, packageName: String): Boolean {
        Logx.d("ForegroundApp.isForeground start package=$packageName")
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val end = System.currentTimeMillis()
        val startHint = if (lastEventTimestamp != 0L) lastEventTimestamp else end - DEFAULT_WINDOW_MS
        val event = UsageEvents.Event()
        var newestTimestamp = lastEventTimestamp
        var updatedCache = false

        fun scan(start: Long) {
            val startBounded = start.coerceAtLeast(0L).coerceAtMost(end)
            val events = manager.queryEvents(startBounded, end)
            while (events != null && events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.timeStamp > newestTimestamp) {
                    newestTimestamp = event.timeStamp
                }
                if (FOREGROUND_EVENTS.contains(event.eventType)) {
                    cachedForegroundPackage = event.packageName
                    updatedCache = true
                }
            }
        }

        scan(startHint)

        if (!updatedCache && cachedForegroundPackage == null) {
            val fallbackStart = end - FALLBACK_WINDOW_MS
            if (fallbackStart < startHint) {
                scan(fallbackStart)
            } else if (!updatedCache) {
                scan(fallbackStart)
            }
        }
        if (newestTimestamp > lastEventTimestamp) {
            lastEventTimestamp = newestTimestamp
        }
        val result = cachedForegroundPackage == packageName
        Logx.d("ForegroundApp.isForeground result package=$packageName cached=$cachedForegroundPackage result=$result")
        return result
    }
}
