package com.misura.phone.screen

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration
import com.misura.core.agent.Say
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.phone.cards.M
import com.misura.phone.cards.ST
import com.misura.phone.cards.u

const val STEPS_TAG = "steps"

/** Your words after you let go: top of the screen, in quotes. */
@Composable
fun Echo(text: String, modifier: Modifier = Modifier) = Text("“$text”", style = ST.echo, modifier = modifier)

/**
 * One line per thing Misura checks or does (design-system §3). ● orange while running (with seconds after 5 s, so a
 * ~30 s search shows it's alive), ✓ done with its result, ✕ failed. Announced politely, never stealing focus.
 */
/** [live] = false for an earlier exchange's steps: nothing in them changes, so they are not announced. */
@Composable
fun Steps(steps: List<Step>, now: Long, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null, live: Boolean = true,
    icon: (Step) -> Icon? = { when { it.taskId != null -> Icon.Chat; it.app == com.misura.core.agent.WEB -> Icon.Search; it.app == com.misura.core.agent.NOTE -> Icon.Note; else -> Icon.Pin } }) {
    // Screen text opens, never commits (§2): a tap anywhere on the steps opens the history.
    // The block takes the touch (no gaps between rows); each row carries the action for TalkBack.
    val tap = if (onOpen == null) Modifier else Modifier.pointerInput(onOpen) { detectTapGestures { onOpen() } }
    Column(modifier.then(if (live) Modifier.testTag(STEPS_TAG).semantics { liveRegion = LiveRegionMode.Polite } else Modifier).then(tap), verticalArrangement = Arrangement.spacedBy(10.u)) {
        steps.forEach { s -> key(s.id) { FadeIn { StepRow(s, now, icon(s), onOpen) } } }
    }
}

@Composable private fun key(id: String, content: @Composable () -> Unit) = androidx.compose.runtime.key(id) { content() }

/** Steps fade in as they happen; no movement, so reduced motion needs nothing extra. */
@Composable
private fun FadeIn(content: @Composable () -> Unit) {
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    AnimatedVisibility(shown, enter = fadeIn(tween(200, easing = M.ease))) { content() }
}

@Composable
private fun StepRow(s: Step, now: Long, icon: Icon?, onOpen: (() -> Unit)?) {
    val secs = ((now - s.startedAt) / 1000).toInt()
    val text = when (s.state) {
        StepState.RUNNING -> (s.result ?: s.label) + if (secs >= 5) " · $secs s" else ""
        StepState.DONE -> s.result ?: s.label
        StepState.FAILED -> listOfNotNull(s.label, s.result).joinToString(" · ")
        StepState.WILL -> s.label
    }
    val word = when (s.state) { StepState.RUNNING -> "working"; StepState.DONE -> "done"; StepState.FAILED -> "failed"; StepState.WILL -> "ready, waits for your go" }
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
            contentDescription = "${s.label}, $word" + (s.result?.let { ", $it" } ?: "")
            if (onOpen != null) { role = Role.Button; onClick(label = "History") { onOpen(); true } }
        },
        verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.u),
    ) {
        val mark = s.app?.let { rememberAppMark(it) }
        when {
            mark != null -> Image(mark, null, Modifier.padding(top = 1.u).size(16.u), colorFilter = ColorFilter.tint(M.text2))
            icon != null -> LineIcon(icon, Modifier.padding(top = 1.u))
            else -> Spacer(Modifier.size(16.u))
        }
        // A step that waits for your go is at full brightness: it is the point of the screen, not background.
        Text(text, style = ST.step, color = if (s.state == StepState.RUNNING) M.text2 else M.text, modifier = Modifier.weight(1f))
        when (s.state) {
            StepState.RUNNING -> WorkingDot()
            StepState.DONE -> Text("✓", style = ST.step, color = M.text)
            StepState.FAILED -> Text("✕", style = ST.step, color = M.orange)
            StepState.WILL -> YourGo()
        }
    }
}

const val THINKING_TAG = "thinking"
const val YOUR_GO_TAG = "your-go"

