package com.misura.phone.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.misura.core.agent.PlaceFact
import com.misura.core.agent.Photo
import com.misura.phone.cards.MisuraSurface
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

class PlaceCardsTest {
    @get:Rule val rule = createComposeRule()
    private val namsan = PlaceFact("P1", "Namsan", "Quiet room, the stone bowl arrives crackling", "12–22", lat = 52.49, lng = 13.42,
        rating = 4.6, ratingCount = 812, price = 2, kind = "Korean restaurant", photos = listOf(Photo("places/P1/photos/a", "Jin")))
    private val kimchi = PlaceFact("P2", "Kimchi Princess", "Lively and late", lat = 52.50, lng = 13.43, rating = 4.4, ratingCount = 2100)
    private val bare = listOf(PlaceFact("P1", "Namsan", "quiet"), PlaceFact("P2", "Kimchi Princess", "lively"))
    private val oneBitmap: PhotoLoader = { _, ph -> com.misura.phone.brain.Shown(ImageBitmap(8, 8), ph) }

    @Test fun carouselKeepsEqualHeightWithWrappedAndMissingCredits() = equalCardHeights(1f)
    @Test fun carouselKeepsEqualHeightWithWrappedCreditsAtLargeFont() = equalCardHeights(2f)

    private fun equalCardHeights(fontScale: Float) {
        val author = "Photographer with a very long complete credit that must wrap across several lines"
        val missing = kimchi.copy(id = "P3", label = "No photo", photos = emptyList())
        val cards = listOf(namsan, kimchi.copy(photos = listOf(Photo("long", author, "https://example.org/author"))), missing)
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                MisuraSurface { androidx.compose.foundation.layout.Box(Modifier.width(320.dp).height(700.dp)) {
                    DecisionScreen(cards, "P1", null, index, { index = it }, {}, oneBitmap)
                } }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Photo: $author"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        val first = rule.onNodeWithText(namsan.label, substring = true).fetchSemanticsNode().layoutInfo.height
        rule.runOnIdle { index = 1 }; rule.waitForIdle()
        val second = rule.onNodeWithText(kimchi.label, substring = true).fetchSemanticsNode().layoutInfo.height
        first shouldBe second
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("Photo: $author", useUnmergedTree = true).performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val credit = layouts.single()
        credit.getLineEnd(credit.lineCount - 1, visibleEnd = true) shouldBe "Photo: $author".length
        credit.layoutInput.text.getLinkAnnotations(0, credit.layoutInput.text.length).size shouldBe 1
        rule.runOnIdle { index = 2 }; rule.waitForIdle()
        rule.onNodeWithText(missing.label, substring = true).fetchSemanticsNode().layoutInfo.height shouldBe first
    }

    private fun decision(places: List<PlaceFact>, pick: String? = "P1", photos: PhotoLoader? = null, opened: MutableList<String> = mutableListOf(), line: String? = null) =
        rule.setContent { MisuraSurface {
            var index by remember { mutableIntStateOf(0) }
            DecisionScreen(places, pick, line, index, { index = it }, { opened += it }, photos)
        } }

    @Test fun carouselShowsPickTagOnPick() { decision(listOf(namsan, kimchi)); rule.onNodeWithText("PICK").assertExists() }

    @Test fun tapOpensDetail() {
        val opened = mutableListOf<String>()
        decision(listOf(namsan, kimchi), opened = opened)
        rule.onNodeWithText("Namsan").performClick()
        rule.runOnIdle { opened shouldBe listOf("P1") }
    }

    @Test fun swipeChangesMapSelection() {
        decision(listOf(namsan, kimchi))
        rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Namsan"))
        rule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kimchi Princess"))
    }

