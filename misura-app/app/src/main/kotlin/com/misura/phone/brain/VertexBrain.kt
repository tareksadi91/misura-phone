package com.misura.phone.brain

import android.content.SharedPreferences
import com.misura.phone.store.privateString
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainError
import com.misura.core.agent.BrainReply
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Where the access token comes from. Debug: pushed over adb by `scripts/push-token.sh`; later a service account. */
fun interface TokenProvider { fun token(): String? }

/** The supplying client captures one identity for its lifetime, including bearer renewals. */
internal fun interface AccountBoundToken : TokenProvider

class PrefsToken(private val prefs: SharedPreferences) : TokenProvider {
    override fun token() = prefs.privateString(KEY)?.takeIf { it.isNotBlank() }
    companion object { const val KEY = "vertex_token" }
}

/** Gemini on Vertex. Model content comes back verbatim so thoughtSignature parts are echoed unchanged next call. */
class VertexBrain(private val tokens: TokenProvider, private val project: () -> String, private val baseUrl: String = VERTEX_BASE, private val model: String = "gemini-3.8-flash", /** How long one call may wait for the answer; the agent that waits longer than this for a call gives it more. */ private val readTimeoutMs: Int = 30_000,
    /** Read on every call: generationConfig.thinkingConfig.thinkingLevel (LOW, MEDIUM, HIGH); null = not sent, the model's own default. */
    private val thinkingLevel: () -> String? = { null },
    /** Read on every call: a model id that replaces [model]; null = [model]. */
    private val modelId: () -> String? = { null }) : Brain {
    private var lastToken: String? = null
    private var lastProject: String? = null
    private var lastModel: String? = null
    private var continuation: ProviderTurns? = null

    @Synchronized private fun state(token: String, project: String, model: String): ProviderTurns {
        val identityToken = if (tokens is AccountBoundToken) null else token
        if (identityToken != lastToken || project != lastProject || model != lastModel || continuation == null) {
            lastToken = identityToken
            lastProject = project
            lastModel = model
            continuation = ProviderTurns("vertex",model)
        }
        return continuation!!
    }

    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
        val project = project()
        val tk = System.nanoTime()
        val token = tokens.token() ?: throw BrainError("no sign-in token", auth = true)
        val tokenMs = (System.nanoTime() - tk) / 1_000_000
        val selected = modelId() ?: model
        val state = state(token,project,selected)
        val body = buildJsonObject {
            putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", system) }) } }
            put("contents", googleContents(turns,state))
            thinkingLevel()?.let { lv -> putJsonObject("generationConfig") { putJsonObject("thinkingConfig") { put("thinkingLevel", lv) } } }
            putJsonArray("tools") {
                add(buildJsonObject {
                    put("functionDeclarations", JsonArray(tools.map { t -> buildJsonObject { put("name", t.name); put("description", t.description); put("parameters", t.parameters) } }))
                })
            }
        }
        return nativePost(baseUrl + vertexPath(selected,project),googleHeaders(token,project),body,readTimeoutMs) { root,bytes,ms ->
            decodeGoogleReply(state,root,bytes,ms,tokenMs,legacyFinish=true)
        }
    }
}
