package com.polaralias.ytfocus.bus

import android.graphics.RectF

object OverlayBus {
    @Volatile var hole: RectF? = null
    @Volatile var maskEnabled: Boolean = true
}
