package com.misura.phone.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.misura.core.agent.ItemFact
import com.misura.phone.cards.MisuraSurface
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.itemsFromResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** Item cards in a set look alike: photos all or none (owner, 25 Nov), and every card the same height whatever it holds. */
class ItemCardsTest {
    @get:Rule val rule = createComposeRule()

    private val withPhoto = ItemFact("I1", "Blue jacket", "€35", "Size M · good condition", photo = "/nonexistent/a.jpg", app = "Sample shop")
    private val bare = ItemFact("I2", "Grey jacket")

    @Test fun oneItemWithoutAPhotoMeansNoCardInTheSetShowsOne() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(listOf(withPhoto, bare), null, null, 0, {}, {}) } } }
        rule.onAllNodesWithTag(ITEM_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test fun aSetWhereEveryItemHasAPhotoShowsThePhotoSlot() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(listOf(withPhoto, withPhoto.copy(id = "I3")), null, null, 0, {}, {}) } } }
        rule.onAllNodesWithTag(ITEM_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
    }

    @Test fun aFullCardAndAnEmptyCardAreTheSameHeight() {
        val full = withPhoto.copy(photo = null, detail = "A long detail line that wraps over more than two lines on a narrow card, so it has to be cut off at two.")
        rule.setContent {
            MisuraSurface { Column {
                Box(Modifier.width(300.dp).testTag("full")) { ItemCard(full, true) {} }
                Box(Modifier.width(300.dp).testTag("bare")) { ItemCard(bare, false) {} }
            } }
        }
        val a = rule.onNodeWithTag("full").getBoundsInRoot()
        val b = rule.onNodeWithTag("bare").getBoundsInRoot()
        (b.bottom - b.top) shouldBe (a.bottom - a.top)
    }

    // ---- Item 7: near-me app finds, through itemsFromResult (what the agent stores) → ItemScreen ----

    /** A near run's result as UseApp returns it: one venue pinned, one shared, one not looked up (invented names). */
    private fun near(pinned: Boolean = true) = itemsFromResult(Json.parseToJsonElement("""{"items":[
        {"id":"A1","title":"Atlas Fitness Westend","detail":"Fitness","distance":"1.2 km","photo":"/nonexistent/a.jpg"${if (pinned) ""","pin":{"lat":48.1482,"lng":11.5761}""" else ""}},
        {"id":"A2","title":"Atlas Fitness","detail":"Fitness","distance":"1.4 km","noPin":"shared","photo":"/nonexistent/b.jpg"},
        {"id":"A3","title":"Kraftwerk Gym","detail":"Fitness","distance":"2.6 km","photo":"/nonexistent/c.jpg"}],
        "itemsFrom":{"app":"Sports app","pkg":"com.example.sports","near":true}}""").jsonObject).values.toList()

    @Test fun aPinnedNearSetShowsTheMapAndDirectionsToTheVenue() {
        val opened = mutableListOf<String>()
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), "A1", "Nearest of the 3 checked.", 0, {}, {}, onDirections = { opened += it }) } } }
        rule.onNodeWithTag(MAP_TAG).assertExists()
        rule.onAllNodesWithTag(ITEM_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0 // near sets are text-only
        rule.onNodeWithText("Nearest of the 3 checked.").assertExists() // Misura's line on the pick's card
        rule.onNodeWithText("Directions").performClick()
        opened shouldBe listOf("https://www.google.com/maps/dir/?api=1&destination=48.148200,11.576100")
    }

    @Test fun aNearSetWithoutAPinShowsNoMapNoDirectionsAndSaysWhy() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(pinned = false), null, null, 1, {}, {}, onDirections = {}) } } }
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Directions")).fetchSemanticsNodes().size shouldBe 0
        rule.onNodeWithText("Not on the map: more than one place has this name nearby.").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Open in app")).fetchSemanticsNodes().isNotEmpty() shouldBe true
    }

    @Test fun anOldSessionShowsNoMapAndNoDirections() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), "A1", null, 0, {}, {}, stale = true, onDirections = {}) } } }
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Directions")).fetchSemanticsNodes().size shouldBe 0
    }

    @Test fun openInAppOpensTheThing() {
        val opened = mutableListOf<String>()
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), null, null, 0, {}, { opened += it.id }, onDirections = {}) } } }
        rule.onAllNodes(androidx.compose.ui.test.hasText("Open in app"))[0].performClick()
        opened shouldBe listOf("A1")
    }

    /** A tap on a venue's pin selects its card (MapLibre's own marker tap, mapped to the item id). Needs the map style from the network. */
    @Test fun aPinTapSelectsItsCard() {
        val items = near().let { l -> l.map { if (it.id == "A3") it.copy(lat = 48.1600, lng = 11.5900, noPin = null) else it } }
        var index by mutableIntStateOf(0)
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(items, null, null, index, { index = it }, {}, onDirections = {}) } } }
        val inst = InstrumentationRegistry.getInstrumentation()
        fun mapView(): org.maplibre.android.maps.MapView? {
            var found: org.maplibre.android.maps.MapView? = null
            inst.runOnMainSync {
                val a = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
                fun find(v: android.view.View): org.maplibre.android.maps.MapView? = v as? org.maplibre.android.maps.MapView
                    ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
                found = find(a.window.decorView)
            }
            return found
        }
        var point: android.graphics.PointF? = null
        val view = checkNotNull(mapView())
        fun locate() { inst.runOnMainSync { view.getMapAsync { m -> m.markers.firstOrNull { it.title == "Kraftwerk Gym" }?.let { k -> point = m.projection.toScreenLocation(k.position) } } }; inst.waitForIdleSync() }
        rule.waitUntil(20_000) { locate(); point != null }
        Thread.sleep(500); locate() // after the camera has framed the pins
        val p = point!!
        rule.onNodeWithTag(MAP_TAG).performTouchInput { click(Offset(p.x, p.y)) }
        rule.waitUntil(5_000) { index == 2 }
        rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Kraftwerk Gym"))
    }

    /** Every near card is the same height: pin + Directions + Misura's line, a reason without a pin, and a bare one. */
    @Test fun nearCardsAreTheSameHeightWithOrWithoutButtonsAndLines() {
        val (a, b, c) = near()
        rule.setContent {
            MisuraSurface { Column {
                Box(Modifier.width(300.dp).testTag("full")) { NearItemCard(a.copy(detail = "A long detail line that runs well past one line on a narrow card"), true, "A long line from Misura that wraps over more than two lines on a narrow card, so it is cut.", {}, {}) }
                Box(Modifier.width(300.dp).testTag("why")) { NearItemCard(b, false, null, {}, null) }
                Box(Modifier.width(300.dp).testTag("bare")) { NearItemCard(c.copy(detail = null, distance = null, app = null), false, null, {}, null) }
            } }
        }
        val h = listOf("full", "why", "bare").map { t -> rule.onNodeWithTag(t).getBoundsInRoot().let { (it.bottom - it.top).value } }
        check(h.max() - h.min() < 0.5f) { "heights full/why/bare (dp): $h" }
    }
}
