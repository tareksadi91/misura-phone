package com.misura.phone.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.misura.phone.R
import kotlin.math.*

/** How strongly the texture shows on each kind of screen (design-system §12 Atmosphere). Home is 1. */
enum class Atmosphere(val strength: Float) {
    HOME(1f), HOME_QUIET(QUIET), LISTENING(1f), HIDDEN(0f), WORKING(.18f), CARD(.12f), READING(.18f),
    /** A task was opened but has not loaded yet: keep whatever shows now. */
    HOLD(-1f)
}
const val QUIET = .35f
/** Texture redraw interval: 15 fps. Fades run on their own animation clock and stay smooth. */
const val TEXTURE_FRAME_MS = 66L
/** Voice level smoothing: at 15 fps steps (~80 ms) each update moves about 63% of the way, so the grain follows speech without snapping. */
const val VOICE_SMOOTH_MS = 250f
/** Work shorter than this never brightens the texture, so it cannot flicker in and out. */
const val WORKING_DELAY_MS = 1_000L

/**
 * The fade curve for every texture change (design-system §12, no abrupt changes): an exponential settle, so a fade
 * starts gently, never overshoots and arrives exactly. Over a 300 ms fade one 33 ms frame moves at most ~30% of the way.
 */
val SettleEasing = Easing { t -> (1f - exp(-3f * t)) / (1f - exp(-3f)) }

/** One smoothing step for the voice level (it changes every frame, so it is smoothed rather than animated). */
fun settle(current: Float, target: Float, dtMs: Float, tauMs: Float): Float {
    if (dtMs <= 0f) return current
    val next = current + (target - current) * (1f - exp(-3f * dtMs / tauMs))
    return if (abs(target - next) < .002f) target else next
}

/** Fade time toward [target]: hiding for the request keyboard follows its 340 ms opening; everything else 300 ms. */
fun fadeMs(target: Float): Float = if (target == 0f) 340f else 300f

/** Decorative texture. Never owns touch, focus, microphone or app-sheet state. */
@Composable
fun HomeAtmosphere(listening: Boolean, level: () -> Float, quiet: Boolean, modifier: Modifier = Modifier) =
    HomeAtmosphere(listening, level, { if (quiet) QUIET else 1f }, modifier)

