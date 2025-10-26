package com.polaralias.audiofocus.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.RectF
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.overlay.OverlayState
import com.polaralias.audiofocus.overlay.OverlayStateStore
import com.polaralias.audiofocus.overlay.PlayMode
import com.polaralias.audiofocus.service.OverlayService
import com.polaralias.audiofocus.util.ForegroundApp
import com.polaralias.audiofocus.util.Logx
import kotlin.math.max

class UiDetectService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var pending = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("UiDetectService.onServiceConnected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString() ?: return
        Logx.d("UiDetectService.onAccessibilityEvent package=$pkg type=${event?.eventType}")
        if (!SUPPORTED_PACKAGES.contains(pkg)) {
            return
        }

        val controller = OverlayService.mediaController ?: MediaControllerStore.getController()
        if (controller?.packageName != pkg) {
            return
        }
        val state = controller.playbackState?.state
        val playingLike = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
        if (!playingLike) {
            return
        }
        if (!ForegroundApp.isForeground(this, pkg)) {
            return
        }

        val current = OverlayStateStore.get()
        val computed = when (pkg) {
            YOUTUBE_PACKAGE -> computeYoutubeState(root)
            YOUTUBE_MUSIC_PACKAGE -> computeYtMusicState(root)
            SPOTIFY_PACKAGE -> computeSpotifyState(root)
            else -> Triple(current.mode, current.maskEnabled, current.hole)
        }
        val next = current.copy(mode = computed.first, maskEnabled = computed.second, hole = computed.third)
        if (next == current) {
            return
        }
        schedule(next, this)
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
    }

    private fun schedule(state: OverlayState, ctx: Context) {
        if (pending) return
        pending = true
        h.postDelayed({
            pending = false
            OverlayStateStore.update(ctx, state)
            ctx.startService(Intent(ctx, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE
            })
        }, 150)
    }

    private fun computeYoutubeState(root: AccessibilityNodeInfo): Triple<PlayMode, Boolean, RectF?> {
        if (isShortsUi(root)) {
            return Triple(PlayMode.SHORTS, true, null)
        }
        val videoVisible = isYoutubePlaybackVisible(root)
        val mode = if (videoVisible) PlayMode.VIDEO else PlayMode.AUDIO
        val mask = mode == PlayMode.VIDEO
        return Triple(mode, mask, null)
    }

    private fun computeYtMusicState(root: AccessibilityNodeInfo): Triple<PlayMode, Boolean, RectF?> {
        val hole = findAudioToggleRect(root) ?: defaultToggleGuess(root)
        val audio = isAudioMode(root)
        val mode = if (audio) PlayMode.AUDIO else PlayMode.VIDEO
        val mask = mode == PlayMode.VIDEO
        return Triple(mode, mask, hole)
    }

    private fun computeSpotifyState(root: AccessibilityNodeInfo): Triple<PlayMode, Boolean, RectF?> {
        val toggle = findSpotifyToggleByText(root) ?: findSpotifyToggleById(root)
        val hole = toggle?.rect ?: defaultSpotifyToggleGuess(root) ?: defaultToggleGuess(root)
        val videoDetected = toggle?.state == ToggleState.VIDEO || hasVisibleVideoNode(root)
        val mode = if (videoDetected) PlayMode.VIDEO else PlayMode.AUDIO
        val mask = mode == PlayMode.VIDEO
        return Triple(mode, mask, hole)
    }

    private fun isShortsUi(root: AccessibilityNodeInfo): Boolean {
        val nodes = collect(root)
        val direct = nodes.any { node ->
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty()
            SHORTS_KEYWORDS.any { key ->
                text.contains(key, true) || desc.contains(key, true) || id.contains(key, true)
            }
        }
        if (direct) {
            return true
        }
        val hasPager = nodes.any { node ->
            node.isScrollable && (node.className?.toString()?.contains("RecyclerView", true) == true || node.className?.toString()?.contains("ViewPager", true) == true)
        }
        if (!hasPager) {
            return false
        }
        return nodes.any { node ->
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            id.endsWith("shorts") || id.contains("/shorts") || desc.contains("Shorts", true)
        }
    }

    private fun findAudioToggleRect(root: AccessibilityNodeInfo): RectF? {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        val bounds = Rect()
        val metrics = resources.displayMetrics
        val padding = TOGGLE_PADDING_DP * metrics.density
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            val match = when {
                text == null && desc == null -> false
                else -> {
                    val value = (text ?: desc).orEmpty()
                    val search = value.lowercase()
                    val containsKeyword = KEYWORD_MATCHES.any { search.contains(it) }
                    val exactMatch = EXACT_MATCHES.any { search == it }
                    containsKeyword || exactMatch
                }
            }
            if (match) {
                node.getBoundsInScreen(bounds)
                var rect = RectF(bounds)
                rect.inset(-padding, -padding)
                rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                return enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
    }

    private fun findSpotifyToggleByText(root: AccessibilityNodeInfo): ToggleMatch? {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        val bounds = Rect()
        val metrics = resources.displayMetrics
        val padding = TOGGLE_PADDING_DP * metrics.density
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            if (!node.isVisibleToUser) {
                for (i in 0 until node.childCount) {
                    queue.add(node.getChild(i))
                }
                continue
            }
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            val value = when {
                !text.isNullOrBlank() -> text
                !desc.isNullOrBlank() -> desc
                else -> null
            }
            if (value != null) {
                val lower = value.lowercase()
                val matched = SPOTIFY_TEXT_MATCHES.any { key -> lower.contains(key) }
                if (matched) {
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        var rect = RectF(bounds)
                        rect.inset(-padding, -padding)
                        rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                        rect = enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                        val state = when {
                            lower.contains("hide video") || lower.contains("switch to audio") -> ToggleState.VIDEO
                            lower.contains("show video") || lower.contains("switch to video") -> ToggleState.AUDIO
                            else -> null
                        }
                        return ToggleMatch(rect, state)
                    }
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
    }

    private fun findSpotifyToggleById(root: AccessibilityNodeInfo): ToggleMatch? {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        val bounds = Rect()
        val metrics = resources.displayMetrics
        val padding = TOGGLE_PADDING_DP * metrics.density
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            if (!node.isVisibleToUser) {
                for (i in 0 until node.childCount) {
                    queue.add(node.getChild(i))
                }
                continue
            }
            val viewId = node.viewIdResourceName.orEmpty()
            val matched = SPOTIFY_ID_MATCHES.any { hint -> viewId.endsWith(hint) || viewId.contains(hint) }
            if (matched) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    var rect = RectF(bounds)
                    rect.inset(-padding, -padding)
                    rect = clampToScreen(rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                    rect = enrichWithParentBounds(node, rect, metrics.widthPixels.toFloat(), metrics.heightPixels.toFloat())
                    return ToggleMatch(rect, null)
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return null
    }

    private fun hasVisibleVideoNode(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            if (node.isVisibleToUser) {
                val className = node.className?.toString().orEmpty()
                val desc = node.contentDescription?.toString().orEmpty()
                val text = node.text?.toString().orEmpty()
                if (className.contains("video", true) || desc.contains("video", true) || text.contains("video", true)) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return false
    }

    private fun isYoutubePlaybackVisible(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        val bounds = Rect()
        val metrics = resources.displayMetrics
        val minWidth = metrics.widthPixels * 0.4f
        val minHeight = metrics.heightPixels * 0.3f
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val className = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName.orEmpty().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().lowercase()
            val text = node.text?.toString().orEmpty().lowercase()
            if (node.isVisibleToUser) {
                if (YOUTUBE_SURFACE_CLASSES.any { className.contains(it, ignoreCase = true) }) {
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty && bounds.width() >= minWidth && bounds.height() >= minHeight) {
                        return true
                    }
                }
                val combined = buildString {
                    if (id.isNotBlank()) append(id)
                    if (desc.isNotBlank()) append(' ').append(desc)
                    if (text.isNotBlank()) append(' ').append(text)
                }
                if (combined.isNotBlank() && YOUTUBE_VIDEO_MATCHES.any { combined.contains(it) }) {
                    return true
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return false
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

    private fun defaultSpotifyToggleGuess(root: AccessibilityNodeInfo): RectF? {
        val r = Rect()
        root.getBoundsInScreen(r)
        val w = r.width().toFloat()
        val h = r.height().toFloat()
        if (w <= 0f || h <= 0f) {
            return null
        }
        val holeW = w * 0.22f
        val holeH = h * 0.12f
        val left = r.left + w - holeW - (w * 0.04f)
        val top = r.top + (h * 0.12f)
        return RectF(left, top, left + holeW, top + holeH)
    }

    private fun defaultToggleGuess(root: AccessibilityNodeInfo): RectF {
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
        return RectF(max(0f, left), top, max(0f, right), bottom)
    }

    private fun enrichWithParentBounds(
        node: AccessibilityNodeInfo,
        fallback: RectF,
        screenWidth: Float,
        screenHeight: Float
    ): RectF {
        var best = fallback
        var parent = node.parent
        val parentBounds = Rect()
        var depth = 0
        while (parent != null && depth < 4) {
            parent.getBoundsInScreen(parentBounds)
            if (!parentBounds.isEmpty) {
                val rect = clampToScreen(RectF(parentBounds), screenWidth, screenHeight)
                val wider = rect.width() >= best.width() * 0.9f
                val taller = rect.height() >= best.height() * 0.9f
                if (wider && taller) {
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

    private fun isAudioMode(root: AccessibilityNodeInfo): Boolean {
        val audioOnTexts = listOf("Audio", "Song", "Switch to song")
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val txt = node.text?.toString().orEmpty()
            val cd = node.contentDescription?.toString().orEmpty()
            val sel = node.isSelected || node.isChecked
            if (sel && audioOnTexts.any { key -> txt.contains(key, true) || cd.contains(key, true) }) {
                return true
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        return false
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
        private const val TOGGLE_PADDING_DP = 12f
        private val KEYWORD_MATCHES = listOf(
            "switch to audio",
            "switch to song",
            "switch to video"
        )
        private val EXACT_MATCHES = listOf("audio", "song", "video")
        private val SPOTIFY_TEXT_MATCHES = listOf(
            "video",
            "show video",
            "hide video",
            "switch to video",
            "switch to audio"
        )
        private val SPOTIFY_ID_MATCHES = listOf(
            ":id/video",
            ":id/player_video",
            ":id/toggle",
            ":id/switch"
        )
        private val SHORTS_KEYWORDS = listOf("shorts", "Shorts")
        private val SUPPORTED_PACKAGES = setOf(
            YOUTUBE_PACKAGE,
            YOUTUBE_MUSIC_PACKAGE,
            SPOTIFY_PACKAGE
        )
        private val YOUTUBE_SURFACE_CLASSES = listOf(
            "SurfaceView",
            "TextureView",
            "PlayerView"
        )
        private val YOUTUBE_VIDEO_MATCHES = listOf(
            "video_player",
            "watch_player",
            "shorts",
            "shorts player",
            "video player",
            "player_view"
        )
    }

    private data class ToggleMatch(val rect: RectF, val state: ToggleState?)

    private enum class ToggleState { VIDEO, AUDIO }
}