/**
 * Misura working: its orange dot with two small ones circling it, one speed everywhere. A dot that stands still is Misura
 * talking (its reason on a place card); a dot with something circling it is Misura at work. [size] = the whole sign.
 */
@Composable
fun WorkingDot(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 14.u) {
    val t = rememberInfiniteTransition(label = "working")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1800, easing = androidx.compose.animation.core.LinearEasing)), label = "outer")
    val b by t.animateFloat(360f, 0f, infiniteRepeatable(tween(2900, easing = androidx.compose.animation.core.LinearEasing)), label = "inner")
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val w = this.size.width; val c = androidx.compose.ui.geometry.Offset(w / 2, w / 2)
        fun at(deg: Float, r: Float) = Math.toRadians(deg.toDouble() - 90).let { androidx.compose.ui.geometry.Offset(c.x + r * kotlin.math.cos(it).toFloat(), c.y + r * kotlin.math.sin(it).toFloat()) }
        drawCircle(M.orange, w * 0.154f, c)
        drawCircle(M.orange.copy(alpha = 0.7f), w * 0.077f, at(a, w * 0.42f))
        drawCircle(M.orange.copy(alpha = 0.45f), w * 0.058f, at(b, w * 0.33f))
    }
}

const val LISTENING_TAG = "listening"

/**
 * While you hold the key: seven orange bars that follow your voice, and "Listening". Your words are not shown as you speak
 * (his call, 15 Nov: watching them stream distracts); they appear as the echo once you let go.
 * The bars' shape is the landing page's (screen.js `listening`): heights 6 12 18 9 15 7 13, 3 wide, 3 apart. Their size follows
 * [level] (0 quiet … 1 loud; his ask, 15 Nov: move with the voice, not at random): low stubs in silence, full height when you
 * speak, with a small ripple across them so neighbours don't move as one block.
 */
@Composable
fun Listening(modifier: Modifier = Modifier, label: String = "Listening", level: () -> Float = { 0f }) = Row(modifier.testTag(LISTENING_TAG), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
    val phase by rememberInfiniteTransition(label = "listening").animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = androidx.compose.animation.core.LinearEasing)), label = "phase")
    // The recogniser reports loudness a few times a second, in steps: ease between them so the bars swell instead of jumping.
    val loud by androidx.compose.animation.core.animateFloatAsState(level(), tween(110), label = "loud")
    val bars = remember { floatArrayOf(6f, 12f, 18f, 9f, 15f, 7f, 13f) }
    androidx.compose.foundation.Canvas(Modifier.size(width = (7 * 3 + 6 * 3).u, height = 18.u)) {
        val unit = size.height / 18f
        bars.forEachIndexed { i, h ->
            val ripple = 0.85f + 0.15f * kotlin.math.sin(phase + i * 1.3f)
            val bar = (h * (QUIET_BARS + (1 - QUIET_BARS) * loud) * ripple).coerceAtLeast(3f) * unit // never shorter than a dot
            drawRoundRect(M.orange, androidx.compose.ui.geometry.Offset(i * 6 * unit, (size.height - bar) / 2), androidx.compose.ui.geometry.Size(3 * unit, bar), androidx.compose.ui.geometry.CornerRadius(1.5f * unit))
        }
    }
    Text(label, style = ST.step, color = M.text2, maxLines = 1)
}
/** How tall the bars stand in silence, as a share of full height. */
private const val QUIET_BARS = 0.22f

/**
 * Ready for your go: an empty orange ring that beats slowly. Nothing happens until you tap (the card under it, or on home
 * the line it sits on). No word: "will" read like a typo. The ring fills into ✓ once it is done.
 */
@Composable
fun YourGo(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 14.u) {
    val beat by rememberInfiniteTransition(label = "your go").animateFloat(0f, 1f, infiniteRepeatable(tween(2000, easing = M.ease)), label = "beat")
    androidx.compose.foundation.Canvas(modifier.size(size).testTag(YOUR_GO_TAG)) {
        val w = this.size.width; val r = w * 0.27f; val stroke = w * 0.1f
        drawCircle(M.orange, r, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
        drawCircle(M.orange.copy(alpha = 0.9f * (1f - beat)), r + (w / 2 - r) * beat, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 0.7f))
    }
}