/** [target] is read only by the frame loop and the drawing, never by composition. */
@Composable
fun HomeAtmosphere(listening: Boolean, level: () -> Float, targetIn: () -> Float, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Long-lived collectors and the frame loop always read the latest callback.
    val targetNow by rememberUpdatedState(targetIn)
    val target: () -> Float = { targetNow() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    fun motionEnabled() = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    var motion by remember { mutableStateOf(motionEnabled()) }
    var screenOn by remember { mutableStateOf(context.getSystemService(PowerManager::class.java).isInteractive) }
    var time by remember { mutableFloatStateOf(0f) }
    val shown = remember { Animatable(target().coerceAtLeast(0f)) }
    val blend = remember { Animatable(if (listening) 1f else 0f) }
    val voice = remember { mutableFloatStateOf(0f) }
    val listeningNow by rememberUpdatedState(listening)
    // Each change is its own finite fade that continues from the current value, so a reversal never jumps.
    LaunchedEffect(shown) {
        snapshotFlow { target().coerceAtLeast(0f) }.collectLatest { goal ->
            if (motion) shown.animateTo(goal, tween(fadeMs(goal).toInt(), easing = SettleEasing)) else shown.snapTo(goal)
        }
    }
    LaunchedEffect(listening, motion) {
        val goal = if (listening) 1f else 0f
        if (motion) blend.animateTo(goal, tween(300, easing = SettleEasing)) else blend.snapTo(goal)
    }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { motion = motionEnabled() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { screenOn = intent.action == Intent.ACTION_SCREEN_ON }
        }
        context.contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        context.registerReceiver(receiver, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, Context.RECEIVER_NOT_EXPORTED)
        onDispose { context.contentResolver.unregisterContentObserver(observer); context.unregisterReceiver(receiver) }
    }
    LaunchedEffect(lifecycle, motion, screenOn) {
        if (motion && screenOn) lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var previous = 0L
            while (true) {
                // Settled out of sight (typing, full app list, also while listening there): no frames until it should show again.
                if (shown.value == 0f && target() <= 0f) {
                    snapshotFlow { target() }.first { it > 0f }
                    previous = 0L
                }
                withInfiniteAnimationFrameNanos { frame ->
                    if (previous == 0L) previous = frame
                    val dt = ((frame - previous) / 1_000_000f).coerceAtMost(200f)
                    time += dt / 1000f
                    voice.floatValue = settle(voice.floatValue, if (listeningNow) level().coerceIn(0f, 1f) else 0f, dt, VOICE_SMOOTH_MS)
                    previous = frame
                }
                // Sleep between redraws instead of waking every display frame: the texture moves slowly, 15 fps is enough
                // (owner, 21 Nov) and roughly halves its main-thread cost versus 30 fps.
                delay(TEXTURE_FRAME_MS)
            }
        }
    }
    val paint = remember(context) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.ibm_plex_mono_medium)
        textAlign = Paint.Align.CENTER
    } }
    Canvas(modifier.clearAndSetSemantics {}.drawWithCache {
        paint.textSize = 9.dp.toPx()
        val cell = 8.dp.toPx()
        val line = 10.dp.toPx()
        val columns = ceil(size.width / cell).toInt()
        val rows = ceil(size.height / line).toInt()
        // Cache spatial phases, not frame state; only this drawing leaf observes time and voice amplitude.
        val grain = FloatArray(columns * rows) { i -> ((sin(i * 127.1) * 43758.5453) % 1.0 + 1.0).rem(1.0).toFloat() }
        val chars = " .:+*ox#%@".map(Char::toString)
        onDrawBehind {
            val t = if (motion) time else 0f
            // Without motion there is nothing to ease: show the target as a still texture.
            val strength = if (motion) shown.value else target().coerceAtLeast(0f)
            val mix = if (motion) blend.value else 0f
            val speech = if (motion) voice.floatValue else 0f
            if (strength < .005f) return@onDrawBehind
            drawIntoCanvas { canvas ->
                for (row in 0 until rows) for (col in 0 until columns) {
                    val x = col * cell
                    val y = row * line
                    val nx = x / size.width * 600f
                    val ny = y / size.height * 1280f
                    val g = grain[row * columns + col]
                    val phase = g * (2f * PI.toFloat())
                    val dx = (nx - 300f) / (430f * (1f + speech * .2f))
                    val dy = (ny - 1140f) / 520f
                    val field = exp(-1.45f * (dx * dx + dy * dy))
                    val gentle = .5f + sin(ny * .033f + sin(nx * .013f) * 1.4f) * .45f
                    val local = .5f + .5f * sin(t * (.55f + g * .35f) + phase + sin(nx / 75f + ny / 85f))
                    val texture = local * .4f + gentle * .45f + g * .15f + sin(t * (.65f + g * .3f) + phase) * .12f
                    val top = ((ny - 360f) / 270f).coerceIn(0f, 1f)
                    val normal = (.1f + texture * .9f) * field * top
                    // Listening has no line or shape: the voice brightens and stirs the same grain in the open area above
                    // the headline, and the texture behind the Listening label and field stays faint.
                    val heard = if (mix > .001f) {
                        val area = exp(-((ny - 820f) / 210f).pow(2)) * exp(-dx * dx * .8f)
                        val stir = .5f + .5f * sin(t * (1.2f + g * 1.4f) * (1f + speech * 1.5f) + phase * 3f + sin(nx / 41f - ny / 53f) * 2f)
                        normal * .3f + area * (stir * .55f + g * .45f) * (.1f + speech * .5f)
                    } else normal
                    val density = (normal + (heard - normal) * mix).coerceIn(0f, 1f)
                    if (density < .065f) continue
                    val edge = ((1280f - ny) / 65f).coerceIn(0f, 1f)
                    val alpha = min(.78f, density * .88f) * strength * edge * edge * (3f - 2f * edge)
                    // Under 2% opacity a glyph is invisible on the dark screen: skip it, so quiet screens draw far fewer glyphs.
                    if (alpha < .02f) continue
                    paint.setARGB((alpha * 255).toInt(), (219 + density * 30).toInt(), (86 + density * 35).toInt(), (36 + density * 12).toInt())
                    canvas.nativeCanvas.drawText(chars[(density * chars.size).toInt().coerceIn(1, chars.lastIndex)], x + sin(t * .55f + phase), y + cos(t * .47f + phase), paint)
                }
            }
        }
    }) {}
}
