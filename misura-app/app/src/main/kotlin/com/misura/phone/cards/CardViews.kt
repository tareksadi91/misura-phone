package com.misura.phone.cards

import android.view.RoundedCorner
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.TextFieldDecorator
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.misura.phone.R
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** Tokens from docs/design/design-system.md §2. Dark only, one orange. */
object M {
    val screen = Color(0xFF080706); val surface = Color(0xFF262423); val sunk = Color(0xFF1C1A19)
    val text = Color(0xFFEFEEEC); val text2 = Color(0xFFADAAA8); val paper = text; val ink = Color(0xFF110F0E)
    val orange = Color(0xFFF47B30); val outline = Color(0xFF82807E)
    val barOff = Color(0xFF504E4C); val rowMeta = Color(0xFF5F5D5B)
    val you = Color(0xFF4285F4); val star = Color(0xFFFBBC04)
    val gutter = 16.u
    val m = 6.u; val p = 6.u; val gap = 4.u
    val ease = CubicBezierEasing(0.22f, 1f, 0.36f, 1f) // ease-out quint
}

// ponytail: the 3a mockup frame is 228 px wide; the emulator is 411 dp (×1.8 would match width, but body text lands at 25 sp,
// which he rejected on cards v1). 1.5 keeps body at ~21 sp and cards in thumb reach. One knob for every size; tune on the real 3a.
const val K = 1.5f
val Number.u: Dp get() = (toFloat() * K).dp
val Number.s: TextUnit get() = (toFloat() * K).sp

