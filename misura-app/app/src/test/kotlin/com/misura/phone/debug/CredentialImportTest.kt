package com.misura.phone.debug

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.brain.ModelProvider
import com.misura.phone.brain.SearchProvider
import com.misura.phone.setup.CredentialSettings
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The adb import route a setup agent uses: broadcast → DebugReceiver → CredentialSettings → the route the next turn captures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CredentialImportTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = ctx.getSharedPreferences("brain", Context.MODE_PRIVATE)
    private val settings get() = CredentialSettings(prefs)

    @Before fun clean() { prefs.edit().clear().commit() }

    private fun send(cmd: String, provider: String?, value: String?) {
        val intent = Intent().setComponent(ComponentName(ctx, DebugReceiver::class.java)).putExtra("cmd", cmd)
        provider?.let { intent.putExtra("provider", it) }
        value?.let { intent.putExtra("value", it) }
        ctx.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** The receiver saves on the graph's background scope: wait for the change, or time out. */
    private fun await(what: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) { if (check()) return; shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        fail("Timed out waiting for $what")
    }

    /** Lets a rejected import finish so "nothing changed" is not just "not yet". */
    private fun settle() { send("notes", null, null); Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle() }

    @Test fun eachModelApiKeyIsStoredAndSelectedForTheNextTurn() {
        for (p in listOf(ModelProvider.GEMINI, ModelProvider.OPENAI, ModelProvider.ANTHROPIC)) {
            val key = "synthetic-${p.name.lowercase()}-key"
            send("apikey", p.name.lowercase(), key)
            await("${p.name} selected") { settings.providerSelection() == p && settings.hasApiKey(p) }
            val route = settings.route()
            assertEquals(p, route.provider)
            assertEquals(key, route.apiKey)
            assertEquals(p.defaultModel, route.model)  // encryption itself: StorageCipherTest (no Keystore under Robolectric)
        }
    }

    @Test fun eachSearchKeyIsStoredAndSelectedWithoutTouchingTheModelChoice() {
        send("apikey", "openai", "synthetic-openai-key")
        await("model key") { settings.providerSelection() == ModelProvider.OPENAI }
        for (s in listOf(SearchProvider.EXA, SearchProvider.TAVILY, SearchProvider.CONTEXT_DEV)) {
            val key = "synthetic-${s.name.lowercase()}-key"
            send("searchkey", s.name.lowercase(), key)
            await("${s.name} selected") { settings.searchSelection() == s && settings.hasSearchKey(s) }
            val route = settings.route()
            assertEquals(s, route.searchProvider)
            assertEquals(key, route.searchKey)
            assertEquals(ModelProvider.OPENAI, route.provider)
            assertEquals("synthetic-openai-key", route.apiKey)
        }
    }

    @Test fun aPushedServiceAccountSelectsVertexAgain() {
        send("apikey", "openai", "synthetic-openai-key")
        await("model key") { settings.providerSelection() == ModelProvider.OPENAI }
        val sa = """{"type":"service_account","project_id":"synthetic-project","client_email":"a@synthetic-project.iam.gserviceaccount.com","private_key":"x"}"""
        val intent = Intent().setComponent(ComponentName(ctx, DebugReceiver::class.java)).putExtra("cmd", "sa")
            .putExtra("value64", android.util.Base64.encodeToString(sa.toByteArray(), android.util.Base64.NO_WRAP))
        ctx.sendBroadcast(intent); shadowOf(Looper.getMainLooper()).idle()
        await("Vertex selected") { settings.providerSelection() == ModelProvider.VERTEX && settings.hasServiceAccount() }
        assertTrue("the OpenAI key stays saved for switching back", settings.hasApiKey(ModelProvider.OPENAI))
    }

    @Test fun badImportsChangeNothing() {
        send("apikey", "openai", "synthetic-openai-key")
        await("model key") { settings.providerSelection() == ModelProvider.OPENAI }
        val before = prefs.all.toMap()
        send("apikey", "openai", "")               // blank must not mean "keep" or "clear"
        send("apikey", "openai", "has a space")
        send("apikey", "vertex", "synthetic")      // Vertex uses a service account
        send("apikey", "nosuch", "synthetic")
        send("apikey", "gemini", null)
        send("searchkey", "native", "synthetic")   // native search has no key
        send("searchkey", "tavily", "x".repeat(2049))
        settle()
        assertEquals(before, prefs.all.toMap())
    }
}
