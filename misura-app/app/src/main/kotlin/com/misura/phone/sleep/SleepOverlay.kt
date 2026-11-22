package com.misura.phone.sleep

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.misura.phone.hidden.MisuraAccessibility

/** Variant B: a full-screen accessibility overlay. Tap to drop it and reach the lock screen. */
object SleepOverlay {
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun show(ctx: Context) = main.post {
        val svc = MisuraAccessibility.instance ?: run { Log.w("MisuraSleep", "overlay: accessibility not connected"); return@post }
        val wm = svc.getSystemService(WindowManager::class.java)
        // Replace an earlier one, so time and cards are from this wake.
        view?.let { old -> runCatching { wm.removeView(old) }; view = null }
        val v = SleepScreen.view(svc) { hide() }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.OPAQUE,
        )
        runCatching { wm.addView(v, lp); view = v; SleepScreen.shown("overlay") }
            .onFailure { if (com.misura.phone.BuildConfig.DEBUG) Log.w("MisuraSleep", "overlay add failed", it) else Log.w("MisuraSleep", "overlay add failed: ${it.javaClass.simpleName}") }
    }

    fun attached() = view?.isAttachedToWindow == true

    fun hide() = main.post {
        val v = view ?: return@post
        runCatching { MisuraAccessibility.instance?.getSystemService(WindowManager::class.java)?.removeView(v) }
        view = null; SleepScreen.gone()
    }
}
