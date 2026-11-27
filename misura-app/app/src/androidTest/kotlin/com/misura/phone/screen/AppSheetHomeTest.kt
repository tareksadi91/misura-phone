package com.misura.phone.screen

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Agent
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.Tool
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
import com.misura.phone.HomeScreen
import com.misura.phone.shortcut.KeepResult
import com.misura.phone.shortcut.ShortcutKeeper
import com.misura.phone.shortcut.ShortcutLauncher
import com.misura.phone.shortcut.ShortcutPins
import com.misura.phone.shortcut.ShortcutStore
import com.misura.phone.shortcut.ShortcutTargets
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomSessionStore
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The app list's long-press menu puts an app or one of its published shortcuts on Home, through the real HomeScreen, a real
 * [ShortcutStore] and the real [ShortcutKeeper] (pins faked): the menu never writes the store itself.
 */
class AppSheetHomeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dbName = "app-sheet-home-test.db"
    private lateinit var db: MisuraDb
    private lateinit var sessions: RoomSessionStore
    private lateinit var store: ShortcutStore
    private lateinit var pins: FakePins
    private lateinit var keeper: ShortcutKeeper
    private val targets = FakeTargets()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pkg = "com.example.club"

    private class FakePins : ShortcutPins {
        private val pinned = mutableMapOf<String, Set<String>>()
        override fun rawPinned(pkg: String): Set<String>? = synchronized(this) { pinned[pkg].orEmpty() }
        override fun pin(pkg: String, ids: Set<String>): Boolean { synchronized(this) { pinned[pkg] = ids }; return true }
        fun of(pkg: String): Set<String> = synchronized(this) { pinned[pkg].orEmpty() }
    }

    private class FakeTargets : ShortcutTargets {
        @Volatile var shortcuts = listOf(ShortcutLauncher.Published("a", "Check in", true), ShortcutLauncher.Published("b", "Book a class", true))
        @Volatile var launchable = true
        override fun published(pkg: String) = shortcuts
        override fun resolve(target: Target) = launchable
        override fun installed(pkg: String) = true
    }

    private object NoBrain : Brain {
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = error("no brain")
    }
    private val noTools = emptyList<Tool>()

    private fun prefs() = ctx.getSharedPreferences("shortcuts", android.content.Context.MODE_PRIVATE)

    @Before fun setUp() {
        ctx.deleteDatabase(dbName); db = MisuraDb.open(ctx, dbName); sessions = RoomSessionStore(db)
        prefs().edit().clear().commit()
        ctx.getSharedPreferences("first-use", 0).edit().putBoolean("apps-opened", true).commit()
        store = ShortcutStore(ctx); pins = FakePins(); keeper = ShortcutKeeper(store, pins)
    }

    @After fun tearDown() {
        prefs().edit().clear().commit()
        ctx.getSharedPreferences("first-use", 0).edit().clear().commit()
        db.close()
    }

    private fun show(home: Boolean = true) {
        val agent = Agent(sessions, NoBrain, noTools, System::currentTimeMillis, scope)
        rule.setContent { HomeScreen(agent, sessions, null, null, apps = listOf("Club" to pkg, "Other" to "com.example.other"),
            shortcutStore = store, shortcutKeeper = keeper, sheetHome = if (home) SheetHome(keeper, store, targets) else null) }
    }

    private fun count(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().size
    private fun waitFor(m: SemanticsMatcher, ms: Long = 8_000) = rule.waitUntil(ms) { count(m) > 0 }
    private fun openMenu() {
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .7f) }
        val row = notHomeLine("Club")
        waitFor(row)
        rule.onNode(row).performTouchInput { longClick() }
        waitFor(hasText("App info"))
    }
    /** Home itself also lists kept shortcuts by title; the sheet and its menu are everything else. */
    private fun notHomeLine(text: String) = hasText(text) and !hasContentDescription(", shortcut", substring = true)
    private fun click(m: SemanticsMatcher) { waitFor(m); rule.onNode(m).performClick() }
    private val add = hasContentDescription("Add Check in to Home")
    private val remove = hasContentDescription("Remove Check in from Home")
    private val full get() = hasText("Home is full. Replace one:")
    private fun kept(title: String, target: Target, id: String = "id-$title") =
        check(store.keep(Shortcut(id, title, null, null, target, createdAt = 1), null) == KeepResult.Kept)
    private fun fill() { kept("One", Target.AppShortcut("com.x", "1")); kept("Two", Target.AppShortcut("com.y", "2")); kept("Three", Target.AppShortcut("com.z", "3")) }

    @Test fun plusHomeKeepsPublishedShortcut() {
        show(); openMenu()
        click(add)
        waitFor(remove)
        waitFor(hasContentDescription("Added to Home"))
        val s = store.all().single()
        s.target shouldBe Target.AppShortcut(pkg, "a")
        s.title shouldBe "Club · Check in"
        s.prompt shouldBe null
        pins.of(pkg) shouldBe setOf("a")
        count(hasText("App info")) shouldBe 1 // the menu stays open
        waitFor(hasContentDescription(", shortcut", substring = true)) // with Home free, the new line shows under the open menu
    }

    @Test fun addAppToHome() {
        show(); openMenu()
        click(hasText("Add to Home"))
        waitFor(hasText("On Home · remove"))
        val s = store.all().single()
        s.target shouldBe Target.App(pkg)
        s.title shouldBe "Club"
    }

    @Test fun onHomeRemoves() {
        kept("Club · Check in", Target.AppShortcut(pkg, "a")); kept("Club", Target.App(pkg))
        keeper.keep(Shortcut("p", "pin", null, null, Target.AppShortcut(pkg, "b"), createdAt = 1), null) { true }
        pins.of(pkg).contains("b") shouldBe true
        show(); openMenu()
        waitFor(remove)
        click(hasText("On Home · remove"))
        rule.waitUntil(8_000) { store.keptFor(Target.App(pkg)) == null }
        waitFor(hasText("Add to Home"))
        click(hasContentDescription("Remove Book a class from Home"))
        rule.waitUntil(8_000) { store.keptFor(Target.AppShortcut(pkg, "b")) == null }
        rule.waitUntil(8_000) { !pins.of(pkg).contains("b") } // the keeper unpins right after its store write: the menu's removal unpins like every other route
        waitFor(hasContentDescription("Add Book a class to Home"))
    }

    @Test fun fullShowsThreeAndReplacesPicked() {
        fill(); show(); openMenu()
        click(add)
        waitFor(full)
        listOf("One", "Two", "Three", "Cancel").forEach { count(notHomeLine(it)) shouldBe 1 }
        click(notHomeLine("Two"))
        rule.waitUntil(8_000) { store.all().any { it.target == Target.AppShortcut(pkg, "a") } }
        store.all().map { it.title }.toSet() shouldBe setOf("One", "Three", "Club · Check in")
        waitFor(remove)
        count(full) shouldBe 0
    }

    @Test fun staleReplacementRebuildsPicker() {
        fill(); show(); openMenu()
        click(add); waitFor(full)
        store.remove("id-Two") // another route removed the line the picker still shows
        kept("Four", Target.AppShortcut("com.w", "4"))
        click(notHomeLine("Two"))
        rule.waitUntil(8_000) { count(notHomeLine("Four")) > 0 }
        waitFor(full)
        count(notHomeLine("Two")) shouldBe 0
        store.all().map { it.title }.toSet() shouldBe setOf("One", "Three", "Four")
    }

    @Test fun staleReplacementWithRoomWritesNothing() {
        fill(); show(); openMenu()
        click(add); waitFor(full)
        store.remove("id-Two") // the chosen line disappears and nothing fills its slot: Home has room now
        click(notHomeLine("Two"))
        waitFor(add) // the fresh menu offers + Home again; the user taps
        count(full) shouldBe 0
        store.all().map { it.title } shouldBe listOf("One", "Three") // nothing was written on its own
        pins.of(pkg) shouldBe emptySet()
    }

    private fun tallMenu() { targets.shortcuts = List(6) { ShortcutLauncher.Published(if (it == 0) "a" else "s$it", if (it == 0) "Check in" else "Class $it", true) } }

    @Test fun tallMenuStillAnnouncesAddedToHome() {
        tallMenu(); show(); openMenu()
        rule.onNode(hasText("App info")).assertIsNotDisplayed() // the menu really is taller than its scroll area
        click(add); waitFor(remove)
        waitFor(hasContentDescription("Added to Home"))
        rule.onNode(hasContentDescription("Added to Home")).assertIsDisplayed() // not clipped out of the accessibility tree
    }

    @Test fun tallMenuStillShowsStorageFailure() {
        tallMenu(); show(); openMenu()
        prefs().edit().putString("all", "{not a list").commit()
        click(add)
        waitFor(hasText("Not done: Misura couldn't save progress."))
        rule.onNode(hasText("Not done: Misura couldn't save progress.")).assertIsDisplayed()
    }

    @Test fun cancelKeepsAll() {
        fill(); show(); openMenu()
        click(add); waitFor(full)
        click(hasText("Cancel"))
        waitFor(add)
        count(full) shouldBe 0
        store.all().map { it.title } shouldBe listOf("One", "Two", "Three")
    }

    @Test fun splitRowHasTwoAccessibleActions() {
        show(); openMenu()
        waitFor(hasContentDescription("Open Check in")); waitFor(add)
        waitFor(hasContentDescription("Open Book a class")); waitFor(hasContentDescription("Add Book a class to Home"))
        val open = rule.onNode(hasContentDescription("Open Check in")).fetchSemanticsNode()
        val toggle = rule.onNode(add).fetchSemanticsNode()
        check(toggle.boundsInRoot.height >= open.boundsInRoot.height * 0.9f)
        // Both targets meet the 48dp minimum, measured in dp at this device's density.
        val dp = rule.activity.resources.displayMetrics.density
        listOf(open, toggle).forEach { n ->
            check(n.boundsInRoot.height / dp >= 47.5f && n.boundsInRoot.width / dp >= 47.5f) { "touch target ${n.boundsInRoot.width / dp}x${n.boundsInRoot.height / dp} dp < 48dp" }
        }
        check(open.id != toggle.id)
        (SemanticsProperties.ContentDescription in toggle.config) shouldBe true
    }

    @Test fun storageFailureShownNotKept() {
        show(); openMenu()
        prefs().edit().putString("all", "{not a list").commit()
        click(add)
        waitFor(hasText("Not done: Misura couldn't save progress."))
        count(hasText("App info")) shouldBe 1
        waitFor(add)
        prefs().getString("all", null) shouldBe "{not a list"
    }

    @Test fun targetChangedRereadsList() {
        show(); openMenu()
        targets.shortcuts = listOf(ShortcutLauncher.Published("a", "Check in later", true))
        click(add)
        waitFor(hasContentDescription("Add Check in later to Home"))
        count(hasContentDescription("Open Book a class")) shouldBe 0
        store.all() shouldBe emptyList()
    }

    @Test fun addedWhileTasksFillHomeStillConfirms() {
        // Four task lines would hide the new Home line; the menu itself still says so.
        runBlockingSeed(4)
        show(); openMenu()
        click(add)
        waitFor(remove); waitFor(hasContentDescription("Added to Home"))
        store.all().size shouldBe 1
        // The four tasks keep Home's lines: the new shortcut really is hidden there, so the menu's own confirmation is the only one.
        Thread.sleep(1_000); rule.waitForIdle() // past Home's next poll
        count(hasText("App info")) shouldBe 1 // the menu is still up
        count(hasContentDescription(", shortcut", substring = true)) shouldBe 0 // (with no tasks the same line shows: plusHomeKeepsPublishedShortcut)
    }

    /**
     * Codex final P2: a store write is in progress (its lock held) while the owner long-presses an app. The UI thread never waits on
     * it: the long-press returns at once, the menu is not up yet, and it appears once the write lets go.
     */
    @Test fun menuOpenNeverBlocksOnAStoreWrite() {
        show()
        rule.onRoot().performTouchInput { swipeDown(startY = height * .15f, endY = height * .7f) }
        val row = notHomeLine("Club"); waitFor(row)
        val lock = ShortcutStore::class.java.getDeclaredField("LOCK").apply { isAccessible = true }.get(null)!!
        val holding = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread { synchronized(lock) { holding.countDown(); release.await(4, java.util.concurrent.TimeUnit.SECONDS) } }.apply { start() }
        try {
            check(holding.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val t0 = android.os.SystemClock.uptimeMillis()
            rule.onNode(row).performTouchInput { longClick() }
            val took = android.os.SystemClock.uptimeMillis() - t0
            // The main thread still answers while the write holds the lock.
            val probe = java.util.concurrent.CountDownLatch(1)
            android.os.Handler(android.os.Looper.getMainLooper()).post { probe.countDown() }
            check(probe.await(1, java.util.concurrent.TimeUnit.SECONDS)) { "main thread blocked" }
            check(took < 2_500) { "long-press blocked the UI for $took ms" }
            count(hasText("App info")) shouldBe 0 // not opened under the held lock
        } finally { release.countDown(); writer.join(5_000) }
        waitFor(hasText("App info")); waitFor(add)
    }

    @Test fun noHomeInjectedKeepsMenuUnchanged() {
        show(home = false); openMenu()
        count(hasText("Check in")) shouldBe 0 // the fake launcher is not consulted
        count(hasText("Add to Home")) shouldBe 0
        count(hasText("Pin")) shouldBe 1
    }

    @Test fun legacyMenuKeepsFullHeightScrollViewport() {
        rule.setContent { MenuPopup(onDismiss = {}) { repeat(12) { MenuItem("Row $it") {} } } }
        rule.waitUntil(8_000) { count(hasText("Row 0")) > 0 }
        val dp = rule.activity.resources.displayMetrics.density
        val scroller = rule.onNode(androidx.compose.ui.test.hasScrollAction()).fetchSemanticsNode().boundsInRoot
        // Padding sits inside the scroll, so the viewport is the whole 360dp card (not 348dp inside 6dp gutters).
        (scroller.height / dp) shouldBe (360f plusOrMinus .5f)
    }

    private fun runBlockingSeed(n: Int) = kotlinx.coroutines.runBlocking {
        repeat(n) { i ->
            val id = "t$i"
            sessions.save(com.misura.core.agent.Session(id, com.misura.core.agent.SessionStatus.IDLE, 0, System.currentTimeMillis(),
                say = com.misura.core.agent.Say.Question("Which?", listOf("A", "B"))))
            sessions.append(id, Turn("user", buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray { add(buildJsonObject { put("text", "Task $i") }) })
            }))
        }
    }
}
