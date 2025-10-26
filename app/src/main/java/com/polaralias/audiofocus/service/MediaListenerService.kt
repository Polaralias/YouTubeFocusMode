package com.polaralias.audiofocus.service

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.overlay.AppKind
import com.polaralias.audiofocus.overlay.OverlayStateStore
import com.polaralias.audiofocus.overlay.PlayMode
import com.polaralias.audiofocus.util.ForegroundApp
import com.polaralias.audiofocus.util.Logx

class MediaListenerService : NotificationListenerService(),
    MediaSessionManager.OnActiveSessionsChangedListener {

    private lateinit var sessionManager: MediaSessionManager
    private var observedController: MediaController? = null
    private lateinit var listenerComponent: ComponentName
    private var overlayShown = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Logx.d("MediaListenerService.controllerCallback.onPlaybackStateChanged state=${state?.state}")
            evaluateControllers()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            Logx.d("MediaListenerService.controllerCallback.onMetadataChanged")
            evaluateControllers()
        }

        override fun onSessionDestroyed() {
            Logx.d("MediaListenerService.controllerCallback.onSessionDestroyed")
            evaluateControllers()
        }
    }

    override fun onCreate() {
        Logx.d("MediaListenerService.onCreate sdk=${Build.VERSION.SDK_INT}")
        super.onCreate()
        sessionManager = getSystemService(MediaSessionManager::class.java)
        listenerComponent = ComponentName(this, MediaListenerService::class.java)
    }

    override fun onDestroy() {
        Logx.d("MediaListenerService.onDestroy")
        super.onDestroy()
    }

    override fun onListenerConnected() {
        Logx.d("MediaListenerService.onListenerConnected")
        super.onListenerConnected()
        sessionManager.addOnActiveSessionsChangedListener(this, listenerComponent)
        evaluateControllers()
    }

    override fun onListenerDisconnected() {
        Logx.d("MediaListenerService.onListenerDisconnected")
        super.onListenerDisconnected()
        sessionManager.removeOnActiveSessionsChangedListener(this)
        updateController(null)
        MediaControllerStore.setController(null)
        publishController(null, false)
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        Logx.d("MediaListenerService.onActiveSessionsChanged size=${controllers?.size}")
        evaluateControllers()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        Logx.d("MediaListenerService.onNotificationPosted package=${sbn?.packageName}")
        super.onNotificationPosted(sbn)
        evaluateControllers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        Logx.d("MediaListenerService.onNotificationRemoved package=${sbn?.packageName}")
        super.onNotificationRemoved(sbn)
        evaluateControllers()
    }

    private fun evaluateControllers() {
        val controllers = sessionManager.getActiveSessions(listenerComponent) ?: emptyList()
        Logx.d("MediaListenerService.evaluateControllers count=${controllers.size}")
        controllers.forEach { controller ->
            Logx.d("MediaListenerService.evaluateControllers controller=${controller.packageName} state=${controller.playbackState?.state}")
        }
        val state = OverlayStateStore.get()
        val pipKind = if (state.mode == PlayMode.PIP) state.app else AppKind.NONE
        val filtered = controllers.filter { appKind(it.packageName) != AppKind.NONE }
        val topPackage = ForegroundApp.topPackage(this)
        var chosen: MediaController? = null
        for (controller in filtered) {
            val pkg = controller.packageName
            val kind = appKind(pkg)
            val playbackState = controller.playbackState?.state
            val playingLike = playbackState == PlaybackState.STATE_PLAYING || playbackState == PlaybackState.STATE_BUFFERING
            val foreground = ForegroundApp.isForeground(this, pkg)
            val pip = kind != AppKind.NONE && pipKind == kind
            if (playingLike && (foreground || pip)) {
                chosen = controller
                break
            }
        }
        if (chosen == null && topPackage != null) {
            chosen = filtered.firstOrNull { it.packageName == topPackage }
        }
        if (chosen == null) {
            chosen = filtered.firstOrNull()
        }
        updateController(chosen)
        MediaControllerStore.setController(chosen)
        val pkg = chosen?.packageName
        val kind = pkg?.let { appKind(it) } ?: AppKind.NONE
        val playbackState = chosen?.playbackState?.state
        val playingLike = playbackState == PlaybackState.STATE_PLAYING || playbackState == PlaybackState.STATE_BUFFERING
        val foreground = pkg != null && ForegroundApp.isForeground(this, pkg)
        val pip = kind != AppKind.NONE && pipKind == kind
        val finalPlaying = playingLike && (foreground || pip)
        val publishPkg = if (kind == AppKind.NONE) null else pkg
        publishController(publishPkg, finalPlaying)
    }

    private fun updateController(controller: MediaController?) {
        if (controller == observedController) {
            Logx.d("MediaListenerService.updateController unchanged=${controller?.packageName}")
            return
        }
        Logx.d("MediaListenerService.updateController from=${observedController?.packageName} to=${controller?.packageName}")
        observedController?.unregisterCallback(controllerCallback)
        observedController = controller
        controller?.registerCallback(controllerCallback)
    }

    private fun appKind(pkg: String) = when (pkg) {
        "com.google.android.youtube" -> AppKind.YOUTUBE
        "com.google.android.apps.youtube.music" -> AppKind.YTMUSIC
        "com.spotify.music" -> AppKind.SPOTIFY
        "org.schabi.newpipe" -> AppKind.NEWPIPE
        else -> AppKind.NONE
    }

    private fun publishController(pkg: String?, playingLike: Boolean) {
        val cur = OverlayStateStore.get()
        val next = if (pkg == null) {
            cur.copy(
                app = AppKind.NONE,
                playing = false,
                mode = PlayMode.NONE,
                maskEnabled = false,
                hole = null
            )
        } else {
            cur.copy(app = appKind(pkg), playing = playingLike)
        }
        OverlayStateStore.update(this, next)
        if (pkg != null && !overlayShown) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW
            })
            overlayShown = true
        }
    }
}
