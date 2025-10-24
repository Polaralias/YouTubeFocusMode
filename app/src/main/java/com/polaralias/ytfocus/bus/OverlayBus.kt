package com.polaralias.ytfocus.bus

import android.graphics.RectF
import java.util.concurrent.CopyOnWriteArrayList

object OverlayBus {
    private val listeners = CopyOnWriteArrayList<(RectF?) -> Unit>()
    @Volatile
    private var hole: RectF? = null

    fun updateHole(newHole: RectF?) {
        hole = newHole
        listeners.forEach { it(newHole) }
    }

    fun addListener(listener: (RectF?) -> Unit) {
        listeners.add(listener)
        listener(hole)
    }

    fun removeListener(listener: (RectF?) -> Unit) {
        listeners.remove(listener)
    }
}