private fun hanken(w: Int) = Font(R.font.hanken_grotesk, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
val Hanken = FontFamily(listOf(300, 400, 500, 600, 700, 800).map(::hanken))
val Jost = FontFamily(listOf(300, 400, 500, 600, 700, 800).map { w ->
    Font(R.font.jost, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
})
val Plex = FontFamily(Font(R.font.ibm_plex_mono_medium, FontWeight.Medium))

/** Card roles (design-system §2): label = src, title = who, body = msg, big = word; plus button and option-row styles. */
object T {
    val src = TextStyle(fontFamily = Plex, fontSize = 11.5.s, fontWeight = FontWeight.Medium, color = M.text2)
    val who = TextStyle(fontFamily = Jost, fontSize = 17.s, fontWeight = FontWeight(700), color = M.text)
    val msg = TextStyle(fontFamily = Hanken, fontSize = 14.s, fontWeight = FontWeight(400), lineHeight = 1.35.em, color = M.text)
    val word = TextStyle(fontFamily = Jost, fontSize = 28.s, fontWeight = FontWeight(800), letterSpacing = (-0.045).em, lineHeight = 0.9.em, color = M.text)
    val line = TextStyle(fontFamily = Hanken, fontSize = 13.s, fontWeight = FontWeight(400), color = M.text2)
    val btn = TextStyle(fontFamily = Hanken, fontSize = 14.s, fontWeight = FontWeight(700))
    val row = TextStyle(fontFamily = Hanken, fontSize = 14.s, fontWeight = FontWeight(600), color = M.ink)
    val rowMeta = TextStyle(fontFamily = Hanken, fontSize = 12.s, fontWeight = FontWeight(500), color = M.rowMeta)
}

/** Screen roles (design-system §2): plain text on the screen layer, never in a card. */
object ST {
    val said = TextStyle(fontFamily = Hanken, fontSize = 19.s, fontWeight = FontWeight(300), lineHeight = 1.3.em, color = M.text)
    val echo = TextStyle(fontFamily = Hanken, fontSize = 16.s, fontWeight = FontWeight(300), lineHeight = 1.3.em, color = M.text2)
    val outcome = TextStyle(fontFamily = Jost, fontSize = 34.s, fontWeight = FontWeight(300), lineHeight = 1.1.em, color = M.text)
    val step = TextStyle(fontFamily = Hanken, fontSize = 13.5.s, fontWeight = FontWeight(400), lineHeight = 1.3.em, color = M.text)
    val nav = TextStyle(fontFamily = Hanken, fontSize = 12.s, fontWeight = FontWeight(600), color = M.text2)
    val clock = TextStyle(fontFamily = Plex, fontSize = 46.s, fontWeight = FontWeight.Medium, color = M.text)
    val home = TextStyle(fontFamily = Jost, fontSize = 30.s, fontWeight = FontWeight.Medium, lineHeight = 1.1.em, color = M.text)
    val date = TextStyle(fontFamily = Hanken, fontSize = 12.s, fontWeight = FontWeight.Medium, color = M.text2)
}

/** Card and tile corners, concentric with the real screen corner (§2). */
data class Radii(val card: Dp, val tile: Dp)
val LocalRadii = staticCompositionLocalOf { Radii(11.u, 5.u) }

@Composable
fun MisuraSurface(content: @Composable () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val screenR = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
        ?.let { with(density) { it.toDp() } }
        // ponytail: floor at the mockup's 3a corner; the emulator reports a smaller one. Drop the floor once tuned on the 3a.
        .let { maxOf(it ?: 0.dp, 17.u) }
    val card = maxOf(screenR - M.m, 8.dp)
    CompositionLocalProvider(LocalRadii provides Radii(card, maxOf(card - M.p, 4.dp))) {
        Box(Modifier.background(M.screen)) { content() }
    }
}

// ---------- Elements ----------

enum class Kind { Commit, Forward, Secondary }

/**
 * .btn: label left, arrow right for Commit and Forward; Secondary centered.
 * [working] swaps the label for a meter and blocks taps.
 */
@Composable
fun MButton(label: String, kind: Kind, modifier: Modifier = Modifier, enabled: Boolean = true, working: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LocalRadii.current.tile)
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.97f else 1f, tween(120, easing = M.ease), label = "press")
    val fg = when (kind) { Kind.Commit -> M.ink; Kind.Forward -> M.orange; Kind.Secondary -> M.text }
    val base = when (kind) {
        Kind.Commit -> Modifier.background(M.orange, shape)
        Kind.Forward -> Modifier.border(1.5.dp, M.orange, shape)
        Kind.Secondary -> Modifier.border(1.5.dp, M.outline, shape)
    }
    Row(
        modifier.width(IntrinsicSize.Max).scale(sc).alpha(if (enabled) 1f else 0.4f).heightIn(min = 48.dp).then(base)
            .clickable(src, null, enabled = enabled && !working, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { if (working) contentDescription = "$label, working" }
            .padding(horizontal = 12.u, vertical = 13.u),
        horizontalArrangement = if (kind == Kind.Secondary || working) Arrangement.Center else Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (working) Meter(fg, fg.copy(alpha = 0.3f), blocks = 3, modifier = Modifier.width(36.u))
        else {
            Text(label, style = T.btn, color = fg, modifier = Modifier.weight(1f, fill = false))
            if (kind != Kind.Secondary) Text("→", style = T.btn, color = fg, modifier = Modifier.padding(start = 6.u))
        }
    }
}

/** .row: paper tile, label left, note right. Selected = 2 dp orange inner edge plus a check, never color alone. */
@Composable
fun OptionRow(text: String, selected: Boolean, note: String? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LocalRadii.current.tile)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).background(M.paper, shape)
            .then(if (selected) Modifier.border(2.dp, M.orange, shape) else Modifier)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.u, vertical = 11.u),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Short notes sit on the right like the design ("no bookings"); longer agent notes go under the label.
        if (note != null && note.length > 16) Column(Modifier.weight(1f)) {
            Text(text, style = T.row)
            Text(note, style = T.rowMeta, modifier = Modifier.padding(top = 2.u))
        } else {
            Text(text, style = T.row, modifier = Modifier.weight(1f))
            if (note != null) Text(note, style = T.rowMeta, modifier = Modifier.padding(start = 8.u))
        }
        if (selected) Text("✓", style = T.btn, color = M.ink, modifier = Modifier.padding(start = 8.u))
    }
}

