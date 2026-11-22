package com.misura.phone.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.misura.core.agent.Photo
import com.misura.core.agent.PlaceFact
import com.misura.phone.cards.Kind
import com.misura.phone.cards.LocalRadii
import com.misura.phone.cards.M
import com.misura.phone.cards.MButton
import com.misura.phone.cards.T
import com.misura.phone.cards.u
import com.misura.phone.brain.Shown
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Loads one photo; the result carries the credit of the photo actually shown (it can be a refreshed one). */
typealias PhotoLoader = suspend (PlaceFact, Photo) -> Shown?

const val CAROUSEL_TAG = "carousel"
const val MOSAIC_TAG = "mosaic"

/** Decision screen: map on top (only if some place has coordinates), Misura's line, place carousel at the bottom. */
@Composable
fun DecisionScreen(
    places: List<PlaceFact>, pick: String?, line: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (String) -> Unit,
    photos: PhotoLoader?, onMapTap: () -> Unit = {}, modifier: Modifier = Modifier, expanded: Boolean = false,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val cardMax = (maxHeight - 120.dp - M.p).coerceAtLeast(maxHeight / 2)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(M.p)) {
            if (places.any { it.lat != null && it.lng != null })
                PlaceMap(places, places.getOrNull(index)?.id, onMapTap, { id -> places.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(onIndex) },
                    Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(if (expanded) 0.dp else LocalRadii.current.card)))
            else Spacer(Modifier.weight(1f))
            // Misura's line is the pick's reason, on its card (design-system §4), not screen text over the map.
            if (!expanded) PlaceCarousel(places.map { if (it.id == pick && line != null) it.copy(why = line) else it }, pick, index, onIndex, onOpen, photos, Modifier.heightIn(max = cardMax))
        }
    }
}

/** One place per card, the next one peeks; swiping moves the map's orange pin. Browsing never commits. */
@Composable
fun PlaceCarousel(places: List<PlaceFact>, pick: String?, index: Int, onIndex: (Int) -> Unit, onOpen: (String) -> Unit, photos: PhotoLoader?, modifier: Modifier = Modifier) = Box(modifier) {
    CardPager(places.size, index, onIndex, "place") { i -> PlaceCard(places[i], places[i].id == pick, photos) { onOpen(places[i].id) } }
}

/** The swipe row both carousels share: one swipe = the next card, the next one peeks. [noun] names the cards for screen readers. */
@Composable
fun CardPager(count: Int, index: Int, onIndex: (Int) -> Unit, noun: String, card: @Composable (Int) -> Unit) {
    val state = rememberPagerState(initialPage = index.coerceIn(0, (count - 1).coerceAtLeast(0))) { count }
    val scope = rememberCoroutineScope()
    // The collector outlives recompositions, so it reads the current index and callback, not the first ones.
    val currentIndex by rememberUpdatedState(index)
    val report by rememberUpdatedState(onIndex)
    LaunchedEffect(state) { snapshotFlow { state.settledPage }.collect { if (it != currentIndex) report(it) } }
    LaunchedEffect(index) { if (index != state.settledPage && index in 0 until count) state.animateScrollToPage(index) }
    HorizontalPager(
        state, contentPadding = PaddingValues(end = 40.u), pageSpacing = M.gap, verticalAlignment = Alignment.Top,
        // One swipe = the next place, never a fling past it.
        flingBehavior = PagerDefaults.flingBehavior(state, pagerSnapDistance = PagerSnapDistance.atMost(1)),
        modifier = Modifier.fillMaxWidth().testTag(CAROUSEL_TAG).semantics {
            customActions = listOf(
                CustomAccessibilityAction("Next $noun") { scope.launch { state.animateScrollToPage((state.currentPage + 1).coerceAtMost(count - 1)) }; true },
                CustomAccessibilityAction("Previous $noun") { scope.launch { state.animateScrollToPage((state.currentPage - 1).coerceAtLeast(0)) }; true },
            )
        },
    ) { i -> card(i) }
}

/** Place card: one photo, title, facts and reason. The card is the tap target. */
@Composable
fun PlaceCard(p: PlaceFact, isPick: Boolean, photos: PhotoLoader?, onOpen: () -> Unit) = Column(
    Modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card))
        .clickable(role = Role.Button, onClickLabel = "Open ${p.label}", onClick = onOpen).verticalScroll(rememberScrollState()).padding(M.p),
    verticalArrangement = Arrangement.spacedBy(M.p),
) {
    Mosaic(p, photos, big = false, isPick = isPick)
    Column(Modifier.padding(horizontal = 6.u).padding(bottom = 6.u), verticalArrangement = Arrangement.spacedBy(3.u)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
            Text(p.label, style = T.who, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (photos == null && isPick) PickTag()
        }
        FactsRow(p, facts(p))
        Why(p.why.orEmpty(), card = true)
    }
}

@Composable
fun PickTag(modifier: Modifier = Modifier) = Text("PICK", style = T.src.copy(color = M.ink), maxLines = 1, modifier = modifier.background(M.paper, RoundedCornerShape(3.u)).padding(horizontal = 4.u, vertical = 1.u))

