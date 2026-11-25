package com.misura.phone.screen

import android.graphics.BitmapFactory
import com.misura.phone.store.StorageCipher
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.misura.core.agent.ItemFact
import com.misura.core.agent.PlaceFact
import com.misura.core.agent.directionsUrl
import com.misura.phone.cards.Kind
import com.misura.phone.cards.MButton
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.LocalContext
import com.misura.phone.cards.LocalRadii
import com.misura.phone.cards.M
import com.misura.phone.cards.T
import com.misura.phone.cards.u
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Items screen: [line] (Misura's words) as plain text above the cards, which sit at the bottom like the place cards.
 * A near-me app find ([ItemFact.near], owner list item 7) is the decision screen instead: a map on top when some venue has a certain pin
 * (never an empty map), text-only cards with Open in app and, with a pin, Directions; Misura's line goes on the pick's card. [stale] = the
 * session is older than PLACES_MAX_AGE_MS: no pins (Places terms, like place cards). [onDirections] opens a Maps link.
 */
@Composable
fun ItemScreen(items: List<ItemFact>, pick: String?, line: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (ItemFact) -> Unit, modifier: Modifier = Modifier,
    stale: Boolean = false, onDirections: ((String) -> Unit)? = null) {
    if (items.any { it.near }) return NearItems(items, pick, line, index, onIndex, onOpen, modifier, stale, onDirections)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(M.p)) {
        Spacer(Modifier.weight(1f))
        if (line != null) AnswerText(line, Modifier.padding(horizontal = M.gutter - M.m))
        // All or nothing per set (owner, 25 Nov): one card without a photo means no card shows one, so the set stays even.
        val photos = items.all { it.photo != null }
        CardPager(items.size, index, onIndex, "item") { i -> ItemCard(items[i], items[i].id == pick, photos) { onOpen(items[i]) } }
    }
}

const val ITEM_PHOTO_TAG = "item-photo"

@Composable
private fun NearItems(items: List<ItemFact>, pick: String?, line: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (ItemFact) -> Unit, modifier: Modifier,
    stale: Boolean, onDirections: ((String) -> Unit)?) {
    val ctx = LocalContext.current
    val open = onDirections ?: { url: String -> runCatching { com.misura.phone.MisuraGraph.openDirections(ctx.applicationContext, url) }; Unit }
    // Past the places age limit a card keeps its words only: no map, no Directions, no reason about the map.
    val shown = if (stale) items.map { it.copy(lat = null, lng = null, noPin = null) } else items
    val pinned = shown.filter { it.lat != null && it.lng != null }
    val onPick = line != null && shown.any { it.id == pick }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(M.p)) {
        if (pinned.isNotEmpty())
            PlaceMap(pinned.map { PlaceFact(it.id, it.title, lat = it.lat, lng = it.lng) }, shown.getOrNull(index)?.id, onTap = {},
                onSelect = { id -> shown.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(onIndex) },
                modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(LocalRadii.current.card)))
        else Spacer(Modifier.weight(1f))
        if (line != null && !onPick) AnswerText(line, Modifier.padding(horizontal = M.gutter - M.m))
        CardPager(shown.size, index, onIndex, "item") { i ->
            val f = shown[i]
            NearItemCard(f, f.id == pick, if (f.id == pick) line else null, { onOpen(items[i]) }, f.directionsUrl()?.let { url -> { open(url) } })
        }
    }
}

/** What a looked-up card without a pin says on its spare line. */
fun noPinLine(why: String?): String? = when (why) {
    com.misura.core.agent.NO_PIN_SHARED -> "Not on the map: more than one place has this name nearby."
    com.misura.core.agent.NO_PIN_NONE -> "Not found on the map."
    else -> null
}

/**
 * A near-me app find's card (owner list item 7), text only and always the same height: title + PICK; "detail · distance · app" on one line;
 * a spare line (Misura's [line] on the pick, else why the venue has no pin, else empty); Open in app, and Directions only with a pin.
 * The buttons are the tap targets, not the card.
 */
