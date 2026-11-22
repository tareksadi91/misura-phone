package com.misura.core.inbox

class InMemoryInboxStore : InboxStore {
    val expectations = mutableListOf<Expectation>()
    private val closed = mutableSetOf<String>()
    private val seen = mutableSetOf<String>()
    val answers = mutableMapOf<String, MutableList<Msg>>()
    var writes = 0

    override suspend fun addExpectation(e: Expectation): Boolean {
        writes++
        if (expectations.any { it.requestId !in closed && it.app == e.app && it.conversationKey == e.conversationKey && it.until >= e.notBefore }) return false
        expectations += e; return true
    }
    override suspend fun openExpectations(now: Long) = expectations.filter { it.requestId !in closed && it.until >= now }
    override suspend fun closeExpectation(requestId: String) { writes++; closed += requestId }
    override suspend fun markSeen(msgId: String): Boolean { writes++; return seen.add(msgId) }
    override suspend fun appendAnswer(requestId: String, m: Msg) { writes++; answers.getOrPut(requestId) { mutableListOf() } += m }
    override suspend fun answer(requestId: String): List<Msg> = answers[requestId].orEmpty()
}
