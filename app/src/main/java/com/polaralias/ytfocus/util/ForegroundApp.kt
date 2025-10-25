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

    private var lastEventTimestamp = 0L
    private var cachedForegroundPackage: String? = null

    @Synchronized
    fun isForeground(context: Context, packageName: String): Boolean {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return false
        val end = System.currentTimeMillis()
        val startHint = if (lastEventTimestamp != 0L) lastEventTimestamp else end - DEFAULT_WINDOW_MS
        val start = startHint.coerceAtLeast(0L).coerceAtMost(end)
        val events = manager.queryEvents(start, end)
        val event = UsageEvents.Event()
        var newestTimestamp = lastEventTimestamp
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp > newestTimestamp) {
                newestTimestamp = event.timeStamp
            }
            if (FOREGROUND_EVENTS.contains(event.eventType)) {
                cachedForegroundPackage = event.packageName
            }
        }
        if (newestTimestamp > lastEventTimestamp) {
            lastEventTimestamp = newestTimestamp
        }
        return cachedForegroundPackage == packageName
    }
}
