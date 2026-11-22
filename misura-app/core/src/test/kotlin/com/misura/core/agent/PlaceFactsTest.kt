package com.misura.core.agent

import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

class PlaceFactsTest {
    private fun response(name: String, json: String) = Turn("user", buildJsonObject {
        put("role", "user")
        put("parts", buildJsonArray { add(buildJsonObject { putJsonObject("functionResponse") { put("name", name); put("response", Json.parseToJsonElement(json).jsonObject) } }) })
    })
    private val namsan = """{"places":[{"label":"Namsan","why":"quiet, great dolsot","hours":"12–22","week":["Monday: 12–22"],"placeId":"P1","mapsUri":"https://maps/1",
        "lat":52.5,"lng":13.4,"rating":4.6,"ratingCount":812,"price":2,"kind":"Korean restaurant","photos":[{"name":"places/P1/photos/a","author":"Jin","authorUri":"https://a"}]},
        {"label":"No id here"}]}"""

    @Test fun factsComeFromFindPlacesResponses() {
        val f = placeFacts(listOf(response("find_places", namsan)))
        f.keys shouldBe setOf("P1")
        f["P1"] shouldBe PlaceFact("P1", "Namsan", "quiet, great dolsot", "12–22", listOf("Monday: 12–22"), 52.5, 13.4, "https://maps/1",
            4.6, 812, 2, "Korean restaurant", listOf(Photo("places/P1/photos/a", "Jin", "https://a")))
    }

    @Test fun otherToolsAreIgnored() {
        placeFacts(listOf(response("something_else", namsan))) shouldBe emptyMap()
    }

    @Test fun laterResultWins() {
        val later = """{"places":[{"label":"Namsan Mitte","placeId":"P1"}]}"""
        placeFacts(listOf(response("find_places", namsan), response("find_places", later)))["P1"]!!.label shouldBe "Namsan Mitte"
    }

    @Test fun placesWithoutIdSkipped() {
        placeFacts(listOf(response("find_places", """{"places":[{"label":"Ghost"}]}"""))) shouldBe emptyMap()
    }

    @Test fun staleKeepsNameAndWhyOnly() {
        placeFacts(listOf(response("find_places", namsan)))["P1"]!!.stale() shouldBe PlaceFact("P1", "Namsan", "quiet, great dolsot")
    }
}
