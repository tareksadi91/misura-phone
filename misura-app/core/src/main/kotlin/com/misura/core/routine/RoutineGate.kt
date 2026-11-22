package com.misura.core.routine

import java.security.MessageDigest
import java.util.UUID

/** Every field a saved routine runs on, so the Save tap approves exactly what the card showed. */
fun Routine.digest(): String {
    val t = trigger as Trigger.Time
    val fields = listOf(title, "${t.hour}:${t.minute}", t.days.sorted().joinToString(","), prompt, conditions.toString())
    val bytes = fields.joinToString("") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/**
 * Saving, removing or running a routine waits for the user's tap on its card. The tool's call only asks; the tap itself
 * does exactly what the card showed (it does not depend on the model calling again). Only the card callback calls
 * [answered]; tool arguments can never grant it. A later call for the same thing in the same session gets the earlier
 * outcome instead of a second card. In memory only: a restart forgets an unanswered card.
 */
class RoutineGate(private val clock: () -> Long = System::currentTimeMillis, private val validMs: Long = 5 * 60_000) {
    sealed interface Result {
        data class Ask(val id: String) : Result
        /** The tap already did it; [summary] says what. */
        data class Done(val summary: String) : Result
    }
    private class Pending(val id: String, val key: String, val expires: Long, val go: () -> String)
    private val pending = mutableListOf<Pending>()
    private val done = mutableMapOf<String, Pair<Long, String>>()

    /** The card's first choice (Save / Remove / Run) does the thing now; any other answer drops it. */
    @Synchronized fun answered(id: String, yes: Boolean) {
        prune()
        val p = pending.firstOrNull { it.id == id }
        if (p == null) { if (yes) outcomes[id] = "Not done: the card had expired or was replaced. Ask again."; return }
        pending.remove(p)
        if (!yes) { outcomes[id] = "Not done: the user said no."; return }
        val result = runCatching { p.go() }.getOrElse { "Not done: it failed (${it.javaClass.simpleName})." }
        done[p.key] = clock() to result
        outcomes[id] = result
    }

    /** What the tap on card [id] actually did, for the model's next turn (Codex P2: never let it guess "Saved"). */
    @Synchronized fun outcome(id: String): String? = outcomes.remove(id)
    private val outcomes = mutableMapOf<String, String>()

    @Synchronized fun revoke() { pending.clear() }

    /** [routine] is stored by [store] when the user taps Save on the card. */
    @Synchronized fun save(routine: Routine, session: String?, store: (Routine) -> String): Result = ask("save:${routine.digest()}", session) { store(routine) }

    /** Removing or running a stored routine. */
    @Synchronized fun act(what: String, routineId: String, session: String?, action: () -> String): Result = ask("$what:$routineId", session, action)

    private fun ask(what: String, session: String?, go: () -> String): Result {
        prune()
        val key = "$session|$what"
        done[key]?.let { return Result.Done(it.second) }
        pending.removeAll { it.key.startsWith("$session|") } // one open card per session: the newest
        val id = "rt:${UUID.randomUUID()}"
        pending += Pending(id, key, clock() + validMs, go)
        return Result.Ask(id)
    }

    private fun prune() {
        val now = clock()
        pending.removeAll { it.expires <= now }
        done.entries.removeAll { it.value.first + validMs <= now }
    }
}
