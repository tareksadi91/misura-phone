package com.misura.phone.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.misura.core.agent.Say
import com.misura.phone.cards.Kind
import com.misura.phone.cards.LocalRadii
import com.misura.phone.cards.M
import com.misura.phone.cards.MButton
import com.misura.phone.cards.OptionRow
import com.misura.phone.cards.T
import com.misura.phone.cards.u

/**
 * One surface: label + body as plain text on the card, tiles under it (design-system §4 Question card).
 * The card recipe: text starts where the button's label starts (card inset 6 + button padding 12), label 4 above its
 * content, 14 from the card's top to the text and from the text to the buttons.
 */
@Composable
private fun AskCard(label: String?, body: String, followActions: Boolean = false, title: String? = null, tiles: @Composable () -> Unit) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val shape = RoundedCornerShape(LocalRadii.current.card)
    val scroll = key(label, title, body) { rememberScrollState() }
    LaunchedEffect(scroll, followActions) {
        if (followActions) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Column(
    Modifier.fillMaxWidth().heightIn(max = maxHeight * .65f).background(M.surface, shape).clip(shape)
        .verticalScroll(scroll).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    Column(Modifier.padding(start = 12.u, end = 12.u, top = 8.u, bottom = 8.u).semantics(mergeDescendants = true) { heading() }) {
        if (label != null) Text(label, style = T.src, modifier = Modifier.padding(bottom = 4.u))
        if (title != null) Text(title, style = T.who, modifier = Modifier.padding(bottom = 4.u))
        Text(body, style = T.msg)
    }
    tiles()
}
}

/** Misura asks you something; choices are paper tiles (one tap sends that choice as your answer). */
@Composable
fun QuestionCard(q: Say.Question, armed: Boolean = false, onUndo: () -> Unit = {}, onChoice: (Int) -> Unit) = AskCard(q.label, q.line, followActions = armed, title = q.title) {
    // A stopped commit (a tap in an app that pays, books, deletes…): the go-ahead is the one solid orange button, and it says
    // Approve. That word is kept for these serious cases; it takes a tap, a spoken "go" never reaches it.
    if (q.gate != null && q.choices.size == 2) Row(horizontalArrangement = Arrangement.spacedBy(M.p)) {
        // A message typed in an app (label = app · chat, line = the exact words) reads like any message card: plain "Send".
        // [armed] = you said "go" on it: the button works for a few seconds and Undo takes it back (the caller only arms these).
        MButton(if (q.label != null) q.choices[0] else "Approve: ${q.choices[0]}", Kind.Commit, Modifier.weight(1.6f), working = armed) { if (!armed) onChoice(0) }
        if (armed) MButton("Undo", Kind.Secondary, Modifier.weight(0.8f), onClick = onUndo)
        else MButton(q.choices[1], Kind.Secondary, Modifier.weight(0.8f)) { onChoice(1) }
    }
    else q.choices.forEachIndexed { i, c -> OptionRow(c, selected = false) { onChoice(i) } }
}

/** After a ✕ step: what failed (the line says whether anything went out) and the fix. */
@Composable
fun ProblemCard(p: Say.Problem, action: String = "Try again", onRetry: () -> Unit) = AskCard("Didn't work", p.line) {
    MButton(action, Kind.Forward, Modifier.fillMaxWidth(), onClick = onRetry)
}

/**
 * Drafted messages (design-system §4 approve): where and to whom, the exact text of each, and the one commit.
 * Solid orange Send is the only thing that sends, all of them on one go; Edit = say what to change. People who get the
 * same words share a block. [sending] swaps Send for a meter and blocks taps; [onUndo] (after a spoken "go") offers
 * Undo in Edit's place until the sends start.
 */
@Composable
fun ApproveCard(app: String, messages: List<Pair<String, String>>, sending: Boolean, onSend: () -> Unit, onEdit: () -> Unit, onUndo: (() -> Unit)? = null,
    scroll: ScrollState = rememberScrollState()) = BoxWithConstraints(Modifier.fillMaxWidth()) {
    val shape = RoundedCornerShape(LocalRadii.current.card)
    LaunchedEffect(scroll, onUndo != null) {
        if (onUndo != null) snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) }
    }
    Column(
    Modifier.fillMaxWidth().heightIn(max = maxHeight * .65f).background(M.surface, shape).clip(shape)
        .verticalScroll(scroll).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    val blocks = messages.groupBy({ it.second }, { it.first }).entries.toList()
    Column(Modifier.padding(start = 12.u, end = 12.u, top = 8.u, bottom = 8.u), verticalArrangement = Arrangement.spacedBy(11.u)) {
        blocks.forEachIndexed { i, (text, names) ->
            Column(Modifier.semantics(mergeDescendants = true) { if (i == 0) heading() }) {
                Text((if (i == 0) "$app · " else "") + names.joinToString(), style = T.src, modifier = Modifier.padding(bottom = 4.u))
                Text("“$text”", style = T.msg)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(M.p)) {
        MButton(if (messages.size > 1) "Send ${messages.size}" else "Send", Kind.Commit, Modifier.weight(1.6f), working = sending, onClick = onSend)
        if (onUndo != null) MButton("Undo", Kind.Secondary, Modifier.weight(0.8f), onClick = onUndo)
        else MButton("Edit", Kind.Secondary, Modifier.weight(0.8f), enabled = !sending, onClick = onEdit)
    }
}
}

@Composable
fun ApproveCard(app: String, name: String, text: String, sending: Boolean, onSend: () -> Unit, onEdit: () -> Unit) = ApproveCard(app, listOf(name to text), sending, onSend, onEdit)
