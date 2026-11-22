package com.misura.phone.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import com.misura.phone.cards.M
import com.misura.phone.cards.ST
import com.misura.phone.cards.u
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** Observes the existing sheet; never consumes a drag or owns keyboard/motion state. */
@Composable
fun FirstUseHints(sheet: AppSheetState, eligible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("first-use", 0) }
    var used by remember(prefs) { mutableStateOf(prefs.getBoolean("apps-opened", false)) }
    LaunchedEffect(sheet, used) {
        if (!used) snapshotFlow { Triple(sheet.searching, sheet.progress.value, sheet.progress.isRunning) }.collect { (searching, progress, animating) ->
            if (searching && progress >= .999f && !animating) {
                // moving is not observable; progress wakes us, then wait for the existing owner's completion.
                val settled = withTimeoutOrNull(2_000) { while (sheet.moving && sheet.searching) delay(16); true } == true
                if (settled && sheet.searching && !sheet.moving && sheet.progress.value >= .999f) {
                    prefs.edit().putBoolean("apps-opened", true).apply()
                    used = true
                }
            }
        }
    }
    if (!used && eligible && !sheet.engaged && !sheet.searching) Row(
        modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.u),
    ) {
        Canvas(Modifier.size(14.u).clearAndSetSemantics {}) {
            for (y in listOf(.15f, .5f)) {
                drawLine(M.text2, Offset(size.width * .2f, size.height * y), Offset(size.width * .5f, size.height * (y + .22f)), 1.5.u.toPx(), StrokeCap.Round)
                drawLine(M.text2, Offset(size.width * .5f, size.height * (y + .22f)), Offset(size.width * .8f, size.height * y), 1.5.u.toPx(), StrokeCap.Round)
            }
        }
        Text("Swipe down for apps", style = ST.date)
    }
}
