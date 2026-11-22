package com.misura.phone.setup

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import com.misura.phone.brain.PrefsToken
import com.misura.phone.debug.EXA_KEY
import com.misura.phone.debug.SA_KEY
import com.misura.phone.store.privateString
import com.misura.phone.store.putPrivateString
import com.misura.phone.store.StorageCipher
import com.misura.phone.brain.CredentialInput
import com.misura.phone.brain.ModelProvider
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import android.content.Intent
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import com.misura.phone.notify.NotificationAccess
import com.misura.phone.notify.MisuraListener

class SetupScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var settings: CredentialSettings

    @Before fun setup() {
        prefs = rule.activity.getSharedPreferences("setup-ui-${System.nanoTime()}", Context.MODE_PRIVATE)
        prefs.putPrivateString(PrefsToken.KEY, "synthetic-token")
        prefs.edit().putString("project", "example-project").commit()
        settings = CredentialSettings(prefs)
    }

    /** Routines are listed in settings and the owner can remove one there, without the agent. */
    @Test fun routinesAreListedAndRemovableInSettings() {
        val prefs = rule.activity.getSharedPreferences("routines", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = com.misura.phone.routine.RoutineStore(rule.activity)
        store.put(com.misura.phone.routine.Saved(com.misura.core.routine.Routine("r-set", "Train check",
            com.misura.core.routine.Trigger.Time(8, 0, com.misura.phone.routine.parseDays("weekdays")!!), "Is my train late?")))
        try {
            rule.setContent { SetupScreen(rule.activity, settings) {} }
            rule.waitUntil(5_000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Train check")).fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithText("weekdays at 08:00 · “Is my train late?”").performScrollTo().assertExists()
            rule.onNodeWithText("Remove").performScrollTo().performClick()
            rule.waitUntil(5_000) { store.all().isEmpty() }
        } finally { prefs.edit().clear().commit() }
    }

    @Test fun secretEntryIsPasswordProtectedAndSavedOnlyOnTap() {
        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        rule.onNodeWithText("Web search · Native").performScrollTo().performClick()
        rule.onNodeWithText("Exa").performClick()
        val field = rule.onNodeWithContentDescription("Exa key")
        field.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
            .performTextInput("synthetic-exa")
        assertFalse(prefs.contains(EXA_KEY))
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save keys").performScrollTo().performClick()
        rule.waitUntil(5000) { prefs.privateString(EXA_KEY) == "synthetic-exa" }
        field.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    }

    @Test fun encryptedCredentialsStayOffMainThreadThroughPollingSelectionAndSave() {
        val threads = CopyOnWriteArrayList<Thread>()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val cipher = StorageCipher({ threads.add(Thread.currentThread()); key }, {})
        prefs.edit().clear().commit()
        settings = CredentialSettings(prefs, cipher)
        settings.saveConfiguration(CredentialInput(ModelProvider.OPENAI, "fixture-model", "synthetic-primary", "", "", "synthetic-exa"))
        // Keep legacy Exa inference and encrypted-blank account semantics in the fixture.
        prefs.edit().remove("search_provider").putString(SA_KEY, cipher.encrypt("")).commit()
        val savedPrimary = prefs.getString("api_key_OPENAI", null)
        val savedExa = prefs.getString(EXA_KEY, null)
        assertTrue(savedPrimary!!.startsWith(StorageCipher.PREFIX))
        assertTrue(savedExa!!.startsWith(StorageCipher.PREFIX))
        threads.clear()

        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        assertTrue("The encrypted fixture must actually be read", threads.isNotEmpty())
        assertFalse("Initial composition read a key on main", threads.contains(Looper.getMainLooper().thread))
        rule.onAllNodesWithText("Saved; leave blank to keep").assertCountEquals(2)
        rule.onNodeWithText("Done").performScrollTo().assertIsEnabled()
        val loadedReads = threads.size
        val started = SystemClock.uptimeMillis()
        rule.waitUntil(3000) { SystemClock.uptimeMillis() - started >= 1200 }
        fun choose(current: String, choice: String) {
            rule.onNodeWithText(current).performScrollTo().performClick()
            rule.onNodeWithText(choice).performClick()
        }
        choose("Model provider · OpenAI", "Gemini")
        choose("Model provider · Gemini", "OpenAI")
        choose("Web search · Exa", "Tavily")
        rule.onNodeWithContentDescription("Tavily key").performScrollTo().performTextInput("synthetic-tavily")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        assertEquals("Polling and editing must use loaded flags", loadedReads, threads.size)
        rule.onNodeWithText("Save keys").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodes(hasText("Keys saved on this phone. Access was not checked.")).fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithText("Saved; leave blank to keep").assertCountEquals(2)
        rule.onNodeWithContentDescription("Tavily key")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals(savedPrimary, prefs.getString("api_key_OPENAI", null))
        assertEquals(savedExa, prefs.getString(EXA_KEY, null))
        assertEquals("synthetic-tavily", settings.route().searchKey)
        assertFalse("Saving/status refresh read a key on main", threads.contains(Looper.getMainLooper().thread))
    }

    private fun awaitForm() {
        rule.waitUntil(5000) { rule.onAllNodes(hasText("Save keys")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun restoringScreenDoesNotRestoreUnsavedSecrets() {
        val provider = com.misura.phone.brain.ModelProvider.OPENAI
        settings.saveConfiguration(com.misura.phone.brain.CredentialInput(provider, provider.defaultModel,
            "saved-primary", "", "", "saved-exa"))
        val restoration = StateRestorationTester(rule)
        var registry: SaveableStateRegistry? = null
        restoration.setContent {
            registry = LocalSaveableStateRegistry.current
            SetupScreen(rule.activity, settings) {}
        }
        awaitForm()
        val primary = rule.onNodeWithContentDescription("OpenAI API key")
        val search = rule.onNodeWithContentDescription("Exa key")
        primary.performScrollTo().performTextInput("unsaved-synthetic-primary")
        search.performScrollTo().performTextInput("unsaved-synthetic-exa")
        rule.runOnIdle {
            val state = checkNotNull(registry).performSave().toString()
            assertFalse(state.contains("unsaved-synthetic"))
        }
        restoration.emulateSavedInstanceStateRestore()
        awaitForm()
        primary.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        search.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertEquals("saved-exa", prefs.privateString(EXA_KEY))
        assertEquals("saved-primary", settings.route().apiKey)
    }

    @Test fun freshSetupSwitchesAccountsWithoutCarryingKeysAndAcceptsOptionalSearch() {
        prefs.edit().clear().commit()
        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        fun choose(current: String, choice: String) {
            rule.onNodeWithText(current).performScrollTo().performClick()
            rule.onNodeWithText(choice).performClick()
        }
        choose("Model provider · Vertex", "Gemini")
        rule.onNodeWithContentDescription("Gemini API key").performScrollTo().performTextInput("discard-me")
        choose("Model provider · Gemini", "OpenAI")
        rule.onNodeWithText("Your selected provider handles model requests, including memory checks and app control. Settings changes apply to the next turn.").assertExists()
        val primary = rule.onNodeWithContentDescription("OpenAI API key")
        primary.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performScrollTo().performTextInput("synthetic-primary")
        choose("Web search · Native", "Tavily")
        rule.onNodeWithContentDescription("Tavily key").performScrollTo().performTextInput("discard-search")
        choose("Web search · Tavily", "Context.dev")
        val search = rule.onNodeWithContentDescription("Context.dev key")
        search.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            .performScrollTo().performTextInput("synthetic-search")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save keys").performScrollTo().performClick()
        rule.waitUntil(5000) { settings.configured() }
        rule.onNodeWithText("Keys saved on this phone. Access was not checked.").assertExists()
        primary.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        search.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        assertFalse(settings.route().googleAvailable)
        assertEquals("synthetic-search", settings.route().searchKey)
    }

    @Test fun freshGeminiSetupUsesOnlyItsKey() = assertFreshProvider(com.misura.phone.brain.ModelProvider.GEMINI)

    @Test fun freshAnthropicSetupUsesOnlyItsKey() = assertFreshProvider(com.misura.phone.brain.ModelProvider.ANTHROPIC)

    private fun assertFreshProvider(provider: com.misura.phone.brain.ModelProvider) {
        prefs.edit().clear().commit()
        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        rule.onNodeWithText("Model provider · Vertex").performScrollTo().performClick()
        rule.onNodeWithText(provider.label).performClick()
        rule.onNodeWithContentDescription("${provider.label} API key").performScrollTo().performTextInput("synthetic-key")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save keys").performScrollTo().performClick()
        rule.waitUntil(5000) { settings.configured() }
        val saved = settings.route()
        assertEquals(provider, saved.provider)
        assertEquals("synthetic-key", saved.apiKey)
        assertFalse(saved.googleAvailable)
        assertEquals(com.misura.phone.brain.SearchProvider.NATIVE, saved.searchProvider)
    }

    @Test fun unsupportedSavedSelectionsRequireExplicitReplacement() {
        prefs.edit().putString("model_provider", "future-model").putString("search_provider", "future-search").commit()
        val before = prefs.all.toMap()
        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        val save = rule.onNodeWithText("Save keys")
        save.assertIsNotEnabled()
        rule.onNodeWithText("Model provider · Unsupported selection").performScrollTo().performClick()
        rule.onNodeWithText("Vertex").performClick()
        save.assertIsNotEnabled()
        assertEquals(before, prefs.all)
        assertFalse(settings.configured())
        rule.onNodeWithText("Web search · Unsupported selection").performScrollTo().performClick()
        rule.onNodeWithText("Native").performClick()
        assertEquals(before, prefs.all)
        save.performScrollTo().performClick()
        rule.waitUntil(5000) { settings.configured() }
        assertEquals("VERTEX", prefs.getString("model_provider", null))
        assertEquals("NATIVE", prefs.getString("search_provider", null))
    }

    @Test fun invalidServiceAccountDoesNotReplaceWorkingCredentials() {
        rule.setContent { SetupScreen(rule.activity, settings) {} }
        awaitForm()
        rule.onNodeWithContentDescription("Service-account JSON").performScrollTo().performTextInput("not-json")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("Save keys").performScrollTo().performClick()
        rule.waitUntil(5000) { rule.onAllNodes(androidx.compose.ui.test.hasText("Paste complete service-account JSON.")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals("synthetic-token", prefs.privateString(PrefsToken.KEY))
        assertFalse(prefs.contains(com.misura.phone.debug.SA_KEY))
    }

    @Test fun setupWindowBlocksScreenshots() {
        ActivityScenario.launch<SetupActivity>(Intent(rule.activity, SetupActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
            }
        }
    }

    @Test fun blockedAppStaysEditableAfterItsNotificationDisappears() {
        val pkg = "com.misura.testapp"
        val access = NotificationAccess(rule.activity)
        val allowed = access.allows(pkg)
        try {
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("am force-stop $pkg").use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            rule.waitUntil(5000) { MisuraListener.instance?.activeNotifications?.none { it.packageName == pkg } != false }
            access.setBlocked(pkg, true)
            rule.setContent { SetupScreen(rule.activity, settings) {} }
            awaitForm()
            rule.onNodeWithText("TestChat").performScrollTo().performClick()
            rule.runOnIdle { assertTrue(access.allows(pkg)) }
        } finally { access.setBlocked(pkg, !allowed) }
    }
}
