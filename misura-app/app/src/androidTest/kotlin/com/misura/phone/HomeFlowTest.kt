package com.misura.phone

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.assertTextEquals
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainError
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Places
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolCall
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.phone.screen.CAROUSEL_TAG
import com.misura.phone.screen.DOCKED_TAG
import com.misura.phone.screen.MAP_TAG
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import com.misura.core.exec.Controller
import com.misura.core.exec.Hand
import com.misura.core.exec.HandResult
import com.misura.core.task.Payload
import com.misura.core.task.TaskState
import androidx.compose.ui.test.onRoot
import com.misura.phone.hands.ChatDrafter
import com.misura.phone.notify.Chat
import com.misura.phone.store.RoomTaskStore
import com.misura.phone.store.SessionRow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class HomeFlowTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "homeflow-test.db"
    private lateinit var db: MisuraDb
    private lateinit var store: RoomSessionStore
    private var keyboardShown by androidx.compose.runtime.mutableStateOf(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Before fun open() { ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); store = RoomSessionStore(db) }
    @After fun close() { db.close() }

    /** Replies in order; records what it was shown. */
    private class Script(vararg val replies: () -> BrainReply) : Brain {
        private val q = ArrayDeque(replies.toList()); val seen = mutableListOf<List<Turn>>()
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply { seen += turns; return q.removeFirst()() }
    }
    private fun call(name: String, args: String = "{}") = {
        val a = Json.parseToJsonElement(args).jsonObject
        BrainReply.Calls(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { putJsonObject("functionCall") { put("name", name); put("args", a) } }) }) },
            listOf(ToolCall(null, name, a)))
    }
    private fun text(t: String) = { BrainReply.Text(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(buildJsonObject { put("text", t) }) }) }, t) }
    private val places = object : Tool {
        override val spec = ToolSpec("find_places", "find", JsonObject(emptyMap()))
        override suspend fun run(args: JsonObject) = Json.parseToJsonElement("""{"places":[
            {"label":"Namsan","why":"quiet","placeId":"P1","lat":52.4986,"lng":13.4189,"week":["Friday: 12–22"]},
            {"label":"Kimchi Princess","why":"lively","placeId":"P2","lat":52.5003,"lng":13.4247},
            {"label":"Ixthys","why":"tiny","placeId":"P3","lat":52.4931,"lng":13.4120}]}""").jsonObject
        override fun label(args: JsonObject) = "Searching bibimbap"
        override fun summary(result: JsonObject) = "3 places found"
    }
    private val find = call("find_places", """{"query":"bibimbap"}""")
    private val show = call("show_places", """{"placeIds":["P1","P2","P3"],"pick":"P1","line":"Namsan is the quiet one"}""")

    private fun home(brain: Brain, open: String? = null): Agent {
        val agent = Agent(store, brain, listOf(places), System::currentTimeMillis, scope)
        rule.setContent {
            val ime = androidx.compose.foundation.layout.WindowInsets.ime
            val density = androidx.compose.ui.platform.LocalDensity.current
            val shown = androidx.compose.runtime.remember(ime, density) {
                androidx.compose.runtime.derivedStateOf { ime.getBottom(density) > 0 }
            }.value
            androidx.compose.runtime.SideEffect { keyboardShown = shown }
            HomeScreen(agent, store, photos = null, openRequest = open?.let { OpenRequest(it, 1) })
        }
        return agent
    }

    @Test fun emptyHomeOffersDirectInput() {
        home(Script())
        rule.onNodeWithText("What should we do?").assertIsDisplayed()
        rule.onNodeWithText("Type a request…").assertIsDisplayed()
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("A draft")
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("A draft")
        rule.onAllNodes(hasText("Earlier", substring = true)).assertCountEquals(0)
    }

    @Test fun requestKeyboardHidesHomeAndBackRestoresItWithoutLosingDraft() {
        runBlocking { seedHomeSession("completed", com.misura.core.agent.Say.Answer("Done.")) }
        home(Script()); waitText(EARLIER)
        rule.onNodeWithText("What should we do?").assertIsDisplayed()
        rule.onNodeWithContentDescription(ASK_FIELD).performClick()
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Keep this draft")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(EARLIER)).fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithText("What should we do?").assertDoesNotExist()
        rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed().assertTextEquals("Keep this draft")
        java.io.File(ctx.getExternalFilesDir(null), "focused-home.png").outputStream().use {
            check(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        }
        rule.waitUntil(5_000) { keyboardShown }
        Espresso.pressBack()
        waitText(EARLIER)
        rule.waitUntil(5_000) { !keyboardShown }
        rule.onNodeWithText("What should we do?").assertIsDisplayed()
        rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed().assertTextEquals("Keep this draft")
        rule.onNodeWithContentDescription(ASK_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
    }

    @Test fun tappingOutsideRequestDismissesKeyboardAndFocusWithoutLosingDraft() {
        runBlocking { seedHomeSession("completed", com.misura.core.agent.Say.Answer("Done.")) }
        home(Script()); waitText(EARLIER)
        rule.onNodeWithContentDescription(ASK_FIELD).performClick().performTextInput("Keep this draft")
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(EARLIER)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(5_000) { keyboardShown }
        rule.onRoot().performTouchInput { click(Offset(width * .5f, height * .2f)) }
        waitText(EARLIER)
        rule.waitUntil(5_000) { !keyboardShown }
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("Keep this draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
        rule.onNodeWithContentDescription(ASK_FIELD).performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(EARLIER)).fetchSemanticsNodes().isEmpty() }
        rule.waitUntil(5_000) { keyboardShown }
        rule.onNodeWithContentDescription(ASK_FIELD).performTouchInput { click(center) }
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("Keep this draft")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
    }

    @Test fun immediateBackFromRequestDoesNotReopenKeyboard() {
        runBlocking { seedHomeSession("completed", com.misura.core.agent.Say.Answer("Done.")) }
        home(Script()); waitText(EARLIER)
        repeat(5) {
            rule.onNodeWithContentDescription(ASK_FIELD).performTouchInput { click(center) }
            rule.waitForIdle()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            waitText(EARLIER)
            rule.waitUntil(5_000) { !keyboardShown }
            Thread.sleep(450) // Let any late IME readiness callback arrive after dismissal.
            rule.onNodeWithContentDescription(ASK_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, false))
            rule.runOnIdle { keyboardShown shouldBe false }
        }
    }

    @Test fun holdingVoicePreservesDraftAndRestoresVisibleInput() {
        val voice = SaysOnRelease("")
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, null, null, voice = voice) }
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Keep these words")
        rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(true) }
        rule.onNodeWithTag(com.misura.phone.screen.LISTENING_TAG).assertIsDisplayed()
        rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(false) }
        rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed().assertTextEquals("Keep these words")
        rule.onNodeWithTag(com.misura.phone.screen.LISTENING_TAG).assertDoesNotExist()
    }

    @Test fun heldVoiceRejectsHiddenEditsAndAsk() {
        val oldSeen = com.misura.phone.hidden.SideKey.seen
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        try {
            com.misura.phone.hidden.SideKey.seen = true
            rule.setContent { HomeScreen(agent, store, null, null, voice = SaysOnRelease("")) }
            rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Keep this edit")
            rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(true) }
            rule.onNodeWithContentDescription(ASK_FIELD).performTextInput(" invisible")
            rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("Keep this edit")
            rule.onNodeWithText("Ask").assertIsNotEnabled()
            rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(false) }
            rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed().assertTextEquals("Keep this edit")
        } finally {
            rule.runOnIdle { com.misura.phone.hidden.SideKey.listener?.invoke(false); com.misura.phone.hidden.SideKey.seen = oldSeen }
        }
    }

    @Test fun heldVoiceBlocksSearchEnterImeAndEdits() {
        val opened = mutableListOf<String>()
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, null, null, voice = SaysOnRelease(""), apps = listOf("Clock" to "org.example.clock")) { opened += it } }
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Home draft")
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .8f) }
        rule.onNodeWithContentDescription("Search apps").performTextInput("cl")
        rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(true) }
        rule.onNodeWithContentDescription("Search apps").performImeAction()
        Thread.sleep(300)
        opened shouldBe emptyList()
        rule.onNodeWithContentDescription("Search apps").performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithContentDescription("Search apps").performTextInput(" invisible")
        rule.onNodeWithContentDescription("Search apps").assertTextEquals("cl")
        rule.runOnIdle { com.misura.phone.hidden.SideKey.listener!!(false) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed().assertTextEquals("cl")
        opened shouldBe emptyList()
        // One Back can close both the search keyboard and the list (AppSheetMotion hides the keyboard when it closes);
        // press again only while the list is still open, so the test never backs out of the app.
        Espresso.pressBack(); rule.waitForIdle()
        if (runCatching { rule.onNodeWithContentDescription("Search apps").assertIsDisplayed() }.isSuccess) Espresso.pressBack()
        rule.waitUntil(5_000) { rule.onAllNodesWithContentDescription(ASK_FIELD).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("Home draft")
    }

    @Test fun cancelledFallbackHoldDoesNotSubmitTranscript() {
        val oldSeen = com.misura.phone.hidden.SideKey.seen
        val brain = Script(text("Unexpected submission"))
        val agent = Agent(store, brain, emptyList(), System::currentTimeMillis, scope)
        try {
            com.misura.phone.hidden.SideKey.seen = false
            rule.setContent { HomeScreen(agent, store, null, null, voice = SaysOnRelease("Unwanted request")) }
            rule.onNodeWithContentDescription("Hold to talk").performTouchInput { down(center); advanceEventTime(300); cancel() }
            Thread.sleep(500)
            brain.seen shouldBe emptyList()
            runBlocking { store.recent(10) } shouldBe emptyList()
        } finally { rule.runOnIdle { com.misura.phone.hidden.SideKey.seen = oldSeen } }
    }

    private suspend fun seedHomeSession(id: String, say: com.misura.core.agent.Say?, status: SessionStatus = SessionStatus.IDLE): Session {
        val s = Session(id, status, 0, System.currentTimeMillis(), say = say)
        store.save(s)
        store.append(id, Turn("user", buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", "Sample task $id") }) }) }))
        return s
    }

    @Test fun completedReadyCompletedHomeKeepsTruthfulEmptyState() {
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        val s = runBlocking { seedHomeSession("only", com.misura.core.agent.Say.Answer("Done.")) }
        home(Script()); waitText(EARLIER)
        rule.onNodeWithText("What should we do?").assertIsDisplayed()
        rule.onNodeWithText("Swipe down for apps").assertIsDisplayed()
        runBlocking { store.save(s.copy(revision = 1, say = com.misura.core.agent.Say.Question("Which park?", listOf("North", "South")))) }
        rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("ready for your go", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("What should we do?").assertDoesNotExist()
        rule.onNodeWithText("Swipe down for apps").assertDoesNotExist()
        runBlocking { store.save(s.copy(revision = 2, say = com.misura.core.agent.Say.Answer("Done."))) }
        waitText("What should we do?")
        rule.onNodeWithText("Swipe down for apps").assertIsDisplayed()
        rule.onNodeWithText(EARLIER).assertIsDisplayed()
    }

    @Test fun crowdedShortHomeKeepsEarlierReachable() {
        runBlocking {
            repeat(6) { i -> seedHomeSession(i.toString(), when {
                i < 3 -> com.misura.core.agent.Say.Question("Which park?", listOf("North", "South"))
                i < 5 -> com.misura.core.agent.Say.Problem("Try another area.")
                else -> null
            }, if (i == 5) SessionStatus.THINKING else if (i < 3) SessionStatus.IDLE else SessionStatus.FAILED) }
        }
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        rule.setContent { androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(400.dp)) { HomeScreen(agent, store, null, null) } }
        waitText("Earlier")
        rule.onNodeWithText("Earlier", substring = true).assertIsDisplayed().performClick()
        rule.onNodeWithText("Sample task 5", substring = true).performScrollTo().assertIsDisplayed()
    }

    @Test fun heldTapCannotApproveReplacementGenericGate() {
        val first = runBlocking { seedHomeSession("gate", com.misura.core.agent.Say.Question("Old exact message", listOf("Send", "Don't"), "g1", "Sample chat · FriendA")) }
        val chosen = mutableListOf<String?>()
        val agent = Agent(store, Script(text("Approved")), emptyList(), System::currentTimeMillis, scope, onChoose = { q, _ -> chosen += q.gate })
        rule.setContent { HomeScreen(agent, store, null, OpenRequest("gate", 1)) }
        waitText("Old exact message")
        rule.onNodeWithText("Send").performTouchInput { down(center) }
        runBlocking { store.save(first.copy(revision = 1, say = com.misura.core.agent.Say.Question("New exact message", listOf("Send", "Don't"), "g2", "Sample chat · FriendA"))) }
        waitText("New exact message")
        rule.onNodeWithText("Send").performTouchInput { up() }
        Thread.sleep(300)
        chosen shouldBe emptyList()
        rule.onNodeWithText("Send").performClick()
        rule.waitUntil(5_000) { chosen == listOf("g2") }
        rule.waitUntil(5_000) { runBlocking { store.load("gate")?.let { it.status == SessionStatus.IDLE && it.say is com.misura.core.agent.Say.Answer } == true } }
    }

    @Test fun swipeCueAcknowledgesSettledOpenAndSurvivesRecreation() {
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        val restoration = StateRestorationTester(rule)
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        restoration.setContent { HomeScreen(agent, store, null, null, apps = listOf("Sample clock" to "org.example.clock")) }
        rule.onNodeWithText("Swipe down for apps").assertIsDisplayed()
        rule.onRoot().performTouchInput {
            down(Offset(width * .5f, height * .15f))
            moveTo(Offset(width * .5f, height * .25f), 300)
            moveTo(Offset(width * .5f, height * .15f), 300)
            cancel()
        }
        rule.onNodeWithText("Swipe down for apps").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .8f) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
        rule.waitUntil(5_000) { ctx.getSharedPreferences("first-use", 0).getBoolean("apps-opened", false) }
        Espresso.pressBack(); Espresso.pressBack()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Swipe down for apps").assertDoesNotExist()
    }

    @Test fun swipeCueStaysOutOfTyping() {
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        home(Script())
        rule.onNodeWithText("Swipe down for apps").assertIsDisplayed()
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Keep this draft")
        rule.onNodeWithText("Swipe down for apps").assertDoesNotExist()
    }

    @Test fun largeTextSwipeCueDoesNotOverlapDate() {
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        val agent = Agent(store, Script(), emptyList(), System::currentTimeMillis, scope)
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                androidx.compose.foundation.layout.Box(Modifier.width(320.dp).fillMaxHeight()) { HomeScreen(agent, store, null, null) }
            }
        }
        val locale = java.util.Locale.getDefault()
        val date = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(java.util.Date())
        val dateBounds = rule.onNodeWithText(date).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val cueBounds = rule.onNodeWithText("Swipe down for apps").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        org.junit.Assert.assertTrue("Swipe cue overlaps the clock/date at 200%", cueBounds.top >= dateBounds.bottom)
    }

    @Test fun unknownRetryPreparesFreshApprovalWithoutSending() {
        val sent = mutableListOf<Payload>()
        val (tasks, _) = messaging(Script(draft, call("draft_message", """{"to":"FriendA","text":"Check this new draft"}""")), sent)
        ask("tell FriendA there in five"); waitText("There in 5")
        runBlocking {
            val s = store.recent(1).single()
            val t = tasks.load((s.say as com.misura.core.agent.Say.Approve).taskId)!!
            tasks.save(t.copy(state = TaskState.OUTCOME_UNKNOWN))
        }
        waitText("Prepare new draft")
        rule.onNodeWithText("Prepare new draft").performClick()
        waitText("Check this new draft")
        rule.onNodeWithText("Send").assertIsDisplayed()
        sent shouldBe emptyList()
    }

    @Test fun mixedRetryExcludesUnknownWhenKnownUnsentRemain() {
        val sent = mutableListOf<Payload>()
        val brain = Script(draftTwo, draft)
        twoChats(brain, sent)
        ask("tell both dinner at eight"); waitText("Send 2")
        runBlocking {
            val ids = (store.recent(1).single().say as com.misura.core.agent.Say.Approve).all
            tasksFor(ids[0], TaskState.FAILED)
            tasksFor(ids[1], TaskState.OUTCOME_UNKNOWN)
        }
        waitText("Retry 1 unsent")
        rule.onNodeWithText("Retry 1 unsent").performClick()
        waitText("There in 5")
        rule.onNodeWithText("Send").assertIsDisplayed()
        brain.seen.last().last().toString() shouldContain "number 1 did not"
        sent shouldBe emptyList()
    }

    @Test fun unknownOnlyRetryStillNeedsFreshApproval() {
        val sent = mutableListOf<Payload>()
        twoChats(Script(draftTwo, draftTwo), sent)
        ask("tell both dinner at eight"); waitText("Send 2")
        val oldIds = runBlocking {
            (store.recent(1).single().say as com.misura.core.agent.Say.Approve).all.also { ids ->
                ids.forEach { tasksFor(it, TaskState.OUTCOME_UNKNOWN) }
            }
        }
        waitText("Prepare new draft")
        rule.onNodeWithText("Prepare new draft").performClick()
        waitText("Send 2")
        rule.waitUntil(5_000) { runBlocking {
            store.recent(1).single().let { s -> s.status == SessionStatus.IDLE && (s.say as? com.misura.core.agent.Say.Approve)?.all?.none { it in oldIds } == true }
        } }
        sent shouldBe emptyList()
        rule.onNodeWithText("Send 2").assertIsDisplayed()
    }

    private suspend fun tasksFor(id: String, state: TaskState) {
        val tasks = RoomTaskStore(db)
        tasks.save(tasks.load(id)!!.copy(state = state))
    }
    @Test fun storageFailureOnChoiceLeavesScreenScopeReadyForNextChoice() {
        val reject = java.util.concurrent.atomic.AtomicBoolean(false)
        val attempts = java.util.concurrent.atomic.AtomicInteger(0)
        val guarded = object : com.misura.core.agent.SessionStore by store {
            override suspend fun save(s: Session) {
                if (reject.getAndSet(false)) {
                    attempts.incrementAndGet()
                    throw com.misura.phone.store.StorageUnavailableException()
                }
                store.save(s)
            }
        }
        val agent = Agent(guarded, Script(call("ask", """{"line":"Which area?","choices":["Kreuzberg","Mitte"]}"""), text("Mitte it is")), listOf(places), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, guarded, photos = null, openRequest = null) }
        ask("ramen tonight"); waitText("Which area?")
        reject.set(true)
        rule.onNodeWithText("Mitte").performClick()
        rule.waitUntil(5_000) { attempts.get() == 1 }
        rule.waitForIdle()
        rule.onNodeWithText("Mitte").performClick()
        waitText("Mitte it is")
    }

    @Test fun tappingAChoiceKeepsTheEchoOnWhatYouSaid() {
        home(Script(call("ask", """{"line":"Which area?","choices":["Kreuzberg","Mitte"]}"""), text("Mitte it is")))
        ask("ramen tonight"); waitText("Which area?")
        rule.onNodeWithText("Mitte").performClick(); waitText("Mitte it is")
        rule.onAllNodes(hasText("I picked", substring = true)).assertCountEquals(0)
        rule.onNodeWithText("“ramen tonight”").assertExists()
    }

    @Test fun aFollowUpKeepsTheEarlierExchangeAboveItAsTheThread() {
        home(Script(text("Paris."), text("About 2.1 million.")))
        ask("capital of France?"); waitText("Paris.")
        rule.onNodeWithTag(com.misura.phone.screen.PAST_TAG).assertDoesNotExist()
        ask("how many people live there?"); waitText("About 2.1 million.")
        // The earlier question and its answer stay, one scroll up; the new one is in front with its own echo.
        rule.onNodeWithTag(com.misura.phone.screen.PAST_TAG).assertExists()
        rule.onNodeWithText("“capital of France?”").assertExists()
        rule.onNodeWithText("Paris.").assertExists()
        rule.onNodeWithText("“how many people live there?”").assertIsDisplayed()
        rule.onNodeWithText("Paris.").assertIsNotDisplayed()
    }

    @Test fun appSearchNeedsASwipeAndKeepsTheAskDraft() {
        var opened: String? = null
        val agent = Agent(store, Script(), listOf(places), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null,
            apps = listOf("Clock" to "com.clock", "Maps" to "com.maps", "YouTube Music" to "com.ytm")) { opened = it } }
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("cl")
        rule.onAllNodes(hasText("Clock")).assertCountEquals(0)
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .7f) }
        rule.onNodeWithContentDescription("Search apps").performTextInput("cl")
        rule.onNodeWithText("Maps").assertDoesNotExist()
        rule.onNodeWithText("Clock").performClick()
        rule.runOnIdle { check(opened == "com.clock") { "opened $opened" } }
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("cl")
    }

    @Test fun settingsEntryIsOnlyInAppSheetAndCanBeOpened() {
        var opened = 0
        val agent = Agent(store, Script(), listOf(places), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null,
            apps = listOf("Clock" to "com.clock"), onOpenSettings = { opened++ }) }
        rule.onNodeWithText("Misura settings").assertDoesNotExist()
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .7f) }
        rule.onNodeWithText("Misura settings").assertIsDisplayed().performClick()
        rule.runOnIdle { check(opened == 1) }
        rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed()
    }

    @Test fun downwardDragScrollsAppsButClosesFromTheField() {
        val agent = Agent(store, Script(), listOf(places), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null,
            apps = List(60) { "Test app %02d".format(it) to "com.test.app$it" }) }
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .7f) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
        val list = rule.onNode(hasScrollAction() and hasAnyDescendant(hasText("Test app ", substring = true)))
        val bounds = list.fetchSemanticsNode().boundsInRoot
        val visibleBefore = rule.onAllNodes(hasText("Test app ", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(bounds) }.map { it.config[SemanticsProperties.Text].single().text }.toSet()
        list.performTouchInput { swipeDown(startY = height * .2f, endY = height * .8f) }
        rule.onNodeWithContentDescription("Search apps").assertIsDisplayed()
        val scrolledBounds = list.fetchSemanticsNode().boundsInRoot
        val newlyVisible = rule.onAllNodes(hasText("Test app ", substring = true)).fetchSemanticsNodes()
            .filter { it.boundsInRoot.overlaps(scrolledBounds) }.map { it.config[SemanticsProperties.Text].single().text }
            .first { it !in visibleBefore }
        rule.onNodeWithText(newlyVisible).assertIsDisplayed()
        val field = rule.onNodeWithContentDescription("Search apps").fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput {
            // Scrolling hides the IME, so continue beyond the root edge for enough close travel.
            swipe(start = field.center, end = field.center + Offset(0f, height * .6f))
        }
        rule.onNodeWithContentDescription(ASK_FIELD).assertIsDisplayed()
    }

    private fun ask(text: String) {
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput(text)
        rule.onNodeWithText("Ask").performClick()
        // Like a person, the next action comes after the keyboard has closed. Ask always closes it, so a timeout is a failure.
        waitKeyboardClosed()
    }
    /** The keyboard from the previous request has finished closing (a person cannot tap the next field faster than that). */
    private fun waitKeyboardClosed() {
        val a = (rule as androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>).activity
        rule.waitUntil(5_000) {
            var open = true
            // Visibility flips when closing starts; the height reaches zero only when the close animation is over.
            rule.runOnUiThread { open = (androidx.core.view.ViewCompat.getRootWindowInsets(a.window.decorView)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())?.bottom ?: 0) > 0 }
            !open && rule.onAllNodes(androidx.compose.ui.test.isFocused()).fetchSemanticsNodes().none { n -> androidx.compose.ui.semantics.SemanticsProperties.ContentDescription.let { k -> if (n.config.contains(k)) n.config[k].contains(ASK_FIELD) else false } }
        }
    }
    /** The dock follows field focus, which settles only after the native keyboard opens: wait for it, then check it once. */
    private fun waitDocked() { rule.waitUntil(5_000) { rule.onAllNodesWithTag(DOCKED_TAG).fetchSemanticsNodes().isNotEmpty() }; rule.onNodeWithTag(DOCKED_TAG).assertExists() }
    private fun waitText(t: String, ms: Long = 8_000) = rule.waitUntil(ms) { rule.onAllNodes(hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty() }
    private fun selected(name: String) = rule.onNodeWithTag(MAP_TAG).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, name))

    @Test fun mapExpandsAndReturnsToSameSelectedPlaceRepeatedly() {
        home(Script(find, show)); ask("Find cafés"); waitText("Namsan")
        val actions = rule.onNodeWithTag(MAP_TAG).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        rule.runOnIdle { actions.first { it.label == "Select Kimchi Princess" }.action() }
        selected("Kimchi Princess")
        val original = currentMapView()
        assertMapRendersLocalStyle()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            original.getMapAsync { map ->
                map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(org.maplibre.android.geometry.LatLng(52.499, 13.421), 14.25))
            }
        }
        fun sameCamera() = InstrumentationRegistry.getInstrumentation().runOnMainSync {
            original.getMapAsync { map ->
                check(kotlin.math.abs(map.cameraPosition.zoom - 14.25) < .00001)
                check(kotlin.math.abs(checkNotNull(map.cameraPosition.target).latitude - 52.499) < .00001)
                check(kotlin.math.abs(checkNotNull(map.cameraPosition.target).longitude - 13.421) < .00001)
            }
        }
        repeat(3) {
            rule.onNodeWithTag(MAP_TAG).performTouchInput { click(Offset(width * .2f, height * .2f)) }
            waitText("Map")
            selected("Kimchi Princess")
            check(currentMapView() === original) { "Expansion replaced the loaded MapView" }
            sameCamera()
            assertMapPixels(original)
            Espresso.pressBack()
            rule.onNodeWithTag(CAROUSEL_TAG).assertExists()
            selected("Kimchi Princess")
            check(currentMapView() === original) { "Collapse replaced the loaded MapView" }
            sameCamera()
        }
    }

    private fun currentMapView(): org.maplibre.android.maps.MapView {
        var result: org.maplibre.android.maps.MapView? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val activity = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).single()
            fun find(view: android.view.View): org.maplibre.android.maps.MapView? {
                if (view is org.maplibre.android.maps.MapView) return view
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                return null
            }
            result = find(activity.window.decorView)
        }
        return checkNotNull(result)
    }

    private fun assertMapRendersLocalStyle() {
        val mapView = currentMapView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            mapView.getMapAsync { map ->
                map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"#225533"}}]}"""))
            }
        }
        assertMapPixels(mapView)
    }

    private fun assertMapPixels(mapView: org.maplibre.android.maps.MapView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rect = android.graphics.Rect()
        rule.waitUntil(10_000) {
            instrumentation.runOnMainSync { mapView.getGlobalVisibleRect(rect) }
            val shot = instrumentation.uiAutomation.takeScreenshot()
            try {
                val pixel = shot.getPixel(rect.centerX(), rect.centerY())
                android.graphics.Color.green(pixel) > android.graphics.Color.red(pixel) * 1.5 && android.graphics.Color.green(pixel) > 60
            } finally { shot.recycle() }
        }
    }

    /** Sends instantly and sees its own message: the happy path of a notification reply. */
    private class OkHand(val sent: MutableList<Payload>) : Hand {
        override val id = "fake"; override val channel = "testchat"; override val lock: String? = null
        override suspend fun prepare(p: Payload) = HandResult.Prepared(p)
        override suspend fun commit(attemptId: String, p: Payload): HandResult { sent += p; return HandResult.Ok("sent") }
        override suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long) = HandResult.Ok("seen")
        override suspend fun cancel(attemptId: String) = HandResult.Unavailable("no")
    }
    private val draft = call("draft_message", """{"to":"FriendA","text":"There in 5"}""")
    private lateinit var messageController: Controller
    private var homeTap by androidx.compose.runtime.mutableLongStateOf(0L)
    private fun messaging(brain: Brain, sent: MutableList<Payload>): Pair<RoomTaskStore, Agent> {
        val tasks = RoomTaskStore(db)
        val controller = Controller(tasks, listOf(OkHand(sent)), System::currentTimeMillis, scope)
        messageController = controller
        val drafter = ChatDrafter({ listOf(Chat("k1", "FriendA", "com.misura.testapp")) }, { "Testchat" }, tasks, controller, System::currentTimeMillis, { listOf("com.misura.testapp") })
        val agent = Agent(store, brain, listOf(places), System::currentTimeMillis, scope, drafter = drafter)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null, sends = Sends(tasks, controller, scope), homeTap = homeTap) }
        return tasks to agent
    }

    @Test fun draftedMessageWaitsForTheTapThenSendsOnce() {
        val sent = mutableListOf<Payload>()
        messaging(Script(draft), sent); ask("tell FriendA we'll be there in 5")
        waitText("There in 5")
        rule.onNodeWithText("Testchat · FriendA").assertExists()
        rule.onNodeWithText("will").assertDoesNotExist() // one effect: the card says it, no will line repeating it
        sent.size shouldBe 0 // nothing goes out before the tap
        rule.onNodeWithText("Send").performClick()
        waitText("Sent.")
        rule.onNodeWithText("To FriendA: “There in 5”").assertExists()
        rule.onNodeWithText("“tell FriendA we'll be there in 5”").assertDoesNotExist() // done = outcome + ✓ steps, no echo
        rule.onNodeWithText("Send").assertDoesNotExist()
        sent.map { it["content"] to it["recipient"] } shouldBe listOf("There in 5" to "k1")
    }

    /**
     * Orange texture glyphs in the open lower band of the screen (design-system §12 Atmosphere). Screen text is neutral and
     * cards are opaque neutral surfaces, so orange there can only be the texture. Pixel sampling keeps the texture free of semantics.
     */
    private fun textureShows(): Boolean = textureCount() >= 20
    private fun textureCount(): Int {
        val img = rule.onRoot().captureToImage().asAndroidBitmap()
        // Above the input row (its Hold to talk / Ask outline is orange): the band just over the field.
        val fieldTop = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().minOfOrNull { it.boundsInRoot.top }?.toInt() ?: img.height
        try {
            var orange = 0
            for (y in (fieldTop - img.height * .14f).toInt().coerceAtLeast(0) until (fieldTop - img.height * .02f).toInt().coerceAtLeast(0) step 2) for (x in 0 until img.width step 2) {
                val c = img.getPixel(x, y)
                if (android.graphics.Color.red(c) - android.graphics.Color.blue(c) > 12 && android.graphics.Color.red(c) > android.graphics.Color.green(c) + 6) orange++
            }
            return orange
        } finally { img.recycle() }
    }

    @Test fun textureStaysThroughWorkingAndAfterTheSend() {
        val sent = mutableListOf<Payload>()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val slow = object : Brain {
            var calls = 0
            override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply { if (calls++ == 0) gate.await(); return draft() }
        }
        messaging(slow, sent)
        rule.waitUntil(5_000) { textureShows() } // empty home
        rule.mainClock.advanceTimeBy(1_000)
        val home = textureCount()
        ask("tell FriendA we'll be there in 5")
        // Work that lasts: the texture comes back on the task screen instead of vanishing, quieter than home.
        rule.waitUntil(8_000) { textureShows() }
        rule.mainClock.advanceTimeBy(1_000)
        val working = textureCount()
        assert(working in 20 until (home * .8f).toInt()) { "working texture $working should be quieter than home $home" }
        gate.complete(Unit)
        waitText("There in 5")
        rule.onNodeWithText("Send").performClick()
        waitText("Sent.")
        rule.waitUntil(5_000) { textureShows() }
    }

    @Test fun requestKeyboardStillHidesTheTexture() {
        home(Script())
        rule.waitUntil(5_000) { textureShows() }
        rule.onNode(hasSetTextAction()).performClick()
        rule.waitUntil(5_000) { !textureShows() }
    }

    @Test fun wordsAfterSentStartANewTask() {
        val sent = mutableListOf<Payload>()
        messaging(Script(draft, text("Friday 12–22")), sent); ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        rule.onNodeWithText("Send").performClick(); waitText("Sent.")
        ask("when is Namsan open?"); waitText("Friday 12–22")
        runBlocking { store.recent(5).size } shouldBe 2 // not a follow-up on the sent message
        rule.onNodeWithText("“when is Namsan open?”").assertExists() // the new task, its own echo
    }

    private val draftTwo = {
        fun fc(to: String) = buildJsonObject { putJsonObject("functionCall") { put("name", "draft_message"); put("args", buildJsonObject { put("to", to); put("text", "Dinner at 8") }) } }
        val args = { to: String -> buildJsonObject { put("to", to); put("text", "Dinner at 8") } }
        BrainReply.Calls(buildJsonObject { put("role", "model"); put("parts", buildJsonArray { add(fc("FriendA")); add(fc("Guest")) }) },
            listOf(ToolCall(null, "draft_message", args("FriendA")), ToolCall(null, "draft_message", args("Guest"))))
    }
    /** Hears [said] the moment the button is let go. */
    private class SaysOnRelease(val said: String) : com.misura.phone.voice.Voice {
        private var done: ((String?) -> Unit)? = null
        override fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit): Boolean { done = onFinal; return true }
        override fun stop() { done?.invoke(said); done = null }
    }
    private fun twoChats(brain: Brain, sent: MutableList<Payload>, voice: com.misura.phone.voice.Voice? = null, fontScale: Float? = null) {
        val tasks = RoomTaskStore(db)
        val controller = Controller(tasks, listOf(OkHand(sent)), System::currentTimeMillis, scope)
        val drafter = ChatDrafter({ listOf(Chat("k1", "FriendA", "com.misura.testapp"), Chat("k2", "Guest", "com.misura.testapp")) }, { "Testchat" }, tasks, controller, System::currentTimeMillis, { listOf("com.misura.testapp") })
        val agent = Agent(store, brain, listOf(places), System::currentTimeMillis, scope, drafter = drafter)
        rule.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale ?: density.fontScale)) {
                HomeScreen(agent, store, photos = null, openRequest = null, sends = Sends(tasks, controller, scope), voice = voice)
            }
        }
    }

    @Test fun longApprovalKeepsUndoVisibleAfterSpokenGo() {
        val sent = mutableListOf<Payload>()
        val args = buildJsonObject { put("to", "FriendA"); put("text", "Read every word before approving. ".repeat(25)) }
        twoChats(Script(call("draft_message", args.toString())), sent, voice = SaysOnRelease("go"), fontScale = 2f)
        ask("draft a detailed message"); waitText("Send")
        rule.onNodeWithText("Edit").performScrollTo().performClick()
        rule.onNodeWithContentDescription(ASK_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("Keep this unsent edit")
        rule.onNodeWithText("Send").performScrollTo().assertIsDisplayed()
        rule.runOnIdle {
            com.misura.phone.hidden.SideKey.listener!!(true)
            com.misura.phone.hidden.SideKey.listener!!(false)
        }
        waitText("Undo")
        rule.waitUntil(1_500) { rule.onNodeWithText("Undo").isDisplayed() }
        rule.onNodeWithText("Undo").assertIsDisplayed().performClick()
        rule.onNodeWithContentDescription(ASK_FIELD).assertTextEquals("Keep this unsent edit")
        Thread.sleep(UNDO_MS + 250)
        sent shouldBe emptyList()
    }

    @Test fun severalMessagesSitOnOneCardAndOneTapSendsThemAll() {
        val sent = mutableListOf<Payload>()
        twoChats(Script(draftTwo), sent); ask("tell FriendA and Guest dinner is at 8")
        waitText("Send 2")
        rule.onNodeWithText("Testchat · FriendA, Guest").assertExists() // same words: one block, both names
        sent.size shouldBe 0 // nothing goes out before the go
        rule.onNodeWithText("Send 2").performClick()
        waitText("Sent.")
        rule.onNodeWithText("To FriendA: “Dinner at 8”").assertExists(); rule.onNodeWithText("To Guest: “Dinner at 8”").assertExists() // each its own ✓ line
        sent.map { it["recipient"].orEmpty() }.sorted() shouldBe listOf("k1", "k2")
    }

    @Test fun aSpokenGoSendsAfterTheUndoWindowAndUndoStopsIt() {
        val sent = mutableListOf<Payload>()
        twoChats(Script(draftTwo), sent, voice = SaysOnRelease("go")); ask("tell FriendA and Guest dinner is at 8")
        waitText("Send 2")
        fun sayGo() = rule.onNodeWithContentDescription("Hold to talk").performTouchInput { down(center); advanceEventTime(300); up() }
        sayGo(); waitText("Undo")
        sent.size shouldBe 0 // armed, not sent
        rule.onNodeWithText("Undo").performClick(); waitText("Edit")
        Thread.sleep(UNDO_MS + 500); sent.size shouldBe 0 // taken back: nothing leaves
        sayGo(); waitText("Sent.", ms = 12_000)
        sent.size shouldBe 2
    }

    /** Stands in for use_app stopped before a send/delete tap: its result asks the user. [label] set = a message (chat + exact words). */
    private fun stopped(label: String?, line: String, word: String) = object : com.misura.core.agent.Tool {
        override val spec = com.misura.core.agent.ToolSpec("use_app", "test", buildJsonObject {})
        override suspend fun run(args: JsonObject) = buildJsonObject { putJsonObject("askUser") { put("id", "g1"); label?.let { put("label", it) }; put("line", line)
            put("choices", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive(word)); add(kotlinx.serialization.json.JsonPrimitive("Don't")) }) } }
        override fun label(args: JsonObject) = "Working in the app"
        override fun summary(result: JsonObject): String? = null
    }
    private fun sayIt() = rule.onNodeWithContentDescription("Hold to talk").performTouchInput { down(center); advanceEventTime(300); up() }

    @Test fun aSpokenGoSendsAMessageTypedInAnAppAfterTheUndoWindow() {
        val picked = mutableListOf<Int>()
        val agent = Agent(store, Script(call("use_app"), text("Sent.")), listOf(stopped("WhatsApp · FriendA", "Dinner at 8", "Send")), System::currentTimeMillis, scope, onChoose = { _, i -> picked += i })
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null, voice = SaysOnRelease("go")) }
        ask("tell FriendA dinner is at 8"); waitText("Dinner at 8")
        sayIt(); waitText("Undo")
        picked.size shouldBe 0 // armed, nothing told to the app yet
        rule.onNodeWithText("Undo").performClick(); waitText("Don't")
        Thread.sleep(UNDO_MS + 500); picked.size shouldBe 0 // taken back
        sayIt(); waitText("Sent.", ms = 12_000)
        picked shouldBe listOf(0) // the yes, once
    }

    @Test fun aSpokenGoNeverApprovesAStoppedTapThatIsNotAMessage() {
        val picked = mutableListOf<Int>()
        val brain = Script(call("use_app"), text("Tap the button to delete it."))
        val agent = Agent(store, brain, listOf(stopped(null, "Clock: tap “Delete”?", "Delete")), System::currentTimeMillis, scope, onChoose = { _, i -> picked += i })
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null, voice = SaysOnRelease("go")) }
        ask("delete my alarm"); waitText("Approve: Delete")
        sayIt(); waitText("Tap the button to delete it.") // the word went to Misura as plain words; no yes was given
        picked.size shouldBe 0
    }

    /** A routine's Save card (label + title) is not a message: a spoken "go" never saves it; the tap does (Codex P1, 22 Nov). */
    @Test fun aSpokenGoNeverSavesARoutine() {
        ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val routines = com.misura.phone.routine.RoutineStore(ctx)
        val tool = com.misura.phone.routine.RoutineTool(routines)
        val add = """{"action":"add","title":"Train","time":"08:00","days":"weekdays","prompt":"Is my train late?","summary":"I'll check if your train is late"}"""
        val agent = Agent(store, Script(call("routine", add), text("Saved."), text("Saved.")), listOf(tool), System::currentTimeMillis, scope,
            onChoose = { q, i -> q.gate?.let { if (it.startsWith("rt:")) tool.answered(it, yes = i == 0) } })
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null, voice = SaysOnRelease("go")) }
        try {
            ask("every weekday at 8 check my train"); waitText("I'll check if your train is late")
            rule.onNodeWithText("“Is my train late?”").assertExists() // the exact prompt stays on the card
            sayIt(); Thread.sleep(UNDO_MS + 1_500)
            routines.all() shouldBe emptyList() // no Undo window, nothing saved
            rule.onNodeWithText("Undo").assertDoesNotExist()
            routines.all() shouldBe emptyList() // "go" went to Misura as plain words; the Save tap itself is covered in RoutinesDeviceTest
        } finally { ctx.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE).edit().clear().commit() }
    }

    @Test fun theSideKeyTalksLikeTheButton() {
        val sent = mutableListOf<Payload>()
        twoChats(Script(draftTwo), sent, voice = SaysOnRelease("go")); ask("tell FriendA and Guest dinner is at 8")
        waitText("Send 2")
        val key = com.misura.phone.hidden.SideKey.listener!!
        rule.runOnUiThread { key(true) } // press: the Listening sign, no words streamed
        rule.onNodeWithTag(com.misura.phone.screen.LISTENING_TAG).assertExists()
        rule.runOnUiThread { key(false) } // release
        waitText("Undo"); sent.size shouldBe 0 // heard "go" for the card in front: armed, like the button
    }

    @Test fun homeGestureClosesTheOpenTask() {
        messaging(Script(draft), mutableListOf()); ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        homeTap = 1L
        // Home: the draft is one line with the ring (ready for your go), not a card; nothing here can send.
        waitText("Message to FriendA")
        rule.onNodeWithTag(com.misura.phone.screen.YOUR_GO_TAG, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Send").assertDoesNotExist()
        rule.onNodeWithText("Message to FriendA").performClick(); waitText("There in 5") // a tap on the line opens the task
        rule.onNodeWithText("Send").assertExists()
    }

    private fun swipeTask(text: String, far: Boolean = true) {
        rule.onNodeWithText(text).performTouchInput {
            swipeLeft(startX = width * .85f, endX = width * (if (far) .1f else .65f), durationMillis = 300)
        }
        rule.waitForIdle()
    }
    private fun earlierTasks() { rule.onNodeWithText("Earlier", substring = true).performClick(); rule.waitForIdle() }
    private val earlierDraft = "tell FriendA we'll be there in 5 · Message to FriendA"

    @Test fun longSwipeDismissesHomeLineButKeepsDraftInEarlier() {
        val sent = mutableListOf<Payload>()
        val (tasks, _) = messaging(Script(draft), sent)
        ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        val id = runBlocking { store.recent(1).single().id }
        homeTap = 1L; waitText("Message to FriendA")
        swipeTask("Message to FriendA")
        rule.waitUntil(8_000) { runBlocking { store.load(id)?.dismissedAt != null } }
        rule.onNodeWithText("Message to FriendA").assertDoesNotExist()
        // The flag survives a new store instance, and dismissal never retires or sends the draft.
        runBlocking { RoomSessionStore(db).load(id)!!.dismissedAt != null } shouldBe true
        runBlocking { tasks.unfinished().single().state } shouldBe TaskState.NEEDS_APPROVAL
        sent shouldBe emptyList()
        earlierTasks(); waitText(earlierDraft)
        rule.onNodeWithText(earlierDraft).performClick(); waitText("There in 5")
        rule.onNodeWithText("Send").assertExists()
    }

    @Test fun shortSwipeSpringsBackAndTapStillOpensTask() {
        messaging(Script(draft), mutableListOf())
        ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        homeTap = 1L; waitText("Message to FriendA")
        swipeTask("Message to FriendA", far = false)
        rule.onNodeWithText("Message to FriendA").assertIsDisplayed()
        runBlocking { store.recent(1).single().dismissedAt } shouldBe null
        rule.onNodeWithText("Message to FriendA").performClick(); waitText("There in 5")
    }

    @Test fun swipingRunningTaskStopsItsStep() {
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val work = object : Tool {
            override val spec = ToolSpec("work", "work", JsonObject(emptyMap()))
            override suspend fun run(args: JsonObject): JsonObject { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
            override fun label(args: JsonObject) = "Finding headphones"
            override fun summary(result: JsonObject): String? = null
        }
        val agent = Agent(store, Script(call("work")), listOf(work), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null) }
        ask("find headphones")
        rule.waitUntil(8_000) { entered.isCompleted }
        Espresso.pressBack(); waitText("find headphones")
        swipeTask("find headphones")
        rule.waitUntil(8_000) { runBlocking { store.recent(1).single().dismissedAt != null } }
        val s = runBlocking { store.recent(1).single() }
        s.steps.single().result shouldBe "stopped"
        s.status shouldBe SessionStatus.IDLE
        rule.onNodeWithText("find headphones").assertDoesNotExist()
        earlierTasks(); waitText("find headphones · Stopped.")
    }

    @Test fun swipeInEarlierDeletesSessionAndSavedTurns() {
        home(Script(text("Done."))); ask("test history"); waitText("Done.")
        val id = runBlocking { store.recent(1).single().id }
        runBlocking { store.turns(id).isNotEmpty() } shouldBe true
        Espresso.pressBack(); waitText(EARLIER); earlierTasks()
        waitText("test history · Done."); swipeTask("test history · Done.")
        rule.waitUntil(8_000) { runBlocking { store.load(id) == null } }
        rule.onNodeWithText("test history · Done.").assertDoesNotExist()
        runBlocking { store.turns(id) } shouldBe emptyList()
    }

    @Test fun deletingWaitingDraftInEarlierRefusesItsOldApproval() {
        val sent = mutableListOf<Payload>()
        val (tasks, _) = messaging(Script(draft), sent)
        ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        val s = runBlocking { store.recent(1).single() }
        val task = runBlocking { tasks.load((s.say as com.misura.core.agent.Say.Approve).taskId)!! }
        homeTap = 1L; waitText("Message to FriendA"); earlierTasks()
        waitText(earlierDraft); swipeTask(earlierDraft)
        rule.waitUntil(8_000) { runBlocking { store.load(s.id) == null } }
        runBlocking {
            store.turns(s.id) shouldBe emptyList()
            messageController.approve(task.id, task.payload!!.digest()) shouldBe null
            messageController.execute(task.id).state shouldBe TaskState.CANCELLED
        }
        sent shouldBe emptyList()
    }

    @Test fun editFocusesTheFieldAndARedraftReplacesTheCard() {
        val sent = mutableListOf<Payload>()
        val (tasks, _) = messaging(Script(draft, call("draft_message", """{"to":"FriendA","text":"Running 5 late"}""")), sent)
        ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        rule.onNodeWithText("Edit").performClick()
        rule.onNodeWithContentDescription(ASK_FIELD).assert(SemanticsMatcher.expectValue(SemanticsProperties.Focused, true))
        ask("say I'm running late"); waitText("Running 5 late")
        rule.onNodeWithText("There in 5", substring = true).assertDoesNotExist()
        rule.onAllNodes(hasText("will")).fetchSemanticsNodes().size shouldBe 0 // the replaced draft leaves no will line
        sent.size shouldBe 0
        // The replaced draft is retired, so nothing else (e.g. the sleep screen) lists it as waiting for you.
        runBlocking { tasks.unfinished().count { it.state == TaskState.NEEDS_APPROVAL } } shouldBe 1
    }

    @Test fun aPressThatStartedOnTheOldDraftNeverSendsTheNewOne() {
        val sent = mutableListOf<Payload>()
        val (_, agent) = messaging(Script(draft, call("draft_message", """{"to":"FriendA","text":"Running 5 late"}""")), sent)
        ask("tell FriendA we'll be there in 5"); waitText("There in 5")
        val at = rule.onNodeWithText("Send").fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(at) }
        val id = runBlocking { store.recent(1).single().id }
        agent.followUp(id, "say I'm late"); waitText("Running 5 late")
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle(); Thread.sleep(500)
        sent.size shouldBe 0
        rule.onNodeWithText("Send").assertExists() // the new draft still waits for its own tap
    }

    @Test fun runOneToDecision() {
        home(Script(find, show)); ask("best bibimbap")
        waitText("PICK")
        rule.onNodeWithTag(CAROUSEL_TAG).assertExists(); selected("Namsan")
        rule.onNodeWithText("Namsan is the quiet one").assertExists()
        rule.onNodeWithText("1 step ›", substring = true).assertExists()
    }

    @Test fun tapPlaceOpensDetailAndBackRestoresIndex() {
        home(Script(find, show)); ask("best bibimbap"); waitText("PICK")
        rule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft(startX = width * 0.7f, endX = width * 0.3f) }; rule.waitForIdle() // a thumb-length swipe
        rule.onNodeWithText("Kimchi Princess").performClick()
        waitText("‹ 3 places")
        Espresso.pressBack(); rule.waitForIdle()
        selected("Kimchi Princess")
    }

    @Test fun backAtRootGoesHomeAndAFinishedTaskIsBehindEarlier() {
        home(Script(find, show)); ask("best bibimbap"); waitText("PICK")
        Espresso.pressBack(); rule.waitForIdle()
        waitText(EARLIER)
        rule.onAllNodes(hasText("best bibimbap", substring = true)).assertCountEquals(0) // finished: not on home
        rule.onNodeWithText(EARLIER).performClick()
        waitText("best bibimbap · 3 places")
        rule.onNodeWithText("best bibimbap · 3 places").performClick()
        waitText("PICK")
    }

    @Test fun focusingFieldDocksFrontCardAndFollowUpCarriesPlace() {
        val brain = Script(find, show, text("Friday 12–22"))
        home(brain); ask("best bibimbap"); waitText("PICK")
        waitKeyboardClosed()
        rule.onNodeWithContentDescription(ASK_FIELD).performClick()
        waitDocked()
        ask("is it open Friday?")
        waitText("Friday 12–22")
        brain.seen.last().last().toString() shouldContain "(Looking at: place id P1)"
        brain.seen.last().last().toString().contains("Namsan") shouldBe false
    }

    @Test fun answerKeepsCarousel() {
        home(Script(find, show, text("Friday 12–22"))); ask("best bibimbap"); waitText("PICK")
        ask("is it open Friday?"); waitText("Friday 12–22")
        rule.onNodeWithText("PICK").assertExists()
    }

    @Test fun problemTryAgainResends() {
        home(Script({ throw BrainError("x", code = "503") }, text("Here you go")))
        ask("best bibimbap"); waitText("Try again")
        rule.onNodeWithText("Try again").performClick()
        waitText("Here you go")
    }

    @Test fun askAfterShowPlacesShowsQuestionOverCarousel() {
        home(Script(find, show, call("ask", """{"line":"Which night?","choices":["Friday","Saturday"]}""")))
        ask("best bibimbap"); waitText("PICK")
        ask("book one"); waitText("Which night?")
        rule.onNodeWithText("Saturday").assertExists()
        rule.onNodeWithTag(CAROUSEL_TAG).assertExists()
    }

    @Test fun failedFollowUpShowsProblemOverDetail() {
        home(Script(find, show, { throw BrainError("x", code = "503") }))
        ask("best bibimbap"); waitText("PICK")
        rule.onNodeWithText("Namsan").performClick(); waitText("‹ 3 places")
        ask("is it open Friday?"); waitText("Try again")
        rule.onNodeWithText("‹ 3 places").assertExists()
    }

    @Test fun notificationOpensItsSession() {
        runBlocking { store.save(Session("n1", SessionStatus.FAILED, 1, System.currentTimeMillis(), say = com.misura.core.agent.Say.Problem("Misura can't reach the model (503). Nothing was sent."))) }
        home(Script(), open = "n1")
        waitText("Try again")
    }

    @Test fun recreateKeepsOpenTaskAndDetail() {
        val agent = Agent(store, Script(find, show), listOf(places), System::currentTimeMillis, scope)
        val tester = StateRestorationTester(rule)
        tester.setContent { HomeScreen(agent, store, photos = null, openRequest = null) }
        ask("best bibimbap"); waitText("PICK")
        rule.onNodeWithText("Namsan").performClick(); waitText("‹ 3 places")
        tester.emulateSavedInstanceStateRestore()
        waitText("‹ 3 places")
    }

    @Test fun notificationWhileTypingDropsTheOldPlaceNote() {
        runBlocking { store.save(Session("b", SessionStatus.IDLE, 1, System.currentTimeMillis(), say = com.misura.core.agent.Say.Answer("Earlier answer"))) }
        val brain = Script(find, show, text("ok"))
        val agent = Agent(store, brain, listOf(places), System::currentTimeMillis, scope)
        var request by androidx.compose.runtime.mutableStateOf<OpenRequest?>(null)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = request) }
        ask("best bibimbap"); waitText("PICK")
        waitKeyboardClosed()
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("is it open?")
        waitDocked() // the draft really is about Namsan before the switch
        rule.runOnIdle { request = OpenRequest("b", 1) }
        waitText("Earlier answer")
        rule.onNodeWithText("Ask").performClick()
        rule.waitUntil(8_000) { runBlocking { store.turns("b").isNotEmpty() } }
        brain.seen.last().last().toString().contains("Looking at") shouldBe false
    }

    @Test fun clearedDockSurvivesRecreation() {
        val agent = Agent(store, Script(find, show, text("new task")), listOf(places), System::currentTimeMillis, scope)
        val tester = StateRestorationTester(rule)
        tester.setContent { HomeScreen(agent, store, photos = null, openRequest = null) }
        ask("best bibimbap"); waitText("PICK")
        waitKeyboardClosed()
        rule.onNodeWithContentDescription(ASK_FIELD).performClick()
        waitText("✕", 5_000)
        rule.onNodeWithText("✕").performClick()
        rule.onNodeWithContentDescription(ASK_FIELD).performTextInput("ramen in Mitte")
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Ask").performClick()
        waitText("new task")
        rule.onAllNodes(hasText("ramen in Mitte", substring = true)).fetchSemanticsNodes().isNotEmpty() shouldBe true // a new task, about what you asked
        rule.onNodeWithText("PICK").assertDoesNotExist()
    }

    @Test fun followUpWithoutToolsShowsNoOldSteps() {
        home(Script(find, show, { Thread.sleep(4_000); text("Friday 12–22")() }))
        ask("best bibimbap"); waitText("PICK")
        ask("is it open Friday?")
        waitText("is it open Friday?")
        // The first exchange's steps are part of the thread now: one scroll up, not on the screen in front.
        rule.waitUntil(3_000) { runCatching { rule.onNodeWithText("3 places found", substring = true).assertIsNotDisplayed() }.isSuccess }
        waitText("Friday 12–22")
        rule.onNodeWithText("3 places found", substring = true).assertIsNotDisplayed()
        rule.onNodeWithText("Friday 12–22").assertIsDisplayed()
    }

    @Test fun oldRowSurvivesUpgrade() {
        runBlocking { db.dao().upsertSession(SessionRow("old", System.currentTimeMillis(), """{"id":"old","status":"IDLE","card":{"type":"text","line":"Hi there"},"revision":1,"createdAt":${System.currentTimeMillis()}}""")) }
        home(Script())
        waitText(EARLIER); rule.onNodeWithText(EARLIER).performClick()
        waitText("Hi there")
    }

    @Test fun oldPlacesRenderStale() {
        val old = System.currentTimeMillis() - 31L * 24 * 3_600_000
        runBlocking {
            store.save(Session("s", SessionStatus.IDLE, 2, old, places = Places(listOf("P1", "P2"), "P1"), say = com.misura.core.agent.Say.Answer("Namsan opens at 12")))
            store.append("s", Turn("user", buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject { put("text", "bibimbap") }) }) }))
            store.append("s", Turn("user", buildJsonObject { put("role", "user"); put("parts", buildJsonArray { add(buildJsonObject {
                putJsonObject("functionResponse") { put("name", "find_places"); put("response", runBlocking { places.run(JsonObject(emptyMap())) }) } }) }) }))
        }
        home(Script(), open = "s")
        waitText("Namsan")
        rule.onNodeWithTag(MAP_TAG).assertDoesNotExist()
        rule.onNodeWithText("Older answer. Ask again for current details.").assertExists()
    }

    /** Stands in for use_app after it browsed an app: two items, one with a real JPEG on disk and one without a photo. */
    private fun itemsHome(brain: Brain, opened: MutableList<String>): Agent {
        val jpg = java.io.File(ctx.filesDir, "item-test.jpg").also { f ->
            val b = android.graphics.Bitmap.createBitmap(600, 600, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF7A8F9E.toInt()) }
            f.outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
        }
        val useApp = object : Tool {
            override val spec = ToolSpec("use_app", "browse an app", JsonObject(emptyMap()))
            override suspend fun run(args: JsonObject) = Json.parseToJsonElement("""{"items":[
                {"id":"i1","title":"Blue jacket","price":"120,00 €","detail":"Size M, like new","photo":${Json.encodeToString(kotlinx.serialization.serializer<String>(), jpg.path)}},
                {"id":"i2","title":"Leather boots","price":"35,00 €","detail":"Size 42"}],"itemsFrom":{"app":"Vinted","pkg":"fr.vinted.test"}}""").jsonObject
            override fun label(args: JsonObject) = "Looking in Vinted"
            override fun summary(result: JsonObject) = "2 items found"
        }
        val agent = Agent(store, brain, listOf(useApp), System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, store, photos = null, openRequest = null, onOpenItem = { opened += it.id }) }
        return agent
    }
    private val showItems = call("show_items", """{"itemIds":["i1","i2"],"pick":"i1","line":"The jacket is the better fit"}""")

    @Test fun itemsShowAsSwipeCardsAndATapOpensTheItem() {
        val opened = mutableListOf<String>()
        itemsHome(Script(call("use_app"), showItems), opened); ask("jackets on vinted"); waitText("Blue jacket")
        waitText("The jacket is the better fit")
        rule.onNodeWithText("PICK").assertExists()
        rule.onNodeWithText("120,00 €").assertExists(); rule.onNodeWithText("Size M, like new").assertExists()
        rule.onAllNodes(hasText("Vinted")).fetchSemanticsNodes().size shouldBe 2 // quiet source text on each card
        rule.onNodeWithTag(CAROUSEL_TAG).performTouchInput { swipeLeft() }
        waitText("Leather boots"); rule.onNodeWithText("35,00 €").assertExists()
        rule.onNodeWithText("Leather boots").performClick()
        rule.waitUntil(3_000) { opened.isNotEmpty() }
        opened shouldBe listOf("i2")
    }

    @Test fun aFollowUpWhileAnItemIsInFrontCarriesItsId() {
        val brain = Script(call("use_app"), showItems, text("Yes, still there"))
        itemsHome(brain, mutableListOf()); ask("jackets on vinted"); waitText("Blue jacket")
        waitKeyboardClosed()
        rule.onNodeWithContentDescription(ASK_FIELD).performClick()
        waitDocked()
        ask("is it still there?"); waitText("Yes, still there")
        brain.seen.last().last().toString() shouldContain "(Looking at: item id i1)"
        brain.seen.last().last().toString().contains("Blue jacket") shouldBe false
    }
}
