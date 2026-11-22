package com.misura.core.agent

/** Where you are inside one task. Back walks this stack; at the root, back goes home. */
sealed interface Screen {
    data object Root : Screen
    data class Detail(val placeId: String) : Screen
    data object FullMap : Screen
    data object History : Screen
}

/** Immutable so the UI can hold it in Compose state; [index] = the carousel card you're on (kept across detail and back). */
data class Nav(val stack: List<Screen> = listOf(Screen.Root), val index: Int = 0) {
    val top: Screen get() = stack.last()
    fun open(s: Screen) = copy(stack = stack + s)
    /** null = already at the root: go home. */
    fun back(): Nav? = if (stack.size > 1) copy(stack = stack.dropLast(1)) else null
    fun select(i: Int) = copy(index = i)
    /** New places came in: back to the carousel, first card. */
    fun reset() = Nav()
}

/** What a task is to you right now. Home shows only the first three; DONE lives behind "Earlier". */
enum class HomeKind { RUNNING, YOURS, FAILED, DONE }

/**
 * YOURS = it is ready and waits for your go (a draft, a question). [draft] = where the send task of the draft on the card
 * stands (null = not known): only a draft that still needs your tap is YOURS; one on its way out is RUNNING, and one that
 * failed or may or may not have gone out is FAILED, never "ready" (Codex P2).
 */
fun homeKind(s: Session, draft: com.misura.core.task.TaskState? = null): HomeKind = when {
    s.status == SessionStatus.THINKING -> HomeKind.RUNNING
    s.status == SessionStatus.FAILED || s.say is Say.Problem -> HomeKind.FAILED
    s.say is Say.Approve -> when (draft) {
        null, com.misura.core.task.TaskState.PLANNING, com.misura.core.task.TaskState.NEEDS_APPROVAL -> HomeKind.YOURS
        com.misura.core.task.TaskState.EXECUTING, com.misura.core.task.TaskState.CHECKING -> HomeKind.RUNNING
        // Sent, with more on the list: between two things, the next one is on its way.
        com.misura.core.task.TaskState.DONE, com.misura.core.task.TaskState.WAITING -> if (s.say.more) HomeKind.RUNNING else HomeKind.DONE
        else -> HomeKind.FAILED
    }
    s.say is Say.Question -> HomeKind.YOURS
    else -> HomeKind.DONE
}

/**
 * Where a card with several messages stands as a whole, in the terms of one send: something that failed or is unsure wins
 * (it needs you), then anything still going out, then "all sent"; otherwise it still waits for your go.
 */
fun cardState(states: List<com.misura.core.task.TaskState?>): com.misura.core.task.TaskState? {
    val sent = setOf(com.misura.core.task.TaskState.DONE, com.misura.core.task.TaskState.WAITING)
    val going = setOf(com.misura.core.task.TaskState.EXECUTING, com.misura.core.task.TaskState.CHECKING)
    val waiting = setOf(null, com.misura.core.task.TaskState.PLANNING, com.misura.core.task.TaskState.NEEDS_APPROVAL)
    return states.firstOrNull { it !in sent && it !in going && it !in waiting } ?: states.firstOrNull { it in going }
        ?: if (states.isNotEmpty() && states.all { it in sent }) com.misura.core.task.TaskState.DONE
        // Some went out and the rest was never tapped (a tap that only half took): that is not "ready", it needs a look.
        else if (states.any { it in sent }) com.misura.core.task.TaskState.PARTIAL else states.firstOrNull()
}

/** When something last happened on a task: a follow-up on yesterday's task makes it today's again. */
fun lastActive(s: Session): Long = maxOf(s.createdAt, s.steps.maxOfOrNull { it.endedAt ?: it.startedAt } ?: 0L)

/** The few words a task goes by on home: who a waiting message is for, else what you asked. */
fun homeName(s: Session, asked: String): String = (s.say as? Say.Approve)?.let { if (it.also.isEmpty()) "Message to ${it.name}" else "${it.all.size} messages" } ?: asked

/** One plain-text row on home: what you asked + the task's state. [stale] = Places content past 30 days. */
/** [sent] = ids of this task's messages that went out: a sent message reads as sent, not as one still waiting for you. */
fun homeLine(s: Session, asked: String, stale: Boolean = false, sent: Set<String> = emptySet()): String {
    val state = when {
        s.status == SessionStatus.THINKING -> "Working"
        s.status == SessionStatus.FAILED -> "Failed"
        s.say is Say.Approve && sent.containsAll(s.say.all) -> if (sent.size > 1) "${sent.size} messages sent" else "Sent to ${s.say.name}"
        s.say is Say.Approve -> if (s.say.also.isEmpty()) "Message to ${s.say.name}" else "${s.say.all.size} messages"
        s.places != null -> s.places.placeIds.size.let { if (it == 1) "1 place" else "$it places" }
        s.items != null -> s.items.itemIds.size.let { if (it == 1) "1 item" else "$it items" }
        // Old answers can carry old hours: past the 30-day limit they show only that they're old.
        stale && s.say is Say.Answer -> "Older answer"
        s.say != null -> when (val say = s.say) {
            is Say.Answer -> say.line; is Say.Question -> say.line; is Say.Problem -> "Failed"; is Say.Approve -> "Message to ${say.name}"
        }.let { if (it.length > 40) it.take(40).trimEnd() + "…" else it }
        else -> null
    }
    return listOfNotNull(asked, state).joinToString(" · ")
}

/** The nav line's "‹ …" label: what back returns to, or null at the root. */
fun backLabel(s: Session, facts: Map<String, PlaceFact>, nav: Nav): String? {
    val under = nav.stack.getOrNull(nav.stack.size - 2) ?: return null
    return when (under) {
        Screen.Root -> s.places?.placeIds?.size?.let { if (it == 1) "1 place" else "$it places" } ?: s.items?.itemIds?.size?.let { if (it == 1) "1 item" else "$it items" } ?: "Back"
        is Screen.Detail -> facts[under.placeId]?.label ?: "Back"
        Screen.FullMap -> "Map"
        Screen.History -> "Steps"
    }
}

/**
 * Uses the same home freshness rule for aged and explicitly dismissed tasks. Running always shows; failed leaves after an hour
 * (a stale ✕ is noise), except a failed message ([kind] FAILED with a draft on the card: it may not have gone out), which keeps the day.
 */
fun onHome(s: Session, now: Long, kind: HomeKind = homeKind(s)): Boolean {
    val failedMessage = s.say is Say.Approve
    val keeps = if (kind == HomeKind.FAILED && !failedMessage) 3_600_000L else 24L * 3_600_000
    return s.dismissedAt == null && (kind == HomeKind.RUNNING || now - lastActive(s) < keeps)
}

/** Exactly halfway still springs back; velocity never dismisses a short swipe. */
fun pastDismissThreshold(left: Float, width: Float): Boolean = width > 0 && left > width / 2
