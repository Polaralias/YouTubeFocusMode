package com.polaralias.ytfocus.service

import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.content.ContextCompat
import com.polaralias.ytfocus.media.MediaControllerStore
import com.polaralias.ytfocus.util.ForegroundApp

class MediaListenerService : NotificationListenerService(), MediaSessionManager.OnActiveSessionsChangedListener {
    private lateinit var sessionManager: MediaSessionManager
    private var observedController: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            evaluateControllers()
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            evaluateControllers()
        }

        override fun onSessionDestroyed() {
            evaluateControllers()
        }
    }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MediaSessionManager::class.java)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        sessionManager.addOnActiveSessionsChangedListener(this, componentName)
        evaluateControllers()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        sessionManager.removeOnActiveSessionsChangedListener(this)
        updateController(null)
        sendHide()
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        evaluateControllers()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        evaluateControllers()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        evaluateControllers()
    }

    private fun evaluateControllers() {
        val controllers = sessionManager.getActiveSessions(componentName) ?: emptyList()
        val target = controllers.firstOrNull { isTargetActive(it) }
        if (target != null) {
            updateController(target)
            MediaControllerStore.setController(target)
            sendShow()
        } else {
            updateController(null)
            MediaControllerStore.setController(null)
            sendHide()
        }
    }

    private fun isTargetActive(controller: MediaController): Boolean {
        val packageName = controller.packageName
        if (packageName != YOUTUBE && packageName != YOUTUBE_MUSIC) {
            return false
        }
        val state = controller.playbackState?.state
        if (state != PlaybackState.STATE_PLAYING) {
            return false
        }
        if (!ForegroundApp.isForeground(this, packageName)) {
            return false
        }
        return true
    }

    private fun updateController(controller: MediaController?) {
        if (controller == observedController) {
            return
        }
        observedController?.unregisterCallback(controllerCallback)
        observedController = controller
        controller?.registerCallback(controllerCallback)
    }

    private fun sendShow() {
        val intent = Intent(this, OverlayService::class.java)
        intent.action = OverlayService.ACTION_SHOW
        ContextCompat.startForegroundService(this, intent)
    }

    private fun sendHide() {
        val intent = Intent(this, OverlayService::class.java)
        intent.action = OverlayService.ACTION_HIDE
        ContextCompat.startForegroundService(this, intent)
    }

    companion object {
        private const val YOUTUBE = "com.google.android.youtube"
        private const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"
    }
}
