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
import kotlin.math.max

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
        val overlayState = OverlayStateStore.get()
        val allowHiddenVideoNodes = overlayState.maskEnabled && overlayState.app == app
        val surfaceFraction = videoSurfaceFraction(nodes, allowHiddenVideoNodes)
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
            val miniPlayer = ytMusicMiniPlayerVisible(nodes)
            val hasVideoSurface = surfaceFraction >= YT_MUSIC_VIDEO_SURFACE_THRESHOLD
            val wantsVideoUi = (hasVideoSurface || selectedMode == PlayMode.VIDEO) && !miniPlayer
            val fullscreenVideo = wantsVideoUi && surfaceFraction >= FULLSCREEN_FRACTION_THRESHOLD
            hole = if (wantsVideoUi && !fullscreenVideo) {
                topBandHole(root, YT_MUSIC_VIDEO_HOLE_FRACTION)
            } else {
                null
            }
            mode = if (wantsVideoUi) PlayMode.VIDEO else PlayMode.AUDIO
            mask = wantsVideoUi
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

    private fun videoSurfaceFraction(
        nodes: List<AccessibilityNodeInfo>,
        allowHidden: Boolean
    ): Float {
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
            if (!allowHidden && !node.isVisibleToUser) {
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
        if (nodes.isEmpty()) {
            return null
        }
        val scores = HashMap<PlayMode, SelectionConfidence>()
        nodes.forEach { node ->
            val mode = ytMusicModeCandidate(node) ?: return@forEach
            val confidence = ytMusicSelectionConfidence(node)
            val existing = scores[mode]
            if (existing == null) {
                scores[mode] = confidence
            } else {
                scores[mode] = SelectionConfidence(
                    selectedScore = max(existing.selectedScore, confidence.selectedScore),
                    unselectedScore = max(existing.unselectedScore, confidence.unselectedScore)
                )
            }
        }
        if (scores.isEmpty()) {
            return null
        }
        val confident = scores.entries
            .filter { it.value.selectedScore > it.value.unselectedScore }
            .maxByOrNull { it.value.selectedScore - it.value.unselectedScore }
        if (confident != null && confident.value.selectedScore > 0) {
            return confident.key
        }
        if (scores.size == 2) {
            val unselectedOnly = scores.entries.filter {
                it.value.selectedScore == 0 && it.value.unselectedScore > 0
            }
            if (unselectedOnly.size == 1) {
                val selectedCandidate = scores.entries.firstOrNull { it.key != unselectedOnly[0].key }
                if (selectedCandidate != null && selectedCandidate.value.unselectedScore == 0) {
                    return selectedCandidate.key
                }
            }
        }
        val fallback = scores.entries.maxByOrNull { it.value.selectedScore }
        if (fallback != null && fallback.value.selectedScore > 0) {
            return fallback.key
        }
        return null
    }

    private fun ytMusicModeCandidate(node: AccessibilityNodeInfo): PlayMode? {
        val sources = sequenceOf(
            node.text,
            node.contentDescription,
            node.stateDescription
        )
        sources.forEach { source ->
            val label = source?.toString()?.trim()?.lowercase().orEmpty()
            if (label.isEmpty()) {
                return@forEach
            }
            when {
                label.startsWith("song") || label.startsWith("audio") -> return PlayMode.AUDIO
                label.startsWith("video") -> return PlayMode.VIDEO
            }
        }
        return null
    }

    private fun ytMusicSelectionConfidence(node: AccessibilityNodeInfo): SelectionConfidence {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        val recycle = ArrayList<AccessibilityNodeInfo>()
        var bestSelected = 0
        var bestUnselected = 0
        while (current != null && depth < 4) {
            val hints = selectionHints(current)
            val penalty = depth * 5
            bestSelected = max(bestSelected, (hints.selectedScore - penalty).coerceAtLeast(0))
            bestUnselected = max(bestUnselected, (hints.unselectedScore - penalty).coerceAtLeast(0))
            val parent = current.parent
            if (parent != null) {
                recycle.add(parent)
            }
            current = parent
            depth++
        }
        recycle.forEach { it.recycle() }
        return SelectionConfidence(bestSelected, bestUnselected)
    }

    private fun selectionHints(node: AccessibilityNodeInfo): SelectionConfidence {
        var selected = 0
        var unselected = 0
        if (node.isSelected) {
            selected = max(selected, 100)
        }
        if (node.isChecked) {
            selected = max(selected, 90)
        }
        if (node.isActivated) {
            selected = max(selected, 80)
        }
        if (node.isFocusable && node.isFocused) {
            selected = max(selected, 60)
        }
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val state = node.stateDescription?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (containsAny(desc, SELECTED_HINTS) || containsAny(state, SELECTED_HINTS) ||
            containsAny(viewId, SELECTED_HINTS) || text.contains("selected") ||
            text.contains("currently")) {
            selected = max(selected, 70)
        }
        if (viewId.contains("selected")) {
            selected = max(selected, 50)
        }
        if (containsAny(desc, UNSELECTED_HINTS) || containsAny(state, UNSELECTED_HINTS) ||
            containsAny(text, UNSELECTED_HINTS)) {
            unselected = max(unselected, 70)
        }
        if (desc.contains("not selected") || state.contains("not selected") || text.contains("not selected")) {
            unselected = max(unselected, 80)
        }
        return SelectionConfidence(selected, unselected)
    }

    private fun containsAny(haystack: String, needles: List<String>): Boolean {
        if (haystack.isEmpty()) {
            return false
        }
        needles.forEach { needle ->
            if (haystack.contains(needle)) {
                return true
            }
        }
        return false
    }

    private data class SelectionConfidence(
        val selectedScore: Int,
        val unselectedScore: Int
    )

    private fun ytMusicMiniPlayerVisible(nodes: List<AccessibilityNodeInfo>): Boolean {
        val metrics = resources.displayMetrics
        val screenHeight = metrics.heightPixels.toFloat().coerceAtLeast(1f)
        val idHints = listOf("mini_player", "miniplayer", "player_bar", "collapsed_player")
        val rect = Rect()
        nodes.forEach { node ->
            if (!node.isVisibleToUser) {
                return@forEach
            }
            val id = node.viewIdResourceName?.lowercase().orEmpty()
            val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
            val candidate = idHints.any { id.contains(it) } ||
                desc.contains("mini player") ||
                desc.contains("collapsed player")
            if (!candidate) {
                return@forEach
            }
            node.getBoundsInScreen(rect)
            val height = rect.height().coerceAtLeast(0)
            if (height <= 0) {
                return@forEach
            }
            val bottom = rect.bottom.toFloat()
            val maxHeight = screenHeight * YT_MUSIC_MINI_PLAYER_MAX_HEIGHT_FRACTION
            val bottomThreshold = screenHeight * (1f - YT_MUSIC_MINI_PLAYER_BOTTOM_ANCHOR_FRACTION)
            if (height <= maxHeight && bottom >= bottomThreshold) {
                return true
            }
        }
        return false
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
        private const val YT_MUSIC_VIDEO_SURFACE_THRESHOLD = 0.15f
        private const val YT_MUSIC_VIDEO_HOLE_FRACTION = 1f / 6f
        private const val YT_MUSIC_MINI_PLAYER_MAX_HEIGHT_FRACTION = 0.4f
        private const val YT_MUSIC_MINI_PLAYER_BOTTOM_ANCHOR_FRACTION = 0.2f
        private val SELECTED_HINTS = listOf(
            "selected",
            "currently playing",
            "currently selected",
            "current tab",
            "current selection",
            "active tab",
            "active selection",
            "now playing"
        )
        private val UNSELECTED_HINTS = listOf(
            "switch to",
            "double tap to switch",
            "tap to switch",
            "tap to select",
            "tap to view",
            "tap to watch",
            "view video",
            "view song",
            "play video",
            "play song",
            "open video",
            "open song",
            "watch video",
            "watch song"
        )
    }
}
