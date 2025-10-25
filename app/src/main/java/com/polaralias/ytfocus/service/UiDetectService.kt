package com.polaralias.ytfocus.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.media.session.PlaybackState
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.ytfocus.bus.OverlayBus
import com.polaralias.ytfocus.util.ForegroundApp
import com.polaralias.ytfocus.util.Logx
import kotlin.math.max

class UiDetectService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("UiDetectService.onServiceConnected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString()
        Logx.d("UiDetectService.onAccessibilityEvent package=$pkg type=${event?.eventType}")
        if (pkg != YOUTUBE_MUSIC_PACKAGE && pkg != YOUTUBE_PACKAGE) {
            return
        }

        val inForeground = ForegroundApp.isTargetInForeground(this)
        val controller = OverlayService.mediaController
        val activeFromTarget = controller?.packageName == pkg && controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (!inForeground || !activeFromTarget) {
            return
        }

        if (pkg == YOUTUBE_PACKAGE) {
            OverlayBus.maskEnabled = true
            OverlayBus.hole = null
            startService(IntentBuilder.show(this))
            return
        }

        val rect = findAudioToggleRect(root) ?: defaultToggleGuess(root)
        OverlayBus.hole = rect
        val audio = isAudioMode(root)
        OverlayBus.maskEnabled = !audio
        startService(IntentBuilder.show(this))
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
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
        private const val TOGGLE_PADDING_DP = 12f
        private val KEYWORD_MATCHES = listOf(
            "switch to audio",
            "switch to song",
            "switch to video"
        )
        private val EXACT_MATCHES = listOf("audio", "song", "video")
    }
}