/** .bar: discrete flat blocks across the width, never a smooth bar. Running = blocks fill one by one. */
@Composable
fun Meter(on: Color = M.text, off: Color = M.barOff, blocks: Int = 7, modifier: Modifier = Modifier.fillMaxWidth()) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(400); tick++ } }
    val lit = (tick % (blocks + 1)).toInt()
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(3.u)) {
        repeat(blocks) { i -> Box(Modifier.weight(1f).height(8.u).background(if (i < lit) on else off, RoundedCornerShape(2.u))) }
    }
}

@Composable
fun Field(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default, singleLine: Boolean = false,
    useStateField: Boolean = false, label: String = hint, acceptInput: Boolean = true, onKeyboardAction: (() -> Unit)? = null) {
    val shape: Shape = RoundedCornerShape(LocalRadii.current.tile)
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldModifier = modifier.fillMaxWidth().heightIn(min = 48.dp).background(M.sunk, shape)
        .border(if (fieldFocused) 2.dp else 1.dp, if (fieldFocused) Color(0xFFAAA49B) else Color(0xFF4C4640), shape)
        .onFocusChanged { fieldFocused = it.isFocused }
        .padding(horizontal = 10.u, vertical = 8.u).semantics { contentDescription = label }
    val decoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            leading?.invoke()
            Box(Modifier.weight(1f)) { if (value.isEmpty()) Text(hint, style = T.msg, color = M.text2); inner() }
        }
    }
    if (useStateField) {
        // Legacy BasicTextField(value, onChange) ignores showKeyboardOnFocus in Foundation 1.12.1.
        val state = rememberTextFieldState(value)
        val currentValue by rememberUpdatedState(value)
        val currentOnChange by rememberUpdatedState(onChange)
        LaunchedEffect(value) { if (state.text.toString() != value) state.setTextAndPlaceCursorAtEnd(value) }
        LaunchedEffect(state) {
            snapshotFlow { state.text.toString() }.collect { if (it != currentValue) currentOnChange(it) }
        }
        BasicTextField(state, modifier = fieldModifier, textStyle = T.msg, cursorBrush = SolidColor(M.orange),
            inputTransformation = if (acceptInput) null else InputTransformation { revertAllChanges() },
            keyboardOptions = keyboardOptions,
            onKeyboardAction = onKeyboardAction?.let { action -> androidx.compose.foundation.text.input.KeyboardActionHandler { action() } },
            lineLimits = if (singleLine) TextFieldLineLimits.SingleLine else TextFieldLineLimits.Default,
            decorator = TextFieldDecorator { inner -> decoration(inner) })
    } else BasicTextField(
        value, { if (acceptInput) onChange(it) }, textStyle = T.msg, cursorBrush = SolidColor(M.orange),
        keyboardOptions = keyboardOptions, keyboardActions = keyboardActions, singleLine = singleLine,
        modifier = fieldModifier, decorationBox = decoration,
    )
}

// ---------- Card ----------

/** .card: one surface. .c holds header + hero as plain text; tiles follow with gap p (§4). */
@Composable
fun CardFrame(source: String, title: String, note: String = "", hero: @Composable () -> Unit = {}, tiles: @Composable () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card))
            .animateContentSize(tween(220, easing = M.ease)).padding(M.p),
        verticalArrangement = Arrangement.spacedBy(M.p),
    ) {
        Column(Modifier.padding(start = 6.u, end = 6.u, top = 6.u, bottom = 2.u)) {
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                Text(source, style = T.src)
                Text(title, style = T.who, modifier = Modifier.padding(top = 2.u))
            }
            hero()
            if (note.isNotEmpty()) Text(note, style = T.line, modifier = Modifier.padding(top = 6.u))
        }
        tiles()
    }
}

private fun clock(at: Long) = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))

@Composable private fun Word(text: String, tick: Boolean = false) = Row(Modifier.padding(top = 8.u), verticalAlignment = Alignment.CenterVertically) {
    if (tick) Box(Modifier.padding(end = 8.u).size(22.u).background(M.text, CircleShape), contentAlignment = Alignment.Center) {
        Text("✓", style = T.btn.copy(fontSize = 12.s, fontWeight = FontWeight(800)), color = M.ink)
    }
    Text(text, style = T.word)
}

