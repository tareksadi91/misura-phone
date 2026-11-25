package com.misura.phone.hands

/** One thing a near-me app run recorded, to look up on the map: its id, its title as the app showed it, the distance the app showed (metres). */
data class PinAsk(val id: String, val title: String, val meters: Double)

/** What the map lookup says for one thing: a certain pin (the venue's coordinates, nothing else from the map) or why there is none. */
sealed interface PinFound {
    data class Pin(val lat: Double, val lng: Double) : PinFound
    /** [why] = "shared" (more than one place with that name at that distance) or "none" (no place with that name at that distance). */
    data class NoPin(val why: String) : PinFound
}

/**
 * Certain map pins for things a near-me app run found (owner list item 7). Each answer goes to [got] as it arrives, so the caller's time
 * limit keeps the ones already in. Implementations read the phone's fix themselves and never hand it back: only venue pins leave.
 */
fun interface ItemPins {
    suspend fun find(asks: List<PinAsk>, city: String?, got: (String, PinFound) -> Unit)
}
