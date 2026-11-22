package com.misura.core.agent

import java.net.URLEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Maps chooses the travel mode; without a place id keep the original Maps link. */
fun directionsUrl(place: PlaceFact): String? {
    if (place.id.isBlank()) return place.mapsUri
    fun encode(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    return "https://www.google.com/maps/dir/?api=1&destination=${encode(place.label)}&destination_place_id=${encode(place.id)}"
}

/** Only the session's find_places facts can name a navigation destination. */
class Directions(private val open: (String) -> Unit, private val places: Map<String, PlaceFact> = emptyMap()) : Tool {
    override val spec = ToolSpec("directions", "Start directions to a place from this session's find_places results. placeId = its returned id.",
        schema("placeId" to "string", required = listOf("placeId")))
    internal fun withPlaces(facts: Map<String, PlaceFact>) = Directions(open, facts)
    private fun place(args: JsonObject) = (args["placeId"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { places[it] }
    override fun label(args: JsonObject) = "Maps: directions to ${place(args)?.label ?: "place"}"
    override fun summary(result: JsonObject): String? = null
    override suspend fun run(args: JsonObject): JsonObject = buildJsonObject {
        val url = place(args)?.let(::directionsUrl)
        if (url == null) put("error", "placeId must come from this session's find_places results")
        else runCatching { open(url) }.exceptionOrNull().let { e ->
            // A policy refusal (protected handler) keeps its own line; anything else stays generic.
            if (e == null) put("ok", true) else put("error", (e as? SecurityException)?.message ?: "Maps could not be opened")
        }
    }
}
