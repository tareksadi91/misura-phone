package com.misura.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** One thing a tool found in an app (a product, a listing, a ticket), straight from the tool's "items". The model never supplies these facts. */
@Serializable
data class ItemFact(
    val id: String, val title: String, val price: String? = null, val detail: String? = null, val photo: String? = null,
    /** The app it was found in, as shown to the user, and its package. */
    val app: String? = null, val pkg: String? = null,
    /** Near-me app finds (owner list item 7): the distance the app showed; the set came from a near_user run ([near]: text-only cards). */
    val distance: String? = null, val near: Boolean = false,
    /** A certain map pin for the venue: its latitude and longitude from Places only, computed in code (never the model, never the phone's own fix); both or neither. */
    val lat: Double? = null, val lng: Double? = null,
    /** Why a looked-up venue has no pin: [NO_PIN_SHARED] or [NO_PIN_NONE]; null = it has a pin or was not looked up. */
    val noPin: String? = null,
)

const val NO_PIN_SHARED = "shared"
const val NO_PIN_NONE = "none"

/** Directions to a pinned venue by its coordinates (Maps chooses the travel mode); null without a pin. */
fun ItemFact.directionsUrl(): String? = if (lat == null || lng == null) null else "https://www.google.com/maps/dir/?api=1&destination=${coord(lat)},${coord(lng)}"
private fun coord(x: Double) = String.format(java.util.Locale.ROOT, "%.6f", x)

/** The card in front, as the follow-up note names it: [kind] is "place" or "item". */
data class Looking(val id: String, val label: String, val kind: String)
fun PlaceFact.looking() = Looking(id, label, "place")
fun ItemFact.looking() = Looking(id, title, "item")

private val SAFE_ID = Regex("[A-Za-z0-9-]{1,64}")

/** Items from one tool result; an entry without id or title can't be a card and is skipped. */
fun itemsFromResult(result: JsonObject): Map<String, ItemFact> {
    val from = result["itemsFrom"] as? JsonObject
    return (result["items"] as? JsonArray).orEmpty().mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        // An id is echoed into the model's turn when a card is tapped, so it may never carry text (Codex P1).
        val id = o.str("id", 200)?.takeIf { SAFE_ID.matches(it) } ?: return@mapNotNull null
        // A pin is both coordinates in range; anything else is no pin. A reason only stands without a pin.
        val pin = o["pin"] as? JsonObject
        val lat = pin?.num("lat")?.takeIf { it in -90.0..90.0 }
        val lng = pin?.num("lng")?.takeIf { it in -180.0..180.0 }
        val pinned = lat != null && lng != null
        ItemFact(
            id, o.str("title", 80) ?: return@mapNotNull null, o.str("price", 24), o.str("detail", 80),
            o.str("photo", 500), from?.str("app", 40), from?.str("pkg", 200),
            o.str("distance", 16), (from?.get("near") as? JsonPrimitive)?.booleanOrNull == true,
            lat.takeIf { pinned }, lng.takeIf { pinned },
            o.str("noPin", 16)?.takeIf { !pinned && it in setOf(NO_PIN_SHARED, NO_PIN_NONE) },
        )
    }.associateBy { it.id }
}

/** All items this session's tool results returned, from any tool; a later result for the same id wins. */
fun itemFacts(turns: List<Turn>): Map<String, ItemFact> {
    val out = LinkedHashMap<String, ItemFact>()
    turns.filter { it.role == "user" }.forEach { t ->
        (t.content["parts"] as? JsonArray).orEmpty().forEach { p ->
            ((p as? JsonObject)?.get("functionResponse") as? JsonObject)?.get("response")?.let { (it as? JsonObject)?.let { r -> out.putAll(itemsFromResult(r)) } }
        }
    }
    return out
}

private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
private fun JsonObject.str(k: String, max: Int) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
