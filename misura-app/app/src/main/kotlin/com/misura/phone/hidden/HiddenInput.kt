package com.misura.phone.hidden

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Taps and types on the hidden display only. Typing sets the field's text directly, so no keyboard opens.
 * Nothing is done while a permission question is up there ([ScreenReader.permissionQuestion]): Misura never answers one; only Back closes it.
 */
object HiddenInput {
    suspend fun tap(s: DisplaySession, x: Int, y: Int): Boolean {
        require(s.displayId != 0) { "display 0 is the main screen" }
        if (ScreenReader.permissionQuestion(s.displayId)) return false
        val svc = MisuraAccessibility.instance ?: return false
        val g = GestureDescription.Builder().setDisplayId(s.displayId)
            .addStroke(GestureDescription.StrokeDescription(Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0, 60)).build()
        return suspendCancellableCoroutine { c ->
            val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!sent && c.isActive) c.resume(false)
        }
    }

    /** Sets an editable field's text, then reads it back; true only on an exact match. */
    suspend fun setText(s: DisplaySession, viewId: String, text: String): Boolean {
        require(s.displayId != 0) { "display 0 is the main screen" }
        if (ScreenReader.permissionQuestion(s.displayId)) return false
        val node = ScreenReader.root(s.displayId)?.findAccessibilityNodeInfosByViewId(viewId)?.firstOrNull { it.isEditable } ?: return false
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
        repeat(10) { if (node.refresh() && node.text?.toString() == text) return true; delay(50) }
        return false
    }

    /** Sets the text of the editable field at exactly these bounds (how [UseApp] names a field it saw). */
    suspend fun setTextAt(s: DisplaySession, bounds: IntArray, text: String): Boolean {
        require(s.displayId != 0) { "display 0 is the main screen" }
        if (ScreenReader.permissionQuestion(s.displayId)) return false
        val want = android.graphics.Rect(bounds[0], bounds[1], bounds[2], bounds[3])
        fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (n.isEditable && android.graphics.Rect().also { n.getBoundsInScreen(it) } == want) return n
            for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
            return null
        }
        val node = ScreenReader.root(s.displayId)?.let(::find) ?: return false
        // Typing puts focus in the field, as a finger would: Enter then goes where the words are (UseApp's Enter gate needs a focused field).
        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    suspend fun swipe(s: DisplaySession, x1: Int, y1: Int, x2: Int, y2: Int): Boolean {
        require(s.displayId != 0) { "display 0 is the main screen" }
        if (ScreenReader.permissionQuestion(s.displayId)) return false
        val svc = MisuraAccessibility.instance ?: return false
        val path = Path().apply { moveTo(x1.toFloat(), y1.toFloat()); lineTo(x2.toFloat(), y2.toFloat()) }
        val g = GestureDescription.Builder().setDisplayId(s.displayId).addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return suspendCancellableCoroutine { c ->
            val sent = svc.dispatchGesture(g, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (c.isActive) c.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (c.isActive) c.resume(false) }
            }, null)
            if (!sent && c.isActive) c.resume(false)
        }
    }
}
