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
        // Both buttons on one line at full screen width (owner 26 Nov: Directions broke over two lines and doubled the row)
        for (label in listOf("Open", "Directions")) {
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onAllNodes(androidx.compose.ui.test.hasText(label), useUnmergedTree = true)[0]
                .fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            layouts.single().lineCount shouldBe 1
        }
        rule.onNodeWithText("Directions").performClick()
        opened shouldBe listOf("https://www.google.com/maps/dir/?api=1&destination=48.148200,11.576100")
    }

    @Test fun aNearSetWithoutAPinShowsNoMapNoDirectionsAndSaysWhy() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(pinned = false), null, null, 1, {}, {}, onDirections = {}) } } }
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Directions")).fetchSemanticsNodes().size shouldBe 0
        rule.onNodeWithText("Not on the map: more than one place has this name nearby.").assertExists()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Open")).fetchSemanticsNodes().isNotEmpty() shouldBe true
    }

    @Test fun anOldSessionShowsNoMapAndNoDirections() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), "A1", null, 0, {}, {}, stale = true, onDirections = {}) } } }
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onAllNodes(androidx.compose.ui.test.hasText("Directions")).fetchSemanticsNodes().size shouldBe 0
    }

    @Test fun openInAppOpensTheThing() {
        val opened = mutableListOf<String>()
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), null, null, 0, {}, { opened += it.id }, onDirections = {}) } } }
        rule.onAllNodes(androidx.compose.ui.test.hasText("Open"))[0].performClick()
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

    // ---- The "you" dot (owner 26 Nov: "near me" starts at my location). Need the map style from the network, like the pin tap. ----

    /** The run's fix in these tests: invented, a few km from every venue of [near]. */
    private val you = 48.1201 to 11.5302

    private fun mapView(): org.maplibre.android.maps.MapView {
        val inst = InstrumentationRegistry.getInstrumentation()
        var found: org.maplibre.android.maps.MapView? = null
        inst.runOnMainSync {
            val a = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            fun find(v: android.view.View): org.maplibre.android.maps.MapView? = v as? org.maplibre.android.maps.MapView
                ?: (v as? android.view.ViewGroup)?.let { g -> (0 until g.childCount).firstNotNullOfOrNull { find(g.getChildAt(it)) } }
            found = find(a.window.decorView)
        }
        return checkNotNull(found)
    }
    /** The map's markers (title, position) and whether each of [points] is inside what the camera shows, once the map is up. */
    private fun look(points: List<Pair<Double, Double>>): Pair<List<Pair<String?, Pair<Double, Double>>>, List<Boolean>>? {
        val inst = InstrumentationRegistry.getInstrumentation()
        var out: Pair<List<Pair<String?, Pair<Double, Double>>>, List<Boolean>>? = null
        val view = mapView()
        inst.runOnMainSync { view.getMapAsync { m ->
            if (m.style?.isFullyLoaded != true) return@getMapAsync
            val region = m.projection.visibleRegion.latLngBounds
            out = m.markers.map { it.title?.takeIf { t -> t.isNotEmpty() } to (it.position.latitude to it.position.longitude) } to
                points.map { region.contains(org.maplibre.android.geometry.LatLng(it.first, it.second)) }
        } }
        inst.waitForIdleSync()
        return out
    }
    private fun awaitLook(points: List<Pair<Double, Double>>, ok: (Pair<List<Pair<String?, Pair<Double, Double>>>, List<Boolean>>) -> Boolean): Pair<List<Pair<String?, Pair<Double, Double>>>, List<Boolean>> {
        var last: Pair<List<Pair<String?, Pair<Double, Double>>>, List<Boolean>>? = null
        rule.waitUntil(20_000) { last = look(points); last?.let(ok) == true }
        return last!!
    }

    /** A near set with the run's fix: a dot without a title (not a place, no card) and a camera that shows it and the pin. Red on prior: no dot. */
    @Test fun aNearMapShowsYouAndFramesYouWithThePins() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(), "A1", null, 0, {}, {}, onDirections = {}, you = you) } } }
        val pin = 48.1482 to 11.5761
        val (markers, inside) = awaitLook(listOf(pin, you)) { (m, i) -> m.any { it.first == null } && i.all { it } }
        markers.count { it.first == null } shouldBe 1
        markers.single { it.first == null }.second shouldBe you
        inside shouldBe listOf(true, true)
    }

    /**
     * The camera frames again when the fix arrives after the first framing, and when the same pin moves (same ids, new coordinates).
     * Red on prior: the camera was framed by pin ids only.
     */
    @Test fun aLateFixOrAMovedPinFramesTheMapAgain() {
        var dot by androidx.compose.runtime.mutableStateOf<Pair<Double, Double>?>(null)
        var items by androidx.compose.runtime.mutableStateOf(near())
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(items, "A1", null, 0, {}, {}, onDirections = {}, you = dot) } } }
        val far = 48.2601 to 11.7102 // ~15 km from the pin: outside a one-pin zoom-15 view
        awaitLook(listOf(48.1482 to 11.5761)) { (_, i) -> i.all { it } }
        look(listOf(far))!!.second shouldBe listOf(false)
        dot = far
        awaitLook(listOf(48.1482 to 11.5761, far)) { (m, i) -> m.any { it.first == null } && i.all { it } }
        dot = null
        awaitLook(listOf(far)) { (m, i) -> m.none { it.first == null } && i == listOf(false) } // back on the pin alone, before it moves
        val moved = 48.0301 to 11.4402
        items = items.map { if (it.id == "A1") it.copy(lat = moved.first, lng = moved.second) else it }
        awaitLook(listOf(moved)) { (_, i) -> i.all { it } }
    }

    /** A dot alone never makes a map: a near set without a pin shows none, with or without the fix. */
    @Test fun aDotAloneNeverMakesAMap() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(near(pinned = false), null, null, 0, {}, {}, onDirections = {}, you = you) } } }
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
    }
}
