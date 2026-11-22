package com.misura.core.exec

import com.misura.core.task.Payload

/** What a hand reports. Only [Unavailable] and Failed(dispatched=false) promise nothing was sent. */
sealed interface HandResult {
    data class Prepared(val payload: Payload) : HandResult
    data class Ok(val evidence: String) : HandResult
    data class Uncertain(val reason: String) : HandResult
    data class Failed(val reason: String, val dispatched: Boolean) : HandResult
    data class Unavailable(val reason: String) : HandResult
}

/** One way of acting on the world (notification reply, hidden screen, ...). */
interface Hand {
    val id: String
    /** Which channel this hand can send on, matched against payload["channel"]. */
    val channel: String
    /** Hands sharing a lock never run at the same time ("hidden_screen" for every hidden-screen hand). */
    val lock: String?
    suspend fun prepare(p: Payload): HandResult
    suspend fun commit(attemptId: String, p: Payload): HandResult
    suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long): HandResult
    suspend fun cancel(attemptId: String): HandResult
}
