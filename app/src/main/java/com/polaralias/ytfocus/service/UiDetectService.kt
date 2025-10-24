package com.polaralias.ytfocus.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.polaralias.ytfocus.bus.OverlayBus
import kotlin.math.max

class UiDetectService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString() ?: return
        if (packageName == YOUTUBE_MUSIC_PACKAGE) {
            handleYouTubeMusic()
        } else {
            OverlayBus.updateHole(null)
        }
    }

    override fun onInterrupt() {
    }

    private fun handleYouTubeMusic() {
        val root = rootInActiveWindow ?: return
        val toggle = findToggleNode(root)
        if (toggle != null) {
            val rect = android.graphics.Rect()
            toggle.getBoundsInScreen(rect)
            if (isVideoMode(toggle)) {
                OverlayBus.updateHole(android.graphics.RectF(rect))
            } else {
                OverlayBus.updateHole(null)
            }
        } else {
            if (hasVideoIndicator(root)) {
                OverlayBus.updateHole(fallbackHole())
            } else {
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
                    return node
                }
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
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
                return true
            }
            if (desc != null && desc.contains("switch to audio")) {
                return true
            }
            for (i in 0 until node.childCount) {
                queue.add(node.getChild(i))
            }
        }
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
