package com.misura.phone.hidden

import android.content.Context
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

/** Main display's real pixels and density, kept in portrait for hidden apps. Defaults also work in JVM tests. */
object HiddenSize {
    var width = 1080; private set
    var height = 2400; private set
    var dpi = 480; private set
    private var loaded = false

    @Suppress("DEPRECATION")
    fun init(ctx: Context) {
        if (loaded) return
        val display = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        if (m.widthPixels <= 0 || m.heightPixels <= 0 || m.densityDpi <= 0) return
        width = minOf(m.widthPixels, m.heightPixels); height = maxOf(m.widthPixels, m.heightPixels); dpi = m.densityDpi
        loaded = true
    }

    /** Tests: a fixed size, whatever the device (layout geometry in the fixtures assumes 1080x2400 at 480). */
    @androidx.annotation.VisibleForTesting
    fun pin(w: Int, h: Int, d: Int) { width = w; height = h; dpi = d; loaded = true }
}
