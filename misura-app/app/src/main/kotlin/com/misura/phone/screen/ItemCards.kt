package com.misura.phone.screen

import android.graphics.BitmapFactory
import com.misura.phone.store.StorageCipher
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
        CardPager(items.size, index, onIndex, "item") { i -> ItemCard(items[i], items[i].id == pick) { onOpen(items[i]) } }
    }
}

/** Item card: photo (only if it loads), title, PICK + price, detail, the app it came from. The card is the tap target. */
@Composable
fun ItemCard(i: ItemFact, isPick: Boolean, onOpen: () -> Unit) = Column(
    Modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card))
        .clickable(role = Role.Button, onClickLabel = "Open ${i.title}", onClick = onOpen).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    itemPhoto(i.photo, 800)?.let { Image(it, null, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(LocalRadii.current.tile)), contentScale = ContentScale.Crop) }
    Column(Modifier.padding(horizontal = 6.u).padding(bottom = 6.u), verticalArrangement = Arrangement.spacedBy(3.u)) {
        Text(i.title, style = T.who, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (isPick || i.price != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
            if (isPick) PickTag()
            i.price?.let { Text(it, style = T.src.copy(color = M.text), maxLines = 1) }
        }
        i.detail?.let { Text(it, style = T.msg, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        i.app?.let { Text(it, style = T.src, maxLines = 1) }
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