@Composable
fun NearItemCard(i: ItemFact, isPick: Boolean, line: String?, onOpen: () -> Unit, onDirections: (() -> Unit)?) = Column(
    Modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card)).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    Column(Modifier.padding(horizontal = 6.u), verticalArrangement = Arrangement.spacedBy(3.u)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
            Text(i.title, style = T.who, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            // Same height with or without the tag (the title line sets it).
            if (isPick) PickTag()
        }
        // The distance is said once: a detail that already shows it keeps it.
        val meta = listOfNotNull(i.detail, i.distance?.takeIf { d -> i.detail?.contains(d) != true }, i.app).joinToString(" · ")
        Text(meta, style = T.src, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(horizontalArrangement = Arrangement.spacedBy(6.u)) {
            if (line != null) Box(Modifier.padding(top = 6.u).size(6.u).background(M.orange, CircleShape))
            Text(line ?: noPinLine(i.noPin).orEmpty(), style = T.line, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    // Directions keeps its slot when there is no pin (an invisible, untappable stand-in), so both buttons wrap alike on every card and the
    // cards stay the same height at any width or font size.
    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(M.gap)) {
        MButton("Open in app", Kind.Forward, Modifier.weight(1.6f).fillMaxHeight(), onClick = onOpen)
        if (onDirections != null) MButton("Directions", Kind.Secondary, Modifier.weight(1f).fillMaxHeight(), onClick = onDirections)
        else Box(Modifier.weight(1f).alpha(0f).clearAndSetSemantics {}) { MButton("Directions", Kind.Secondary, Modifier.fillMaxWidth(), enabled = false) {} }
    }
}

/**
 * Item card: photo slot ([photo]: the whole set has one), title, PICK + price, detail, the app it came from. The card is the tap target.
 * Every text slot keeps its height when empty, so cards in a set are the same height; a photo that doesn't load leaves its square empty.
 */
@Composable
fun ItemCard(i: ItemFact, isPick: Boolean, photo: Boolean = i.photo != null, onOpen: () -> Unit) = Column(
    Modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card))
        .clickable(role = Role.Button, onClickLabel = "Open ${i.title}", onClick = onOpen).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    if (photo) Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(LocalRadii.current.tile)).background(M.sunk).testTag(ITEM_PHOTO_TAG)) {
        itemPhoto(i.photo, 800)?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
    Column(Modifier.padding(horizontal = 6.u).padding(bottom = 6.u), verticalArrangement = Arrangement.spacedBy(3.u)) {
        Text(i.title, style = T.who, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
            if (isPick) PickTag()
            // Same vertical padding as PickTag, so the row is as tall with or without it.
            Text(i.price.orEmpty(), style = T.src.copy(color = M.text), maxLines = 1, modifier = Modifier.padding(vertical = 1.u))
        }
        Text(i.detail.orEmpty(), style = T.msg, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(i.app.orEmpty(), style = T.src, maxLines = 1)
    }
}

/**
 * The item's JPEG off its file, read off the main thread and sampled down to about [px] wide; null while loading or when it can't be read.
 * ponytail: decoded per composition, no cache; fine for the few cards on screen, add a small LRU if swiping ever stutters.
 */
@Composable
fun itemPhoto(path: String?, px: Int): ImageBitmap? = produceState<ImageBitmap?>(null, path, px) {
    value = path?.let { p -> withContext(Dispatchers.IO) { runCatching { decodeSampled(File(p), px) }.getOrNull() } }
}.value

private fun decodeSampled(f: File, px: Int): ImageBitmap? {
    val bytes = StorageCipher.shared.decrypt(f.readBytes()) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    var s = 1
    while (bounds.outWidth / (s * 2) >= px) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })?.asImageBitmap()
}
