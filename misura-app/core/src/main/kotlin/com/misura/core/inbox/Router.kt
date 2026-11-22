package com.misura.core.inbox

sealed interface Route {
    data class ToTask(val taskId: String, val requestId: String) : Route
    data object NeedsYou : Route
    data object Drop : Route
    data object PassThrough : Route
    data object Duplicate : Route
}

private val SYSTEM_APPS = setOf("com.android.dialer", "com.google.android.dialer", "com.android.phone", "com.google.android.deskclock")
private val OTP = Regex("(?i)(code|otp|verification|passcode)\\D{0,20}\\d{4,8}")

/**
 * Decides where an incoming message goes. Replies that belong to a running task go there first,
 * even from bots or groups (spec §4.3). The router only touches the inbox store: message text can
 * never create or satisfy an approval.
 */
class Router(
    private val store: InboxStore,
    private val isPerson: (Msg) -> Boolean,
    private val asksSomething: (Msg) -> Boolean,
    /** Messages older than this are treated as already handled (the store forgets seen ids after the same span). */
    private val maxAgeMs: Long = Long.MAX_VALUE,
) {
    suspend fun route(m: Msg, now: Long): Route {
        if (m.sentAt < now - maxAgeMs) return Route.Duplicate
        if (!store.markSeen(m.id)) return Route.Duplicate
        if (m.senderIsSelf) return Route.Drop
        store.openExpectations(now).firstOrNull { e ->
            // "Anyone in this chat" never includes a group: a group can share a name-derived key with a direct chat.
            e.app == m.app && e.conversationKey == m.conversationKey && (e.sender == m.sender || (e.sender == null && !m.isGroup)) && m.sentAt >= e.notBefore
        }?.let { e -> store.appendAnswer(e.requestId, m); return Route.ToTask(e.taskId, e.requestId) }
        if (m.app in SYSTEM_APPS || OTP.containsMatchIn(m.text)) return Route.PassThrough
        if (m.isGroup || !isPerson(m)) return Route.Drop
        return if (asksSomething(m)) Route.NeedsYou else Route.Drop
    }
}
