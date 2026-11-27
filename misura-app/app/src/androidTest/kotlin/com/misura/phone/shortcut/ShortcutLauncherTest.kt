package com.misura.phone.shortcut

import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.shortcut.LinkTemplate
import com.misura.core.shortcut.SettingsPage
import com.misura.core.shortcut.Target
import com.misura.phone.MisuraGraph
import com.misura.phone.screen.AppSheetState
import com.misura.phone.screen.SheetEntry
import com.misura.phone.screen.launchSheetEntry
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Needs Misura as the Home app (shortcut host permission) on the emulator and the debug `testapp` fixture installed. */
@RunWith(AndroidJUnit4::class)
class ShortcutLauncherTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val ctx = inst.targetContext
    private val launcher = ShortcutLauncher(ctx)
    private val noPark: suspend (String) -> Boolean = { false }

    private fun shell(cmd: String): String = inst.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(cmd)
        .use { ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText() }
    private fun control(op: String, vararg extra: String) =
        shell("am broadcast --include-stopped-packages -a com.misura.testapp.SHORTCUTS -p $PKG --es op $op ${extra.joinToString(" ")}")
    private fun opened() = shell("run-as $PKG cat files/opened.txt").trim()
    private suspend fun until(block: () -> Boolean): Boolean { repeat(50) { if (block()) return true; delay(100) }; return block() }
    private fun labels() = launcher.published(PKG).associate { it.id to it.label }

    @Before fun setUp() {
        check(ctx.getSystemService(android.content.pm.LauncherApps::class.java).hasShortcutHostPermission()) { "Misura must be the Home app" }
        shell("am force-stop $PKG"); shell("run-as $PKG rm -f files/opened.txt")
        control("remove"); control("publish")
        runBlocking { check(until { labels().keys.containsAll(listOf("fx_a", "fx_b")) }) { "fixture shortcuts not published" } }
    }

    @After fun tearDown() {
        launcher.pin(PKG, emptySet()); control("remove")
        shell("am force-stop $PKG"); shell("input keyevent KEYCODE_HOME")
    }

    @Test fun opensDynamicShortcutOfFixture(): Unit = runBlocking {
        launcher.resolve(Target.AppShortcut(PKG, "fx_a")) shouldBe true
        launcher.open(Target.AppShortcut(PKG, "fx_a"), noPark) shouldBe true
        until { opened() == "opened=fx_a" } shouldBe true
    }

    @Test fun pinnedCopySurvivesDynamicRemoval(): Unit = runBlocking {
        launcher.pin(PKG, setOf("fx_a")) shouldBe true
        launcher.rawPinned(PKG)!! shouldBe setOf("fx_a")
        control("remove")
        until { "fx_b" !in labels() } shouldBe true
        launcher.rawPinned(PKG)!! shouldBe setOf("fx_a")
        launcher.open(Target.AppShortcut(PKG, "fx_a"), noPark) shouldBe true
        until { opened() == "opened=fx_a" } shouldBe true
    }

    @Test fun labelChangeDetected(): Unit = runBlocking {
        labels()["fx_a"] shouldBe "Fixture A"
        control("label", "--es", "label", "Renamed")
        until { labels()["fx_a"] == "Renamed" } shouldBe true
    }

    @Test fun missingShortcutReturnsFalse(): Unit = runBlocking {
        launcher.resolve(Target.AppShortcut(PKG, "fx_none")) shouldBe false
        launcher.open(Target.AppShortcut(PKG, "fx_none"), noPark) shouldBe false
        launcher.open(Target.AppShortcut("com.misura.nothere", "x"), noPark) shouldBe false
        opened() shouldBe ""
    }

    @Test fun settingsPageOpensAndUnavailableRefused(): Unit = runBlocking {
        // Every table page resolves through the manifest's scoped <queries>.
        SettingsPage.values().forEach { page ->
            val t = if (page == SettingsPage.APP_DETAILS) Target.Settings(page, PKG) else Target.Settings(page)
            check(launcher.resolve(t)) { "$page does not resolve" }
        }
        val parked = mutableListOf<String>()
        launcher.open(Target.Settings(SettingsPage.WIFI)) { parked += it; false } shouldBe true
        parked.size shouldBe 1 // handoff asked for the handler's package first
        shell("input keyevent KEYCODE_HOME")
        val none = ShortcutLauncher(ctx, actions = { "com.misura.test.NO_SUCH_SETTINGS" })
        none.resolve(Target.Settings(SettingsPage.WIFI)) shouldBe false
        parked.clear()
        none.open(Target.Settings(SettingsPage.WIFI)) { parked += it; false } shouldBe false
        parked shouldBe emptyList()
    }

    /** Spotify is absent, a decoy handles `spotify:`: the link must be refused, not handed to the decoy (red when setPackage is dropped). */
    @Test fun linkIsPackageScopedAndFailsClosed(): Unit = runBlocking {
        val spotify = Target.Link(LinkTemplate.SPOTIFY_PAGE, "playlist:" + "a".repeat(22))
        check(ctx.packageManager.getLaunchIntentForPackage("com.spotify.music") == null) { "Spotify must be absent on the test device" }
        shell("run-as $PKG rm -f files/decoy.txt")
        val i = launcher.linkIntent(spotify)!!
        i.`package` shouldBe "com.spotify.music"
        i.dataString shouldBe "spotify:playlist:" + "a".repeat(22)
        launcher.linkIntent(Target.Link(LinkTemplate.MAPS_ROUTE, "Home"))!!.also {
            it.`package` shouldBe "com.google.android.apps.maps"; it.dataString shouldBe "https://www.google.com/maps/dir/?api=1&destination=Home" }
        launcher.linkIntent(Target.Link(LinkTemplate.MAPS_ROUTE, "Nowhere")) shouldBe null
        MisuraGraph.openTarget(ctx, spotify) shouldBe false
        MisuraGraph.openTarget(ctx, Target.Link(LinkTemplate.MAPS_ROUTE, "Nowhere")) shouldBe false
        delay(1_000)
        shell("run-as $PKG cat files/decoy.txt").trim() shouldBe ""
    }

    /** A launch that throws (uninstall/disable race) is a refusal through the production opener, not an exception at the caller. */
    @Test fun throwingAppLaunchIsRefusedThroughOpenTarget(): Unit = runBlocking {
        val prior = MisuraGraph.appOpener
        try {
            MisuraGraph.appOpener = { _, _ -> throw android.content.ActivityNotFoundException("gone") }
            MisuraGraph.openTarget(ctx, Target.App(PKG)) shouldBe false
            MisuraGraph.appOpener = { _, _ -> throw SecurityException("no") }
            MisuraGraph.openTarget(ctx, Target.App(PKG)) shouldBe false
        } finally { MisuraGraph.appOpener = prior }
    }

    /** The app sheet's shortcut launch goes through openTarget: the parked-screen handoff is asked for first (fails on source that calls LauncherApps directly). */
    @Test fun sheetShortcutLaunchGoesThroughOpenTarget(): Unit = runBlocking {
        val asked = mutableListOf<String>()
        val prior = MisuraGraph.bringParked
        MisuraGraph.bringParked = { asked += it; false }
        try {
            val state = inst.runOnMainSyncResult { AppSheetState(ctx, CoroutineScope(Dispatchers.Main + object : androidx.compose.runtime.MonotonicFrameClock { override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(System.nanoTime()) }), View(ctx)) {} }
            launchSheetEntry(ctx, state, SheetEntry(PKG, "TestChat", "fx_a", "Fixture A"), {})
            until { opened() == "opened=fx_a" } shouldBe true
            asked shouldContain PKG
        } finally { MisuraGraph.bringParked = prior }
    }

    private fun <T> android.app.Instrumentation.runOnMainSyncResult(f: () -> T): T { var r: Any? = null; runOnMainSync { r = f() }; @Suppress("UNCHECKED_CAST") return r as T }

    private companion object { const val PKG = "com.misura.testapp" }
}
