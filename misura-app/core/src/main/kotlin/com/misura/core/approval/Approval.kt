package com.misura.core.approval

import com.misura.core.task.Payload
import kotlinx.serialization.Serializable

const val APPROVAL_TTL_MS = 10 * 60 * 1000L

/** Only a button tap can approve. Voice prepares, it never commits (spec §4.3, §8). */
enum class ApprovalSource { BUTTON }

/** Permission to commit exactly one payload once. */
@Serializable
data class Approval(
    val id: String,
    val taskId: String,
    val payloadDigest: String,
    val grantedAt: Long,
    val expiresAt: Long,
    val source: ApprovalSource,
    val consumedByAttempt: String? = null,
)

fun Approval.covers(taskId: String, payload: Payload, now: Long): Boolean =
    consumedByAttempt == null && this.taskId == taskId && now <= expiresAt && payloadDigest == payload.digest()
