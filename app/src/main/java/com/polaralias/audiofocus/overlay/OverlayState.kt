package com.polaralias.audiofocus.overlay

import android.graphics.RectF

enum class AppKind { NONE, YOUTUBE, YTMUSIC, SPOTIFY }
enum class PlayMode { NONE, AUDIO, VIDEO, SHORTS }

data class OverlayState(
    val app: AppKind = AppKind.NONE,
    val playing: Boolean = false,
    val mode: PlayMode = PlayMode.NONE,
    val hole: RectF? = null,
    val maskEnabled: Boolean = false
)
