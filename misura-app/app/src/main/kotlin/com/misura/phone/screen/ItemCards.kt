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
import com.misura.phone.cards.LocalRadii
import com.misura.phone.cards.M
import com.misura.phone.cards.T
import com.misura.phone.cards.u
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Items screen: [line] (Misura's words) as plain text above the cards, which sit at the bottom like the place cards. */
@Composable
fun ItemScreen(items: List<ItemFact>, pick: String?, line: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (ItemFact) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(M.p)) {
        Spacer(Modifier.weight(1f))
        if (line != null) AnswerText(line, Modifier.padding(horizontal = M.gutter - M.m))
        // All or nothing per set (owner, 25 Nov): one card without a photo means no card shows one, so the set stays even.
        val photos = items.all { it.photo != null }
        CardPager(items.size, index, onIndex, "item") { i -> ItemCard(items[i], items[i].id == pick, photos) { onOpen(items[i]) } }
    }
}

const val ITEM_PHOTO_TAG = "item-photo"

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
