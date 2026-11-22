package com.misura.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Everything shown about a place, straight from find_places results. The model never supplies these facts. */
@Serializable
data class PlaceFact(
    val id: String, val label: String, val why: String? = null, val hours: String? = null,
    val week: List<String> = emptyList(), val lat: Double? = null, val lng: Double? = null, val mapsUri: String? = null,
    val rating: Double? = null, val ratingCount: Int? = null, val price: Int? = null, val kind: String? = null,
    val photos: List<Photo> = emptyList(),
)
@Serializable data class Photo(val name: String, val author: String? = null, val authorUri: String? = null)

const val FIND_PLACES = "find_places"

/** Places from one find_places result; entries without a Google place id can't be shown as cards and are skipped. */
fun factsFromResult(result: JsonObject): Map<String, PlaceFact> =
    (result["places"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val id = o.str("placeId") ?: return@mapNotNull null
        PlaceFact(
            id, o.str("label") ?: return@mapNotNull null, o.str("why"), o.str("hours"),
            (o["week"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
            o.num("lat"), o.num("lng"), o.str("mapsUri"), o.num("rating"), (o["ratingCount"] as? JsonPrimitive)?.intOrNull,
            (o["price"] as? JsonPrimitive)?.intOrNull, o.str("kind"),
            (o["photos"] as? JsonArray).orEmpty().mapNotNull { p -> (p as? JsonObject)?.let { ph -> ph.str("name")?.let { Photo(it, ph.str("author"), ph.str("authorUri")) } } },
        )
    }.associateBy { it.id }

/** All places this session's find_places calls returned; a later result for the same id wins. */
fun placeFacts(turns: List<Turn>): Map<String, PlaceFact> {
    val out = LinkedHashMap<String, PlaceFact>()
    turns.filter { it.role == "user" }.forEach { t ->
        (t.content["parts"] as? JsonArray).orEmpty().forEach { p ->
            val fr = (p as? JsonObject)?.get("functionResponse") as? JsonObject ?: return@forEach
            if ((fr["name"] as? JsonPrimitive)?.content != FIND_PLACES) return@forEach
            (fr["response"] as? JsonObject)?.let { out.putAll(factsFromResult(it)) }
        }
    }
    return out
}

/** Places content past the 30-day limit: name and why only. */
fun PlaceFact.stale() = PlaceFact(id, label, why)

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
