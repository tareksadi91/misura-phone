package com.misura.phone.hands

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.phone.brain.TokenProvider
import com.misura.phone.store.privateString
import com.misura.phone.brain.VertexBrain
import com.misura.phone.hidden.ScreenReader
import com.misura.phone.hidden.ShizukuBridge
import com.misura.phone.hidden.ShizukuHiddenScreen
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Gemini driving real apps on the hidden display. A report, not a gate: read the MisuraEval log lines.
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.misura.phone.hands.UseAppEval \
 *   -Pandroid.testInstrumentationRunnerArguments.token=$(gcloud auth application-default print-access-token) [-P...only=3]
 */
@RunWith(AndroidJUnit4::class)
class UseAppEval {
    private val args = InstrumentationRegistry.getArguments()
    private val tasks = listOf(
        "com.google.android.deskclock" to "Set an alarm for 7:15 in the morning",
        "com.google.android.deskclock" to "Start a 3 minute timer",
        "com.google.android.contacts" to "Create a contact named Test Person with phone number 030 1234567",
        "com.google.android.contacts" to "What is Test Person's phone number?",
        "com.android.settings" to "Which Android version is this phone running?",
        "com.android.settings" to "Turn on dark theme",
        "com.android.chrome" to "Open the English Wikipedia article on Kreuzberg and tell me its population",
        "com.google.android.apps.maps" to "What time does Markthalle Neun in Berlin close today?",
        "com.google.android.calendar" to "Create an event called Dentist next Tuesday at 10:00",
        "com.google.android.youtube" to "Search for lofi and tell me the title of the first result",
    )

    @Test fun report() = runBlocking<Unit> {
        val token = args.getString("token").orEmpty()
        assumeTrue("pass -e token", token.isNotBlank())
        // Same dance as HiddenChatTest: this UiAutomation flag keeps accessibility services alive; a reinstall needs the setting re-toggled.
        val ui = InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun sh(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).readBytes()
        repeat(50) { if (ShizukuBridge.ready()) return@repeat; Thread.sleep(100) }
        if (!ScreenReader.connected()) {
            sh("settings put secure enabled_accessibility_services null"); Thread.sleep(300)
            sh("settings put secure enabled_accessibility_services com.misura.phone/com.misura.phone.hidden.MisuraAccessibility"); sh("settings put secure accessibility_enabled 1")
        }
        repeat(50) { if (ScreenReader.connected()) return@repeat; Thread.sleep(100) }
        check(ShizukuBridge.ready()) { "Shizuku not running or Misura not granted" }
        check(ScreenReader.connected()) { "accessibility service not connected" }
        val prefs = InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("brain", android.content.Context.MODE_PRIVATE)
        val project = { com.misura.phone.brain.vertexProject(prefs.privateString(com.misura.phone.debug.SA_KEY), prefs.getString("project", null)) }
        val tool = UseApp(ShizukuHiddenScreen(InstrumentationRegistry.getInstrumentation().targetContext), VertexBrain(TokenProvider { token }, project), log = { Log.i("MisuraEval", it) })
        val only = args.getString("only")?.split(',')?.map { it.toInt() }
        tasks.forEachIndexed { i, (app, goal) ->
            if (only != null && i !in only) return@forEachIndexed
            val t0 = System.currentTimeMillis()
            val r = runCatching { tool.run(buildJsonObject { put("app", app); put("goal", goal) }).toString() }.getOrElse { "THREW $it" }
            Log.i("MisuraEval", "TASK $i | $goal | ${(System.currentTimeMillis() - t0) / 1000}s | $r")
        }
    }
}
