package com.misura.phone.cards

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/** §10.2: a draft that changes between touch-down and release is not sent by that tap. */
class CommitTapTest {
    @get:Rule val rule = createComposeRule()
    private val send = "Send to Friend"
    private var content by mutableStateOf("A")
    private val sent = mutableListOf<String>()

    private fun show() = rule.setContent {
        val c = content
        CardView(Card.Approve("Testchat", "Friend", c, "Send to Friend"), commitKey = c, onCommit = { sent += c })
    }

    @Test fun aTapSendsTheDraftOnScreen() {
        show()
        rule.onNodeWithText(send).performClick()
        rule.runOnIdle { sent shouldBe listOf("A") }
    }

    @Test fun aDraftThatChangesDuringAPressIsNotSent() {
        show()
        rule.onNodeWithText(send).performTouchInput { down(center) }
        rule.runOnIdle { content = "B" }
        rule.onNodeWithText(send).performTouchInput { up() }
        rule.runOnIdle { sent shouldBe emptyList() }
    }
}
