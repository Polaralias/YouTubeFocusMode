package com.polaralias.audiofocus.media

import android.media.session.PlaybackState
import android.os.SystemClock
import kotlin.math.abs

private const val SPEED_EPSILON = 0.01f
private const val POSITION_STALE_WINDOW_MS = 2_000L

fun PlaybackState?.isActivelyPlaying(): Boolean {
    val state = this ?: return false
    return when (state.state) {
        PlaybackState.STATE_PLAYING -> state.isAdvancing()
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_CONNECTING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS -> true
        else -> false
    }
}

fun PlaybackState?.isExplicitlyInactive(): Boolean {
    val state = this ?: return false
    return when (state.state) {
        PlaybackState.STATE_PAUSED,
        PlaybackState.STATE_STOPPED,
        PlaybackState.STATE_ERROR,
        PlaybackState.STATE_NONE -> true
        else -> false
    }
}

private fun PlaybackState.isAdvancing(): Boolean {
    if (abs(playbackSpeed) > SPEED_EPSILON) {
        return true
    }
    val lastUpdate = lastPositionUpdateTime
    if (lastUpdate <= 0L) {
        return false
    }
    val elapsed = SystemClock.elapsedRealtime() - lastUpdate
    return elapsed <= POSITION_STALE_WINDOW_MS
}
