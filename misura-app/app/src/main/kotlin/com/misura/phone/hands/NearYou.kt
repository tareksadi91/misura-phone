package com.misura.phone.hands

/**
 * The "you" dot on a near-me map (owner 26 Nov: "near me" starts at my location): the fix the session's last near run used, kept in memory
 * only, with the time it was kept and the ids of the things that run found. Never in the session, an item, the tool's result, a log line or
 * a model turn; gone when Misura's process ends, when the same session's next near run replaces it, and after [MAX_AGE_MS] (a dot older than
 * that is not "you"). A set of other things (another run's) gets no dot.
 */
object NearYou {
    const val MAX_AGE_MS = 60 * 60_000L
    private class Kept(val at: Pair<Double, Double>, val ids: Set<String>, val nanos: Long)
    private val kept = java.util.concurrent.ConcurrentHashMap<String, Kept>()

    /** A near run of [session] ended with [ids] found at fix [at]; no fix or nothing found drops what the session had. */
    fun keep(session: String, at: Pair<Double, Double>?, ids: List<String>, nanos: Long = System.nanoTime()) {
        if (at == null || ids.isEmpty()) kept.remove(session) else kept[session] = Kept(at, ids.toSet(), nanos)
    }

    /** Where to draw "you" for [session]'s set [ids]: only the run that found exactly these things, kept within the last hour. */
    fun at(session: String, ids: Collection<String>, nanos: Long = System.nanoTime()): Pair<Double, Double>? {
        val k = kept[session] ?: return null
        if (nanos - k.nanos !in 0..MAX_AGE_MS * 1_000_000) { kept.remove(session, k); return null }
        return k.at.takeIf { ids.isNotEmpty() && k.ids.containsAll(ids) }
    }
}
