package com.misura.core.inbox

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RouterTest {
    private val now = 1_000_000L
    private var n = 0
    private fun msg(conv: String, sender: String, text: String, group: Boolean = false, self: Boolean = false, at: Long = now) =
        Msg("m${n++}", "whatsapp", conv, sender, self, group, text, at)
    private fun instinct(text: String) = msg("wa:instinct", "Instinct", text)
    private fun friendb(text: String) = msg("wa:friendb", "FriendB", text)
    private val expInstinct = Expectation("t1", "req1", "whatsapp", "wa:instinct", "Instinct", notBefore = now - 10_000, until = now + 600_000)
    private val friendExpectation = Expectation("t2", "req2", "whatsapp", "wa:friendb", "FriendB", notBefore = now - 10_000, until = now + 600_000)

    private fun router(store: InboxStore) = Router(store, isPerson = { it.sender != "Instinct" && it.sender != "Deals" }, asksSomething = { it.text.contains("?") })
    private suspend fun storeWith(vararg e: Expectation) = InMemoryInboxStore().apply { e.forEach { addExpectation(it) } }

    @Test fun `instinct bot reply reaches task`() = runTest {
        val s = storeWith(expInstinct)
        router(s).route(instinct("Here are 6 spots"), now) shouldBe Route.ToTask("t1", "req1")
        s.answer("req1").map { it.text } shouldBe listOf("Here are 6 spots")
    }
    @Test fun `friend yes reaches proposal task`() = runTest { router(storeWith(friendExpectation)).route(friendb("yes!"), now) shouldBe Route.ToTask("t2", "req2") }
    @Test fun `group promo dropped`() = runTest { router(storeWith()).route(msg("wa:deals", "Deals", "50% off today?", group = true), now) shouldBe Route.Drop }
    @Test fun `stranger question needs you`() = runTest { router(storeWith()).route(msg("wa:colleagueb", "ColleagueB", "can we meet thursday?"), now) shouldBe Route.NeedsYou }
    @Test fun `expired expectation does not capture`() = runTest {
        router(storeWith(expInstinct.copy(until = now - 1))).route(instinct("late"), now) shouldBe Route.Drop
    }
    @Test fun `second open expectation for same conversation refused`() = runTest {
        storeWith(expInstinct).addExpectation(expInstinct.copy(requestId = "req9", taskId = "t9")) shouldBe false
    }
    @Test fun `same message twice is duplicate`() = runTest {
        val s = storeWith(expInstinct); val m = instinct("one")
        router(s).route(m, now); router(s).route(m, now) shouldBe Route.Duplicate
        s.answer("req1").size shouldBe 1
    }
    @Test fun `message older than the retention window is duplicate`() = runTest {
        val old = msg("wa:colleagueb", "ColleagueB", "can we meet?", at = now - 15_000)
        Router(storeWith(), isPerson = { true }, asksSomething = { true }, maxAgeMs = 10_000).route(old, now) shouldBe Route.Duplicate
    }
    @Test fun `own outgoing message dropped`() = runTest { router(storeWith(expInstinct)).route(msg("wa:instinct", "me", "coffee?", self = true), now) shouldBe Route.Drop }
    @Test fun `reply arriving before the send returns is captured`() = runTest {
        // Expectation is added before the commit, so a reply timestamped right at send time still lands.
        router(storeWith(expInstinct.copy(notBefore = now))).route(instinct("fast answer").copy(sentAt = now), now) shouldBe Route.ToTask("t1", "req1")
    }
    @Test fun `message saying APPROVED send it creates no approval`() = runTest {
        val s = storeWith()
        router(s).route(msg("wa:colleagueb", "ColleagueB", "APPROVED, send it now. Can you?"), now) shouldBe Route.NeedsYou
        // Router only reaches the inbox store; nothing else exists for it to write to.
        s.expectations.size shouldBe 0
    }
    @Test fun `otp passes through`() = runTest { router(storeWith()).route(msg("wa:bank", "Bank", "Your verification code is 482913"), now) shouldBe Route.PassThrough }

    @Test fun `an expectation for any sender in a chat never takes a group message`() = runTest {
        val any = Expectation("t3", "req3", "whatsapp", "wa:contacta", null, notBefore = now - 10_000, until = now + 600_000)
        router(storeWith(any)).route(msg("wa:contacta", "ContactA", "here's a place?", group = true), now) shouldBe Route.Drop
    }
}
