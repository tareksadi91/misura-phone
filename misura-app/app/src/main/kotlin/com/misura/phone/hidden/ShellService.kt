package com.misura.phone.hidden

import android.annotation.SuppressLint
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Shizuku UserService: runs in its own process as the shell user (uid 2000), not in the app.
 * Creates hidden displays the way scrcpy's NewDisplayCapture does.
 */
class ShellService(private val context: Context) : IShellService.Stub() {
    private val displays = ConcurrentHashMap<Int, Triple<VirtualDisplay, ImageReader, Held>>()
    private val handler = Handler(HandlerThread("hidden-display").apply { start() }.looper)

    override fun destroy() {
        displays.keys.forEach { releaseDisplay(it) }
        exitProcess(0)
    }

    /** The newest frame of one display; [lock] keeps it from being closed while [grab] reads it. */
    private class Held { var image: Image? = null; var closed = false }

    override fun createDisplay(name: String, width: Int, height: Int, dpi: Int): Int {
        // Keep the newest frame for grab(); consuming the rest keeps the display composing. 3 images so holding one never starves the next.
        val held = Held()
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val next = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            synchronized(held) { if (held.closed) next.close() else { held.image?.close(); held.image = next } }
        }, handler)
        val dm = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(ShellContext(context))
        val vd = dm.createVirtualDisplay(name, width, height, dpi, reader.surface, FLAGS)
            ?: run { reader.close(); error("createVirtualDisplay returned null") }
        val id = vd.display.displayId
        displays[id] = Triple(vd, reader, held)
        runCatching { hideIme(id) }.onFailure { if (com.misura.phone.BuildConfig.DEBUG) Log.w(TAG, "IME policy not set on $id", it) else Log.w(TAG, "IME policy failed: ${it.javaClass.simpleName}") }
        return id
    }

    override fun releaseDisplay(displayId: Int): Boolean {
        val (vd, reader, held) = displays.remove(displayId) ?: return false
        vd.release()
        synchronized(held) { held.closed = true; held.image?.close(); held.image = null }
        reader.close()
        return true
    }

    override fun grab(displayId: Int, left: Int, top: Int, right: Int, bottom: Int, maxWidth: Int): ByteArray? {
        val held = displays[displayId]?.third ?: return null
        synchronized(held) {
            val img = held.image ?: return null
            val l = left.coerceIn(0, img.width); val t = top.coerceIn(0, img.height)
            val w = right.coerceIn(0, img.width) - l; val h = bottom.coerceIn(0, img.height) - t
            if (w <= 0 || h <= 0) return null
            val plane = img.planes[0]
            if (plane.pixelStride != 4) return null // RGBA_8888 is always 4 bytes a pixel
            // Rows can be padded wider than the image: copy just the region, row by row.
            val src = plane.buffer.duplicate()
            val px = ByteBuffer.allocate(w * h * 4)
            val row = ByteArray(w * 4)
            for (y in 0 until h) { src.position((t + y) * plane.rowStride + l * 4); src.get(row); px.put(row) }
            px.rewind()
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            try {
                bmp.copyPixelsFromBuffer(px)
                // A binder transaction holds ~1 MB: one smaller retry, then give up.
                return jpeg(bmp, maxWidth, 85)?.takeIf { it.size <= MAX_JPEG } ?: jpeg(bmp, minOf(maxWidth, w) / 2, 60)?.takeIf { it.size <= MAX_JPEG }
            } finally { bmp.recycle() }
        }
    }

    private fun jpeg(src: Bitmap, maxWidth: Int, quality: Int): ByteArray? {
        if (maxWidth < 1) return null
        val bmp = if (src.width > maxWidth) Bitmap.createScaledBitmap(src, maxWidth, maxOf(1, src.height * maxWidth / src.width), true) else src
        try {
            return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        } finally { if (bmp !== src) bmp.recycle() }
    }

    override fun exec(argv: Array<String>): String {
        return runBounded(argv, 15_000)
    }

    /** Keyboard never shows for the hidden display (typing goes through accessibility). */
    @SuppressLint("PrivateApi")
    private fun hideIme(displayId: Int) {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "window") as IBinder
        val wm = Class.forName("android.view.IWindowManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
        wm.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType).invoke(wm, displayId, DISPLAY_IME_POLICY_HIDE)
    }

    /** The system checks that the calling package belongs to the calling uid: speak as com.android.shell. */
    private class ShellContext(base: Context) : ContextWrapper(base) {
        override fun getPackageName() = SHELL_PKG
        override fun getOpPackageName() = SHELL_PKG
        override fun getAttributionSource(): AttributionSource = AttributionSource.Builder(SHELL_UID).setPackageName(SHELL_PKG).build()
        override fun getApplicationContext(): Context = this
    }

    companion object {
        private const val TAG = "MisuraShell"
        private const val SHELL_PKG = "com.android.shell"
        private const val SHELL_UID = 2000
        private const val DISPLAY_IME_POLICY_HIDE = 2
        private const val MAX_JPEG = 600_000

        // Same set scrcpy uses for a new display on API 34+ (hidden flags as raw bits).
        private const val FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) or  // SUPPORTS_TOUCH
            (1 shl 7) or  // ROTATES_WITH_CONTENT
            (1 shl 8) or  // DESTROY_CONTENT_ON_REMOVAL: tasks die with the display, never move to the main screen
            (1 shl 10) or // TRUSTED
            (1 shl 11) or // OWN_DISPLAY_GROUP
            (1 shl 12) or // ALWAYS_UNLOCKED
            (1 shl 13) or // TOUCH_FEEDBACK_DISABLED
            (1 shl 14) or // OWN_FOCUS
            (1 shl 15)    // DEVICE_DISPLAY_GROUP
    }
}

/** Runs [argv] and returns its output; a command that hangs is killed at [timeoutMs] instead of blocking its caller. */
fun runBounded(argv: Array<String>, timeoutMs: Long): String {
    val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
    val out = StringBuffer()
    val reader = thread { runCatching { p.inputStream.bufferedReader().use { out.append(it.readText()) } } }
    if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) p.destroyForcibly()
    reader.join(1_000)
    return out.toString()
}
