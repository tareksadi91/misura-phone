package com.misura.phone.brain

import com.misura.phone.hands.PinAsk
import com.misura.phone.hands.PinFound
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The certain-pin rule (owner list item 7): strict name match, the app's distance, one result only. Invented venue names throughout. */
class PinLookupTest {
    // The matcher, with the shapes seen in the 25 Nov probe (city word added, punctuation, run-together words, a word the map lacks).
    @Test fun everyWordOfTheAppsTitleMustBeInTheMapName() {
        assertTrue(PinMatch.names("Atlas Fitness Westend", "Atlas Fitness Westend", null))
        assertFalse("Westend is not Eastend", PinMatch.names("Atlas Fitness Westend", "Atlas Fitness Eastend", null))
        assertTrue("the city word is left out", PinMatch.names("Atlas Fitness München - Westend", "Atlas Fitness Westend", "München"))
        assertTrue("the map adds the city", PinMatch.names("Atlas Fitness Westend", "Atlas Fitness München-Westend", "München"))
        assertTrue("run-together words", PinMatch.names("Kraftwerk PowerPlate", "Kraftwerk Power Plate EMS", null))
        assertFalse("a word the map name lacks", PinMatch.names("Kraftwerk PowerPlate & Kurse", "Kraftwerk Power Plate EMS", null))
        assertTrue("accents fold", PinMatch.names("Studio Grün", "STUDIO GRUN", null))
        assertTrue("ß is ss", PinMatch.names("Kraftstraße Gym", "Kraftstrasse Gym", null))
        assertFalse("a renamed venue", PinMatch.names("Atlas Fitness Nordpark", "Fit Base Nordpark", null))
        assertTrue("run-together on the map side too (Codex re-check)", PinMatch.names("Kraftwerk Power Plate", "Kraftwerk PowerPlate EMS", null))
        assertFalse("never part of a word (Codex P2)", PinMatch.names("Gym Ost", "Gym Rostock", null))
        assertFalse("never part of a word, run-together either", PinMatch.names("Atlas Fit", "Atlas Fitness", null))
        assertFalse("only the city is no name", PinMatch.names("München", "München Hauptbahnhof", "München"))
    }

    @Test fun thePlaceMustBeAtTheDistanceTheAppShows() {
        val phone = 48.0 to 11.0
        fun north(m: Double) = (48.0 + m / 111_195.0) to 11.0
        assertTrue(PinMatch.near(phone, north(1_200.0), 1_000.0))   // within 300 m
        assertFalse(PinMatch.near(phone, north(1_400.0), 1_000.0))  // 400 m off, more than 25 %
        assertTrue(PinMatch.near(phone, north(4_900.0), 4_000.0))   // 25 % of 4 km = 1 km
        assertFalse(PinMatch.near(phone, north(5_200.0), 4_000.0))
        assertTrue(PinMatch.near(phone, north(650.0), 400.0))       // short distances keep 300 m
    }

    private val phone = 48.137154 to 11.576124
    private fun north(m: Double) = (phone.first + m / 111_195.0) to phone.second
    private fun place(name: String, at: Pair<Double, Double>, id: String = name + at.first) =
        """{"id":"$id","displayName":{"text":"$name"},"location":{"latitude":${at.first},"longitude":${at.second}}}"""

    private fun lookup(http: TinyHttp) = PinLookup({ "fixture-token" }, { "fixture-project" }, { phone }, http.base + "/v1")

