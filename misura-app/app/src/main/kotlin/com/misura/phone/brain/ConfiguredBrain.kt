package com.misura.phone.brain

import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Tool
import com.misura.core.agent.Turn
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

internal class GoogleAccess(val account: String?, val fallback: String?, val project: String) {
    val tokens = ServiceAccountToken({ account }, { fallback })
    val photos by lazy { Photos(tokens, { project }) }
}

internal class ModelRouteContext(
    val route: ModelRoute,
    val main: Brain,
    val google: GoogleAccess,
    val hand: Brain = main,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ModelRouteContext> {
        suspend fun current() = requireNotNull(coroutineContext[Key]) { "Model route unavailable." }
    }

    override fun toString() = "ModelRouteContext"
}

internal class ModelRoutes(private val read: () -> ModelRoute,
    private val loopbackOrigin: String? = null) {
    private var latest: ModelRouteContext? = null

    @Synchronized fun capture(): ModelRouteContext {
        val route = read()
        val old = latest
        val google = old?.google?.takeIf {
            it.account == route.serviceAccount && it.fallback == route.fallbackToken && it.project == route.project
        } ?: GoogleAccess(route.serviceAccount, route.fallbackToken, route.project)
        val prior = old?.route
        val sameModel = prior != null && prior.provider == route.provider && prior.model == route.model && prior.apiKey == route.apiKey
        val vertex = route.provider == ModelProvider.VERTEX
        val reuseMain = sameModel && (!vertex || (old.google === google && prior.mainThinking == route.mainThinking))
        val reuseHand = sameModel && (!vertex || (old.google === google && prior.handModel == route.handModel && prior.handThinking == route.handThinking))
        val main = if (reuseMain) requireNotNull(old).main else modelBrain(route, google, false, loopbackOrigin)
        val hand = if (reuseHand) requireNotNull(old).hand else modelBrain(route, google, true, loopbackOrigin)
        return ModelRouteContext(route, main, google, hand).also { latest = it }
    }
}

private fun modelBrain(route: ModelRoute, google: GoogleAccess, hand: Boolean, loopbackOrigin: String?): Brain {
    val timeout = if (hand) 30_000 else 60_000
    return when (route.provider) {
        ModelProvider.VERTEX -> VertexBrain(AccountBoundToken(google.tokens::token), { google.project }, fixedOrigin(loopbackOrigin ?: VERTEX_BASE, VERTEX_BASE), model = if (hand) route.handModel else route.model,
            thinkingLevel = { if (hand) route.handThinking else route.mainThinking }, readTimeoutMs = timeout)
        ModelProvider.GEMINI -> GeminiBrain(requireNotNull(route.apiKey), route.model, timeout, loopbackOrigin ?: GEMINI)
        ModelProvider.OPENAI -> OpenAiBrain(requireNotNull(route.apiKey), route.model, timeout, loopbackOrigin ?: OPENAI)
        ModelProvider.ANTHROPIC -> AnthropicBrain(requireNotNull(route.apiKey), route.model, timeout, loopbackOrigin ?: ANTHROPIC)
    }
}

internal object ConfiguredBrain : Brain {
    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply =
        ModelRouteContext.current().main.next(system, turns, tools)
}

internal object ConfiguredHandBrain : Brain {
    override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply =
        ModelRouteContext.current().hand.next(system, turns, tools)
}

/** Labels are pure; credential-bearing tool instances are created only within the owning turn. */
internal class RouteTool(private val prototype: Tool, private val create: (ModelRouteContext) -> Tool) : Tool by prototype {
    override suspend fun run(args: JsonObject): JsonObject = create(ModelRouteContext.current()).run(args)
    // Delegated interface defaults run on the prototype; Agent uses this overload, so route it explicitly too.
    override suspend fun run(args: JsonObject, note: suspend (String) -> Unit): JsonObject = create(ModelRouteContext.current()).run(args, note)
}
