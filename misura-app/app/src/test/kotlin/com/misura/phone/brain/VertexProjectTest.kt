package com.misura.phone.brain

import com.misura.core.agent.BrainError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.Test

class VertexProjectTest {
    @Test fun keyProjectThenPrefsOrAuthError() {
        vertexProject("""{"project_id":"key-project"}""", "prefs-project") shouldBe "key-project"
        for (key in listOf(null, "not json", "{}", """{"project_id":" "}""", """{"project_id":null}""")) {
            vertexProject(key, "prefs-project") shouldBe "prefs-project"
            for (fallback in listOf(null, "", " ")) {
                val e = shouldThrow<BrainError> { vertexProject(key, fallback) }
                e.auth shouldBe true
                e.code shouldBe "project"
            }
        }
    }
}
