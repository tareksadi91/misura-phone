package com.misura.core.inbox

import kotlinx.serialization.Serializable

/** One incoming or outgoing chat message, as read from a notification or a screen. Untrusted. */
@Serializable
data class Msg(
    /** Stable id: notification key + message timestamp + sender. */
    val id: String,
    val app: String,
    val conversationKey: String,
    val sender: String,
    val senderIsSelf: Boolean,
    val isGroup: Boolean,
    val text: String,
    val sentAt: Long,
)

/** A running task waiting for a reply in one conversation. */
@Serializable
data class Expectation(
    val taskId: String,
    val requestId: String,
    val app: String,
    val conversationKey: String,
    val sender: String?,
    val notBefore: Long,
    val until: Long,
)

interface InboxStore {
    /** False if an open expectation already exists for the same app + conversation. */
    suspend fun addExpectation(e: Expectation): Boolean
    suspend fun openExpectations(now: Long): List<Expectation>
    suspend fun closeExpectation(requestId: String)
    /** True if the message is new (and marks it seen). */
    suspend fun markSeen(msgId: String): Boolean
    suspend fun appendAnswer(requestId: String, m: Msg)
    suspend fun answer(requestId: String): List<Msg>
}
