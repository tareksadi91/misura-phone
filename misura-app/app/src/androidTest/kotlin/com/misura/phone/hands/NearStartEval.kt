package com.misura.phone.hands

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.misura.core.agent.Brain
import com.misura.core.agent.BrainReply
import com.misura.core.agent.ToolSpec
import com.misura.core.agent.Turn
import com.misura.phone.brain.TokenProvider
import com.misura.phone.brain.VertexBrain
import com.misura.phone.hidden.ScreenReader
import com.misura.phone.hidden.ShizukuBridge
import com.misura.phone.hidden.ShizukuHiddenScreen
import com.misura.phone.store.privateString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Paired live eval for "near me starts at the user" (owner 26 Nov): the real hand model on the testapp Gyms page (its default order is not by
 * distance, the nearest is past where a capped run looks, a "Sort: distance" control and a "Near me" that asks Android for location), with the
 * new near prompt and with the prior one (the same turns, the near line rewritten back). A report, not a gate: read the MisuraEval lines.
 * Paid model calls: opt-in only.
 * ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.misura.phone.hands.NearStartEval \
 *   -Pandroid.testInstrumentationRunnerArguments.token=$(gcloud auth application-default print-access-token) [-P...runs=3]
 */
@RunWith(AndroidJUnit4::class)
class NearStartEval {
    private val args = InstrumentationRegistry.getArguments()
    private val pkg = "com.misura.testapp"

    /** The prior near line (before 26 Nov), rebuilt from the new one: no distance-first sentence, and "near me" buttons banned. */
    private fun prior(text: String) = text.replace(" ${Near.DISTANCE_FIRST}", "")
        .replace(" and call area with its words (a list around your location: say so).", " and call area with its words.")
        .replace(" search; then call area again.", " search, not with a 'use my location' or 'near me' button (that asks for a permission nobody can answer here); then call area again.")

    private fun arm(brain: Brain, old: Boolean) = object : Brain {
        override suspend fun next(system: String, turns: List<Turn>, tools: List<ToolSpec>): BrainReply = brain.next(system, if (!old) turns else turns.map { t ->
            Turn(t.role, buildJsonObject { t.content.forEach { (k, v) -> put(k, if (k != "parts") v else buildJsonArray {
                (v as JsonArray).forEach { p -> add(if (p is JsonObject && p["text"] is JsonPrimitive) buildJsonObject { put("text", prior((p["text"] as JsonPrimitive).content)) } else p) } }) } })
        }, tools)
    }

    @Test fun report() = runBlocking<Unit> {
        val token = args.getString("token").orEmpty()
        assumeTrue("pass -e token", token.isNotBlank())
        val inst = InstrumentationRegistry.getInstrumentation()
        val ui = inst.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        fun sh(c: String) = android.os.ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(c)).readBytes()
        repeat(50) { if (ShizukuBridge.ready()) return@repeat; Thread.sleep(100) }
        if (!ScreenReader.connected()) {
            sh("settings put secure enabled_accessibility_services null"); Thread.sleep(300)
            sh("settings put secure enabled_accessibility_services com.misura.phone/com.misura.phone.hidden.MisuraAccessibility"); sh("settings put secure accessibility_enabled 1")
        }
        repeat(50) { if (ScreenReader.connected()) return@repeat; Thread.sleep(100) }
        check(ShizukuBridge.ready() && ScreenReader.connected()) { "Shizuku or accessibility not ready" }
        val ctx = inst.targetContext
        val prefs = ctx.getSharedPreferences("brain", android.content.Context.MODE_PRIVATE)
        val project = { com.misura.phone.brain.vertexProject(prefs.privateString(com.misura.phone.debug.SA_KEY), prefs.getString("project", null)) }
        val runs = args.getString("runs")?.toIntOrNull() ?: 3
        val goal = "Open Venues, then Show gyms, and find the nearest gym near me"
        for (old in listOf(false, true)) repeat(runs) { k ->
            sh("am force-stop $pkg"); sh("pm revoke $pkg android.permission.ACCESS_COARSE_LOCATION")
            val tool = UseApp(ShizukuHiddenScreen(ctx), arm(VertexBrain(TokenProvider { token }, project), old), filesDir = ctx.filesDir, whereNow = { "Munich" }, log = { Log.i("MisuraEval", it) })
            val t0 = System.currentTimeMillis()
            val r = runCatching { tool.run(buildJsonObject { put("app", pkg); put("goal", goal); put("near_user", true); put("count", 1) }) }.getOrNull()
            tool.releaseParked()
            val first = (r?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject
            val granted = ctx.packageManager.checkPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, pkg) == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.i("MisuraEval", "NEAR ${if (old) "prior" else "new"} run=${k + 1} s=${(System.currentTimeMillis() - t0) / 1000} first=${first?.get("title")} ${first?.get("distance")} " +
                "gymLastFirst=${first?.get("title")?.toString() == "\"Gym Last\""} items=${(r?.get("items") as? JsonArray)?.size ?: 0} permissionGranted=$granted error=${r?.get("error") != null}")
        }
    }
}
