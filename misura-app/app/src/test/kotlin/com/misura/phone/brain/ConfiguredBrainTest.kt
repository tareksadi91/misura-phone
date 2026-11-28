package com.misura.phone.brain

import com.misura.core.agent.TurnOrigin
import com.misura.core.agent.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ConfiguredBrainTest {
    @Test fun agentDispatchesRoutedSearchWithCapturedExaKeyAndShowsSourcedAnswer() = runBlocking<Unit> {
        routedToolThroughAgent(false)
    }

    @Test fun agentDispatchesRoutedPlacesWithCapturedGoogleCredentialsAndShowsCards() = runBlocking<Unit> {
        routedToolThroughAgent(true)
    }

    private suspend fun CoroutineScope.routedToolThroughAgent(places: Boolean) {
        val name = if (places) "find_places" else "look_up"
        val args = buildJsonObject { put(if (places) "query" else "question", "public fixture") }
        val grounding = """{"candidates":[{"content":{"parts":[{"text":"Fixture Museum | History | 10:00"}]},"groundingMetadata":{"groundingChunks":[{"maps":{"title":"Fixture Museum","placeId":"fixture-place","uri":"https://maps.google.com/?cid=fixture"}}]}}]}"""
        TinyHttp { request -> 200 to when {
            request.path == "/answer" -> """{"answer":"Sourced fixture.","citations":[{"url":"https://example.org/source","title":"Source"}]}"""
            request.method == "GET" -> """{"location":{"latitude":52.5,"longitude":13.4}}"""
            else -> grounding
        } }.use { http ->
            val route = ModelRoute(ModelProvider.VERTEX, "fixture-model", null, "fixture-hand", null, null,
                null, "fixture-google-token", "fixture-project", SearchProvider.EXA, "fixture-exa-key", false, false)
            val context = ModelRoutes({ route }, http.base).capture()
            val prototype: Tool = if (places) FindPlaces({ null }, { "" }, vertexBase = http.base)
                else LookUp({ null }, { "" }, vertexBase = http.base, exaBase = http.base)
            val routed = RouteTool(prototype) { captured ->
                if (places) FindPlaces(captured.google.tokens, { captured.google.project }, vertexBase = http.base, placesBase = http.base + "/v1")
                else LookUp(captured.google.tokens, { captured.google.project }, route = captured.route, testOrigin = http.base)
            }
            fun call(name: String, args: JsonObject): BrainReply {
                val content = buildJsonObject { put("role", "model"); putJsonArray("parts") { add(callPart(null, name, args)) } }
                return BrainReply.Calls(content, listOf(ToolCall(null, name, args)))
            }
            var round = 0
            val brain = object : Brain {
                override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
                    if (round++ == 0) return call(name, args)
                    if (places) return call("show_places", buildJsonObject {
                        putJsonArray("placeIds") { add(JsonPrimitive("fixture-place")) }; put("pick", "fixture-place"); put("line", "Museum fixture.")
                    })
                    val lookup = sourcedLookups(turns).keys.singleOrNull()
                    return if (lookup != null) call("answer", buildJsonObject { put("lookup_id", lookup) })
                        else call("answer", buildJsonObject { put("line", "Direct search unavailable") })
                }
            }
            val store = Store()
            val agent = Agent(store, brain, listOf(routed), System::currentTimeMillis, this, turnContext = { context })
            val id = agent.start("public fixture", TurnOrigin.OWNER)
            withTimeout(5000) { while (store.load(id)?.status == SessionStatus.THINKING) delay(10) }
            assertEquals(SessionStatus.IDLE, store.load(id)?.status)
            if (places) {
                assertEquals(setOf("fixture-place"), placeFacts(store.turns(id)).keys)
                assertEquals(Places(listOf("fixture-place"), "fixture-place"), store.load(id)?.places)
                assertEquals(2, http.requests.size)
                assertTrue(http.requests.all { it.headers["authorization"] == "Bearer fixture-google-token" && it.headers["x-goog-user-project"] == "fixture-project" })
            } else {
                val answer = store.load(id)?.say as? Say.Answer
                assertEquals("Exa", answer?.provider)
                assertEquals("Sourced fixture.", answer?.line)
                assertEquals("fixture-exa-key", http.requests.single().headers["x-api-key"])
            }
        }
    }

    /** "Cafes near me" through Agent → routed find_places: the phone's fix reaches Google's search, never the model's turns. */
    @Test fun agentSearchesWhereThePhoneIsWithoutTellingTheModelTheCoordinates() = runBlocking<Unit> {
        val grounding = """{"candidates":[{"content":{"parts":[{"text":"Fixture Café | Coffee | 08:00"}]},"groundingMetadata":{"groundingChunks":[{"maps":{"title":"Fixture Café","placeId":"fixture-cafe","uri":"https://maps.google.com/?cid=fixture"}}]}}]}"""
        TinyHttp { request -> 200 to if (request.method == "GET") """{"location":{"latitude":48.1,"longitude":11.5}}""" else grounding }.use { http ->
            val route = ModelRoute(ModelProvider.VERTEX, "fixture-model", null, "fixture-hand", null, null,
                null, "fixture-google-token", "fixture-project", SearchProvider.EXA, "fixture-exa-key", false, false)
            val context = ModelRoutes({ route }, http.base).capture()
            val phone: suspend () -> Pair<Double, Double>? = { 48.137 to 11.575 }
            val routed = RouteTool(FindPlaces({ null }, { "" }, vertexBase = http.base)) { captured ->
                FindPlaces(captured.google.tokens, { captured.google.project }, vertexBase = http.base, placesBase = http.base + "/v1", here = phone)
            }
            val seen = mutableListOf<String>()
            var round = 0
            val brain = object : Brain {
                override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply {
                    seen += turns.joinToString { it.content.toString() }
                    val (name, args) = if (round++ == 0) "find_places" to buildJsonObject { put("query", "cafes") }
                        else "show_places" to buildJsonObject { putJsonArray("placeIds") { add(JsonPrimitive("fixture-cafe")) }; put("pick", "fixture-cafe"); put("line", "Cafe fixture.") }
                    val content = buildJsonObject { put("role", "model"); putJsonArray("parts") { add(callPart(null, name, args)) } }
                    return BrainReply.Calls(content, listOf(ToolCall(null, name, args)))
                }
            }
            val store = Store()
            val agent = Agent(store, brain, listOf(routed), System::currentTimeMillis, this, turnContext = { context })
            val id = agent.start("cafes near me", TurnOrigin.OWNER)
            withTimeout(5000) { while (store.load(id)?.status == SessionStatus.THINKING) delay(10) }
            assertEquals(SessionStatus.IDLE, store.load(id)?.status)
            val search = Json.parseToJsonElement(http.requests.first { it.method == "POST" }.body).jsonObject
            assertEquals("48.137", search["toolConfig"]!!.jsonObject["retrievalConfig"]!!.jsonObject["latLng"]!!.jsonObject["latitude"]!!.jsonPrimitive.content)
            assertTrue(seen.last().contains("where the phone is now"))
            assertFalse(seen.any { it.contains("48.137") || it.contains("11.575") })
        }
    }

    private fun route(key: String, provider: ModelProvider = ModelProvider.OPENAI,
        main: String = "main-model", hand: String = "hand-model", mainThinking: String = "HIGH", handThinking: String = "LOW") = ModelRoute(
        provider, main, key, hand, mainThinking, handThinking, null,
        if (provider == ModelProvider.VERTEX) key else null, "project-$key",
        SearchProvider.TAVILY, "search-$key", false, false)
    private val text = """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"done"}]}]}"""
    private val user = listOf(Turn("user", buildJsonObject {
        put("role", "user"); putJsonArray("parts") { addJsonObject { put("text", "fixture") } }
    }))
    private class Store : SessionStore {
        val sessions = mutableMapOf<String, Session>()
        val history = mutableMapOf<String, MutableList<Turn>>()
        override suspend fun load(id: String) = sessions[id]
        override suspend fun save(s: Session) { sessions[s.id] = s }
        override suspend fun turns(id: String) = history[id].orEmpty().toList()
        override suspend fun append(id: String, t: Turn) { history.getOrPut(id) { mutableListOf() }.add(t) }
        override suspend fun delete(id: String) { sessions.remove(id); history.remove(id) }
        override suspend fun recent(limit: Int) = sessions.values.take(limit)
    }

    @Test fun nativeTurnKeepsOneAccountAcrossNestedHandAndNextTurnRotates() = runBlocking<Unit> {
        val saved = AtomicReference(route("fixture-old"))
        val n = AtomicInteger()
        TinyHttp {
            if (n.getAndIncrement() == 0) {
                saved.set(route("fixture-new"))
                200 to """{"status":"completed","output":[{"type":"function_call","call_id":"call-1","name":"nested_fixture","arguments":"{}"}]}"""
            } else 200 to text
        }.use { http ->
            val routes = ModelRoutes(saved::get, http.base)
            val nested = object : Tool {
                override val spec = ToolSpec("nested_fixture", "fixture", schema())
                override fun label(args: JsonObject) = "fixture"
                override fun summary(result: JsonObject) = "fixture finished"
                override suspend fun run(args: JsonObject): JsonObject {
                    val snapshot = ModelRouteContext.current()
                    assertEquals("search-fixture-old", snapshot.route.searchKey)
                    assertEquals("project-fixture-old", snapshot.google.project)
                    ConfiguredHandBrain.next("hand fixture", user, emptyList())
                    return buildJsonObject { put("ok", true) }
                }
            }
            val store = Store()
            val steps = mutableListOf<String>()
            val agent = Agent(store, ConfiguredBrain, listOf(nested), System::currentTimeMillis, this,
                onStep = { steps.add(it) }, turnContext = routes::capture)
            val id = agent.start("fixture", TurnOrigin.OWNER)
            withTimeout(5_000) { while (store.load(id)?.status == SessionStatus.THINKING) delay(10) }
            assertEquals(SessionStatus.IDLE, store.load(id)?.status)
            agent.followUp(id, "next fixture").join()
            assertEquals(SessionStatus.IDLE, store.load(id)?.status)
            assertEquals(listOf("Bearer fixture-old", "Bearer fixture-old", "Bearer fixture-old", "Bearer fixture-new"),
                http.requests.map { it.headers["authorization"] })
            assertTrue(steps.isNotEmpty())
            for (evidence in listOf(steps.toString(), store.turns(id).toString())) {
                for (sensitive in listOf("fixture-old", "fixture-new", "Bearer ", "authorization", "x-api-key", "x-goog-api-key"))
                    assertFalse(evidence.contains(sensitive, ignoreCase = true))
            }
        }
    }

    @Test fun apiKeyRoutesUseTheSelectedAccountForOwnerAuthorizationAndContinuation() = runBlocking<Unit> {
        for (provider in listOf(ModelProvider.GEMINI, ModelProvider.OPENAI, ModelProvider.ANTHROPIC)) {
            val credential = "fixture-${provider.name}"
            val requests = AtomicInteger()
            TinyHttp {
                val round = requests.getAndIncrement()
                val note = if (round == 0) "proposed" else "authorized"
                val call = round <= 1
                val reply = when (provider) {
                    ModelProvider.GEMINI -> {
                        val part = if (call) """{"functionCall":{"name":"remember","args":{"note":"$note"}}}"""
                            else """{"text":"done"}"""
                        """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[$part]}}]}"""
                    }
                    ModelProvider.OPENAI -> if (call)
                        """{"status":"completed","output":[{"type":"function_call","call_id":"id-$round","name":"remember","arguments":"{\"note\":\"$note\"}"}]}""" else text
                    ModelProvider.ANTHROPIC -> if (call)
                        """{"stop_reason":"tool_use","content":[{"type":"tool_use","name":"remember","id":"id-$round","input":{"note":"$note"}}]}"""
                        else """{"stop_reason":"end_turn","content":[{"type":"text","text":"done"}]}"""
                    ModelProvider.VERTEX -> error("unexpected provider")
                }
                200 to reply
            }.use { http ->
                val captured = ModelRoutes({ route(credential, provider) }, http.base).capture()
                val notes = mutableListOf<String>()
                val memory = object : Tool {
                    override val spec = ToolSpec("remember", "fixture", schema())
                    override val userOnly = true
                    override fun label(args: JsonObject) = "remember"
                    override fun summary(result: JsonObject) = "saved"
                    override suspend fun run(args: JsonObject): JsonObject {
                        notes.add(args.getValue("note").jsonPrimitive.content)
                        return buildJsonObject { put("ok", true) }
                    }
                }
                val store = Store()
                val agent = Agent(store, ConfiguredBrain, listOf(memory), System::currentTimeMillis, this,
                    about = { "private briefing fixture" }, turnContext = { captured })
                val id = agent.start("Remember this fixture", TurnOrigin.OWNER)
                withTimeout(5_000) { while (store.load(id)?.status == SessionStatus.THINKING) delay(10) }
                assertEquals(provider.name, listOf("authorized"), notes)
                agent.followUp(id, "Next fixture").join()
                withContext(captured) { ConfiguredHandBrain.next("hand fixture", user, emptyList()) }
                assertEquals(5, http.requests.size)
                val auth = when (provider) {
                    ModelProvider.GEMINI -> "x-goog-api-key" to credential
                    ModelProvider.OPENAI -> "authorization" to "Bearer $credential"
                    ModelProvider.ANTHROPIC -> "x-api-key" to credential
                    ModelProvider.VERTEX -> error("unexpected provider")
                }
                http.requests.forEach { req ->
                    assertEquals(auth.second, req.headers[auth.first])
                    assertFalse(req.headers.containsKey("x-goog-user-project"))
                }
                assertTrue(http.requests[1].body.contains("Remember this fixture"))
                assertFalse(http.requests[1].body.contains("private briefing fixture"))
                assertFalse(http.requests[1].body.contains("proposed"))
                assertTrue(http.requests[2].body.contains("remember"))
                assertTrue(http.requests[3].body.contains("Next fixture"))
                assertTrue(http.requests[4].body.contains("hand fixture"))
            }
        }
    }

    @Test fun vertexRolesKeepCapturedModelsThinkingAndGoogleIdentity() = runBlocking<Unit> {
        var saved = route("fixture-old", ModelProvider.VERTEX)
        TinyHttp { 200 to """{"candidates":[{"finishReason":"STOP","content":{"role":"model","parts":[{"text":"done"}]}}]}""" }.use { http ->
            val routes = ModelRoutes({ saved }, http.base)
            val old = routes.capture()
            saved = route("fixture-new", ModelProvider.VERTEX, "second-main", "second-hand", "LOW", "MEDIUM")
            val fresh = routes.capture()
            withContext(old) {
                ConfiguredBrain.next("main", user, emptyList())
                ConfiguredHandBrain.next("hand", user, emptyList())
            }
            withContext(fresh) {
                ConfiguredBrain.next("main", user, emptyList())
                ConfiguredHandBrain.next("hand", user, emptyList())
            }
            assertEquals(listOf("Bearer fixture-old", "Bearer fixture-old", "Bearer fixture-new", "Bearer fixture-new"),
                http.requests.map { it.headers["authorization"] })
            assertTrue(http.requests[0].path.contains("projects/project-fixture-old/"))
            assertTrue(http.requests[0].path.contains("models/main-model:"))
            assertTrue(http.requests[1].path.contains("models/hand-model:"))
            assertTrue(http.requests[2].path.contains("projects/project-fixture-new/"))
            assertTrue(http.requests[2].path.contains("models/second-main:"))
            assertTrue(http.requests[3].path.contains("models/second-hand:"))
            assertEquals(listOf("project-fixture-old", "project-fixture-old", "project-fixture-new", "project-fixture-new"),
                http.requests.map { it.headers["x-goog-user-project"] })
            assertEquals(listOf("HIGH", "LOW", "LOW", "MEDIUM"), http.requests.map {
                Json.parseToJsonElement(it.body).jsonObject["generationConfig"]!!.jsonObject["thinkingConfig"]!!.jsonObject["thinkingLevel"]!!.jsonPrimitive.content
            })
        }
    }
}
