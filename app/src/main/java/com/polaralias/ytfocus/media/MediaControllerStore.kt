package com.polaralias.ytfocus.media

import android.media.session.MediaController
import java.util.concurrent.CopyOnWriteArrayList

object MediaControllerStore {
    private val listeners = CopyOnWriteArrayList<(MediaController?) -> Unit>()
    @Volatile
    private var controller: MediaController? = null

    fun setController(newController: MediaController?) {
        controller = newController
        listeners.forEach { it(newController) }
    }

    fun getController(): MediaController? = controller

    fun addListener(listener: (MediaController?) -> Unit) {
        listeners.add(listener)
        listener(controller)
    }

    fun removeListener(listener: (MediaController?) -> Unit) {
        listeners.remove(listener)
    }
}