/**
 * Draws [card]. Callbacks are null when the card has no such action.
 * [onCommit] / [onEdit] go with what is rendered right now; the caller binds the digest.
 */
@Composable
fun CardView(
    card: Card,
    note: String = "",
    working: Boolean = false,
    commitKey: String? = null,
    onCommit: (() -> Unit)? = null,
    onPick: ((String) -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
) = AnimatedContent(
    card,
    transitionSpec = { fadeIn(tween(220, easing = M.ease)) togetherWith fadeOut(tween(150, easing = M.ease)) using SizeTransform(clip = false) },
    contentKey = { it::class to it.title },
    label = "card",
) { c ->
    // The card fading out is display only: its buttons must never act on the new task state.
    if (c == card) CardBody(c, note, working, commitKey, onCommit, onPick, onEdit) else CardBody(c, note)
}

@Composable
private fun CardBody(
    card: Card,
    note: String = "",
    working: Boolean = false,
    commitKey: String? = null,
    onCommit: (() -> Unit)? = null,
    onPick: ((String) -> Unit)? = null,
    onEdit: ((String) -> Unit)? = null,
) {
    var picked by remember(card) { mutableStateOf<String?>(null) }
    var editing by remember(card) { mutableStateOf<String?>(null) }
    when (card) {
        is Card.Progress -> {
            var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
            LaunchedEffect(card.since) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
            val secs = ((now - card.since) / 1000).coerceAtLeast(0)
            CardFrame(card.source, card.title, note, hero = {
                Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "${card.line}, $secs seconds" }) {
                    Box(Modifier.padding(top = 12.u)) { Meter() }
                    Text("$secs s · ${card.line.lowercase()}", style = T.line, modifier = Modifier.padding(top = 6.u))
                }
            })
        }
        is Card.Choices -> CardFrame(card.source, card.title, note, tiles = {
            Column(verticalArrangement = Arrangement.spacedBy(M.gap)) { card.options.forEach { o -> OptionRow(o, picked == o) { picked = o } } }
            // Rows only select (§10.3); the next card shows the exact message to approve.
            if (onPick != null) MButton("Draft reply", Kind.Forward, Modifier.fillMaxWidth(), enabled = picked != null) { picked?.let(onPick) }
        })
        is Card.Approve -> {
            val e = editing
            if (e == null) CardFrame(card.source, card.title, note, hero = {
                Text(card.content, style = T.msg, modifier = Modifier.padding(top = 8.u))
            }, tiles = {
                // A new draft makes a new button, so a press that began on the old draft can't send this one (§10.2).
                key(commitKey) {
                    Row(horizontalArrangement = Arrangement.spacedBy(M.p)) {
                        if (onCommit != null) MButton(card.commit, Kind.Commit, Modifier.weight(1.6f), working = working, onClick = onCommit)
                        if (onEdit != null && !working) MButton("Edit", Kind.Secondary, Modifier.weight(0.8f)) { editing = card.content }
                    }
                }
            })
            else CardFrame(card.source, card.title, note, tiles = {
                Field(e, { editing = it }, "Message")
                Row(horizontalArrangement = Arrangement.spacedBy(M.p)) {
                    MButton("Use this", Kind.Forward, Modifier.weight(1.6f), enabled = e.isNotBlank()) { onEdit?.invoke(e); editing = null }
                    MButton("Cancel", Kind.Secondary, Modifier.weight(0.8f)) { editing = null }
                }
            })
        }
        is Card.Done -> CardFrame(card.source, card.title, hero = {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Word(card.word, tick = true)
                Text("${card.word.lowercase()} ${clock(card.at)}", style = T.line, modifier = Modifier.padding(top = 6.u))
            }
        })
        is Card.Problem -> CardFrame(card.source, card.title, note, hero = {
            Column(Modifier.semantics(mergeDescendants = true) {}) {
                Word(card.word)
                Text(card.line, style = T.line, modifier = Modifier.padding(top = 6.u))
            }
        })
    }
}
