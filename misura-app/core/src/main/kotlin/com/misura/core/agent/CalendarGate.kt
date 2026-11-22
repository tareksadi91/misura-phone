package com.misura.core.agent

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** All fields written to the provider are covered, including optional text and timezone. */
data class CalendarCreate(
    val title: String, val start: Instant, val end: Instant, val calendarId: Long,
    val zone: String, val location: String? = null, val description: String? = null,
) {
    fun digest(): String {
        val fields = listOf(title, start.toString(), end.toString(), calendarId.toString(), zone, location, description)
        val bytes = fields.joinToString("") { if (it == null) "-1:" else "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** Only the card callback calls [answered]; tool args cannot grant approval. State never leaves this process. */
class CalendarGate(private val clock: () -> Long = System::currentTimeMillis, private val validMs: Long = 5 * 60_000) {
    sealed interface Result {
        data class Ask(val id: String) : Result
        data class Added(val id: Long) : Result
    }
    private data class Pending(val id: String, val digest: String, val session: String?, val expires: Long, val yes: Boolean = false)
    private val pending = mutableListOf<Pending>()

    @Synchronized fun answered(id: String, yes: Boolean) {
        prune()
        val p = pending.firstOrNull { it.id == id } ?: return
        if (p.yes && yes) return
        pending.remove(p)
        if (yes) pending += p.copy(yes = true, expires = clock() + validMs)
    }

    @Synchronized fun revoke() { pending.clear() }

    @Synchronized fun create(event: CalendarCreate, session: String?, insert: (CalendarCreate) -> Long): Result {
        require(event.title.isNotBlank() && event.start < event.end) { "title and a positive event duration required" }
        prune()
        val digest = event.digest()
        val p = pending.firstOrNull { it.session == session }
        pending.removeAll { it.session == session }
        // Consume before insertion: even a provider error cannot silently retry a possibly completed write.
        if (p != null && p.yes && p.digest == digest) return Result.Added(insert(event))
        val id = "cal:${UUID.randomUUID()}"
        pending += Pending(id, digest, session, clock() + validMs)
        return Result.Ask(id)
    }

    private fun prune() { pending.removeAll { it.expires <= clock() } }
}
