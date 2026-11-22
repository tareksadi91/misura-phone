package com.misura.phone.notify

import com.misura.core.agent.Items
import com.misura.core.agent.PICK_NOTE
import com.misura.core.agent.Places
import com.misura.core.agent.SENT_NOTE
import com.misura.core.agent.Say
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Turn
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SessionAlertsTest {
    private fun s(say: Say? = null, places: Places? = null, items: Items? = null) = Session("s1", SessionStatus.IDLE, 3, 0, places = places, say = say, items = items)
    private fun user(vararg parts: String) = Turn("user", buildJsonObject { put("role", "user"); put("parts", buildJsonArray { parts.forEach { p -> add(buildJsonObject { put("text", p) }) } }) })

    @Test fun `an answer is a result titled with what was asked`() {
        alertFor(s(Say.Answer("Hornbach closes at 20:00.")), "when does hornbach close") shouldBe Alert.Result("when does hornbach close", "Hornbach closes at 20:00.")
    }

    @Test fun `cards without a line say how many`() {
        alertFor(s(places = Places(listOf("a", "b", "c"))), "ramen") shouldBe Alert.Result("ramen", "Found 3 places")
        alertFor(s(places = Places(listOf("a"))), "ramen") shouldBe Alert.Result("ramen", "Found 1 place")
        alertFor(s(items = Items(listOf("a", "b"))), "flights") shouldBe Alert.Result("flights", "Found 2 items")
    }

    @Test fun `a finished list says sent`() {
        alertFor(s(Say.Approve("t1", "Lena", "WhatsApp")), "tell Lena", allSent = true) shouldBe Alert.Result("tell Lena", "Sent.")
    }

    @Test fun `a waiting draft needs your ok and never shows the words`() {
        alertFor(s(Say.Approve("t1", "Lena", "WhatsApp")), "x") shouldBe Alert.NeedsOk("Message to Lena")
        alertFor(s(Say.Approve("t1", "Lena", "WhatsApp", also = listOf(Say.Also("t2", "Ana")))), "x") shouldBe Alert.NeedsOk("Messages to 2 people")
    }

    @Test fun `a tool's stopped tap is an approval showing its label, not its text`() {
        alertFor(s(Say.Question("I'll be there at 8", listOf("Send", "Don't"), gate = "g1", label = "WhatsApp · Lena")), "x") shouldBe Alert.NeedsOk("WhatsApp · Lena")
        alertFor(s(Say.Question("secret words", gate = "g1")), "x") shouldBe Alert.NeedsOk("A step is waiting for your OK")
    }

    @Test fun `the model's question needs your input`() {
        alertFor(s(Say.Question("Tuesday or Thursday?", listOf("Tue", "Thu"))), "x") shouldBe Alert.NeedsInput("Tuesday or Thursday?")
    }

    @Test fun `problems, empty results and blank answers say nothing`() {
        alertFor(s(Say.Problem("failed")), "x") shouldBe null
        alertFor(s(), "x") shouldBe null
        alertFor(s(places = Places(emptyList())), "x") shouldBe null
        alertFor(s(Say.Answer("  ")), "x") shouldBe null
    }

    @Test fun `no request falls back to the app name`() {
        alertFor(s(Say.Answer("ok")), null) shouldBe Alert.Result("Misura", "ok")
        alertFor(s(Say.Answer("ok")), " ") shouldBe Alert.Result("Misura", "ok")
    }

    @Test fun `the title is the latest words, not a choice tap or sent note`() {
        lastAsked(listOf(user("find cafés"), user("Kreuzberg", PICK_NOTE))) shouldBe "find cafés"
        lastAsked(listOf(user("tell Lena"), user("went out", SENT_NOTE))) shouldBe "tell Lena"
        lastAsked(listOf(user("first"), user("second"))) shouldBe "second"
        lastAsked(emptyList()) shouldBe null
    }
}
