package com.polaralias.audiofocus.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.polaralias.audiofocus.HoleOverlayView
import com.polaralias.audiofocus.R
import com.polaralias.audiofocus.bus.OverlayBus
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.media.isActivelyPlaying
import com.polaralias.audiofocus.util.Logx

class OverlayService : Service() {
    companion object {
        const val ACTION_SHOW = "com.polaralias.audiofocus.action.SHOW"
        const val ACTION_HIDE = "com.polaralias.audiofocus.action.HIDE"
        private const val CHANNEL_ID = "veil_audio"
        private const val NOTIFICATION_ID = 101
        @Volatile var mediaController: MediaController? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            updateProgress()
            handler.postDelayed(this, 500)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayContext: Context? = null
    private var blockView: HoleOverlayView? = null
    private var controlsView: View? = null
    private var playPauseButton: ImageButton? = null
    private var previousButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var elapsedLabel: TextView? = null
    private var totalLabel: TextView? = null
    private var seekBar: SeekBar? = null
    private var currentController: MediaController? = null
    private var isTracking = false
    private var currentHole: RectF? = null
    private var currentMaskEnabled = true

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Logx.d("OverlayService.controllerCallback.onPlaybackStateChanged state=${state?.state}")
            updateState()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Logx.d("OverlayService.controllerCallback.onMetadataChanged")
            updateDuration()
        }

