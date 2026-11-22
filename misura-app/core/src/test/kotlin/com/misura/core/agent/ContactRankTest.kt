package com.misura.core.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ContactRankTest {
    @Test fun exactThenPrefixThenContainsThenUnrelated() {
        val names = listOf("Test Other", "Test ContactA", "ContactA Test", "ContactA")
        names.sortedBy { contactRank(it, " cOnTaCtA ") } shouldBe
            listOf("ContactA", "ContactA Test", "Test ContactA", "Test Other")
        names.map { contactRank(it, "ContactA") } shouldBe listOf(3, 2, 1, 0)
    }

    @Test fun blankQueryMatchesNobody() {
        contactRank("Fixture", "  ") shouldBe 3
        contactRank("", "") shouldBe 3
    }
}
