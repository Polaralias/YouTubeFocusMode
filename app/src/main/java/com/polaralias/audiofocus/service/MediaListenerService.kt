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
        publishInactive()
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
        val active = controllers.firstOrNull { isActive(it) }
        Logx.d("MediaListenerService.evaluateControllers selected=${active?.packageName}")
        if (active != null) {
            updateController(active)
            MediaControllerStore.setController(active)
            publishActive(active.packageName)
        } else {
            updateController(null)
            MediaControllerStore.setController(null)
            publishInactive()
        }
    }

    private fun isActive(controller: MediaController): Boolean {
        val pkg = controller.packageName
        val target = pkg == "com.google.android.youtube" || pkg == "com.google.android.apps.youtube.music" || pkg == "com.spotify.music"
        if (!target) {
            Logx.d("MediaListenerService.isActive package=$pkg target=$target")
            return false
        }
        val state = controller.playbackState?.state
        val playingLike = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        if (!playingLike) {
            Logx.d("MediaListenerService.isActive package=$pkg state=$state playingLike=$playingLike")
            return false
        }
        if (!ForegroundApp.isForeground(this, pkg)) {
            Logx.d("MediaListenerService.isActive package=$pkg foreground=false")
            return false
        }
        return true
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

    private fun publishActive(pkg: String) {
        val app = when (pkg) {
            "com.google.android.youtube" -> AppKind.YOUTUBE
            "com.google.android.apps.youtube.music" -> AppKind.YTMUSIC
            "com.spotify.music" -> AppKind.SPOTIFY
            else -> AppKind.NONE
        }
        val current = OverlayStateStore.get()
        val next = current.copy(app = app, playing = true)
        OverlayStateStore.update(this, next)
        if (!current.playing || current.app != app) {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SHOW
            })
        }
    }

    private fun publishInactive() {
        val current = OverlayStateStore.get()
        val next = current.copy(
            playing = false,
            mode = PlayMode.NONE,
            maskEnabled = false,
            hole = null,
            app = AppKind.NONE
        )
        OverlayStateStore.update(this, next)
    }
}
