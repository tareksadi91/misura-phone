package com.misura.core.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One thing you said in a task and what Misura said back. [answer] is null when it showed cards or a draft without words, or was stopped. */
/** [turn]..[last] = the turn numbers it covers: a tap on a choice carries the same exchange on, so its steps stay with it. */
data class Exchange(val turn: Int, val asked: String, val answer: String?, val last: Int = turn, val citedAnswer: Say.Answer? = null)

private val SAYS = setOf("answer", "ask", "show_places", "show_items")

/**
 * The conversation so far, read from the saved turns: your words and Misura's own lines, including the exact accepted display for a cited answer.
 * Turn numbers match [turnNumber], so each exchange finds its steps. A tap on a choice is not speech: it carries the exchange on.
 */
fun thread(turns: List<Turn>): List<Exchange> {
    val out = mutableListOf<Exchange>()
    var n = 0
    var calls = emptyList<JsonObject>() // the model's last calls, waiting for their results
    fun say(line: String?, cited: Say.Answer? = null) { if (!line.isNullOrBlank() && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(answer = cited?.line ?: line.trim(), citedAnswer = cited) }
    for (t in turns) {
        val parts = (t.content["parts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val texts = parts.mapNotNull { (it["text"] as? JsonPrimitive)?.content }
        if (t.role == "user") {
            // Tool results answer the calls in order. A line counts only once the app took it: one the code refused
            // (unknown place ids) or never reached was not shown, so it is not in the thread either (Codex P2).
            val results = parts.mapNotNull { it["functionResponse"] as? JsonObject }
            for (result in results) {
                val match = calls.indexOfFirst {
                    it["name"] == result["name"] && (result["id"] == null || it["id"] == result["id"])
                }
                if (match < 0) continue
                val call = calls[match]
                calls = calls.filterIndexed { index, _ -> index != match }
                if ((call["name"] as? JsonPrimitive)?.content in SAYS && (result["response"] as? JsonObject)?.get("ok") == JsonPrimitive(true)) {
                    val display = (result["response"] as? JsonObject)?.get("display") as? JsonObject
                    val cited = if (call["name"] == JsonPrimitive("answer") && display != null) try {
                        kotlinx.serialization.json.Json.decodeFromJsonElement(Say.Answer.serializer(), display)
                            .takeIf { a -> a.citations.isNotEmpty() && a.citations.all { it.validFor(a.line) } }
                    } catch (_: IllegalArgumentException) { null } else null
                    if (cited != null) say(cited.line, cited) else if (display == null) say(((call["args"] as? JsonObject)?.get("line") as? JsonPrimitive)?.content)
                }
            }
            if (texts.isEmpty() || SENT_NOTE in texts) continue // tool results, or the app's own "sent" word
            n++
            if (PICK_NOTE in texts && out.isNotEmpty()) out[out.lastIndex] = out.last().copy(last = n) else out += Exchange(n, texts.first(), null)
        } else {
            calls = parts.mapNotNull { it["functionCall"] as? JsonObject }
            if (calls.isEmpty()) say(texts.joinToString(""))
        }
    }
    return out
}
