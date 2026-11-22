package com.misura.phone.brain

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class FindPlacesTest {
    private fun grounded(lines: List<String>, chunks: List<Pair<String, String>>) = 200 to buildString {
        val text = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(lines.joinToString("\n")))
        append("""{"candidates":[{"content":{"role":"model","parts":[{"text":$text}]},"groundingMetadata":{"groundingChunks":[""")
        append(chunks.joinToString(",") { (title, id) -> """{"maps":{"uri":"https://maps.google.com/?cid=$id","title":"$title - Google Maps","placeId":"$id"}}""" })
        append("]}}]}")
    }
    private fun details(lat: Double, lng: Double, hours: List<String> = emptyList()) =
        200 to """{"location":{"latitude":$lat,"longitude":$lng},"regularOpeningHours":{"weekdayDescriptions":[${hours.joinToString(",") { "\"$it\"" }}]}}"""

    private val three = grounded(
        listOf("Izakaya Lisboa | Tokyo-style tavern | 19:00–01:00", "Lost In | terrace with a view | 12:30–00:00", "Faz Frio | classic Portuguese tavern | 12:00–01:00"),
        listOf("Izakaya Lisboa" to "ChIJa", "Lost In" to "ChIJb", "Faz Frio" to "ChIJc"))

    private fun tool(http: TinyHttp) = FindPlaces({ "t" }, { "test-project" }, vertexBase = http.base, placesBase = http.base + "/v1")
    private fun args(vararg kv: Pair<String, String>) = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
    private fun JsonObject.places() = this["places"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    @Test fun groundedAnswerBecomesPlacesWithIds() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else 404 to "{}" }.use { http ->
            val p = tool(http).run(args("query" to "dinner Príncipe Real")).places()
            p.map { it.s("label") } shouldBe listOf("Izakaya Lisboa", "Lost In", "Faz Frio")
            p.map { it.s("placeId") } shouldBe listOf("ChIJa", "ChIJb", "ChIJc")
            p[1].s("why") shouldBe "terrace with a view"
            p[1].s("mapsUri") shouldBe "https://maps.google.com/?cid=ChIJb"
        }
    }

    @Test fun placesDetailsAddCoordinates() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else details(38.71, -9.14) }.use { http ->
            val p = tool(http).run(args("query" to "x")).places()
            p[0].s("lat") shouldBe "38.71"; p[0].s("lng") shouldBe "-9.14"
            val d = http.requests.first { it.path.startsWith("/v1/places/") }
            d.headers["x-goog-fieldmask"] shouldBe "displayName,location,regularOpeningHours.weekdayDescriptions,rating,userRatingCount,priceLevel,primaryTypeDisplayName,photos"
            d.headers["x-goog-user-project"] shouldBe "test-project"
        }
    }

    @Test fun placesFailureKeepsPlacesWithoutCoordinates() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else 403 to """{"error":{"message":"disabled"}}""" }.use { http ->
            val p = tool(http).run(args("query" to "x")).places()
            p.size shouldBe 3
            p.all { it["lat"] == null } shouldBe true
        }
    }

    @Test fun noGroundingChunksStillReturnsNames() = runBlocking<Unit> {
        TinyHttp { grounded(listOf("Prado | natural wine | 19:00–23:00"), emptyList()) }.use { http ->
            val p = tool(http).run(args("query" to "x")).places().single()
            p.s("label") shouldBe "Prado"; p["placeId"] shouldBe null
        }
    }

    @Test fun atMostTenPlaces() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) grounded((1..12).map { "P$it | why | 10:00" }, emptyList()) else 404 to "{}" }.use { http ->
            tool(http).run(args("query" to "x")).places().size shouldBe 10
        }
    }

    @Test fun prefixedPlaceIdIsNormalised() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) grounded(listOf("Prado | wine | 19:00"), listOf("Prado" to "places/ChIJp")) else details(1.0, 2.0) }.use { http ->
            tool(http).run(args("query" to "x")).places().single().s("placeId") shouldBe "ChIJp"
            http.requests.single { it.method == "GET" }.path shouldBe "/v1/places/ChIJp"
        }
    }

    @Test fun overlappingTitlesStayUnresolved() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) grounded(listOf("Prado | wine | 19:00"), listOf("Prado" to "ChIJ1", "Prado" to "ChIJ2", "Prado Mercearia" to "ChIJ3")) else details(1.0, 2.0) }.use { http ->
            tool(http).run(args("query" to "x")).places().single()["placeId"] shouldBe null
        }
    }

    @Test fun hoursForTheRequestedDay() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) grounded(listOf("Lost In | view | -"), listOf("Lost In" to "ChIJb"))
            else details(1.0, 2.0, listOf("Friday: 12:30 PM – 12:00 AM", "Saturday: 12:30 PM – 1:00 AM")) }.use { http ->
            tool(http).run(args("query" to "x", "day" to "Saturday")).places().single().s("hours") shouldBe "12:30 PM – 1:00 AM"
            http.requests.first { it.method == "POST" }.body shouldContain "Saturday"
        }
    }

    @Test fun fullWeekIsKeptForFollowUps() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) grounded(listOf("Lost In | view | 19:00"), listOf("Lost In" to "ChIJb"))
            else details(1.0, 2.0, listOf("Friday: 12:30 PM – 12:00 AM", "Saturday: 12:30 PM – 1:00 AM")) }.use { http ->
            val p = tool(http).run(args("query" to "x")).places().single()
            p["week"].toString() shouldBe """["Friday: 12:30 PM – 12:00 AM","Saturday: 12:30 PM – 1:00 AM"]"""
        }
    }

    private val rich = 200 to """{"location":{"latitude":52.5,"longitude":13.4},"rating":4.6,"userRatingCount":812,"priceLevel":"PRICE_LEVEL_MODERATE",
        "primaryTypeDisplayName":{"text":"Korean restaurant","languageCode":"en"},
        "photos":[${(1..4).joinToString(",") { """{"name":"places/ChIJa/photos/p$it","widthPx":800,"authorAttributions":[{"displayName":"Author $it","uri":"https://maps.google.com/contrib/$it"}]}""" }}]}"""

    @Test fun detailsAddRatingPriceKindAndPhotos() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else rich }.use { http ->
            val p = tool(http).run(args("query" to "x")).places()[0]
            p["rating"]!!.jsonPrimitive.content shouldBe "4.6"
            p["ratingCount"]!!.jsonPrimitive.content shouldBe "812"
            p["price"]!!.jsonPrimitive.content shouldBe "2"
            p.s("kind") shouldBe "Korean restaurant"
            val photos = p["photos"]!!.jsonArray.map { it.jsonObject }
            photos.map { it.s("name") } shouldBe listOf("places/ChIJa/photos/p1", "places/ChIJa/photos/p2", "places/ChIJa/photos/p3")
            photos[0].s("author") shouldBe "Author 1"
            photos[0].s("authorUri") shouldBe "https://maps.google.com/contrib/1"
        }
    }

    @Test fun missingEnrichmentFieldsAreAbsent() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else details(38.71, -9.14) }.use { http ->
            val p = tool(http).run(args("query" to "x")).places()[0]
            listOf("rating", "ratingCount", "price", "kind", "photos").forEach { p.containsKey(it) shouldBe false }
        }
    }

    @Test fun freePriceIsAbsent() = runBlocking<Unit> {
        TinyHttp { r -> if (r.path.contains("generateContent")) three else 200 to """{"priceLevel":"PRICE_LEVEL_FREE"}""" }.use { http ->
            tool(http).run(args("query" to "x")).places()[0].containsKey("price") shouldBe false
        }
    }

    @Test fun labelUsesQueryAndNear() {
        val t = FindPlaces({ "t" }, { "test-project" })
        t.label(args("query" to "bibimbap", "near" to "Kreuzberg")) shouldBe "Searching bibimbap near Kreuzberg"
        t.label(args("query" to "a".repeat(60))) shouldBe "Searching " + "a".repeat(40)
    }

    @Test fun summaryCountsPlaces() {
        val t = FindPlaces({ "t" }, { "test-project" })
        fun r(n: Int) = Json.parseToJsonElement("""{"places":[${List(n) { "{}" }.joinToString(",")}]}""").jsonObject
        t.summary(r(6)) shouldBe "6 places"
        t.summary(r(1)) shouldBe "1 place"
        t.summary(r(0)) shouldBe "Nothing found"
        val q = Json.parseToJsonElement("""{"query":"restaurants in Kreuzberg","places":[{},{}]}""").jsonObject
        t.summary(q) shouldBe "2 places for restaurants in Kreuzberg" // two searches never read as the same line
    }

    private val twoOneGrounded = grounded(listOf("Namsan | quiet | 12–22", "Kimchi Princess | lively | 17–24"), listOf("Namsan" to "ChIJn"))

    @Test fun missingIdIsResolvedByTextSearchWhenTheNameMatches() = runBlocking<Unit> {
        TinyHttp { r -> when {
            r.path.contains("generateContent") -> twoOneGrounded
            r.path == "/v1/places:searchText" -> 200 to """{"places":[{"id":"ChIJk","googleMapsUri":"https://maps/k","displayName":{"text":"Kimchi Princess"}}]}"""
            else -> details(52.5, 13.4)
        } }.use { http ->
            val p = tool(http).run(args("query" to "bibimbap", "near" to "Kreuzberg")).places()
            p[1].s("placeId") shouldBe "ChIJk"
            p[1].s("mapsUri") shouldBe "https://maps/k"
            p[1].s("lat") shouldBe "52.5"
            val q = http.requests.single { it.path == "/v1/places:searchText" }
            q.body shouldContain "Kimchi Princess"; q.body shouldContain "Kreuzberg"
            q.headers["x-goog-fieldmask"] shouldBe "places.id,places.googleMapsUri,places.displayName"
        }
    }

    @Test fun textSearchWithADifferentNameStaysUnresolved() = runBlocking<Unit> {
        TinyHttp { r -> when {
            r.path.contains("generateContent") -> twoOneGrounded
            r.path == "/v1/places:searchText" -> 200 to """{"places":[{"id":"ChIJz","displayName":{"text":"Kimchi Palace"}}]}"""
            else -> details(52.5, 13.4)
        } }.use { http ->
            tool(http).run(args("query" to "bibimbap")).places()[1]["placeId"] shouldBe null
        }
    }
}
