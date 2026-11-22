package com.misura.phone.brain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.misura.phone.store.StorageCipher
import com.misura.phone.store.privateString
import com.misura.core.agent.MEMORY_REQUIRE_MATCH
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RememberStorageTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = StorageCipher({ key }, { })
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("remember-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test fun legacyNotesMigrateAndNewNotesNeverExpireOlderMemories() = runBlocking {
        prefs.edit().putString(NOTES_KEY, (1..60).joinToString("\n") { "2026-01-01: note $it" }).commit()
        val remember = Remember(prefs, { LocalDate.of(2026, 11, 19) }, cipher)
        remember.run(buildJsonObject { put("note", "new memory") })
        val stored = prefs.getString(NOTES_KEY, null)!!
        assertTrue(stored.startsWith(StorageCipher.PREFIX))
        val notes = prefs.privateString(NOTES_KEY, cipher)!!.lines()
        assertEquals(61, notes.size)
        assertEquals("2026-01-01: note 1", notes.first())
        assertEquals("2026-11-19: new memory", notes.last())
    }

    @Test fun unreadableNotesStayIntactInsteadOfBeingOverwrittenByAnEmptyList() = runBlocking {
        val stored = cipher.encrypt("remember this")
        prefs.edit().putString(NOTES_KEY, stored).commit()
        val unavailable = StorageCipher({ throw java.security.KeyStoreException("test unavailable") }, { })
        val result = Remember(prefs, cipher = unavailable).run(buildJsonObject { put("note", "new memory") })
        assertTrue(result.containsKey("error"))
        assertEquals(stored, prefs.getString(NOTES_KEY, null))
    }

    @Test fun correctionWhoseTargetDisappearedDoesNotAppendAContradictoryFact() = runBlocking {
        prefs.edit().putString(NOTES_KEY, "2026-10-01: The owner cycles to work").commit()
        val result = Remember(prefs, cipher = cipher).run(buildJsonObject {
            put("note", "The owner prefers tea")
            put("replaces", "2026-10-01: The owner drinks decaf flat whites")
            put(MEMORY_REQUIRE_MATCH, true)
        })
        assertTrue(result.containsKey("error"))
        assertEquals("2026-10-01: The owner cycles to work", prefs.privateString(NOTES_KEY, cipher))
    }
}
