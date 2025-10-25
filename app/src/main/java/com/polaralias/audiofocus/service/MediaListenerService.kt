package com.polaralias.audiofocus.service

import android.content.ComponentName
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.polaralias.audiofocus.bus.OverlayBus
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.util.ForegroundApp
import com.polaralias.audiofocus.util.Logx
import com.polaralias.audiofocus.util.PermissionStatus
import com.polaralias.audiofocus.util.SafeServiceStarter

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
        sendHide()
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
        val target = controllers.firstOrNull { isTargetActive(it) }
        Logx.d("MediaListenerService.evaluateControllers selected=${target?.packageName}")
        val hasPip = OverlayBus.pipRect != null
        val hasUiDetection = OverlayBus.hasRecentUiDetection()
        val detectionActive = hasPip || hasUiDetection
        if (target != null) {
            updateController(target)
            MediaControllerStore.setController(target)
        } else if (!detectionActive) {
            updateController(null)
            MediaControllerStore.setController(null)
        }
        val pkg = target?.packageName ?: MediaControllerStore.getController()?.packageName
        val foreground = pkg != null && ForegroundApp.isForeground(this, pkg)
        val hasPipForPkg = hasPip && pkg != null
        Logx.d(
            "MediaListenerService.evaluateControllers visibility pkg=$pkg foreground=$foreground hasPip=$hasPip uiDetect=$hasUiDetection"
        )
        if (target != null && (foreground || hasPipForPkg || hasUiDetection)) {
            sendShow()
        } else if (target == null && detectionActive) {
            sendShow()
        } else {
            sendHide()
        }
    }

    private fun isTargetActive(controller: MediaController): Boolean {
        val pkg = controller.packageName
        val state = controller.playbackState?.state
        val target = TARGET_PACKAGES.contains(pkg)
        val active = target && state == PlaybackState.STATE_PLAYING
        Logx.d("MediaListenerService.isTargetActive package=$pkg state=$state active=$active")
        return active
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

    private fun sendShow() {
        val snapshot = PermissionStatus.snapshot(this)
        Logx.d("MediaListenerService.sendShow overlay=${snapshot.overlay} notification=${snapshot.notificationListener} usage=${snapshot.usageAccess} accessibility=${snapshot.accessibility}")
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
        SafeServiceStarter.startFg(this, intent)
    }

    private fun sendHide() {
        val snapshot = PermissionStatus.snapshot(this)
        Logx.d("MediaListenerService.sendHide overlay=${snapshot.overlay} notification=${snapshot.notificationListener} usage=${snapshot.usageAccess} accessibility=${snapshot.accessibility}")
        val intent = Intent(this, OverlayService::class.java).apply {
            action = OverlayService.ACTION_HIDE
        }
        SafeServiceStarter.startFg(this, intent)
    }

    companion object {
        private val TARGET_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.spotify.music"
        )
    }
}