/** While Misura decides what to do next and no step is running: the working sign alone, at the left edge of your words. */
@Composable
fun Thinking(modifier: Modifier = Modifier) = Box(modifier.fillMaxWidth().testTag(THINKING_TAG).semantics(mergeDescendants = true) { contentDescription = "Misura is working" }) {
    // Shifted so the centre dot, not the empty space its small dots circle through, sits on the left edge of your words.
    WorkingDot(Modifier.offset(x = (-7).u), size = 22.u)
}

/**
 * Top of the screen. Root: what you asked on the left. Deeper: "‹ previous" on the left, where you are on the right.
 * "n steps ›" opens the history. Both targets are at least 48 dp tall.
 */
@Composable
fun NavLine(back: String?, here: String, stepCount: Int, onBack: () -> Unit, onSteps: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Text("‹ $back", style = ST.nav, color = M.text, maxLines = 1,
            modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Back", onClick = onBack).padding(end = 12.u).wrapCenter())
        // Root: what you asked takes the free width; ellipsis only if it really doesn't fit.
        else Text(here, style = ST.nav, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).heightIn(min = 48.dp).wrapCenter())
        // With "‹ …" on the left, your words take what's left and end in "…"; the step count always stays whole.
        val words = here.takeIf { back != null && it.isNotEmpty() }
        val count = if (stepCount > 0) "$stepCount ${if (stepCount == 1) "step" else "steps"} ›" else null
        if (words != null || count != null) Row((if (back != null) Modifier.weight(1f, fill = false) else Modifier).heightIn(min = 48.dp)
            .clickable(enabled = stepCount > 0, role = Role.Button, onClickLabel = "History", onClick = onSteps).padding(start = 12.u).wrapCenter()) {
            if (words != null) Text(words, style = ST.nav, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (count != null) Text(if (words != null) " · $count" else count, style = ST.nav, maxLines = 1)
        }
    }
}

private fun Modifier.wrapCenter() = this.then(Modifier.padding(vertical = 16.dp))

const val PAST_TAG = "past"

/** An earlier exchange in a task (the thread): your words, what Misura did, what it said. Quieter than the one in front. */
@Composable
fun Past(asked: String, steps: List<Step>, answer: String?, now: Long, modifier: Modifier = Modifier, cited: Say.Answer? = null) = Column(modifier.testTag(PAST_TAG), verticalArrangement = Arrangement.spacedBy(10.u)) {
    if (asked.isNotEmpty()) Echo(asked)
    if (steps.isNotEmpty()) Steps(steps, now, live = false)
    if (cited != null) AnswerText(cited.line, cited = cited) else answer?.let { Text(it, style = ST.echo, color = M.text) }
}

/** Misura's answer as screen text: short ones big ("Booked."), longer ones at said size. */
/** Long ones (an explanation, a view) drop to echo size in text colour: at said size 120 words ran over two screens. */
@Composable
fun AnswerText(line: String, modifier: Modifier = Modifier, cited: Say.Answer? = null) {
    val text = buildAnnotatedString {
        var at = 0
        val citations = cited?.takeIf { it.line == line }?.citations.orEmpty().filter { it.validFor(line) }
        for ((end, sources) in citations.groupBy { it.end ?: line.length }.toSortedMap()) {
            append(line.substring(at, end))
            for (source in sources) {
                append(" ")
                withLink(LinkAnnotation.Url(source.url, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline)))) {
                    append("[${source.title}]")
                }
            }
            at = end
        }
        append(line.substring(at))
        if (citations.isNotEmpty()) cited?.provider?.let { append("\n$it") }
    }
    Text(text, style = when { line.length <= 24 -> ST.outcome; line.length <= 160 -> ST.said; else -> ST.echo.copy(color = M.text) }, modifier = modifier)
}
