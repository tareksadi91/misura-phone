package com.misura.phone.brain

import com.misura.core.agent.Citation
import kotlinx.serialization.json.*
import java.net.URI
import java.util.UUID

internal sealed interface LookupResult {
    data class Complete(val answer: String, val citations: List<Citation>, val provider: String) : LookupResult
    data object Empty : LookupResult
    data object Partial : LookupResult
    data object Failed : LookupResult
    data object SuggestionsUnsupported : LookupResult
}

internal fun JsonObject.field(name: String) = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.objects(name: String) = getValue(name).jsonArray.map { it.jsonObject }

internal fun source(url: String, title: String?, start: Int? = null, end: Int? = null) =
    Citation(title?.takeIf { it.isNotBlank() && it.length <= 256 } ?: URI(url).host.orEmpty(), url, start, end)

internal fun complete(text: String?, sources: List<Citation>, provider: String): LookupResult {
    if (text.isNullOrBlank() || text.length > 16000 || sources.size !in 1..64 || sources.any { !it.validFor(text) }) return LookupResult.Partial
    return LookupResult.Complete(text, sources.distinct(), provider)
}

internal fun LookupResult.reply() = buildJsonObject {
    when (this@reply) {
        is LookupResult.Complete -> {
            put("status", "complete"); put("lookup_id", UUID.randomUUID().toString())
            put("provider", provider); put("answer", answer)
            put("citations", Json.encodeToJsonElement(citations))
        }
        LookupResult.Empty -> put("status", "no_results")
        else -> {
            put("status", "needs_browser")
            put("reason", when (this@reply) {
                LookupResult.Partial -> "incomplete_or_unsourced"
                LookupResult.SuggestionsUnsupported -> "search_suggestions_display_unavailable"
                else -> "direct_search_unavailable"
            })
        }
    }
}