/** Under the name: ★ rating (count), then kind · price. The name has its own row and PICK sits on the photo, so neither line is cut short by a tag. */
@Composable
private fun FactsRow(p: PlaceFact, rest: String?) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.u)) {
    val style = T.line.copy(fontSize = T.src.fontSize)
    p.rating?.let { r ->
        Text("★", style = style.copy(color = M.star))
        Text("$r" + (p.ratingCount?.let { " (${count(it)})" } ?: ""), style = style.copy(color = M.text), maxLines = 1)
    }
    // Always drawn, so a card without facts keeps the row's height.
    Text(listOfNotNull("·".takeIf { p.rating != null && rest != null }, rest).joinToString(" "), style = style, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
}

private fun count(n: Int) = if (n >= 1000) "${n / 100 / 10.0}k".replace(".0k", "k") else "$n"
private fun facts(p: PlaceFact) = listOfNotNull(p.kind, p.price?.let { "€".repeat(it) }).joinToString(" · ").ifEmpty { null }

@Composable
private fun Why(text: String, card: Boolean = false) = Row(horizontalArrangement = Arrangement.spacedBy(6.u)) {
    Box(Modifier.padding(top = 6.u).size(6.u).background(M.orange, CircleShape))
    Text(text, style = if (card) T.line else T.msg, minLines = if (card) 3 else 1, maxLines = if (card) 3 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
}

/** One preview photo; detail retains its mosaic. Credit only the photos actually displayed. */
@Composable
private fun Mosaic(p: PlaceFact, photos: PhotoLoader?, big: Boolean, isPick: Boolean = false) {
    if (photos == null) return
    val loaded by androidx.compose.runtime.key(p.id, big, p.photos, photos) {
        produceState<List<Pair<Photo, ImageBitmap>>>(emptyList()) {
            value = p.photos.take(if (big) 3 else 1).mapNotNull { ph -> photos(p, ph)?.let { it.photo to it.bitmap } }
        }
    }
    // Credits overlay the reserved media slot, so loading and author length never change carousel height.
    if (big && loaded.isEmpty()) return
    val r = RoundedCornerShape(LocalRadii.current.tile)
    @Composable fun slot(i: Int, m: Modifier) = loaded.getOrNull(i)?.let { Img(it.second, m.clip(r)) } ?: Box(m.clip(r).background(PHOTO_EMPTY))
    BoxWithConstraints(Modifier.testTag(MOSAIC_TAG)) {
        val mediaHeight = maxWidth / if (big) 1.2f else 1.6f
        Row(Modifier.fillMaxWidth().height(mediaHeight), horizontalArrangement = Arrangement.spacedBy(M.gap)) {
            Box(Modifier.weight(2f).fillMaxHeight()) {
                slot(0, Modifier.fillMaxSize())
                // Misura's pick is marked on the photo's corner (his call 13 Nov): it reads first and leaves both text lines whole.
                if (isPick) PickTag(Modifier.padding(6.u))
            }
            if (big && loaded.size > 1) Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(M.gap)) {
                loaded.drop(1).forEach { Img(it.second, Modifier.weight(1f).fillMaxWidth().clip(r)) }
            }
        }
        Attribution(loaded.mapNotNull { it.first.author?.let { a -> a to it.first.authorUri } }.distinct(),
            Modifier.align(Alignment.BottomEnd).padding(4.dp).heightIn(max = (mediaHeight - 8.dp).coerceAtLeast(1.dp)))
    }
}

/** An empty photo slot: a shade darker than the card, no icon (it's waiting, not an error). */
private val PHOTO_EMPTY = androidx.compose.ui.graphics.Color(0xFF2A2826)

@Composable private fun Img(b: ImageBitmap, m: Modifier) = Image(b, null, m, contentScale = ContentScale.Crop)

@Composable
private fun Attribution(authors: List<Pair<String, String?>>, modifier: Modifier = Modifier) {
    if (authors.isEmpty()) return
    // Keep every displayed photo's author and profile link reachable at large text sizes.
    val text = buildAnnotatedString {
        append(if (authors.size == 1) "Photo: " else "Photos: ")
        authors.forEachIndexed { i, (name, uri) ->
            if (i > 0) append(", ")
            if (uri != null) withLink(LinkAnnotation.Url(uri, TextLinkStyles(SpanStyle(color = M.paper)))) { append(name) } else append(name)
        }
    }
    Text(text, style = T.line.copy(fontSize = 10.sp, lineHeight = 12.sp, color = M.paper),
        modifier = modifier.background(M.ink.copy(alpha = .82f), RoundedCornerShape(4.dp))
            .verticalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp))
}

/** One place, full card under the nav line: bigger mosaic, title + rating, kind · price, hours today (week on tap), Directions. */
@Composable
fun PlaceDetail(p: PlaceFact, photos: PhotoLoader?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var week by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(M.surface, RoundedCornerShape(LocalRadii.current.card)).padding(M.p), verticalArrangement = Arrangement.spacedBy(M.p)) {
        Mosaic(p, photos, big = true)
        Column(Modifier.padding(horizontal = 6.u), verticalArrangement = Arrangement.spacedBy(4.u)) {
            Text(p.label, style = T.who)
            // Today's hours come from the week; find_places' "hours" may be for the searched day, so it never says "today".
            val today = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val todayHours = p.week.firstOrNull { it.startsWith("$today:", ignoreCase = true) }?.substringAfter(":")?.trim()
            FactsRow(p, listOfNotNull(facts(p), todayHours?.let { "open today $it" }).joinToString(" · ").ifEmpty { null })
            p.why?.let { Why(it) }
            if (p.week.isNotEmpty()) {
                Text(if (week) "Hours this week ▴" else "Hours this week ▾", style = T.src.copy(color = M.text),
                    modifier = Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { week = !week }.padding(vertical = 8.u))
                if (week) {
                    val today = LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                    p.week.forEach { d -> Text(d, style = if (d.startsWith(today)) T.msg else T.msg.copy(color = M.text2)) }
                }
            }
        }
        com.misura.core.agent.directionsUrl(p)?.let { uri ->
            MButton("Directions", Kind.Secondary, Modifier.fillMaxWidth()) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
