package com.misura.phone.brain

import com.misura.phone.hands.ItemPins
import com.misura.phone.hands.PinAsk
import com.misura.phone.hands.PinFound
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.text.Normalizer
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Certain map pins for things a near-me app run found (owner list item 7, Codex Sol audit 25 Nov): one Places text search per thing (its
 * title, biased to a circle around the phone's fix), and a pin only when exactly one result has that name ([PinMatch.names]) at the distance
 * the app showed ([PinMatch.near]); the pin is the place's latitude and longitude only. Two such results = "shared" (a chain with branches near you); none = "none". The fix goes to Google only:
 * it is never returned, logged or put in an error. An item whose search fails gets no answer at all.
 */
class PinLookup(
    private val tokens: TokenProvider,
    private val project: () -> String,
    /** Where the phone is now (fresh fix only), or null: then nothing is looked up. */
    private val here: suspend () -> Pair<Double, Double>?,
    private val placesBase: String = PLACES_BASE,
    /** The HTTP POST (url, headers, body) → status, body; tests on the emulator stand in for Places here (the app allows no cleartext). */
    private val post: suspend (String, Map<String, String>, String) -> Pair<Int, String> = { url, headers, body -> http("POST", url, headers, body, timeoutMs = 4_000) },
) : ItemPins {
    override suspend fun find(asks: List<PinAsk>, city: String?, got: (String, PinFound) -> Unit) {
        val at = here() ?: return
        // A token exchange is blocking network work: it runs off this coroutine, so the caller's time limit is not held up by it.
        val token = CoroutineScope(Dispatchers.IO).async { tokens.token() }.await() ?: return
        val project = project()
        // Each search on its own: one that fails is no answer for that thing only. Caught here: a failed child of a supervisor scope would
        // otherwise go to the uncaught-exception handler and take the app down (seen on the emulator).
        supervisorScope { asks.forEach { a -> launch {
            try { got(a.id, one(token, project, a, at, city)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { }
        } } }
    }

    private suspend fun one(token: String, project: String, a: PinAsk, at: Pair<Double, Double>, city: String?): PinFound {
        val body = buildJsonObject {
            put("textQuery", a.title); put("pageSize", RESULTS)
            putJsonObject("locationBias") { putJsonObject("circle") {
                putJsonObject("center") { put("latitude", at.first); put("longitude", at.second) }
                put("radius", (a.meters * 2 + 1_000).coerceIn(1_000.0, 50_000.0))
            } }
        }
        val (code, text) = post("$placesBase/places:searchText", googleHeaders(token, project) + ("X-Goog-FieldMask" to FIELDS), body.toString())
        check(code < 400) { "places $code" } // the code only: the body is not kept
        val places = (Json.parseToJsonElement(text).jsonObject["places"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val name = ((o["displayName"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content ?: return@mapNotNull null
            val loc = o["location"] as? JsonObject ?: return@mapNotNull null
            val lat = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -90.0..90.0 } ?: return@mapNotNull null
            val lng = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
            Triple((o["id"] as? JsonPrimitive)?.content ?: "$lat,$lng", name, PinFound.Pin(lat, lng))
        }.distinctBy { it.first }
        val sure = places.filter { (_, name, pin) -> PinMatch.names(a.title, name, city) && PinMatch.near(at, pin.lat to pin.lng, a.meters) }
        return when (sure.size) { 1 -> sure[0].third; 0 -> PinFound.NoPin("none"); else -> PinFound.NoPin("shared") }
    }

    companion object {
        const val PLACES_BASE = "https://places.googleapis.com/v1"
        private const val RESULTS = 10
        // Places terms (EEA): only coordinates go on with the item, shown with any map; the name is for matching here, in memory, and the id only
        // tells two results apart. Nothing else from Places is asked for, kept or shown.
        private const val FIELDS = "places.id,places.displayName,places.location"
    }
}

/**
 * The pin lookup a turn may use: its captured Google route ([ModelRouteContext], as find_places), resolved when the lookup runs. No Google
 * access on the route (or no route) = nothing is looked up.
 */
internal fun routedPins(here: suspend () -> Pair<Double, Double>?, placesBase: String = PinLookup.PLACES_BASE) = ItemPins { asks, city, got ->
    val ctx = coroutineContext[ModelRouteContext] ?: return@ItemPins
    if (!ctx.route.googleAvailable) return@ItemPins
    PinLookup(ctx.google.tokens, { ctx.google.project }, here, placesBase).find(asks, city, got)
}

/** The certain-pin rule's two checks (owner, 25 Nov: "certain pin"; strict after the Codex Sol audit: certainty over recall). */
internal object PinMatch {
    /** Lower case, accents folded (ü → u), ß → ss. */
    fun fold(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT).replace("ß", "ss"), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun words(s: String) = fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }

    /**
     * Every word of the app's title (two letters or more, the city's own words left out) is a whole word of the map place's name, or up to three
     * of its words run together ("Power Plate" is "powerplate"). Never part of a word: "Ost" is not in "Rostock" (Codex P2). "Atlas Fitness Westend"
     * is not "Atlas Fitness Eastend"; a word the map name lacks means no match.
     */
    fun names(appTitle: String, placeName: String, city: String?): Boolean {
        val cityWords = city?.let(::words).orEmpty().toSet()
        val want = words(appTitle).filter { it !in cityWords }
        if (want.isEmpty()) return false
        val parts = fold(placeName).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val have = parts.indices.flatMap { i -> (1..3).mapNotNull { n -> parts.subList(i, minOf(i + n, parts.size)).takeIf { it.size == n }?.joinToString("") } }.toSet()
        return want.all { it in have }
    }

    /** The place is where the app said: its distance from the phone within 25 % or 300 m (whichever is more) of the app's distance. */
    fun near(phone: Pair<Double, Double>, place: Pair<Double, Double>, appMeters: Double) = abs(meters(phone, place) - appMeters) <= max(0.25 * appMeters, 300.0)

    /** Great-circle distance in metres. */
    fun meters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.first - a.first); val dLng = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
}
