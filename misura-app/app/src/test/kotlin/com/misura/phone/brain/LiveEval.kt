package com.misura.phone.brain

import com.misura.core.agent.TurnOrigin
import com.misura.core.agent.Agent
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.SessionStore
import com.misura.core.agent.StepState
import com.misura.core.agent.looking
import com.misura.core.agent.placeFacts
import com.misura.core.agent.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Real Gemini + real find_places, no phone. Runs only with MISURA_TOKEN and PROJECT set:
 * MISURA_TOKEN=$(gcloud auth application-default print-access-token) ./gradlew -q :app:testDebugUnitTest --tests '*LiveEval*' -i
 * Prints what the model did; it's a report, not a CI gate.
 */
class LiveEval {
    private val token = System.getenv("MISURA_TOKEN").orEmpty()
    private val store = object : SessionStore {
        val s = ConcurrentHashMap<String, Session>(); val t = ConcurrentHashMap<String, MutableList<Turn>>()
        override suspend fun load(id: String) = s[id]
        override suspend fun save(s: Session) { this.s[s.id] = s }
        override suspend fun turns(id: String) = synchronized(t) { t[id].orEmpty().toList() }
        override suspend fun append(id: String, t: Turn) = synchronized(this.t) { this.t.getOrPut(id) { mutableListOf() } += t; Unit }
        override suspend fun delete(id: String) { s.remove(id); synchronized(t) { t.remove(id) } }
        override suspend fun recent(limit: Int) = s.values.sortedByDescending { it.createdAt }.take(limit)
    }
    private val tokens = TokenProvider { token }
    private val project = { vertexProject(null, System.getenv("PROJECT")) }
    private val agent = Agent(store, VertexBrain(tokens, project), listOf(FindPlaces(tokens, project)), System::currentTimeMillis, CoroutineScope(SupervisorJob() + Dispatchers.Default), onStep = { println("  · $it") })
    private val time = Regex("\\d{1,2}[:.]\\d{2}")

    private suspend fun settle(id: String): Session { repeat(240) { val s = store.load(id)!!; if (s.status != SessionStatus.THINKING) return s; delay(500) }; error("still thinking after 120 s") }
    private suspend fun toolsUsed(id: String) = store.turns(id).filter { it.role == "model" }
        .flatMap { (it.content["parts"]?.jsonArray).orEmpty() }.mapNotNull { (it.jsonObject["functionCall"] as? JsonObject)?.get("name")?.let { n -> (n as JsonPrimitive).content } }

    @Test fun bibimbapShowsPlacesWithAPickThenHoursWithoutASecondSearch() = runBlocking<Unit> {
        assumeTrue("set MISURA_TOKEN and PROJECT", token.isNotBlank() && !System.getenv("PROJECT").isNullOrBlank())
        val id = agent.start("best bibimbap in Kreuzberg", TurnOrigin.OWNER)
        val first = settle(id)
        println("1) ${first.status} tools=${toolsUsed(id)} places=${first.places} say=${first.say}")
        first.steps.forEach { println("   step: ${it.state} ${it.label} → ${it.result}") }
        val places = first.places ?: error("no places shown: ${first.say}")
        check(places.placeIds.size >= 3) { "expected ≥ 3 places, got ${places.placeIds}" }
        check(places.pick in places.placeIds) { "pick ${places.pick} not among ${places.placeIds}" }
        check(first.steps.any { it.state == StepState.DONE && it.result?.contains("found") == true }) { "no done search step: ${first.steps}" }

        val pick = placeFacts(store.turns(id))[places.pick]!!
        val searchesBefore = toolsUsed(id).count { it == "find_places" }
        agent.followUp(id, "is it open on Friday?", lookingAt = pick.looking()).join()
        val second = settle(id)
        val again = toolsUsed(id).count { it == "find_places" } - searchesBefore
        println("2) ${second.status} searches=$again say=${second.say} (looking at ${pick.label})")
        check(again == 0) { "follow-up searched again $again× (hours were already in the first result)" }
        check(second.say is Say.Answer) { "expected an answer, got ${second.say}" }
        check(second.places == first.places) { "follow-up answer replaced the place cards" }
    }

    @Test fun plainQuestion() = runBlocking<Unit> {
        assumeTrue("set MISURA_TOKEN and PROJECT", token.isNotBlank() && !System.getenv("PROJECT").isNullOrBlank())
        val id = agent.start("what time does Markthalle Neun close on Saturday?", TurnOrigin.OWNER)
        val s = settle(id)
        println("6) ${s.status} tools=${toolsUsed(id)} say=${s.say}")
        check(s.status == SessionStatus.IDLE) { "status ${s.status}" }
        check(time.containsMatchIn((s.say as? Say.Answer)?.line.orEmpty())) { "no time in ${s.say}" }
    }
}