    @Test fun carouselHasNextPreviousActions() {
        decision(listOf(namsan, kimchi))
        rule.onNodeWithTag(CAROUSEL_TAG).assert(SemanticsMatcher("has Next place") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)?.map { it.label }?.containsAll(listOf("Next place", "Previous place")) == true
        })
    }

    @Test fun mapOffersSelectActionsPerPlace() {
        decision(listOf(namsan, kimchi))
        rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher("select actions") { n ->
            n.config.getOrNull(androidx.compose.ui.semantics.SemanticsActions.CustomActions)?.map { it.label } == listOf("Select Namsan", "Select Kimchi Princess")
        })
    }

    @Test fun carouselWithoutCoordinatesHidesMap() {
        decision(bare)
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onNodeWithTag(CAROUSEL_TAG).assertExists()
    }

    @Test fun cardWithoutPhotosHasNoMosaic() {
        decision(listOf(namsan, kimchi), photos = null)
        rule.onNodeWithTag(MOSAIC_TAG, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun photoAttributionShown() {
        decision(listOf(namsan, kimchi), photos = oneBitmap)
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists(MOSAIC_TAG) }
        rule.onNodeWithText("Photo: Jin", useUnmergedTree = true).assertExists()
    }

    @Test fun detailShowsRatingAndCount() {
        rule.setContent { MisuraSurface { PlaceDetail(namsan, null) } }
        rule.onNodeWithText("4.6 (812)", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Korean restaurant · €€", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun pickCardsReasonIsMisurasLine() {
        decision(listOf(namsan, kimchi), line = "The stone bowl arrives still crackling")
        rule.onNodeWithText("The stone bowl arrives still crackling", substring = true, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Quiet room", substring = true, useUnmergedTree = true).assertDoesNotExist() // the search blurb gives way on the pick
        rule.onNodeWithText("Lively and late", substring = true, useUnmergedTree = true).assertExists() // other cards keep theirs
    }

    @Test fun creditsFitOneLine() {
        val two = namsan.copy(photos = listOf(Photo("a", "Jin"), Photo("b", "Christoph Rücker")))
        rule.setContent { MisuraSurface { PlaceDetail(two, oneBitmap) } }
        rule.waitUntil(3_000) { rule.onAllNodesWithTagExists(MOSAIC_TAG) }
        rule.onNodeWithText("Photos: Jin, Christoph Rücker", useUnmergedTree = true).assertExists()
    }

    @Test fun carouselLoadsOnlyDisplayedPhotoAndCreditsIt() {
        val loaded = mutableListOf<String>()
        val two = namsan.copy(photos = listOf(Photo("a", "Jin"), Photo("b", "Other author")))
        val loader: PhotoLoader = { _, photo -> loaded += photo.name; com.misura.phone.brain.Shown(ImageBitmap(8, 8), photo) }
        decision(listOf(two), photos = loader)
        rule.waitUntil(3_000) { loaded.isNotEmpty() }
        rule.waitForIdle()
        loaded shouldBe listOf("a")
        rule.onNodeWithText("Photo: Jin", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Other author", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun detailShowsCompleteLinkedPhotoCreditsAtLargeFont() {
        val authors = listOf("First credited photographer with a long name", "Second credited photographer with a long name")
        val place = namsan.copy(photos = authors.mapIndexed { i, author -> Photo("photo-$i", author, "https://example.org/author/$i") })
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MisuraSurface { Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) { PlaceDetail(place, oneBitmap) } }
            }
        }
        val credit = "Photos: ${authors.joinToString()}"
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText(credit), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val node = rule.onNodeWithText(credit, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) shouldBe credit.length
        val links = layout.layoutInput.text.getLinkAnnotations(0, credit.length)
        links.size shouldBe 2
        for (link in links) {
            val first = layout.getBoundingBox(link.start)
            val last = layout.getBoundingBox(link.end - 1)
            check(first.left >= -1 && last.right <= layout.size.width + 1 && last.bottom <= layout.size.height + 1)
        }
    }

    @Test fun replacementPlaceNeverShowsPreviousPhotoCreditWhileLoading() {
        val pending = kotlinx.coroutines.CompletableDeferred<Unit>()
        var shown by androidx.compose.runtime.mutableStateOf(namsan.copy(photos = listOf(Photo("a", "First photographer"))))
        val loader: PhotoLoader = { place, photo ->
            if (place.id == "P2") pending.await()
            com.misura.phone.brain.Shown(ImageBitmap(8, 8), photo)
        }
        rule.setContent { MisuraSurface { PlaceCard(shown, false, loader) {} } }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Photo: First photographer"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.runOnIdle { shown = kimchi.copy(photos = listOf(Photo("b", "Second photographer"))) }
        rule.onNodeWithText(kimchi.label, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithText("Photo: First photographer", useUnmergedTree = true).assertDoesNotExist()
        pending.complete(Unit)
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Photo: Second photographer"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun swipeBackRestoresMapSelection() {
        decision(listOf(namsan, kimchi))
        rule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft(startX = width * 0.7f, endX = width * 0.3f) }; rule.waitForIdle()
        rule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeRight(startX = width * 0.3f, endX = width * 0.7f) }; rule.waitForIdle()
        rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Namsan"))
    }

    /** His call 13 Nov: the chip says what you're asking about and nothing else; the place's own card credits its photos. */
    @Test fun dockedChipShowsLabelAndNameWithoutPhotoCredit() {
        rule.setContent { MisuraSurface { Docked(namsan, "Asking about", oneBitmap, null) } }
        rule.waitForIdle()
        rule.onNodeWithText("Asking about").assertExists(); rule.onNodeWithText("Namsan").assertExists()
        check(rule.onAllNodes(androidx.compose.ui.test.hasText("Photo:", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
    }

    @Test fun detailShowsTodayFromWeekNotTheSearchedDay() {
        val today = java.time.LocalDate.now().dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        rule.setContent { MisuraSurface { PlaceDetail(namsan.copy(hours = "19:00–23:00", week = listOf("$today: 11:00–21:30")), null) } }
        rule.onNodeWithText("open today 11:00–21:30", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test fun detailWithoutWeekNeverSaysToday() {
        rule.setContent { MisuraSurface { PlaceDetail(namsan.copy(hours = "19:00–23:00", week = emptyList()), null) } }
        rule.onNodeWithText("today", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
}
