package com.polaralias.audiofocus.overlay

import android.content.Context
import android.content.Intent
import java.util.concurrent.atomic.AtomicReference

object OverlayStateStore {
    private val ref = AtomicReference(OverlayState())
    fun update(ctx: Context, next: OverlayState) {
        val prev = ref.get()
        if (prev == next) return
        ref.set(next)
        ctx.startService(Intent(ctx, com.polaralias.audiofocus.service.OverlayService::class.java).apply {
            action = com.polaralias.audiofocus.service.OverlayService.ACTION_UPDATE
        })
    }
    fun get(): OverlayState = ref.get()
}
