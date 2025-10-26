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
import com.polaralias.audiofocus.overlay.OverlayState
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
        val cur = OverlayStateStore.get()
        var mode = PlayMode.NONE
        var hole: RectF? = null
        var mask = false
        val controller = (OverlayService.mediaController ?: MediaControllerStore.getController())
            ?.takeIf { it.packageName == pkg }
        val playingLike = controller?.playbackState?.state in listOf(
            android.media.session.PlaybackState.STATE_PLAYING,
            android.media.session.PlaybackState.STATE_BUFFERING
        )
        if (app == AppKind.YOUTUBE) {
            val shorts = isShortsUi(root)
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
                hasVideoSurface(root) -> {
                    mode = PlayMode.VIDEO
                    mask = true
                }
                else -> {
                    mode = PlayMode.AUDIO
                }
            }
        }
        if (app == AppKind.YTMUSIC) {
            val videoUi = hasVideoSurface(root) || collect(root).any {
                val id = it.viewIdResourceName?.contains("watch", true) == true
                val desc = it.contentDescription?.toString()?.contains("Video", true) == true
                id || desc
            }
            hole = findToggleRectForMusic(root) ?: defaultTopRight(root)
            mode = if (videoUi) PlayMode.VIDEO else PlayMode.AUDIO
            mask = videoUi
        }
        if (app == AppKind.SPOTIFY) {
            val videoUi = hasVideoSurface(root) || collect(root).any {
                it.contentDescription?.toString()?.contains("Video", true) == true ||
                    it.text?.toString()?.contains("Video", true) == true
            }
            hole = findToggleRectForSpotify(root) ?: defaultTopRight(root)
            mode = if (videoUi) PlayMode.VIDEO else PlayMode.AUDIO
            mask = videoUi
        }
        if (app == AppKind.NEWPIPE) {
            val pip = detectPiP(pkg)
            val videoUi = hasVideoSurface(root) || pip
            mode = when {
                pip -> PlayMode.PIP
                videoUi -> PlayMode.VIDEO
                else -> PlayMode.NONE
            }
            mask = videoUi
        }
        val next = cur.copy(
            app = app,
            playing = playingLike,
            mode = mode,
            maskEnabled = mask && playingLike,
            hole = if (mask) hole else null
        )
        schedule(next)
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
    }

    private fun schedule(next: OverlayState) {
        if (pending) return
        pending = true
        h.postDelayed({
            pending = false
            if (OverlayStateStore.get() == next) {
                return@postDelayed
            }
            OverlayStateStore.update(this, next)
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE
            })
        }, 120)
    }

    private fun isShortsUi(root: AccessibilityNodeInfo): Boolean {
        val ids = listOf(
            ":id/shorts",
            ":id/shorts_container",
            ":id/shorts_player",
            ":id/reel",
            ":id/reels",
            ":id/reel_player"
        )
        val textHits = listOf("Shorts", "Reel")
        val nodes = collect(root)
        val hasId = nodes.any { node ->
            val id = node.viewIdResourceName
            id != null && ids.any { hint -> id.endsWith(hint) }
        }
        val hasTxt = nodes.any { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            textHits.any { key -> text.contains(key, true) || desc.contains(key, true) }
        }
        val pager = nodes.any { node ->
            node.isScrollable && (
                node.className?.toString()?.contains("ViewPager", true) == true ||
                    node.className?.toString()?.contains("RecyclerView", true) == true
                )
        }
        return hasId || (hasTxt && pager)
    }

    private fun hasVideoSurface(root: AccessibilityNodeInfo): Boolean {
        return collect(root).any { node ->
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            cls.contains("SurfaceView", true) ||
                cls.contains("TextureView", true) ||
                id.endsWith(":id/player_view") ||
                id.endsWith(":id/video") ||
                id.endsWith(":id/player_surface")
        }
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

    private fun findToggleRectForMusic(root: AccessibilityNodeInfo): RectF? {
        val metrics = resources.displayMetrics
        val padding = 12f * metrics.density
        val bounds = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        val keywords = listOf("switch to audio", "switch to song", "switch to video", "audio", "song", "video")
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val value = node.text?.toString().takeUnless { it.isNullOrBlank() }
                ?: node.contentDescription?.toString().takeUnless { it.isNullOrBlank() }
            val lower = value?.lowercase()
            if (!lower.isNullOrEmpty() && keywords.any { lower.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    var rect = RectF(bounds)
                    rect.inset(-padding, -padding)
                    rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                    return enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
    }

    private fun findToggleRectForSpotify(root: AccessibilityNodeInfo): RectF? {
        findSpotifyRectByText(root)?.let { return it }
        findSpotifyRectById(root)?.let { return it }
        return null
    }

    private fun findSpotifyRectByText(root: AccessibilityNodeInfo): RectF? {
        val metrics = resources.displayMetrics
        val padding = 12f * metrics.density
        val bounds = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        val hints = listOf("video", "show video", "hide video", "switch to video", "switch to audio")
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            if (!node.isVisibleToUser) {
                for (i in 0 until node.childCount) {
                    queue.add(node.getChild(i))
                }
                continue
            }
            val value = node.text?.toString().takeUnless { it.isNullOrBlank() }
                ?: node.contentDescription?.toString()
            val lower = value?.lowercase()
            if (!lower.isNullOrEmpty() && hints.any { lower.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    var rect = RectF(bounds)
                    rect.inset(-padding, -padding)
                    rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                    return enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
    }

    private fun findSpotifyRectById(root: AccessibilityNodeInfo): RectF? {
        val metrics = resources.displayMetrics
        val padding = 12f * metrics.density
        val bounds = Rect()
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        val hints = listOf(":id/video", ":id/player_video", ":id/toggle", ":id/switch")
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            if (!node.isVisibleToUser) {
                for (i in 0 until node.childCount) {
                    queue.add(node.getChild(i))
                }
                continue
            }
            val viewId = node.viewIdResourceName.orEmpty()
            if (hints.any { viewId.endsWith(it) || viewId.contains(it) }) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    var rect = RectF(bounds)
                    rect.inset(-padding, -padding)
                    rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                    return enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
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

    private fun enrichWithParentBounds(
        node: AccessibilityNodeInfo,
        fallback: RectF,
        screenWidth: Float,
        screenHeight: Float
    ): RectF {
        var best = fallback
        var parent = node.parent
        val bounds = Rect()
        var depth = 0
        while (parent != null && depth < 4) {
            parent.getBoundsInScreen(bounds)
            if (!bounds.isEmpty) {
                val rect = clampToScreen(RectF(bounds), screenWidth, screenHeight)
                val wideEnough = rect.width() >= best.width() * 0.9f
                val tallEnough = rect.height() >= best.height() * 0.9f
                if (wideEnough && tallEnough) {
                    best = rect
                }
            }
            val next = parent.parent
            parent.recycle()
            parent = next
            depth++
        }
        return best
    }

    private fun clampToScreen(rect: RectF, screenWidth: Float, screenHeight: Float): RectF {
        val left = rect.left.coerceIn(0f, screenWidth)
        val top = rect.top.coerceIn(0f, screenHeight)
        val right = rect.right.coerceIn(left, screenWidth)
        val bottom = rect.bottom.coerceIn(top, screenHeight)
        return RectF(left, top, right, bottom)
    }

    private fun defaultTopRight(root: AccessibilityNodeInfo): RectF {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val size = 96f * density
        val margin = 16f * density
        val rootBounds = Rect()
        root.getBoundsInScreen(rootBounds)
        val topBase = rootBounds.top.toFloat().coerceAtLeast(0f)
        val left = metrics.widthPixels - size - margin
        val top = margin + topBase
        val right = metrics.widthPixels - margin
        val bottom = top + size
        return RectF(left.coerceAtLeast(0f), top, right.coerceAtLeast(left), bottom)
    }

    companion object {
        private const val YOUTUBE = "com.google.android.youtube"
        private const val YTMUSIC = "com.google.android.apps.youtube.music"
        private const val SPOTIFY = "com.spotify.music"
        private const val NEWPIPE = "org.schabi.newpipe"
        private val SUPPORTED = setOf(YOUTUBE, YTMUSIC, SPOTIFY, NEWPIPE)
        private val TARGET_PACKAGES = arrayOf(YOUTUBE, YTMUSIC, SPOTIFY, NEWPIPE)
    }
}
