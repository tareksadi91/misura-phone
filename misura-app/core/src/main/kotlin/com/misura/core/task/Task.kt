package com.misura.core.task

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest

enum class TaskState { PLANNING, WAITING, NEEDS_APPROVAL, EXECUTING, CHECKING, DONE, PARTIAL, FAILED, CANCELLED, EXPIRED, OUTCOME_UNKNOWN }

class IllegalTransition(from: TaskState, to: TaskState) : IllegalStateException("$from -> $to not allowed")

/** What a commit acts on. Any commit needs `channel`, `recipient` and `content`. */
@Serializable
data class Payload(val fields: Map<String, String>) {
    operator fun get(key: String): String? = fields[key]

    /** JSON with sorted keys: unambiguous for any value, including newlines and `=`. */
    fun canonical(): String = Json.encodeToString(JsonObject.serializer(), JsonObject(fields.toSortedMap().mapValues { JsonPrimitive(it.value) }))

    fun digest(): String = MessageDigest.getInstance("SHA-256").digest(canonical().toByteArray()).joinToString("") { "%02x".format(it) }
}

@Serializable
data class Event(val at: Long, val from: TaskState, val to: TaskState, val note: String, val evidence: String? = null)

@Serializable
data class Task(
    val id: String,
    val kind: String,
    val state: TaskState,
    val payload: Payload?,
    val history: List<Event>,
    val createdAt: Long,
    val deadline: Long?,
    /** True once the user was buzzed for this task's answer; a task buzzes at most once. */
    val buzzed: Boolean = false,
    /** The problem state the user was last told about; a new problem state is reported again. */
    val reportedAs: TaskState? = null,
)

private val ALLOWED: Map<TaskState, Set<TaskState>> = with(TaskState.entries.associateWith { emptySet<TaskState>() }.toMutableMap()) {
    put(TaskState.PLANNING, setOf(TaskState.WAITING, TaskState.NEEDS_APPROVAL, TaskState.FAILED, TaskState.CANCELLED))
    put(TaskState.WAITING, setOf(TaskState.PLANNING, TaskState.NEEDS_APPROVAL, TaskState.EXPIRED, TaskState.CANCELLED, TaskState.FAILED))
    put(TaskState.NEEDS_APPROVAL, setOf(TaskState.EXECUTING, TaskState.PLANNING, TaskState.CANCELLED, TaskState.EXPIRED))
    // PLANNING only when the hand reports nothing was dispatched.
    put(TaskState.EXECUTING, setOf(TaskState.CHECKING, TaskState.FAILED, TaskState.OUTCOME_UNKNOWN, TaskState.PLANNING))
    put(TaskState.OUTCOME_UNKNOWN, setOf(TaskState.CHECKING, TaskState.FAILED, TaskState.CANCELLED))
    put(TaskState.CHECKING, setOf(TaskState.DONE, TaskState.PARTIAL, TaskState.FAILED, TaskState.OUTCOME_UNKNOWN, TaskState.WAITING, TaskState.NEEDS_APPROVAL, TaskState.PLANNING))
    toMap()
}

fun Task.canGo(to: TaskState): Boolean = to in ALLOWED.getValue(state)

fun Task.transition(to: TaskState, at: Long, note: String, evidence: String? = null): Task {
    if (!canGo(to)) throw IllegalTransition(state, to)
    return copy(state = to, history = history + Event(at, state, to, note, evidence))
}
