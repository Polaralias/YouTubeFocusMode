package com.polaralias.audiofocus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.media.session.PlaybackState
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.audiofocus.bus.OverlayBus
import com.polaralias.audiofocus.media.MediaControllerStore
import com.polaralias.audiofocus.util.ForegroundApp
import com.polaralias.audiofocus.util.Logx
import kotlin.math.max

class UiDetectService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("UiDetectService.onServiceConnected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString() ?: return
        Logx.d("UiDetectService.onAccessibilityEvent package=$pkg type=${event?.eventType}")
        if (!SUPPORTED_PACKAGES.contains(pkg)) {
            OverlayBus.clearUiDetection()
            return
        }

        val controller = OverlayService.mediaController ?: MediaControllerStore.getController()
        val activeFromTarget = controller?.packageName == pkg && controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!activeFromTarget) {
            OverlayBus.pipRect = null
            OverlayBus.clearUiDetection()
            return
        }

        val pip = findPipRectFor(pkg)
        if (pip != null) {
            OverlayBus.pipRect = pip
            var shouldShow = false
            when (pkg) {
                YOUTUBE_PACKAGE, NEWPIPE_PACKAGE -> {
                    OverlayBus.hole = null
                    OverlayBus.maskEnabled = true
                    shouldShow = true
                }
                YOUTUBE_MUSIC_PACKAGE -> {
                    val isAudio = isAudioMode(root)
                    val hole = findAudioToggleRect(root) ?: defaultToggleGuess(root)
                    OverlayBus.hole = hole
                    OverlayBus.maskEnabled = !isAudio
                    shouldShow = true
                }
                SPOTIFY_PACKAGE -> {
                    val vm = isSpotifyVideoMode(root)
                    val hole = findSpotifyToggleRect(root) ?: defaultSpotifyToggleGuess(root)
                    OverlayBus.hole = hole
                    OverlayBus.maskEnabled = vm
                    shouldShow = true
                }
            }
            if (shouldShow) {
                OverlayBus.markUiDetection()
                startService(IntentBuilder.show(this))
            } else {
                OverlayBus.clearUiDetection()
            }
            return
        } else {
            OverlayBus.pipRect = null
        }

        if (!activeFromTarget) {
            OverlayBus.clearUiDetection()
            return
        }

        val inForeground = ForegroundApp.isTargetInForeground(this)
        if (!inForeground) {
            Logx.d("UiDetectService.onAccessibilityEvent not detected in foreground via usage stats")
        }

        var shouldShow = false
        when (pkg) {
            YOUTUBE_PACKAGE, NEWPIPE_PACKAGE -> {
                OverlayBus.maskEnabled = true
                OverlayBus.hole = null
                shouldShow = true
            }
            YOUTUBE_MUSIC_PACKAGE -> {
                handleYtMusic(root)
                shouldShow = true
            }
            SPOTIFY_PACKAGE -> {
                handleSpotify(root)
                shouldShow = true
            }
        }
        if (shouldShow) {
            OverlayBus.markUiDetection()
            startService(IntentBuilder.show(this))
        } else {
            OverlayBus.clearUiDetection()
        }
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
    }

    private fun handleYtMusic(root: AccessibilityNodeInfo) {
        val rect = findAudioToggleRect(root) ?: defaultToggleGuess(root)
        OverlayBus.hole = rect
        val audio = isAudioMode(root)
        OverlayBus.maskEnabled = !audio
    }

    private fun handleSpotify(root: AccessibilityNodeInfo) {
        val toggle = findSpotifyToggleByText(root) ?: findSpotifyToggleById(root)
        val rect = toggle?.rect ?: defaultToggleGuess(root)
        val videoDetected = toggle?.state == ToggleState.VIDEO || hasVisibleVideoNode(root)
        val audioDetected = toggle?.state == ToggleState.AUDIO && !videoDetected
        val videoMode = when {
            videoDetected -> true
            audioDetected -> false
            else -> false
        }
        OverlayBus.hole = rect
        OverlayBus.maskEnabled = videoMode
        Logx.d("UiDetectService.spotify video=$videoMode rect=$rect")
    }

    private fun findPipRectFor(pkg: String): RectF? {
        val windows = windows ?: return null
        if (windows.isEmpty()) return null
        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val screenA = screenW.toFloat() * screenH.toFloat()
        var best: RectF? = null
        for (w in windows) {
            val root = w.root ?: continue
            val owner = root.packageName?.toString() ?: continue
            if (owner != pkg) continue
            val r = Rect()
            w.getBoundsInScreen(r)
            val rw = r.width().toFloat()
            val rh = r.height().toFloat()
            if (rw <= 0f || rh <= 0f) continue
            val area = rw * rh
            val isSmall = area <= screenA * 0.4f
            if (!isSmall) continue
            val rectf = RectF(r)
            if (best == null || area < (best!!.width() * best!!.height())) best = rectf
        }
        return best
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

    private fun isSpotifyVideoMode(root: AccessibilityNodeInfo): Boolean {
        val hits = listOf("Video", "Show video", "Hide video")
        return collect(root).any { n ->
            val t = n.text?.toString().orEmpty()
            val c = n.contentDescription?.toString().orEmpty()
            hits.any { k -> t.contains(k, true) || c.contains(k, true) }
        }
    }

    private fun findSpotifyToggleRect(root: AccessibilityNodeInfo): RectF? {
        val nodes = collect(root).filter { n ->
            val t = n.text?.toString().orEmpty()
            val c = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            listOf("Video", "Show video", "Hide video").any { k -> t.contains(k, true) || c.contains(k, true) } ||
                    id.endsWith(":id/video") || id.endsWith(":id/toggle") || id.endsWith(":id/switch")
        }
        val node = nodes.firstOrNull() ?: return null
        val r = Rect()
        node.getBoundsInScreen(r)
        return RectF(r)
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

    private object IntentBuilder {
        fun show(context: AccessibilityService) = android.content.Intent(context, OverlayService::class.java).apply {
            action = OverlayService.ACTION_SHOW
        }
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
        private const val YOUTUBE_PACKAGE = "com.google.android.youtube"
        private const val NEWPIPE_PACKAGE = "org.schabi.newpipe"
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
        private val SUPPORTED_PACKAGES = setOf(
            YOUTUBE_PACKAGE,
            NEWPIPE_PACKAGE,
            YOUTUBE_MUSIC_PACKAGE,
            SPOTIFY_PACKAGE
        )
    }

    private data class ToggleMatch(val rect: RectF, val state: ToggleState?)

    private enum class ToggleState { VIDEO, AUDIO }
}
