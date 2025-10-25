package com.polaralias.ytfocus.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.ytfocus.bus.OverlayBus
import com.polaralias.ytfocus.util.Logx
import kotlin.math.max

class UiDetectService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        Logx.d("UiDetectService.onServiceConnected sdk=${Build.VERSION.SDK_INT}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        Logx.d("UiDetectService.onAccessibilityEvent package=$packageName type=${event?.eventType}")
        if (packageName == YOUTUBE_MUSIC_PACKAGE) {
            handleYouTubeMusic()
        } else {
            Logx.d("UiDetectService.onAccessibilityEvent clearing for package=$packageName")
            OverlayBus.updateHole(null)
        }
    }

    override fun onInterrupt() {
        Logx.d("UiDetectService.onInterrupt")
    }

    private fun handleYouTubeMusic() {
        val root = rootInActiveWindow ?: run {
            Logx.d("UiDetectService.handleYouTubeMusic noRoot")
            return
        }
        val toggle = findToggleNode(root)
        if (toggle != null) {
            val rect = android.graphics.Rect()
            toggle.getBoundsInScreen(rect)
            if (isVideoMode(toggle)) {
                val hole = android.graphics.RectF(rect)
                Logx.d("UiDetectService.handleYouTubeMusic video rect=$hole")
                OverlayBus.updateHole(hole)
            } else {
                Logx.d("UiDetectService.handleYouTubeMusic audio")
                OverlayBus.updateHole(null)
            }
        } else {
            if (hasVideoIndicator(root)) {
                val hole = fallbackHole()
                Logx.d("UiDetectService.handleYouTubeMusic fallback rect=$hole")
                OverlayBus.updateHole(hole)
            } else {
                Logx.d("UiDetectService.handleYouTubeMusic noIndicator")
                OverlayBus.updateHole(null)
            }
        }
    }

    private fun findToggleNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val label = node.text?.toString()?.lowercase() ?: node.contentDescription?.toString()?.lowercase()
            if (label != null) {
                if (label.contains("switch to audio") || label.contains("switch to video") || label == "video" || label == "audio") {
                    Logx.d("UiDetectService.findToggleNode found=$label")
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        Logx.d("UiDetectService.findToggleNode none")
        return null
    }

    private fun isVideoMode(node: AccessibilityNodeInfo): Boolean {
        val label = node.contentDescription?.toString()?.lowercase() ?: node.text?.toString()?.lowercase()
        if (label != null) {
            if (label.contains("switch to audio")) {
                return true
            }
            if (label.contains("switch to video")) {
                return false
            }
        }
        if (node.isChecked || node.isSelected) {
            return true
        }
        return false
    }

    private fun hasVideoIndicator(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo?>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst() ?: continue
            val text = node.text?.toString()?.lowercase()
            val desc = node.contentDescription?.toString()?.lowercase()
            if (text != null && text.contains("switch to audio")) {
                Logx.d("UiDetectService.hasVideoIndicator text=$text")
                return true
            }
            if (desc != null && desc.contains("switch to audio")) {
                Logx.d("UiDetectService.hasVideoIndicator desc=$desc")
                return true
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
        Logx.d("UiDetectService.hasVideoIndicator none")
        return false
    }

    private fun fallbackHole(): android.graphics.RectF {
        val metrics = resources.displayMetrics
        val density = metrics.density
        val size = 96f * density
        val margin = 16f * density
        val left = metrics.widthPixels - size - margin
        val top = margin
        val right = metrics.widthPixels - margin
        val bottom = top + size
        return android.graphics.RectF(max(0f, left), top, max(0f, right), bottom)
    }

    companion object {
        private const val YOUTUBE_MUSIC_PACKAGE = "com.google.android.apps.youtube.music"
    }
}
