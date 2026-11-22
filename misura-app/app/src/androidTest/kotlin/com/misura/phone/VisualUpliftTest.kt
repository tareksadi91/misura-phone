package com.misura.phone

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.*
import com.misura.phone.cards.*
import com.misura.phone.screen.*
import com.misura.phone.setup.CredentialSettings
import com.misura.phone.setup.SetupScreen
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Synthetic native reference captures, reusable before and after the visual uplift. No network tools. */
class VisualUpliftTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx get() = rule.activity
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var db: MisuraDb? = null
    @After fun close() { scope.cancel(); db?.close() }

    private fun shot(name: String) {
        rule.waitForIdle()
        val args = InstrumentationRegistry.getArguments()
        val dir = File(ctx.getExternalFilesDir(null), "visual-uplift/${args.getString("visualStage", "baseline")}").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use {
            val bitmap = if (name == "map") InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                else rule.onRoot().captureToImage().asAndroidBitmap()
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    }

    @Test fun nativeScreensAndAcceptedSheetTransition() {
        ctx.deleteDatabase("visual-uplift.db")
        val store = RoomSessionStore(MisuraDb.open(ctx, "visual-uplift.db").also { db = it })
        val brain = object : Brain {
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = error("Fixture must not call a model")
        }
        val agent = Agent(store, brain, emptyList(), System::currentTimeMillis, scope)
        var screen by mutableStateOf("home")
        var scale by mutableFloatStateOf(1f)
        val place = PlaceFact("P1", "Sample cafe", "Quiet tables near the park.", rating = 4.7, ratingCount = 128, kind = "Cafe")
        val settings = CredentialSettings(ctx.getSharedPreferences("visual-uplift-settings", 0))
        rule.setContent {
            val density = LocalDensity.current
            val direction = androidx.compose.ui.platform.LocalLayoutDirection.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale),
                androidx.compose.ui.platform.LocalLayoutDirection provides if (screen.endsWith("-rtl")) androidx.compose.ui.unit.LayoutDirection.Rtl else direction) {
                key(screen, scale) {
                    if (screen == "home") HomeScreen(agent, store, null, null,
                        apps = List(50) { "Sample app %02d".format(it) to "org.example.visual.app$it" })
                    else if (screen == "settings") SetupScreen(ctx, settings) {}
                    else MisuraSurface {
                        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(M.m), verticalArrangement = Arrangement.spacedBy(M.p)) {
                            NavLine("Home", "Sample task", 2, {}, {})
                            when (screen) {
                                "working" -> Steps(listOf(Step("s", 1, "Finding a quiet table", StepState.RUNNING, null, 0)), 12_000)
                                "approval" -> ApproveCard("Sample chat", "FriendA", "Meet by the park at six?", false, {}, {})
                                "approval-rtl" -> ApproveCard("مثال", listOf("صديق" to "نلتقي قرب الحديقة عند السادسة؟", "同僚" to "午後六時に公園で会いましょう。"), false, {}, {})
                                "long-approval" -> ApproveCard("Sample chat", listOf("FriendA" to "Please read every word. ".repeat(35), "FriendB" to "Different recipient, different exact message."), false, {}, {})
                                "question" -> QuestionCard(Say.Question("Which area?", listOf("North", "South"))) {}
                                "recovery" -> ProblemCard(Say.Problem("Outcome not confirmed. Check the chat before sending again.")) {}
                                "done" -> AnswerText("All set.")
                                "place" -> PlaceDetail(place, null)
                                "map" -> PlaceMap(listOf(place.copy(lat = 52.5, lng = 13.4)), "P1", {}, {}, Modifier.weight(1f))
                                "item" -> ItemCard(ItemFact("I1", "Blue jacket", "€35", "Size M · good condition", app = "Sample shop"), true) {}
                                "docked" -> Docked(place, "Asking about", null, {})
                                "listening" -> Listening(level = { .6f })
                            }
                            Spacer(Modifier.weight(1f))
                            Field("", {}, "Type a request…")
                        }
                    }
                }
            }
        }
        shot("home-empty")
        // Record slow finger-follow, reversal/cancel, full reveal, keyboard and Back without retuning the sheet.
        rule.onRoot().performTouchInput {
            down(Offset(width * .5f, height * .15f))
            moveTo(Offset(width * .5f, height * .4f), 500)
        }
        shot("sheet-mid-drag")
        rule.onRoot().performTouchInput { moveTo(Offset(width * .5f, height * .18f), 400); cancel() }
        shot("sheet-reversal")
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .8f, durationMillis = 1000) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
        shot("sheet-open-keyboard")
        rule.onNodeWithContentDescription("Search apps").performTextInput("49")
        rule.onNodeWithText("Sample app 49").assertIsDisplayed()
        shot("sheet-search")
        Espresso.pressBack()
        rule.waitForIdle()
        Espresso.pressBack()
        rule.waitForIdle()
        runBlocking {
            store.save(Session("sample-failed", SessionStatus.FAILED, 0, System.currentTimeMillis(), say = Say.Problem("No table found.")))
        }
        rule.waitUntil(8_000) { rule.onAllNodes(hasText("Earlier", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        shot("home-populated")
        rule.onNodeWithText(EARLIER).performClick()
        shot("earlier")
        for (name in listOf("working", "approval", "approval-rtl", "long-approval", "question", "recovery", "done", "place", "map", "item", "docked", "listening", "settings")) {
            rule.runOnIdle { screen = name }
            if (name == "settings") rule.waitUntil(5_000) { rule.onAllNodes(hasText("Save keys")).fetchSemanticsNodes().isNotEmpty() }
            if (name == "map") Thread.sleep(2_500) // Native map surface and tile callbacks are outside Compose's idle clock.
            shot(name)
        }
        rule.runOnIdle { screen = "long-approval"; scale = 2f }
        shot("long-approval-200")
        rule.runOnIdle { screen = "home"; scale = 1f }
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .6f, durationMillis = 90) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
        rule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Sample app", substring = true))).performTouchInput {
            swipeDown(startY = height * .2f, endY = height * .8f)
        }
        shot("sheet-scroll-ime-down")
        Espresso.pressBack()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity(android.Manifest.permission.WRITE_SECURE_SETTINGS)
        val oldScale = android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        try {
            android.provider.Settings.Global.putFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .8f) }
            rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
            shot("sheet-motion-off")
        } finally {
            android.provider.Settings.Global.putFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, oldScale)
            automation.dropShellPermissionIdentity()
        }
    }
}
