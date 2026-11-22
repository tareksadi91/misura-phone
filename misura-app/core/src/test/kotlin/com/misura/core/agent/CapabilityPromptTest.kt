package com.misura.core.agent

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CapabilityPromptTest {
    @Test fun missingGoogleUsesInstalledMapsAndKeepsNativeLookupFirst() {
        val prompt = systemPrompt(null, setOf("find_places", "show_places", "directions"))
        prompt.contains("call show_places") shouldBe false
        prompt.contains("broad find_places search") shouldBe false
        prompt.contains("call directions with") shouldBe false
        prompt.contains("installed Maps") shouldBe true
        prompt.contains("call look_up once") shouldBe true
        prompt.contains("needs_browser") shouldBe true
        prompt.contains("Never open a browser") shouldBe false
        prompt.contains("lookup_id") shouldBe true
        prompt.contains("currently available find_places") shouldBe false
    }

    @Test fun configuredGoogleKeepsDirectToolsAndConditionalBrowserFallback() {
        val prompt = systemPrompt("Fixture briefing")
        prompt.contains("call show_places") shouldBe true
        prompt.contains("broad find_places search") shouldBe true
        prompt.contains("call directions with") shouldBe true
        prompt.contains("needs_browser") shouldBe true
        prompt.contains("Never open a browser") shouldBe false
        prompt.contains("use find_places rather than look_up") shouldBe true
        prompt.contains("old failure does not describe this turn's access") shouldBe true
        prompt.contains("Never invent place IDs") shouldBe true
        prompt.endsWith("Fixture briefing") shouldBe true
    }
}
