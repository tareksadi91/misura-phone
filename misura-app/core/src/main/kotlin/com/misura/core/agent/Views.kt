package com.misura.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

class ViewError(message: String) : Exception(message)

const val MAX_PLACES = 6
const val MAX_ITEMS = 10
const val MAX_CHOICES = 4

/** show_places args → (places, line). Ids not from find_places are dropped; a pick outside the ids is ignored. */
/** [names] = place id → its name, for a pick given by name. */
fun parseShowPlaces(args: JsonObject, known: Set<String>, names: Map<String, String> = emptyMap()): Pair<Places, String?> {
    val raw = (args["placeIds"] as? JsonArray ?: throw ViewError("show_places needs \"placeIds\" (a list of placeId values from find_places)"))
        .mapNotNull { (it as? JsonPrimitive)?.content }
    if (raw.size > MAX_PLACES) throw ViewError("show at most $MAX_PLACES places, got ${raw.size}")
    val ids = raw.filter { it in known }.distinct()
    if (ids.isEmpty()) throw ViewError("none of these placeIds came from find_places results; use the placeId values it returned")
    // The model often names its pick ("Namsan Berlin") instead of giving the id, and the tag never showed. An id wins, then an
    // exact name; with neither, the first place is the pick, since the list is in its order of preference.
    val said = args.str("pick")
    val pick = said?.takeIf { it in ids } ?: said?.let { n -> ids.firstOrNull { names[it].equals(n, ignoreCase = true) } } ?: ids.first()
    return Places(ids, pick) to args.str("line")
}

/** show_items args → (items, line). Unlike places, an unknown id is an error: items only come from a tool's own results. */
fun parseShowItems(args: JsonObject, known: Map<String, ItemFact>): Pair<Items, String?> {
    val ids = (args["itemIds"] as? JsonArray ?: throw ViewError("show_items needs \"itemIds\" (a list of item id values from a tool result)"))
        .mapNotNull { (it as? JsonPrimitive)?.content }.distinct()
    if (ids.isEmpty() || ids.size > MAX_ITEMS) throw ViewError("show 1 to $MAX_ITEMS items, got ${ids.size}")
    ids.firstOrNull { it !in known }?.let { throw ViewError("\"$it\" is not an item id from a tool result; use the id values the tool returned") }
    val said = args.str("pick")
    val pick = said?.takeIf { it in ids } ?: said?.let { n -> ids.firstOrNull { known[it]!!.title.equals(n, ignoreCase = true) } }
    return Items(ids, pick) to args.str("line")
}

fun parseAsk(args: JsonObject): Say.Question {
    val line = args.str("line") ?: throw ViewError("ask needs \"line\"")
    val choices = (args["choices"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content?.trim()?.takeIf { c -> c.isNotEmpty() } }
    if (choices.size > MAX_CHOICES) throw ViewError("at most $MAX_CHOICES choices, got ${choices.size}")
    return Say.Question(line, choices)
}

fun parseAnswer(args: JsonObject, lookups: Map<String, Say.Answer> = emptyMap()): Say.Answer {
    if ("lookup_id" in args) return lookups[args.str("lookup_id")] ?: throw ViewError("Unknown lookup_id for this conversation")
    return Say.Answer(args.str("line") ?: throw ViewError("answer needs a line or lookup_id"))
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
