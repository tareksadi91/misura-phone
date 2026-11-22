package com.misura.phone.screen

import android.animation.ValueAnimator
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.mandatorySystemGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.misura.core.agent.pastDismissThreshold
import com.misura.phone.cards.M
import com.misura.phone.cards.ST
import com.misura.phone.cards.K
import com.misura.phone.cards.u
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Variant C: the line follows the finger; only distance, never fling speed, removes it. */
@Composable
fun SwipeTaskLine(action: String, onRemove: suspend () -> Unit, bleed: androidx.compose.ui.unit.Dp = M.gutter - M.m, content: @Composable (Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    val remove by rememberUpdatedState(onRemove)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val view = LocalView.current
    val leftEdge = WindowInsets.mandatorySystemGestures.getLeft(density, direction)
    val rightEdge = WindowInsets.mandatorySystemGestures.getRight(density, direction)
    var windowX by remember { mutableFloatStateOf(0f) }
    var width by remember { mutableFloatStateOf(0f) }
    var left by remember { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun settle(commit: Boolean) {
        if (busy) return
        busy = true; failed = false
        scope.launch {
            try {
                if (commit) {
                    if (ValueAnimator.areAnimatorsEnabled()) {
                        animate(left, width + with(density) { 8.u.toPx() }, animationSpec = tween(200, easing = CubicBezierEasing(.4f, 0f, .6f, 1f))) { v, _ -> left = v }
                        animate(1f, 0f, animationSpec = tween(180)) { v, _ -> height = v }
                    }
                    try { remove() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { failed = true; left = 0f; height = 1f }
                } else if (ValueAnimator.areAnimatorsEnabled()) {
                    animate(left, 0f, animationSpec = tween(180, easing = CubicBezierEasing(.2f, .8f, .2f, 1f))) { v, _ -> left = v }
                } else left = 0f
            } finally { busy = false }
        }
    }
    val bleedPx = with(density) { bleed.roundToPx() }
    // The lines sit inside the screen's side padding; the band runs out to the screen edge like the mockup's full-width row.
    Box(Modifier.layout { measurable, constraints ->
            val w = constraints.maxWidth + 2 * bleedPx
            val placeable = measurable.measure(constraints.copy(minWidth = w, maxWidth = w))
            layout(constraints.maxWidth, placeable.height) { placeable.place(-bleedPx, 0) }
        }.fillMaxWidth().clipToBounds().onGloballyPositioned { width = it.size.width.toFloat(); windowX = it.positionInWindow().x }
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, (placeable.height * height).roundToInt()) { placeable.place(0, 0) }
        }
        .pointerInput(leftEdge, rightEdge, view) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val x = windowX + down.position.x
                if (busy || x < leftEdge || x >= view.width - rightEdge) return@awaitEachGesture
                var start: PointerInputChange? = null
                while (start == null) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed || change.isConsumed) break
                    val delta = change.position - down.position
                    if (abs(delta.y) > viewConfiguration.touchSlop && abs(delta.y) >= abs(delta.x)) break
                    if (abs(delta.x) > viewConfiguration.touchSlop) {
                        if (delta.x >= 0f || abs(delta.x) <= abs(delta.y)) break
                        left = -delta.x
                        change.consume()
                        start = change
                    }
                }
                val drag = start ?: return@awaitEachGesture
                val released = horizontalDrag(drag.id) { change ->
                    left = (left - change.positionChange().x).coerceAtLeast(0f)
                    change.consume()
                }
                settle(released && pastDismissThreshold(left, width))
            }
        }) {
        val cue = if (pastDismissThreshold(left, width)) M.text else M.text2
        val travel = with(density) { left.toDp() }
        Box(Modifier.matchParentSize().padding(top = 3.u, bottom = 3.u, end = 6.u).drawBehind {
            val w = (left - 10.u.toPx()).coerceIn(0f, size.width)
            drawRoundRect(M.surface, Offset(size.width - w, 0f), Size(w, size.height), CornerRadius(8.u.toPx()))
        })
        Row(Modifier.align(Alignment.CenterEnd).padding(end = 18.u).clearAndSetSemantics {}.alpha(((travel.value / K - 14f) / 26f).coerceIn(0f, 1f)),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
            Canvas(Modifier.size(11.u)) {
                val a = size.width * 2.5f / 12f
                val b = size.width - a
                drawLine(cue, Offset(a, a), Offset(b, b), 1.6.u.toPx(), StrokeCap.Round)
                drawLine(cue, Offset(b, a), Offset(a, b), 1.6.u.toPx(), StrokeCap.Round)
            }
            Text(action, style = ST.step, fontWeight = FontWeight.SemiBold, color = cue)
        }
        Column(Modifier.offset { IntOffset(-left.roundToInt(), 0) }.fillMaxWidth().padding(horizontal = bleed)) {
            content(Modifier.semantics { customActions = listOf(CustomAccessibilityAction(action) { if (!busy) settle(true); true }) })
            if (failed) Text("Could not ${action.lowercase()}. Try again.", style = ST.step, color = M.text2,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
    }
}
