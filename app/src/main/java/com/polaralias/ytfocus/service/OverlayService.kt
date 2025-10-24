package com.polaralias.ytfocus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.RectF
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.polaralias.ytfocus.R
import com.polaralias.ytfocus.bus.OverlayBus
import com.polaralias.ytfocus.media.MediaControllerStore
import com.polaralias.ytfocus.overlay.HoleOverlayView

class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.polaralias.ytfocus.action.SHOW"
        const val ACTION_HIDE = "com.polaralias.ytfocus.action.HIDE"
        private const val CHANNEL_ID = "overlay_channel"
        private const val NOTIFICATION_ID = 101
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var holeView: HoleOverlayView? = null
    private var playPauseButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var elapsedLabel: TextView? = null
    private var totalLabel: TextView? = null
    private var seekBar: SeekBar? = null
    private var currentController: MediaController? = null
    private var isTracking = false
    private var currentHole: RectF? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            updateState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            updateDuration()
        }

        override fun onSessionDestroyed() {
            clearController()
            hideOverlay()
        }
    }

    private val controllerListener: (MediaController?) -> Unit = {
        attachController(it)
    }

    private val holeListener: (RectF?) -> Unit = {
        currentHole = it
        applyHole()
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        MediaControllerStore.addListener(controllerListener)
        OverlayBus.addListener(holeListener)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        OverlayBus.removeListener(holeListener)
        MediaControllerStore.removeListener(controllerListener)
        clearController()
        removeOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> hideOverlay()
            else -> showOverlay()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.overlay_notification_channel), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            return
        }
        if (overlayView == null) {
            val inflater = LayoutInflater.from(this)
            val view = inflater.inflate(R.layout.service_overlay, null)
            overlayView = view
            holeView = view.findViewById(R.id.holeView)
            playPauseButton = view.findViewById(R.id.buttonPlayPause)
            previousButton = view.findViewById(R.id.buttonPrevious)
            nextButton = view.findViewById(R.id.buttonNext)
            elapsedLabel = view.findViewById(R.id.elapsedTime)
            totalLabel = view.findViewById(R.id.totalTime)
            seekBar = view.findViewById(R.id.seekBar)
            configureControls()
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager?.addView(view, params)
        }
        attachController(MediaControllerStore.getController())
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun hideOverlay() {
        handler.removeCallbacks(ticker)
        removeOverlay()
        clearController()
    }

    private fun removeOverlay() {
        overlayView?.let { windowManager?.removeView(it) }
        overlayView = null
        holeView = null
        playPauseButton = null
        previousButton = null
        nextButton = null
        elapsedLabel = null
        totalLabel = null
        seekBar = null
    }

    private fun configureControls() {
        previousButton?.setOnClickListener {
            currentController?.transportControls?.skipToPrevious()
        }
        nextButton?.setOnClickListener {
            currentController?.transportControls?.skipToNext()
        }
        playPauseButton?.setOnClickListener {
            val state = currentController?.playbackState?.state
            if (state == PlaybackState.STATE_PLAYING) {
                currentController?.transportControls?.pause()
            } else {
                currentController?.transportControls?.play()
            }
        }
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                isTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = currentController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val position = duration * seekBar.progress / seekBar.max
                currentController?.transportControls?.seekTo(position)
                isTracking = false
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = currentController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                    val position = duration * progress / seekBar.max
                    elapsedLabel?.text = formatTime(position)
                }
            }
        })
    }

    private fun attachController(controller: MediaController?) {
        if (controller == currentController) {
            updateState()
            return
        }
        currentController?.unregisterCallback(controllerCallback)
        currentController = controller
        if (controller != null) {
            controller.registerCallback(controllerCallback)
            updateDuration()
            updateState()
        } else {
            hideOverlay()
        }
    }

    private fun clearController() {
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun updateState() {
        val controller = currentController
        if (controller == null) {
            hideOverlay()
            return
        }
        val state = controller.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else {
            handler.removeCallbacks(ticker)
        }
        if (state == PlaybackState.STATE_PLAYING) {
            playPauseButton?.setImageResource(R.drawable.ic_pause)
            playPauseButton?.contentDescription = getString(R.string.media_pause)
        } else {
            playPauseButton?.setImageResource(R.drawable.ic_play)
            playPauseButton?.contentDescription = getString(R.string.media_play)
        }
        updateProgress()
        applyHole()
    }

    private fun updateDuration() {
        val duration = currentController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        totalLabel?.text = formatTime(duration)
    }

    private fun updateProgress() {
        if (isTracking) {
            return
        }
        val controller = currentController ?: return
        val state = controller.playbackState ?: return
        val duration = controller.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (duration <= 0L) {
            seekBar?.isEnabled = false
            elapsedLabel?.text = formatTime(0L)
            totalLabel?.text = formatTime(0L)
            return
        }
        seekBar?.isEnabled = true
        val position = state.position.coerceIn(0L, duration)
        val max = seekBar?.max ?: 1000
        val scaled = ((position * max) / duration).toInt()
        seekBar?.progress = scaled
        elapsedLabel?.text = formatTime(position)
        totalLabel?.text = formatTime(duration)
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }

    private fun applyHole() {
        val controller = currentController
        if (controller?.packageName == YOUTUBE_MUSIC_PACKAGE) {
            holeView?.setHole(currentHole)
        } else {
            holeView?.setHole(null)
        }
    }
}