        override fun onSessionDestroyed() {
            Logx.d("OverlayService.controllerCallback.onSessionDestroyed")
            clearController()
            hideOverlay()
        }
    }

    private val controllerListener: (MediaController?) -> Unit = {
        Logx.d("OverlayService.controllerListener package=${it?.packageName}")
        attachController(it)
    }

    override fun onCreate() {
        Logx.d("OverlayService.onCreate sdk=${Build.VERSION.SDK_INT} canDraw=${Settings.canDrawOverlays(this)}")
        super.onCreate()
        createChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        MediaControllerStore.addListener(controllerListener)
    }

    override fun onDestroy() {
        Logx.d("OverlayService.onDestroy")
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        MediaControllerStore.removeListener(controllerListener)
        clearController()
        removeOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Logx.d("OverlayService.onStartCommand action=${intent?.action} flags=$flags startId=$startId")
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
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_running))
            .setContentText(getString(R.string.overlay_notification_text))
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setOngoing(true)
            .build()
    }

    private fun showOverlay() {
        val canDraw = Settings.canDrawOverlays(this)
        Logx.d("OverlayService.showOverlay canDraw=$canDraw")
        if (!canDraw) {
            return
        }
        ensureBlockView()
        ensureControlsView()
        setHole(OverlayBus.hole)
        applyBounds()
        setMaskEnabled(OverlayBus.maskEnabled)
        blockView?.visibility = View.VISIBLE
        controlsView?.visibility = View.VISIBLE
        updateForOrientation()
        attachController(MediaControllerStore.getController())
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    private fun hideOverlay() {
        Logx.d("OverlayService.hideOverlay")
        handler.removeCallbacks(ticker)
        removeOverlay()
        clearController()
    }

    private fun ensureBlockView() {
        if (blockView != null) {
            return
        }
        val context = obtainOverlayContext()
        val manager = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
        val view = HoleOverlayView(context)
        blockView = view
        val params = overlayParams()
        manager.addView(view, params)
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(0, sb.top, 0, 0)
            } else {
                v.setPadding(0, insets.systemWindowInsetTop, 0, 0)
            }
            insets
        }
        view.requestApplyInsets()
    }

    private fun ensureControlsView() {
        if (controlsView != null) {
            return
        }
        val context = obtainOverlayContext()
        val manager = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).also {
            windowManager = it
        }
        val view = LayoutInflater.from(context).inflate(R.layout.service_overlay, null)
        controlsView = view
        playPauseButton = view.findViewById(R.id.buttonPlayPause)
        previousButton = view.findViewById(R.id.buttonPrevious)
        nextButton = view.findViewById(R.id.buttonNext)
        elapsedLabel = view.findViewById(R.id.elapsedTime)
        totalLabel = view.findViewById(R.id.totalTime)
        seekBar = view.findViewById(R.id.seekBar)
        configureControls()
        val params = controlsParams()
        manager.addView(view, params)
        view.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val sb = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(sb.left, 0, sb.right, sb.bottom)
            } else {
                v.setPadding(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        view.requestApplyInsets()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun overlayParams(fullscreen: Boolean = true): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        return params
    }

    private fun applyBounds() {
        ensureBlockView()
        val manager = windowManager ?: return
        val view = blockView ?: return
        val lp = overlayParams(true)
        if (!currentMaskEnabled) {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        manager.updateViewLayout(view, lp)
    }

    private fun controlsParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        if (Build.VERSION.SDK_INT >= 28) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_NEVER
        }
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        return params
    }

    private fun removeOverlay() {
        blockView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        controlsView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {
            }
        }
        blockView = null
        controlsView = null
        playPauseButton = null
        previousButton = null
        nextButton = null
        elapsedLabel = null
        totalLabel = null
        seekBar = null
        windowManager = null
        overlayContext = null
    }

    private fun configureControls() {
        previousButton?.setOnClickListener {
            Logx.d("OverlayService.previousButton click")
            currentController?.transportControls?.skipToPrevious()
        }
        nextButton?.setOnClickListener {
            Logx.d("OverlayService.nextButton click")
            currentController?.transportControls?.skipToNext()
        }
        playPauseButton?.setOnClickListener {
            val playbackState = currentController?.playbackState
            val isPlaying = playbackState.isActivelyPlaying()
            Logx.d("OverlayService.playPauseButton click state=${playbackState?.state} speed=${playbackState?.playbackSpeed} playing=$isPlaying")
            if (isPlaying) {
                currentController?.transportControls?.pause()
            } else {
                currentController?.transportControls?.play()
            }
        }
        seekBar?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                Logx.d("OverlayService.seekBar.onStartTrackingTouch")
                isTracking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = currentController?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                val position = duration * seekBar.progress / seekBar.max
                Logx.d("OverlayService.seekBar.onStopTrackingTouch duration=$duration position=$position")
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

    private fun obtainOverlayContext(): Context {
        overlayContext?.let { return it }
        val baseContext = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                val displayManager = getSystemService(DisplayManager::class.java)
                @Suppress("DEPRECATION")
                val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
                if (display != null) {
                    createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                } else {
                    createConfigurationContext(resources.configuration)
                }
            }
            else -> createConfigurationContext(resources.configuration)
        }
        val themedContext = ContextThemeWrapper(baseContext, R.style.Theme_AudioFocus)
        overlayContext = themedContext
        return themedContext
    }

    private fun attachController(controller: MediaController?) {
        if (controller == currentController) {
            Logx.d("OverlayService.attachController unchanged=${controller?.packageName}")
            updateState()
            return
        }
        Logx.d("OverlayService.attachController from=${currentController?.packageName} to=${controller?.packageName}")
        currentController?.unregisterCallback(controllerCallback)
        currentController = controller
        mediaController = controller
        if (controller != null) {
            controller.registerCallback(controllerCallback)
            updateDuration()
            updateState()
        } else {
            hideOverlay()
        }
    }

    private fun clearController() {
        Logx.d("OverlayService.clearController from=${currentController?.packageName}")
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
        mediaController = null
    }

    private fun updateState() {
        val controller = currentController
        if (controller == null) {
            Logx.d("OverlayService.updateState controller null")
            hideOverlay()
            return
        }
        val playbackState = controller.playbackState
        val isPlaying = playbackState.isActivelyPlaying()
        Logx.d("OverlayService.updateState package=${controller.packageName} state=${playbackState?.state} speed=${playbackState?.playbackSpeed} playing=$isPlaying canDraw=${Settings.canDrawOverlays(this)}")
        if (isPlaying) {
            handler.removeCallbacks(ticker)
            handler.post(ticker)
        } else {
            handler.removeCallbacks(ticker)
        }
        if (isPlaying) {
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
        Logx.d("OverlayService.updateDuration duration=$duration")
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
        val packageName = currentController?.packageName
        Logx.d("OverlayService.applyHole package=$packageName rect=$currentHole mask=$currentMaskEnabled")
        blockView?.setHole(currentHole)
        blockView?.setMaskEnabled(currentMaskEnabled)
    }

    private fun setHole(rect: RectF?) {
        currentHole = rect
        blockView?.setHole(rect)
    }

    fun setMaskEnabled(enabled: Boolean) {
        currentMaskEnabled = enabled
        blockView?.setMaskEnabled(enabled)
        val manager = windowManager ?: return
        val view = blockView ?: return
        val lp = view.layoutParams as WindowManager.LayoutParams
        val type = if (enabled) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        lp.flags = (lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or type
        manager.updateViewLayout(view, lp)
    }

    private fun updateForOrientation() {
        val manager = windowManager ?: return
        val params = controlsView?.layoutParams as? WindowManager.LayoutParams ?: return
        params.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        manager.updateViewLayout(controlsView, params)
        applyBounds()
    }
}
