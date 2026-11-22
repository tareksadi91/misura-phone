package com.misura.phone.screen

import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.misura.core.agent.Say
import com.misura.core.agent.Step
import com.misura.core.agent.StepState
import com.misura.phone.cards.MisuraSurface
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

class ScreenPiecesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun longApprovalKeepsAllContentReachable() {
        val exact = "Please read every word before approving. ".repeat(22)
        var sent = false
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MisuraSurface { Box(Modifier.fillMaxSize()) {
                    ApproveCard("Sample chat", listOf("FriendA" to exact, "FriendB" to "Different exact message."), false, { sent = true }, {})
                } }
            }
        }
        rule.onNodeWithText("“$exact”", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("FriendB", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("“Different exact message.”", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Send 2").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { sent shouldBe true }
    }

    @Test fun longRecoveryActionStaysFullyVisibleAtLargeFont() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MisuraSurface { Box(Modifier.width(320.dp)) { ProblemCard(Say.Problem("Check the chat first."), "Prepare new draft") {} } }
            }
        }
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        rule.onNodeWithText("Prepare new draft", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        layout.getLineEnd(layout.lineCount - 1, visibleEnd = true) shouldBe "Prepare new draft".length
        for (line in 0 until layout.lineCount) {
            org.junit.Assert.assertTrue("Recovery text exceeds its visible width", layout.getLineRight(line) <= layout.size.width + 1f)
            org.junit.Assert.assertTrue("Recovery text exceeds its visible height", layout.getLineBottom(line) <= layout.size.height + 1f)
        }
    }

    @Test fun longAppGateKeepsExactTextAndActionsReachable() {
        val exact = "Check these exact words before sending. ".repeat(22)
        var choice = -1
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MisuraSurface { QuestionCard(Say.Question(exact, listOf("Send", "Don't"), "synthetic-gate", "Sample chat · FriendA")) { choice = it } }
            }
        }
        rule.onNodeWithText(exact, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Send").performScrollTo().assertIsDisplayed().performClick()
        rule.runOnIdle { choice shouldBe 0 }
    }
    private fun step(state: StepState, result: String? = null) = Step("a", 1, "Searching bibimbap", state, result, startedAt = 0)
    private fun steps(vararg s: Step, now: Long = 0) = rule.setContent { MisuraSurface { Steps(s.toList(), now) } }

    @Test fun currentAttributionContainsAnAccessibleInlineLink() = assertCitation(historical = false)
    @Test fun historicalAttributionContainsAnAccessibleInlineLink() = assertCitation(historical = true)

    private fun assertCitation(historical: Boolean) {
        val cited = Say.Answer("A fact.", listOf(com.misura.core.agent.Citation("Original report", "https://example.org/report", 0, 6)), "OpenAI")
        rule.setContent {
            MisuraSurface {
                if (historical) Past("Question", emptyList(), cited.line, 0, cited = cited)
                else AnswerText(cited.line, cited = cited)
            }
        }
        val annotated = rule.onNodeWithText("Original report", substring = true).fetchSemanticsNode()
            .config[SemanticsProperties.Text].single()
        val links = annotated.getLinkAnnotations(0, annotated.length)
        (links.single().item as androidx.compose.ui.text.LinkAnnotation.Url).url shouldBe "https://example.org/report"
        annotated.text.substring(links.single().start, links.single().end) shouldBe "[Original report]"
    }

    @Test fun stepsAreAPoliteLiveRegion() {
        steps(step(StepState.RUNNING))
        rule.onNodeWithTag(STEPS_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun runningStepShowsElapsedAfterFiveSeconds() {
        steps(step(StepState.RUNNING), now = 12_000)
        rule.onNodeWithText("Searching bibimbap", substring = true).assertExists()
        rule.onNodeWithText("12 s", substring = true).assertExists()
    }

    @Test fun runningStepHidesElapsedBeforeFiveSeconds() {
        steps(step(StepState.RUNNING), now = 3_000)
        rule.onNodeWithText("3 s", substring = true).assertDoesNotExist()
    }

    @Test fun doneStepShowsCheckAndResult() {
        steps(step(StepState.DONE, "6 places found"))
        rule.onNodeWithText("6 places found", substring = true).assertExists()
        rule.onNodeWithText("✓", substring = true).assertExists()
    }

    @Test fun failedStepShowsCrossAndResult() {
        steps(step(StepState.FAILED, "quota exceeded"))
        rule.onNodeWithText("quota exceeded", substring = true).assertExists()
        rule.onNodeWithText("✕", substring = true).assertExists()
    }

    @Test fun stepRowsHaveTalkBackText() {
        steps(step(StepState.DONE, "6 places found"))
        rule.onNodeWithContentDescription("Searching bibimbap, done, 6 places found").assertExists()
    }

    @Test fun tappingAStepOpensTheHistory() {
        var opened = 0
        rule.setContent { MisuraSurface { Steps(listOf(step(StepState.DONE, "6 places found")), 0, onOpen = { opened++ }) } }
        rule.onNodeWithContentDescription("Searching bibimbap, done, 6 places found")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick)).performClick()
        rule.runOnIdle { opened shouldBe 1 }
    }

    @Test fun stepsWithoutOnOpenAreNotTappable() {
        steps(step(StepState.DONE, "6 places found"))
        rule.onNodeWithContentDescription("Searching bibimbap, done, 6 places found").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test fun appMarkComesFromTheInstalledAppOrIsNull() {
        val ctx = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        (appMark(ctx, "com.google.android.apps.maps", 48) != null) shouldBe true // Maps ships a themed-icon layer
        appMark(ctx, "com.misura.testapp", 48) shouldBe null // no layer: the step keeps its line icon
        appMark(ctx, "not.installed.app", 48) shouldBe null
    }

    @Test fun carouselCardsAreTheSameHeightWhateverTheirContent() {
        val short = com.misura.core.agent.PlaceFact("a", "Ixthys", why = "Tiny.", rating = 4.5, photos = emptyList())
        val long = com.misura.core.agent.PlaceFact("b", "REI Izakaya Bar & Restaurant Kreuzberg", why = "A calm, low-key izakaya serving refined Japanese small plates and great sake, with a long quiet counter at the back.",
            kind = "Izakaya Restaurant", price = 3, rating = 4.7, ratingCount = 334, photos = listOf(com.misura.core.agent.Photo("p1", "REI")))
        val noPhotoYet: PhotoLoader = { _, _ -> null }
        rule.setContent { MisuraSurface { androidx.compose.foundation.layout.Row {
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).testTag("s")) { PlaceCard(short, false, noPhotoYet) {} }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.weight(1f).testTag("l")) { PlaceCard(long, true, noPhotoYet) {} }
        } } }
        rule.onNodeWithTag("s").fetchSemanticsNode().size.height shouldBe rule.onNodeWithTag("l").fetchSemanticsNode().size.height
    }

    @Test fun navBackIs48dpAndTaps() {
        var backs = 0
        rule.setContent { MisuraSurface { NavLine("6 places", "Namsan", 3, onBack = { backs++ }, onSteps = {}) } }
        rule.onNodeWithText("‹ 6 places").assertHeightIsAtLeast(48.dp).performClick()
        rule.runOnIdle { backs shouldBe 1 }
        rule.onNodeWithText("3 steps ›", substring = true).assertExists()
    }

    @Test fun questionChoiceTapReportsIndex() {
        var picked = -1
        rule.setContent { MisuraSurface { QuestionCard(Say.Question("Which area?", listOf("Kreuzberg", "Mitte"))) { picked = it } } }
        rule.onNodeWithText("Which area?").assertExists()
        rule.onNodeWithText("Mitte").performClick()
        rule.runOnIdle { picked shouldBe 1 }
    }

    @Test fun problemCardOffersTryAgain() {
        var retried = false
        rule.setContent { MisuraSurface { ProblemCard(Say.Problem("Misura can't reach the model (503). Nothing was sent.")) { retried = true } } }
        rule.onNodeWithText("Try again").performClick()
        rule.runOnIdle { retried shouldBe true }
    }
}
