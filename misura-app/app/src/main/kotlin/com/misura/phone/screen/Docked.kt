package com.misura.phone.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.misura.core.agent.ItemFact
import com.misura.core.agent.PlaceFact
import com.misura.phone.cards.LocalRadii
import com.misura.phone.cards.M
import com.misura.phone.cards.T
import com.misura.phone.cards.u

const val DOCKED_TAG = "docked"

/** The card you're talking about, small at the bottom while you type or Misura works (design-system §4 Docked). */
@Composable
fun Docked(p: PlaceFact, label: String, photos: PhotoLoader?, onClear: (() -> Unit)?, modifier: Modifier = Modifier) {
    val thumb by produceState<com.misura.phone.brain.Shown?>(null, p.id) { value = p.photos.firstOrNull()?.let { ph -> photos?.invoke(p, ph) } }
    DockedRow(p.label, label, thumb?.bitmap, onClear, modifier)
}

/** The same chip for an item: its photo comes off the file, sized for a thumbnail. */
@Composable
fun Docked(i: ItemFact, label: String, onClear: (() -> Unit)?, modifier: Modifier = Modifier) = DockedRow(i.title, label, itemPhoto(i.photo, 120), onClear, modifier)

@Composable
private fun DockedRow(title: String, label: String, thumb: ImageBitmap?, onClear: (() -> Unit)?, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().testTag(DOCKED_TAG).background(M.surface, RoundedCornerShape(LocalRadii.current.card)).padding(M.p),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u),
    ) {
        val r = RoundedCornerShape(LocalRadii.current.tile)
        Box(Modifier.size(40.u).clip(r).background(M.sunk)) { thumb?.let { Image(it, null, Modifier.size(40.u), contentScale = ContentScale.Crop) } }
        Column(Modifier.weight(1f)) {
            // No credit here (his call 13 Nov: "Asking about · Photo: …" read like a bug); the place's own card credits its photos.
            Text(label, style = T.src, maxLines = 1)
            Text(title, style = T.who, maxLines = 1)
        }
        if (onClear != null) Text("✕", style = T.who.copy(color = M.text2),
            modifier = Modifier.size(48.dp).clickable(role = Role.Button, onClickLabel = "Ask about something else", onClick = onClear).padding(14.dp))
    }
}
