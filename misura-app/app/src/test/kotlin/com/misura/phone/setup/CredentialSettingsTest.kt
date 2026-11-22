package com.misura.phone.setup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.brain.CredentialInput
import com.misura.phone.brain.ModelProvider
import com.misura.phone.brain.PrefsToken
import com.misura.phone.brain.SearchProvider
import com.misura.phone.debug.EXA_KEY
import com.misura.phone.debug.SA_KEY
import com.misura.phone.store.StorageCipher
import com.misura.phone.store.privateString
import com.misura.phone.store.putPrivateString
import kotlinx.coroutines.async
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.util.Base64
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class CredentialSettingsTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = StorageCipher({ key }, {})
    private val prefs: SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("setup-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test fun concurrentRoutesRetainTheirOriginalClientAndSettings() = kotlinx.coroutines.test.runTest {
        val settings = CredentialSettings(prefs, cipher)
        fun save(key: String, searchKey: String) = settings.saveConfiguration(CredentialInput(
            ModelProvider.OPENAI, "fixture-model", key, "", "", "", SearchProvider.TAVILY, searchKey))
        val routes = com.misura.phone.brain.ModelRoutes(settings::route)
        save("fixture-key-a", "fixture-search-a")
        val old = routes.capture()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val resume = kotlinx.coroutines.CompletableDeferred<Unit>()
        val pending = async(old) {
            entered.complete(Unit)
            resume.await()
            com.misura.phone.brain.ModelRouteContext.current()
        }
        entered.await()
        assertSame(old.main, routes.capture().main)
        save("fixture-key-b", "fixture-search-b")
        val fresh = routes.capture()
        resume.complete(Unit)
        val resumed = pending.await()
        assertSame(old.main, resumed.main)
        assertNotSame(resumed.main, fresh.main)
        assertEquals("fixture-search-a", resumed.route.searchKey)
        assertEquals("fixture-search-b", fresh.route.searchKey)
        assertFalse(old.toString().contains("fixture-key"))
    }

    @Test fun vertexConfigurationSurvivesCredentialAndRoleRotation() {
        val settings = CredentialSettings(prefs, cipher)
        prefs.edit().putString("main_think", "HIGH").putString("hand_think", "LOW")
            .putString("hand_model", "first-hand").commit()
        prefs.putPrivateString(PrefsToken.KEY, "fixture-fallback", cipher)
        settings.save("", "first-project", "")
        val routes = com.misura.phone.brain.ModelRoutes(settings::route)
        val first = routes.capture()
        prefs.edit().putString("main_think", "LOW").putString("hand_think", "MEDIUM")
            .putString("hand_model", "second-hand").commit()
        settings.save(serviceAccount, "second-project", "fixture-exa")
        val second = routes.capture()
        assertEquals("fixture-fallback", first.google.tokens.token())
        assertEquals("first-project", first.google.project)
        assertEquals("first-hand", first.route.handModel)
        assertEquals("LOW", first.route.handThinking)
        assertEquals("HIGH", first.route.mainThinking)
        assertEquals("second-hand", second.route.handModel)
        assertEquals("MEDIUM", second.route.handThinking)
        assertEquals("LOW", second.route.mainThinking)
        assertEquals(serviceAccount, second.google.account)
        assertNull(second.google.fallback)
        assertEquals("second-project", second.google.project)
        assertNotSame(first.main, second.main)
        assertEquals(SearchProvider.NATIVE, first.route.searchProvider)
        assertEquals(SearchProvider.EXA, second.route.searchProvider)
    }

    @Test fun photoCredentialsStayTogetherAcrossRotation() {
        val settings = CredentialSettings(prefs, cipher)
        prefs.putPrivateString(PrefsToken.KEY, "test-token-old", cipher)
        settings.save("", "project-old", "")
        val old = settings.googleAccess()
        prefs.putPrivateString(PrefsToken.KEY, "test-token-new", cipher)
        settings.save("", "project-new", "")
        val fresh = settings.googleAccess()
        assertEquals("test-token-old", old.tokens.token())
        assertEquals("project-old", old.project)
        assertEquals("test-token-new", fresh.tokens.token())
        assertEquals("project-new", fresh.project)
    }

    @Test fun validatesAndEncryptsNewCredentialsWithoutChangingLegacyProjectRules() {
        val settings = CredentialSettings(prefs, cipher)
        assertFalse(settings.configured())
        settings.save(serviceAccount, "billing-project", "synthetic-exa")
        assertTrue(settings.configured())
        assertEquals("billing-project", settings.project())
        assertTrue(prefs.getString(SA_KEY, "")!!.startsWith(StorageCipher.PREFIX))
        assertTrue(prefs.getString(EXA_KEY, "")!!.startsWith(StorageCipher.PREFIX))
        assertTrue(serviceAccount == prefs.privateString(SA_KEY, cipher))
        assertEquals("synthetic-exa", prefs.privateString(EXA_KEY, cipher))
    }

    @Test fun blankSecretFieldsKeepExistingKeysAndBlankProjectUsesKeyProject() {
        val settings = CredentialSettings(prefs, cipher)
        settings.save(serviceAccount, "billing-project", "synthetic-exa")
        val savedSa = prefs.getString(SA_KEY, null)
        val savedExa = prefs.getString(EXA_KEY, null)
        settings.save("", "", "")
        assertTrue(savedSa == prefs.getString(SA_KEY, null))
        assertTrue(savedExa == prefs.getString(EXA_KEY, null))
        assertEquals("example-project", settings.project())
    }

    @Test fun rejectsInvalidInputBeforeTouchingWorkingCredentials() {
        val settings = CredentialSettings(prefs, cipher)
        settings.save(serviceAccount, "billing-project", "synthetic-exa")
        val before = prefs.all.toMap()
        val invalid = listOf("not-json", "{}", serviceAccount.replace("service_account", "authorized_user"),
            serviceAccount.replace("agent@example.invalid", "invalid-address"), serviceAccount.replace("PRIVATE KEY", "WRONG KEY"))
        invalid.forEach { input ->
            assertThrows(IllegalArgumentException::class.java) { settings.save(input, "other-project", "replacement") }
            assertTrue(before == prefs.all)
        }
        assertThrows(IllegalArgumentException::class.java) { settings.save("", "bad/project", "") }
        assertThrows(IllegalArgumentException::class.java) { settings.save("", "billing-project", "bad\r\nheader") }
        assertTrue(before == prefs.all)
    }

    @Test fun blankFieldsCannotReplaceUnreadableSavedCredentials() {
        CredentialSettings(prefs, cipher).save(serviceAccount, "billing-project", "synthetic-exa")
        val before = prefs.all.toMap()
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val unreadable = CredentialSettings(prefs, StorageCipher({ wrongKey }, {}))
        assertFalse(unreadable.configured())
        assertThrows(IllegalArgumentException::class.java) { unreadable.save("", "other-project", "") }
        assertTrue(before == prefs.all)
    }

    @Test fun existingShortLivedTokenRemainsUsableWithoutAServiceAccount() {
        prefs.putPrivateString(PrefsToken.KEY, "synthetic-token", cipher)
        prefs.edit().putString("project", "token-project").commit()
        val settings = CredentialSettings(prefs, cipher)
        assertTrue(settings.configured())
        assertEquals("token-project", settings.project())
        settings.save("", "other-project", "synthetic-exa")
        assertEquals("other-project", settings.project())
        assertEquals("synthetic-token", prefs.privateString(PrefsToken.KEY, cipher))
        assertFalse(prefs.contains(SA_KEY))
    }

    @Test fun failedSaveRestoresPreviouslyWorkingValues() {
        CredentialSettings(prefs, cipher).save(serviceAccount, "billing-project", "synthetic-exa")
        val before = prefs.all.toMap()
        var writes = 0
        val failing = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                    override fun remove(key: String?): SharedPreferences.Editor { delegate.remove(key); return this }
                    override fun commit(): Boolean { delegate.commit(); return writes++ != 0 }
                }
            }
        }
        assertThrows(IllegalStateException::class.java) { CredentialSettings(failing, cipher).save("", "other-project", "replacement") }
        assertTrue(before == prefs.all)
        writes = 0
        assertThrows(IllegalStateException::class.java) {
            CredentialSettings(failing, cipher).saveConfiguration(CredentialInput(ModelProvider.OPENAI, "new-model",
                "new-key", serviceAccount, "different-project", "different-exa", SearchProvider.TAVILY, "new-search"))
        }
        assertTrue(before == prefs.all)
    }

    @Test fun independentProviderAndSearchSaveAtomicallyAndNeverReuseOtherAccounts() {
        val settings = CredentialSettings(prefs, cipher)
        assertEquals(ModelProvider.VERTEX, settings.provider())
        for (search in SearchProvider.entries) {
            val input = CredentialInput(ModelProvider.OPENAI, "test-model", "synthetic-primary", "", "", "", search,
                if (search == SearchProvider.NATIVE) "" else "synthetic-search")
            settings.saveConfiguration(input)
            val route = settings.route()
            assertEquals(search, route.searchProvider)
            assertEquals("synthetic-primary", route.apiKey)
            assertFalse(route.googleAvailable)
            assertTrue(settings.configured())
            assertFalse(route.toString().contains("synthetic"))
            assertFalse(input.toString().contains("synthetic"))
        }
        val before = prefs.all.toMap()
        assertThrows(IllegalArgumentException::class.java) { settings.saveProvider(ModelProvider.GEMINI, "test-model", "") }
        assertTrue(before == prefs.all)
        settings.saveProvider(ModelProvider.OPENAI, "different", "")
        assertEquals("synthetic-primary", settings.route().apiKey)
    }

    @Test fun legacyExaAndUnavailableOptionalCredentialsKeepTheirIdentity() {
        prefs.putPrivateString(EXA_KEY, "legacy-exa", cipher)
        val settings = CredentialSettings(prefs, cipher)
        assertEquals(SearchProvider.EXA, settings.searchProvider())
        settings.saveConfiguration(CredentialInput(ModelProvider.GEMINI, "gemini-model", "primary",
            serviceAccount, "billing", "", SearchProvider.EXA))
        val corrupt = StorageCipher.PREFIX + "invalid"
        prefs.edit().putString(EXA_KEY, corrupt).putString(SA_KEY, corrupt).commit()
        val route = settings.route()
        assertFalse(route.googleAvailable)
        assertTrue(route.googleCredentialUnreadable)
        assertTrue(route.searchCredentialUnreadable)
        assertEquals(SearchProvider.EXA, route.searchProvider)
        assertNull(route.searchKey)
        assertTrue(settings.configured())
        val before = prefs.all.toMap()
        assertThrows(IllegalArgumentException::class.java) {
            settings.saveConfiguration(CredentialInput(ModelProvider.GEMINI, "new-model", "", "", "", "", SearchProvider.EXA))
        }
        assertTrue(before == prefs.all)
        settings.saveConfiguration(CredentialInput(ModelProvider.GEMINI, "new-model", "", "", "", "", SearchProvider.NATIVE))
        assertEquals(corrupt, prefs.getString(EXA_KEY, null))
        assertEquals(corrupt, prefs.getString(SA_KEY, null))
    }

    @Test fun rejectsInvalidModelAndHeaderBytesBeforeWritingAnyCredentials() {
        val settings = CredentialSettings(prefs, cipher)
        for ((model, key, searchKey) in listOf(Triple("../path", "key", "search"), Triple("model", "key\n", "search"),
                Triple("model", "key", "search\r"), Triple("model", "key", ""))) {
            assertThrows(IllegalArgumentException::class.java) {
                settings.saveConfiguration(CredentialInput(ModelProvider.OPENAI, model, key, "", "", "", SearchProvider.TAVILY, searchKey))
            }
            assertTrue(prefs.all.isEmpty())
        }
    }

    @Test fun readerWaitsForCombinedSaveAndOldSnapshotStaysUnchanged() {
        val started = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val blocking = object : SharedPreferences by prefs {
            override fun edit(): SharedPreferences.Editor {
                val delegate = prefs.edit()
                return object : SharedPreferences.Editor by delegate {
                    override fun commit(): Boolean {
                        started.countDown()
                        check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        return delegate.commit()
                    }
                }
            }
        }
        val old = CredentialSettings(prefs, cipher)
        old.save(serviceAccount, "old-project", "old-search")
        val settings = CredentialSettings(blocking, cipher)
        val previous = settings.route()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writer = pool.submit { settings.saveConfiguration(CredentialInput(ModelProvider.ANTHROPIC,
                "new-model", "primary", serviceAccount, "new-project", "", SearchProvider.TAVILY, "new-search")) }
            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val reading = java.util.concurrent.CountDownLatch(1)
            val reader = pool.submit<com.misura.phone.brain.ModelRoute> { reading.countDown(); settings.route() }
            assertTrue(reading.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertThrows(java.util.concurrent.TimeoutException::class.java) { reader.get(100, java.util.concurrent.TimeUnit.MILLISECONDS) }
            proceed.countDown()
            writer.get(5, java.util.concurrent.TimeUnit.SECONDS)
            val after = reader.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(ModelProvider.ANTHROPIC, after.provider)
            assertEquals("new-model", after.handModel)
            assertEquals("new-project", after.project)
            assertEquals("new-search", after.searchKey)
            assertEquals("old-project", previous.project)
            assertEquals("old-search", previous.searchKey)
        } finally { proceed.countDown(); pool.shutdownNow() }
    }

    @Test fun validatedRouteMigratesLegacyPlaintextButFailedFormLeavesBytesAlone() {
        prefs.edit().putString(PrefsToken.KEY, "old-token").putString(EXA_KEY, "old-search")
            .putString("project", "old-project").commit()
        val settings = CredentialSettings(prefs, cipher)
        val before = prefs.all.toMap()
        assertThrows(IllegalArgumentException::class.java) { settings.save("", "bad/project", "") }
        assertTrue(before == prefs.all)
        val route = settings.route()
        assertEquals("old-search", route.searchKey)
        assertEquals("old-token", route.fallbackToken)
        assertTrue(prefs.getString(PrefsToken.KEY, null)!!.startsWith(StorageCipher.PREFIX))
        assertTrue(prefs.getString(EXA_KEY, null)!!.startsWith(StorageCipher.PREFIX))
    }

    @Test fun emptyDebugCredentialsSelectNativeAndPreserveTokenSignIn() {
        prefs.putPrivateString(EXA_KEY, "", cipher)
        prefs.putPrivateString(SA_KEY, "", cipher)
        prefs.putPrivateString(PrefsToken.KEY, "working-token", cipher)
        prefs.edit().putString("project", "token-project").commit()
        val settings = CredentialSettings(prefs, cipher)
        assertEquals(SearchProvider.NATIVE, settings.searchProvider())
        assertFalse(settings.hasServiceAccount())
        assertFalse(settings.hasExaKey())
        assertFalse(settings.hasSearchKey(SearchProvider.EXA))
        prefs.putPrivateString("api_key_OPENAI", "", cipher)
        assertFalse(settings.hasApiKey(ModelProvider.OPENAI))
        assertEquals("working-token", settings.route().fallbackToken)
    }

    @Test fun freshOpenAiAndExaNeedNoGoogleConfigurationAndUnreadablePrimaryBlocksSave() {
        val settings = CredentialSettings(prefs, cipher)
        settings.saveConfiguration(CredentialInput(ModelProvider.OPENAI, "selected", "primary", "", "", "search"))
        val route = settings.route()
        assertEquals("primary", route.apiKey)
        assertEquals(SearchProvider.EXA, route.searchProvider)
        assertEquals("search", route.searchKey)
        assertFalse(route.googleAvailable)
        assertFalse(settings.hasServiceAccount())
        assertEquals("", route.project)
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val unavailable = CredentialSettings(prefs, StorageCipher({ wrongKey }, {}))
        val before = prefs.all.toMap()
        assertFalse(unavailable.configured())
        assertThrows(IllegalArgumentException::class.java) { unavailable.route() }
        assertThrows(IllegalArgumentException::class.java) {
            unavailable.saveConfiguration(CredentialInput(ModelProvider.OPENAI, "other", "", "", "", "replacement"))
        }
        assertTrue(before == prefs.all)
    }

    @Test fun vertexOverridesBelongOnlyToVertexAndInvalidStoredProviderFailsClosed() {
        prefs.edit().putString("hand_think", "HIGH").putString("main_think", "MEDIUM").putString("hand_model", "hand").commit()
        val settings = CredentialSettings(prefs, cipher)
        settings.save(serviceAccount, "", "")
        val vertex = settings.route()
        assertEquals("hand", vertex.handModel)
        assertEquals("HIGH", vertex.handThinking)
        assertEquals("MEDIUM", vertex.mainThinking)
        settings.saveProvider(ModelProvider.GEMINI, "primary", "key")
        val gemini = settings.route()
        assertEquals("primary", gemini.handModel)
        assertNull(gemini.mainThinking)
        assertNull(gemini.handThinking)
        prefs.edit().putString("model_provider", "unsupported").commit()
        assertFalse(settings.configured())
    }

    companion object {
        private val serviceAccount: String by lazy {
            val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(rsa.private.encoded) + "\n-----END PRIVATE KEY-----"
            buildJsonObject {
                put("type", "service_account")
                put("project_id", "example-project")
                put("client_email", "agent@example.invalid")
                put("private_key", pem)
            }.toString()
        }
    }
}
