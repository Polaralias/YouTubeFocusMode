package com.polaralias.audiofocus.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import java.util.concurrent.TimeUnit

object ForegroundApp {
    private val FOREGROUND_EVENTS = setOf(
        UsageEvents.Event.MOVE_TO_FOREGROUND,
        UsageEvents.Event.ACTIVITY_RESUMED
    )
    private val WINDOW_MS = TimeUnit.MINUTES.toMillis(1)
    private var cachedTopPackage: String? = null
    private var cachedTimestamp: Long = 0

    @Synchronized
    fun topPackage(context: Context): String? {
        val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
        val now = System.currentTimeMillis()
        if (manager == null) {
            return cachedWithinWindow(now)
        }
        val start = now - WINDOW_MS
        val events = manager.queryEvents(start, now)
        val event = UsageEvents.Event()
        var latestPackage: String? = null
        var latestTimestamp = Long.MIN_VALUE
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.timeStamp >= start && FOREGROUND_EVENTS.contains(event.eventType)) {
                if (event.timeStamp >= latestTimestamp) {
                    latestTimestamp = event.timeStamp
                    latestPackage = event.packageName
                }
            }
        }
        if (latestPackage != null) {
            cachedTopPackage = latestPackage
            cachedTimestamp = latestTimestamp
            return latestPackage
        }
        return cachedWithinWindow(now)
    }

    @Synchronized
    fun isForeground(context: Context, packageName: String): Boolean {
        return topPackage(context) == packageName
    }

    fun isTargetInForeground(context: Context): Boolean {
        return TARGET_PACKAGES.any { pkg -> isForeground(context, pkg) }
    }

    private fun cachedWithinWindow(now: Long): String? {
        return if (now - cachedTimestamp <= WINDOW_MS) cachedTopPackage else null
    }

    private val TARGET_PACKAGES = setOf(
        "com.google.android.youtube",
        "org.schabi.newpipe",
        "com.google.android.apps.youtube.music",
        "com.spotify.music"
    )
}
