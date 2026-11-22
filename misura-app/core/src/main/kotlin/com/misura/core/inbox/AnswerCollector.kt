package com.misura.core.inbox

/** An answer spread over several messages is complete after [quietMs] with no new message. Tuned in Task 14. */
class AnswerCollector(val quietMs: Long = 20_000) {
    fun isComplete(lastMsgAt: Long, now: Long): Boolean = now - lastMsgAt >= quietMs
}
