package com.misura.core.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.URI

/** Offsets address the exact displayed string in UTF-16. A source without offsets follows the answer. */
@Serializable
data class Citation(val title: String, val url: String, val start: Int? = null, val end: Int? = null) {
    fun validFor(text: String): Boolean {
        if (title.isBlank() || title.length > 256 || url.length > 2048) return false
        val uri = try { URI(url) } catch (_: java.net.URISyntaxException) { return false }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return false
        if (start == null || end == null) return start == null && end == null
        return start in 0 until end && end <= text.length &&
            !text[start].isLowSurrogate() && (end == text.length || !text[end].isLowSurrogate())
    }
}

private val LOOKUP_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

fun sourcedLookups(turns: List<Turn>): Map<String, Say.Answer> = buildMap {
    for (turn in turns.asReversed()) {
        if (turn.role != "user") continue
        for (part in (turn.content["parts"] as? JsonArray).orEmpty().asReversed()) {
            val response = (part as? JsonObject)?.get("functionResponse") as? JsonObject ?: continue
            if (response["name"] != JsonPrimitive("look_up")) continue
            val data = response["response"] as? JsonObject ?: continue
            val id = (data["lookup_id"] as? JsonPrimitive)?.content ?: continue
            if (!LOOKUP_ID.matches(id)) continue
            val record = data.sourcedAnswer() ?: continue
            put(id, record)
            if (size == 16) return@buildMap
        }
    }
}

fun JsonObject.sourcedAnswer(): Say.Answer? {
    if (this["status"] != JsonPrimitive("complete")) return null
    val line = (this["answer"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val provider = (this["provider"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
    val sources = this["citations"] as? JsonArray ?: return null
    if (line.isBlank() || line.length > 16000 || provider.isBlank() || provider.length > 32 || sources.size !in 1..64) return null
    val citations = try { Json.decodeFromJsonElement<List<Citation>>(sources) } catch (_: IllegalArgumentException) { return null }
    return Say.Answer(line, citations, provider).takeIf { citations.all { it.validFor(line) } }
}
