package com.misura.phone.brain

import com.misura.core.agent.BrainError
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.schema
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/**
 * Real places via Gemini's Google Maps grounding, then coordinates (and hours if missing) from Places details.
 * Ids and links come only from Google's grounding data; a name that doesn't match exactly one source stays without them.
 */
class FindPlaces(
    private val tokens: TokenProvider,
    private val project: () -> String,
    private val vertexBase: String = VERTEX_BASE,
    private val placesBase: String = "https://places.googleapis.com/v1",
    private val model: String = "gemini-3.8-flash",
) : Tool {
    override val app = "com.google.android.apps.maps" // Maps grounding + Places: the step shows the Maps mark
    override val spec = ToolSpec(
        "find_places",
        "Find real places (restaurants, cafés, shops…) with a short why, opening hours for a day plus the whole week when known, Google place id and Maps link. Returns at most 10.",
        schema("query" to "string", "near" to "string", "day" to "string", required = listOf("query")),
    )

    override fun label(args: JsonObject): String {
        val query = (args["query"] as? JsonPrimitive)?.content?.trim()?.take(40) ?: "places"
        return "Searching $query" + ((args["near"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }?.let { " near $it" } ?: "")
    }
    /** Says what was searched, so two searches never read as the same line ("6 places found" twice). */
    override fun summary(result: JsonObject): String? {
        val n = (result["places"] as? JsonArray)?.size ?: 0
        val what = (result["query"] as? JsonPrimitive)?.content?.trim()?.take(40)?.let { " for $it" } ?: ""
        return when (n) { 0 -> "Nothing found$what"; 1 -> "1 place$what"; else -> "$n places$what" }
    }

    private data class Place(
        val label: String, val why: String?, var hours: String?, var placeId: String?, var mapsUri: String?,
        var lat: Double? = null, var lng: Double? = null, var week: List<String> = emptyList(),
        var rating: Double? = null, var ratingCount: Int? = null, var price: Int? = null, var kind: String? = null, var photos: List<JsonObject> = emptyList(),
    )

    override suspend fun run(args: JsonObject): JsonObject {
        val project = project()
        val token = tokens.token() ?: throw BrainError("no sign-in token", auth = true)
        val query = (args["query"] as? JsonPrimitive)?.content ?: return buildJsonObject { put("error", "query is required") }
        val near = (args["near"] as? JsonPrimitive)?.content
        val day = (args["day"] as? JsonPrimitive)?.content ?: LocalDate.now().dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        val places = grounded(token, project, query, near, day)
        // Grounding names that matched no source get their id from a quick text search (~1 s), so the model never
        // has to re-run this ~30 s search just to get ids it can show.
        coroutineScope { places.filter { it.placeId == null }.map { p -> async { runCatching { resolve(token, project, p, near) } } }.awaitAll() }
        coroutineScope { places.filter { it.placeId != null }.map { p -> async { runCatching { details(token, project, p, day) } } }.awaitAll() }
        return buildJsonObject {
            put("query", query)
            putJsonArray("places") {
                places.forEach { p ->
                    add(buildJsonObject {
                        put("label", p.label); p.why?.let { put("why", it) }; p.hours?.let { put("hours", it) }
                        if (p.week.isNotEmpty()) put("week", JsonArray(p.week.map { JsonPrimitive(it) }))
                        p.placeId?.let { put("placeId", it) }; p.mapsUri?.let { put("mapsUri", it) }
                        p.lat?.let { put("lat", it) }; p.lng?.let { put("lng", it) }
                        p.rating?.let { put("rating", it) }; p.ratingCount?.let { put("ratingCount", it) }; p.price?.let { put("price", it) }; p.kind?.let { put("kind", it) }
                        if (p.photos.isNotEmpty()) put("photos", JsonArray(p.photos))
                    })
                }
            }
        }
    }

    private suspend fun grounded(token: String, project: String, query: String, near: String?, day: String): List<Place> {
        val prompt = "List up to 10 real places for: $query${near?.let { " near $it" } ?: ""}. " +
            "One per line, exactly: name | why it fits (at most 8 words) | opening hours on $day. Write - when hours are unknown. No other text."
        val body = buildJsonObject {
            putJsonArray("contents") { add(buildJsonObject { put("role", "user"); putJsonArray("parts") { add(buildJsonObject { put("text", prompt) }) } }) }
            putJsonArray("tools") { add(buildJsonObject { putJsonObject("googleMaps") {} }) }
        }
        // Grounded search measured at ~30 s on 10 Nov; the agent's 90 s turn limit still bounds it.
        val (code, text) = http("POST", vertexBase + vertexPath(model, project), googleHeaders(token, project), body.toString(), timeoutMs = 60_000)
        if (code == 401) throw BrainError("401", auth = true, code = "401")
        if (code >= 400) throw BrainError("places search $code", code = "places $code")
        val cand = (Json.parseToJsonElement(text).jsonObject["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject ?: return emptyList()
        val answer = (cand["content"]?.jsonObject?.get("parts") as? JsonArray).orEmpty().mapNotNull { (it.jsonObject["text"] as? JsonPrimitive)?.content }.joinToString("")
        val sources = ((cand["groundingMetadata"] as? JsonObject)?.get("groundingChunks") as? JsonArray).orEmpty()
            .mapNotNull { (it.jsonObject["maps"] as? JsonObject) }
            .map { m -> Triple(m.str("title")?.removeSuffix(" - Google Maps")?.trim().orEmpty(), m.str("placeId")?.removePrefix("places/"), m.str("uri")) }
        return answer.lines().mapNotNull { line ->
            val cols = line.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "").replace("**", "").split("|").map { it.trim() }
            if (cols.size < 2 || cols[0].isEmpty()) return@mapNotNull null
            val match = sources.filter { it.first.equals(cols[0], ignoreCase = true) }.singleOrNull()
            Place(cols[0], cols[1].ifEmpty { null }, cols.getOrNull(2)?.takeIf { it.isNotEmpty() && it != "-" }, match?.second, match?.third)
        }.take(10) // matches "up to 10" in the prompt: the agent ranks the whole set by taste
    }

    /** Takes the top text-search hit only when its name is the same as the grounded name (no guessing at a lookalike). */
    private suspend fun resolve(token: String, project: String, p: Place, near: String?) {
        val body = buildJsonObject { put("textQuery", p.label + (near?.let { " $it" } ?: "")) }
        val (code, text) = http("POST", "$placesBase/places:searchText", googleHeaders(token, project) + ("X-Goog-FieldMask" to "places.id,places.googleMapsUri,places.displayName"), body.toString())
        if (code >= 400) return
        val top = (Json.parseToJsonElement(text).jsonObject["places"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        val name = ((top["displayName"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: return
        if (!name.equals(p.label, ignoreCase = true)) return
        p.placeId = top.str("id"); p.mapsUri = top.str("googleMapsUri")
    }

    private suspend fun details(token: String, project: String, p: Place, day: String) {
        val (code, text) = http("GET", "$placesBase/places/${p.placeId}", googleHeaders(token, project) + ("X-Goog-FieldMask" to "displayName,location,regularOpeningHours.weekdayDescriptions,rating,userRatingCount,priceLevel,primaryTypeDisplayName,photos"))
        if (code >= 400) return
        val d = Json.parseToJsonElement(text).jsonObject
        (d["location"] as? JsonObject)?.let { loc -> p.lat = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull; p.lng = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull }
        // The whole week stays in the result, so "when are they open?" follow-ups don't need another ~30 s search.
        p.week = ((d["regularOpeningHours"] as? JsonObject)?.get("weekdayDescriptions") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content }
        if (p.hours == null) p.hours = p.week.firstOrNull { it.startsWith("$day:", ignoreCase = true) }?.substringAfter(":")?.trim()
        p.rating = (d["rating"] as? JsonPrimitive)?.doubleOrNull
        p.ratingCount = (d["userRatingCount"] as? JsonPrimitive)?.intOrNull
        p.price = PRICE_LEVELS.indexOf((d["priceLevel"] as? JsonPrimitive)?.content).takeIf { it >= 0 }?.plus(1)
        p.kind = ((d["primaryTypeDisplayName"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content
        // Photo names plus their authors: Places terms require showing the attribution with the photo.
        p.photos = (d["photos"] as? JsonArray).orEmpty().take(3).mapNotNull { e ->
            val ph = e as? JsonObject ?: return@mapNotNull null
            val name = ph.str("name") ?: return@mapNotNull null
            val a = ((ph["authorAttributions"] as? JsonArray)?.firstOrNull() as? JsonObject)
            buildJsonObject { put("name", name); a?.str("displayName")?.let { put("author", it) }; a?.str("uri")?.let { put("authorUri", it) } }
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.content

    private companion object {
        /** Index + 1 = the €-count we show; FREE and UNSPECIFIED show no price. */
        val PRICE_LEVELS = listOf("PRICE_LEVEL_INEXPENSIVE", "PRICE_LEVEL_MODERATE", "PRICE_LEVEL_EXPENSIVE", "PRICE_LEVEL_VERY_EXPENSIVE")
    }
}
