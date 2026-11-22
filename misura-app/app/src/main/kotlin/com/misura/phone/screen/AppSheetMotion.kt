package com.misura.phone.screen

import android.animation.ValueAnimator
import android.content.Context
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.misura.phone.store.Pins
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

internal fun sheetRowAlpha(progress: Float, thumbY: Float, centerY: Float, edgePx: Float, lit: Float): Float {
    val sheet = ((progress - .12f) / (.55f - .12f)).coerceIn(0f, 1f)
    val light = ((thumbY - centerY) / edgePx + 1f).coerceIn(0f, 1f)
    return sheet * (light + (1f - light) * lit.coerceIn(0f, 1f))
}

class AppSheetState internal constructor(context: Context, private val scope: CoroutineScope, view: View, clearFocus: () -> Unit) {
    val progress = Animatable(0f)
    internal var thumbY by mutableFloatStateOf(Float.POSITIVE_INFINITY); private set
    internal val lit = Animatable(0f)
    val list = LazyListState()
    val fieldFocus = FocusRequester()
    internal val keyboard = SheetKeyboard(view, scope, progress, { fieldFocus.requestFocus() }, clearFocus)
    val engaged get() = keyboard.engaged
    val store = Pins(context)
    var searching by mutableStateOf(false); private set
    var closing = false; private set
    var moving = false; private set
    var typed by mutableStateOf("")
    var pins by mutableStateOf<List<SheetEntry>>(emptyList()); private set
    var counts by mutableStateOf<Map<String, Int>>(emptyMap()); private set
    var menuOpen by mutableStateOf(false)
    internal var listBounds: Rect? = null
    internal var gestureOffset = Offset.Zero
    private var settling: Job? = null

    fun showKeyboard() = keyboard.show()
    fun hideKeyboard() = keyboard.hide()
    fun close() { keyboard.hide(); settle(false) }

    suspend fun refreshStored() {
        val stored = withContext(Dispatchers.IO) { store.entries() to store.launchCounts() }
        pins = stored.first; counts = stored.second
    }

    fun togglePin(entry: SheetEntry) {
        scope.launch {
            pins = withContext(Dispatchers.IO) { store.toggle(entry); store.entries() }
            list.scrollToItem(0)
        }
    }

    fun beginDrag() {
        settling?.cancel()
        if (searching) keyboard.hide() else keyboard.beginOpen()
        closing = false; moving = true
        scope.launch(start = CoroutineStart.UNDISPATCHED) { lit.snapTo(0f) }
    }

    fun dragTo(value: Float, openingThumbY: Float) {
        thumbY = openingThumbY
        scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(value) }
    }

    fun settle(open: Boolean, velocity: Float = 0f) {
        settling?.cancel()
        menuOpen = false
        closing = !open; moving = true
        // Field, list interaction and Back handling switch only after the finger lifts.
        if (open) searching = true
        else thumbY = Float.POSITIVE_INFINITY
        keyboard.release(open)
        settling = scope.launch {
            if (ValueAnimator.areAnimatorsEnabled()) {
                coroutineScope {
                    if (open && thumbY.isFinite()) launch {
                        lit.animateTo(1f, spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = .001f))
                    }
                    progress.animateTo(if (open) 1f else 0f,
                        spring(dampingRatio = 1f, stiffness = 700f, visibilityThreshold = .001f), velocity)
                }
            } else progress.snapTo(if (open) 1f else 0f)
            thumbY = Float.POSITIVE_INFINITY
            lit.snapTo(0f)
            keyboard.settled(open)
            moving = false
            if (!open) { searching = false; closing = false; typed = ""; listBounds = null; list.scrollToItem(0) }
        }
    }
}

@Composable
fun rememberAppSheetState(): AppSheetState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val focus = LocalFocusManager.current
    val state = remember(context, scope, view, focus) { AppSheetState(context, scope, view) { focus.clearFocus() } }
    LaunchedEffect(state) { snapshotFlow { state.progress.value }.collect { state.keyboard.follow(it) } }
    DisposableEffect(state) { onDispose { state.keyboard.hide() } }
    return state
}

/** Observe before children; take only a downward drag, leaving taps, holds and list scrolling alone. */
fun Modifier.appSheetGesture(state: AppSheetState, enabled: Boolean): Modifier = pointerInput(state, enabled) {
    if (!enabled) return@pointerInput
    val travel = 260.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (state.menuOpen) return@awaitEachGesture
        val wasOpen = state.searching
        // Reverse layout: downward drags advance the list, even from index 0.
        if (wasOpen && state.listBounds?.contains(down.position + state.gestureOffset) == true && state.list.canScrollForward) return@awaitEachGesture
        val start = state.progress.value
        val direction = if (wasOpen) -1f else 1f
        val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        // Invert the rubber band when catching an interrupted settle outside the range.
        val dragStart = when {
            start < 0f -> start / .25f
            start > 1f -> 1f + (start - 1f) / .25f
            else -> start
        }
        var draggedProgress = start
        var dragging = false
        var released = false
        var lastMove = down.uptimeMillis
        var lastTime = down.uptimeMillis
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (state.menuOpen) break
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                lastTime = change.uptimeMillis
                if (event.changes.count { it.pressed } > 1) break
                if (!change.pressed) { released = true; if (dragging) change.consume(); break }
                val delta = change.position - down.position
                if (!dragging) {
                    if ((abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y)) || delta.y < -viewConfiguration.touchSlop) break
                    if (delta.y <= viewConfiguration.touchSlop) continue
                    state.beginDrag()
                    dragging = true
                }
                if (change.position != change.previousPosition) lastMove = change.uptimeMillis
                change.consume()
                draggedProgress = sheetDragProgress(dragStart + direction * delta.y / travel)
                state.dragTo(draggedProgress,
                    if (wasOpen) Float.POSITIVE_INFINITY else change.position.y + state.gestureOffset.y)
            }
        } finally {
            if (dragging) {
                val velocity = if (released && lastTime - lastMove <= 100) direction * tracker.calculateVelocity().y / travel else 0f
                val target = if (!released) wasOpen else if (abs(velocity) > 1.5f) velocity > 0f else draggedProgress > .5f
                val resistance = if (draggedProgress < 0f || draggedProgress > 1f) .25f else 1f
                state.settle(target, velocity * resistance)
            }
        }
    }
}
