package com.misura.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing a tool found in an app (a product, a listing, a ticket), straight from the tool's "items". The model never supplies these facts. */
@Serializable
data class ItemFact(
    val id: String, val title: String, val price: String? = null, val detail: String? = null, val photo: String? = null,
    /** The app it was found in, as shown to the user, and its package. */
    val app: String? = null, val pkg: String? = null,
)

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
        ItemFact(
            id, o.str("title", 80) ?: return@mapNotNull null, o.str("price", 24), o.str("detail", 80),
            o.str("photo", 500), from?.str("app", 40), from?.str("pkg", 200),
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

private fun JsonObject.str(k: String, max: Int) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }?.take(max)
