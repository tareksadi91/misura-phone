package com.misura.phone.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.AdaptiveIconDrawable
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import com.misura.phone.cards.M
import com.misura.phone.cards.u
import kotlin.math.cos
import kotlin.math.sin

private data class AppMark(val bitmap: ImageBitmap?)

// Cache missing monochrome layers too, so scrolling never repeats their package queries.
private val appMarks = object : LruCache<String, AppMark>(2 * 1024 * 1024) {
    override fun sizeOf(key: String, value: AppMark): Int =
        value.bitmap?.let { it.width * it.height * 4 } ?: 1
}

/**
 * The real app's own one-colour mark (the themed-icon layer apps ship for Android 13+), drawn like a line icon.
 * Null when the app isn't installed or ships no such layer: callers fall back to [LineIcon] (his call, 12 Nov).
 */
@Composable
fun rememberAppMark(pkg: String, size: Dp = 16.u): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    val px = with(LocalDensity.current) { size.roundToPx() }
    val mark by produceState(appMarks.get("$pkg:$px")?.bitmap, ctx, pkg, px) {
        value = withContext(Dispatchers.IO) { appMark(ctx, pkg, px) }
    }
    return mark
}

fun appMark(ctx: Context, pkg: String, px: Int): ImageBitmap? {
    val key = "$pkg:$px"
    appMarks.get(key)?.let { return it.bitmap }
    val mono = (runCatching { ctx.packageManager.getApplicationIcon(pkg) }.getOrNull() as? AdaptiveIconDrawable)?.monochrome
    // The layer is 108 dp with the mark inside the middle 72. Draw it at its real size, then scale the middle down:
    // some apps (Drive) wrap the layer in an InsetDrawable with fixed dp insets that swallow a 20 dp canvas entirely.
    val layer = (108 * ctx.resources.displayMetrics.density).toInt()
    val mark = mono?.let {
        val full = Bitmap.createBitmap(layer, layer, Bitmap.Config.ARGB_8888)
        it.mutate().apply { setBounds(0, 0, layer, layer) }.draw(android.graphics.Canvas(full))
        val cut = layer / 6
        Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { b ->
            android.graphics.Canvas(b).drawBitmap(full, android.graphics.Rect(cut, cut, layer - cut, layer - cut),
                android.graphics.Rect(0, 0, px, px), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            full.recycle()
        }.asImageBitmap()
    }
    appMarks.put(key, AppMark(mark))
    return mark
}

/** Thin single-colour line icons for apps in steps (design-system §3): 16 at mockup scale, 1.4 stroke, text-2. */
enum class Icon { Pin, Star, Bowl, Chat, Search, Note }

@Composable
fun LineIcon(icon: Icon, modifier: Modifier = Modifier, color: Color = M.text2) = Canvas(modifier.size(16.u)) {
    val w = size.width; val st = Stroke(width = w * 1.4f / 16f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(w * x / 16f, w * y / 16f)
    when (icon) {
        Icon.Pin -> {
            drawPath(Path().apply {
                moveTo(p(8f, 15f).x, p(8f, 15f).y)
                cubicTo(p(8f, 15f).x, p(8f, 15f).y, p(3f, 9.5f).x, p(3f, 9.5f).y, p(3f, 6.5f).x, p(3f, 6.5f).y)
                cubicTo(p(3f, 3.5f).x, p(3f, 3.5f).y, p(5.2f, 1.5f).x, p(5.2f, 1.5f).y, p(8f, 1.5f).x, p(8f, 1.5f).y)
                cubicTo(p(10.8f, 1.5f).x, p(10.8f, 1.5f).y, p(13f, 3.5f).x, p(13f, 3.5f).y, p(13f, 6.5f).x, p(13f, 6.5f).y)
                cubicTo(p(13f, 9.5f).x, p(13f, 9.5f).y, p(8f, 15f).x, p(8f, 15f).y, p(8f, 15f).x, p(8f, 15f).y)
            }, color, style = st)
            drawCircle(color, w * 2f / 16f, p(8f, 6.5f), style = st)
        }
        Icon.Star -> drawPath(Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) 6.5f else 2.8f; val a = Math.toRadians(-90.0 + i * 36.0)
                val pt = p(8f + r * cos(a).toFloat(), 8.5f + r * sin(a).toFloat())
                if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
            close()
        }, color, style = st)
        Icon.Bowl -> {
            drawArc(color, 0f, 180f, false, p(2f, 3f), Size(w * 12f / 16f, w * 11f / 16f), style = st)
            drawLine(color, p(1.5f, 8.5f), p(14.5f, 8.5f), st.width, StrokeCap.Round)
        }
        // A bookmark: something kept.
        Icon.Note -> drawPath(Path().apply { moveTo(p(4f, 2f).x, p(4f, 2f).y); lineTo(p(12f, 2f).x, p(12f, 2f).y); lineTo(p(12f, 14f).x, p(12f, 14f).y); lineTo(p(8f, 10.5f).x, p(8f, 10.5f).y); lineTo(p(4f, 14f).x, p(4f, 14f).y); close() }, color, style = st)
        Icon.Search -> { drawCircle(color, w * 4.5f / 16f, p(7f, 7f), style = st); drawLine(color, p(10.4f, 10.4f), p(14f, 14f), st.width, StrokeCap.Round) }
        Icon.Chat -> drawPath(Path().apply {
            moveTo(p(2f, 3f).x, p(2f, 3f).y); lineTo(p(14f, 3f).x, p(14f, 3f).y); lineTo(p(14f, 11f).x, p(14f, 11f).y)
            lineTo(p(7f, 11f).x, p(7f, 11f).y); lineTo(p(4f, 14f).x, p(4f, 14f).y); lineTo(p(4f, 11f).x, p(4f, 11f).y)
            lineTo(p(2f, 11f).x, p(2f, 11f).y); close()
        }, color, style = st)
    }
}
