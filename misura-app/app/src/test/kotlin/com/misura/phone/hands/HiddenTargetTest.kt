package com.misura.phone.hands

import com.misura.core.task.Payload
import io.kotest.matchers.shouldBe
import org.junit.Test

class HiddenTargetTest {
    private fun p(vararg kv: Pair<String, String>) = Payload(mapOf(*kv))

    @Test fun aNameKeyOpensByName() { hiddenTarget(p("recipient" to "com.misura.testapp:ContactA", "name" to "ContactA"), "com.misura.testapp") shouldBe "ContactA" }
    @Test fun aRealChatIdCantBeOpenedByName() { hiddenTarget(p("recipient" to "4917@s.whatsapp.net", "name" to "ContactA"), "com.misura.testapp") shouldBe null }
    @Test fun aPlainNameStillWorks() { hiddenTarget(p("recipient" to "ContactA"), "com.misura.testapp") shouldBe "ContactA" }
}
