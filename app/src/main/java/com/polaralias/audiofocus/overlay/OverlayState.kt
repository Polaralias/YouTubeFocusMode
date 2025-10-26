package com.polaralias.audiofocus.overlay

import android.graphics.RectF

enum class AppKind { NONE, YOUTUBE, YTMUSIC, SPOTIFY, NEWPIPE }
enum class PlayMode { NONE, AUDIO, VIDEO, SHORTS, PIP }

data class OverlayState(
    val app: AppKind = AppKind.NONE,
    val playing: Boolean = false,
    val mode: PlayMode = PlayMode.NONE,
    val maskEnabled: Boolean = false,
    val hole: RectF? = null
)
