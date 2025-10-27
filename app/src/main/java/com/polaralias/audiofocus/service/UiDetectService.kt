package com.polaralias.audiofocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.overlay.AppKind
import com.polaralias.audiofocus.overlay.OverlayStateStore
import com.polaralias.audiofocus.overlay.PlayMode
import com.polaralias.audiofocus.util.Logx

class UiDetectService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("UiDetectService.onServiceConnected sdk=${Build.VERSION.SDK_INT}")
        val info = serviceInfo
        if (info != null) {
            info.packageNames = TARGET_PACKAGES
            info.eventTypes = info.eventTypes or AccessibilityEvent.TYPE_VIEW_SCROLLED
            serviceInfo = info
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString() ?: return
        if (!SUPPORTED.contains(pkg)) {
            return
        }
        val app = when (pkg) {
            YOUTUBE -> AppKind.YOUTUBE
            YTMUSIC -> AppKind.YTMUSIC
            SPOTIFY -> AppKind.SPOTIFY
            NEWPIPE -> AppKind.NEWPIPE
            else -> AppKind.NONE
        }
        if (app == AppKind.NONE) {
            return
        }
        var mode = PlayMode.NONE
        var hole: RectF? = null
        var mask = false
        val controller = OverlayService.mediaController ?: MediaControllerStore.getController()
        val playingLike = controller?.playbackState?.state in listOf(
            android.media.session.PlaybackState.STATE_PLAYING,
            android.media.session.PlaybackState.STATE_BUFFERING
        )
        val nodes = collect(root)
        val surfaceFraction = videoSurfaceFraction(nodes)
        if (app == AppKind.YOUTUBE) {
            val shorts = isShortsUi(nodes)
            val pip = detectPiP(pkg)
            when {
                shorts -> {
                    mode = PlayMode.SHORTS
                    mask = true
                }
                pip -> {
                    mode = PlayMode.PIP
                    mask = true
                }
                surfaceFraction > 0f -> {
                    mode = PlayMode.VIDEO
                    mask = true
                }
                else -> {
                    mode = PlayMode.AUDIO
                }
            }
        }
        if (app == AppKind.YTMUSIC) {
            val selectedMode = ytMusicSelectedMode(nodes)
            val detectedVideoUi = surfaceFraction > 0f || nodes.any {
                val id = it.viewIdResourceName?.contains("watch", true) == true
                val desc = it.contentDescription?.toString()?.contains("Video", true) == true
                id || desc
            }
            val videoUi = when (selectedMode) {
                PlayMode.AUDIO -> false
                PlayMode.VIDEO -> true
                else -> detectedVideoUi
            }
            val fullscreenVideo = videoUi && surfaceFraction >= FULLSCREEN_FRACTION_THRESHOLD
            hole = if (videoUi && !fullscreenVideo) {
                topBandHole(root, TOP_BAND_FRACTION)
            } else {
                null
            }
            mode = selectedMode ?: if (videoUi) PlayMode.VIDEO else PlayMode.AUDIO
            mask = videoUi
        }
        if (app == AppKind.SPOTIFY) {
            val videoUi = surfaceFraction > 0f || nodes.any {
                it.contentDescription?.toString()?.contains("Video", true) == true ||
                    it.text?.toString()?.contains("Video", true) == true
            }
            val fullscreenVideo = videoUi && surfaceFraction >= FULLSCREEN_FRACTION_THRESHOLD
            hole = if (videoUi && !fullscreenVideo) {
                topBandHole(root, TOP_BAND_FRACTION)
            } else {
                null
            }
            mode = if (videoUi) PlayMode.VIDEO else PlayMode.AUDIO
            mask = videoUi
        }
        if (app == AppKind.NEWPIPE) {
            val pip = detectPiP(pkg)
            val videoUi = surfaceFraction > 0f || pip
            mode = when {
                pip -> PlayMode.PIP
                surfaceFraction > 0f -> PlayMode.VIDEO
                else -> PlayMode.NONE
            }
            mask = videoUi
        }
        schedule(app, mode, mask, hole, playingLike)
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
    }

    private fun schedule(
        app: AppKind,
        mode: PlayMode,
        mask: Boolean,
        hole: RectF?,
        playingLike: Boolean
    ) {
        if (pending) return
        pending = true
        h.postDelayed({
            pending = false
            val cur = OverlayStateStore.get()
            val playing = if (playingLike) cur.playing else false
            val next = cur.copy(
                app = app,
                playing = playing,
                mode = mode,
                maskEnabled = mask,
                hole = if (mask) hole else null
            )
            if (cur == next) {
                return@postDelayed
            }
            OverlayStateStore.update(this, next)
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE
            })
        }, 120)
    }

    private fun isShortsUi(nodes: List<AccessibilityNodeInfo>): Boolean {
        val ids = listOf(
            "shorts",
            "reel",
            "reels",
            "pivot_shorts",
            "shorts_pivot",
            "shorts_player"
        )
        val textHits = listOf("Shorts", "Reel")
        val hasId = nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            ids.any { hint -> id.endsWith(hint) || id.contains(hint) }
        }
        val hasTxt = nodes.any { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            textHits.any { key -> text.contains(key, true) || desc.contains(key, true) }
        }
        val hasClass = nodes.any { node ->
            val cls = node.className?.toString().orEmpty()
            cls.contains("Short", true) || cls.contains("Reel", true)
        }
        val pager = nodes.any { node ->
            node.isScrollable && (
                node.className?.toString()?.contains("ViewPager", true) == true ||
                    node.className?.toString()?.contains("RecyclerView", true) == true ||
                    node.className?.toString()?.contains("ViewPager2", true) == true
                )
        }
        return hasId || hasClass || (hasTxt && pager)
    }

    private fun videoSurfaceFraction(nodes: List<AccessibilityNodeInfo>): Float {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat()
        val screenHeight = metrics.heightPixels.toFloat()
        if (screenWidth <= 0f || screenHeight <= 0f) {
            return 0f
        }
        val screenArea = screenWidth * screenHeight
        val screenRect = Rect()
        var best = 0f
        for (node in nodes) {
            if (!node.isVisibleToUser) {
                continue
            }
            node.getBoundsInScreen(screenRect)
            val width = screenRect.width().coerceAtLeast(0)
            val height = screenRect.height().coerceAtLeast(0)
            if (width <= 0 || height <= 0) {
                continue
            }
            val clsLower = node.className?.toString()?.lowercase().orEmpty()
            val idLower = node.viewIdResourceName.orEmpty().lowercase()
            val descLower = node.contentDescription?.toString()?.lowercase().orEmpty()
            val classHit = clsLower.contains("surfaceview") ||
                clsLower.contains("textureview") ||
                clsLower.contains("playerview") ||
                clsLower.contains("youtubeplayer") ||
                clsLower.contains("videoview") ||
                clsLower.contains("videoplayer") ||
                clsLower.contains("recyclertextureview")
            val idHit = idLower.endsWith(":id/player_view") ||
                idLower.endsWith(":id/video") ||
                idLower.endsWith(":id/player_surface") ||
                idLower.contains("player_view") ||
                idLower.contains("player_container") ||
                idLower.contains("watch_player") ||
                idLower.contains("video_surface") ||
                idLower.contains("video_player") ||
                idLower.contains("shorts_player")
            val descHit = descLower.contains("video player") ||
                descLower.contains("double tap to seek") ||
                descLower.contains("playing video")
            if (classHit || idHit || descHit) {
                val area = width.toFloat() * height.toFloat()
                val fraction = (area / screenArea).coerceAtLeast(0f)
                if (fraction > best) {
                    best = fraction
                }
            }
        }
        return best.coerceIn(0f, 1f)
    }

    private fun detectPiP(packageName: String): Boolean {
        val wins = windows ?: emptyList()
        val screen = resources.displayMetrics
        val w = screen.widthPixels
        val h = screen.heightPixels
        return wins.any { window ->
            val rect = Rect()
            window.root?.getBoundsInScreen(rect)
            val small = rect.width() * rect.height() < (w * h) / 3
            window.root?.packageName == packageName && small
        }
    }

    private fun ytMusicSelectedMode(nodes: List<AccessibilityNodeInfo>): PlayMode? {
        val labels = setOf("song", "songs", "video", "videos")
        nodes.forEach { node ->
            if (!node.isSelected) {
                return@forEach
            }
            val label = node.text?.toString().orEmpty().ifBlank {
                node.contentDescription?.toString().orEmpty()
            }.trim().lowercase()
            if (!labels.contains(label)) {
                return@forEach
            }
            return when {
                label.startsWith("song") -> PlayMode.AUDIO
                label.startsWith("video") -> PlayMode.VIDEO
                else -> null
            }
        }
        return null
    }

    private fun topBandHole(root: AccessibilityNodeInfo, fraction: Float): RectF {
        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels.toFloat().coerceAtLeast(0f)
        val screenHeight = metrics.heightPixels.toFloat().coerceAtLeast(0f)
        val clampedFraction = fraction.coerceIn(0f, 1f)
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val topBase = rootBounds.top.toFloat().coerceIn(0f, screenHeight)
        val availableHeight = (screenHeight - topBase).coerceAtLeast(0f)
        val bandHeight = (availableHeight * clampedFraction).coerceAtMost(screenHeight)
        val bottom = (topBase + bandHeight).coerceIn(topBase, screenHeight)
        return RectF(0f, topBase, screenWidth, bottom)
    }

    private fun collect(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> {
        val result = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            result.add(node)
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return result
    }

    companion object {
        private const val YOUTUBE = "com.google.android.youtube"
        private const val YTMUSIC = "com.google.android.apps.youtube.music"
        private const val SPOTIFY = "com.spotify.music"
        private const val NEWPIPE = "org.schabi.newpipe"
        private val SUPPORTED = setOf(YOUTUBE, YTMUSIC, SPOTIFY, NEWPIPE)
        private val TARGET_PACKAGES = arrayOf(YOUTUBE, YTMUSIC, SPOTIFY, NEWPIPE)
        private const val FULLSCREEN_FRACTION_THRESHOLD = 0.45f
        private const val TOP_BAND_FRACTION = 0.22f
    }
}