    @Test fun onlyExactlyOneNamedPlaceAtTheAppsDistanceIsAPin() = runBlocking<Unit> {
        TinyHttp { r ->
            val q = Json.parseToJsonElement(r.body).jsonObject["textQuery"]!!.jsonPrimitive.content
            when (q) {
                // One at the distance, one of the same name far off: a pin.
                "Atlas Fitness Westend" -> 200 to """{"places":[${place("Atlas Fitness Westend", north(1_250.0))},${place("Atlas Fitness Westend", north(9_000.0))}]}"""
                // Two branches at the same distance: shared.
                "Atlas Fitness" -> 200 to """{"places":[${place("Atlas Fitness", north(800.0))},${place("Atlas Fitness", 48.137154 to 11.586900)}]}"""
                // Named right, but not where the app says: none.
                "Kraftwerk Gym" -> 200 to """{"places":[${place("Kraftwerk Gym", north(6_000.0))}]}"""
                else -> 500 to "{}"
            }
        }.use { http ->
            val got = mutableMapOf<String, PinFound>()
            lookup(http).find(listOf(PinAsk("a", "Atlas Fitness Westend", 1_200.0), PinAsk("b", "Atlas Fitness", 800.0),
                PinAsk("c", "Kraftwerk Gym", 2_000.0), PinAsk("d", "Broken Gym", 500.0)), null) { id, f -> got[id] = f }
            val pin = got["a"] as PinFound.Pin
            assertEquals(north(1_250.0).first, pin.lat, 1e-9)
            assertEquals(PinFound.NoPin("shared"), got["b"])
            assertEquals(PinFound.NoPin("none"), got["c"])
            assertFalse("a failed search is no answer, not 'not found'", "d" in got)
            val first = http.requests.first()
            assertEquals("Bearer fixture-token", first.headers["authorization"])
            assertEquals("places.id,places.displayName,places.location", first.headers["x-goog-fieldmask"])
            val bias = Json.parseToJsonElement(first.body).jsonObject["locationBias"]!!.jsonObject["circle"]!!.jsonObject["center"]!!.jsonObject
            assertEquals("48.137154", bias["latitude"]!!.jsonPrimitive.content)
        }
    }

    @Test fun noFixMeansNoLookup() = runBlocking<Unit> {
        TinyHttp { 200 to """{"places":[]}""" }.use { http ->
            PinLookup({ "t" }, { "p" }, { null }, http.base).find(listOf(PinAsk("a", "Atlas Fitness", 500.0)), null) { _, _ -> }
            assertEquals(0, http.requests.size)
        }
    }

    /** The graph's lookup takes the turn's captured Google route (as find_places, BYOK repair 21 Nov); a route without Google asks nothing. */
    @Test fun theRoutedLookupUsesTheTurnsGoogleRouteAndNoneWithoutIt() = runBlocking<Unit> {
        TinyHttp { 200 to """{"places":[${place("Atlas Fitness", north(500.0))}]}""" }.use { http ->
            val pins = routedPins(here = { phone }, placesBase = http.base + "/v1")
            val google = ModelRoute(ModelProvider.VERTEX, "fixture-model", null, "fixture-hand", null, null,
                null, "fixture-google-token", "fixture-project", SearchProvider.EXA, "fixture-exa-key", false, false)
            val got = mutableMapOf<String, PinFound>()
            withContext(ModelRoutes({ google }, http.base).capture()) { pins.find(listOf(PinAsk("a", "Atlas Fitness", 500.0)), null) { id, f -> got[id] = f } }
            assertTrue(got["a"] is PinFound.Pin)
            assertEquals("Bearer fixture-google-token", http.requests.single().headers["authorization"])
            assertEquals("fixture-project", http.requests.single().headers["x-goog-user-project"])

            val keyOnly = ModelRoute(ModelProvider.OPENAI, "m", "fixture-key", "h", null, null, null, null, "", SearchProvider.EXA, "k", false, false)
            withContext(ModelRoutes({ keyOnly }, http.base).capture()) { pins.find(listOf(PinAsk("a", "Atlas Fitness", 500.0)), null) { _, _ -> error("no lookup without Google") } }
            pins.find(listOf(PinAsk("a", "Atlas Fitness", 500.0)), null) { _, _ -> error("no lookup outside a turn") }
            assertEquals(1, http.requests.size)
        }
    }
}
