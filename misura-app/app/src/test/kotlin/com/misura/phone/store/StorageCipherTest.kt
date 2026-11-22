package com.misura.phone.store

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyStoreException
import java.util.Base64
import javax.crypto.KeyGenerator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class StorageCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = StorageCipher({ key }, { })
    private fun newPrefs(): SharedPreferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("crypto-test-${System.nanoTime()}", Context.MODE_PRIVATE)

    @Test fun textAndPhotoRoundTripsUseFreshIvsAndSurviveANewHelper() {
        val text = "Grüße\nprivate conversation"
        val first = cipher.encrypt(text)
        assertTrue(first.startsWith(StorageCipher.PREFIX))
        assertFalse(first.contains(text))
        assertNotEquals(first, cipher.encrypt(text))
        assertEquals(text, StorageCipher({ key }, { }).decrypt(first))
        val photo = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0, 1, 2)
        assertArrayEquals(photo, cipher.decrypt(cipher.encrypt(photo)))
        assertEquals("", cipher.decrypt(cipher.encrypt("")))
    }

    @Test fun tamperWrongKeyTruncationAndUnknownVersionNeverReturnCiphertextAsPlaintext() {
        val stored = cipher.encrypt("synthetic secret")
        val bytes = Base64.getDecoder().decode(stored.removePrefix(StorageCipher.PREFIX))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        assertNull(cipher.decrypt(StorageCipher.PREFIX + Base64.getEncoder().encodeToString(bytes)))
        val wrongKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertNull(StorageCipher({ wrongKey }, { }).decrypt(stored))
        assertNull(cipher.decrypt(StorageCipher.PREFIX + "broken"))
        assertNull(cipher.decrypt(stored.replace(":v1:", ":v2:")))
        val photo = cipher.encrypt(byteArrayOf(1, 2, 3))
        photo[photo.lastIndex] = (photo.last().toInt() xor 1).toByte()
        assertNull(cipher.decrypt(photo))
    }

    @Test fun unavailableKeystoreFallsBackWithOneWarningAndRetriesMigrationWhenAvailable() {
        var available = false
        var warnings = 0
        val retrying = StorageCipher({ if (available) key else throw KeyStoreException("test unavailable") }, { warnings++ })
        val prefs = newPrefs()
        prefs.putPrivateString("credential", "synthetic value", retrying)
        assertEquals("synthetic value", prefs.getString("credential", null))
        assertEquals("synthetic value", prefs.privateString("credential", retrying))
        assertArrayEquals(byteArrayOf(1, 2), retrying.encrypt(byteArrayOf(1, 2)))
        assertEquals(1, warnings)
        available = true
        assertEquals("synthetic value", prefs.privateString("credential", retrying))
        val stored = prefs.getString("credential", null)!!
        assertTrue(stored.startsWith(StorageCipher.PREFIX))
        available = false
        assertNull(prefs.privateString("credential", retrying))
        assertEquals(stored, prefs.getString("credential", null))
    }

    @Test fun legacyPlaintextMigratesOnceAndFailedPersistenceStillReturnsTheValue() {
        assertEquals("legacy", cipher.decrypt("legacy"))
        assertArrayEquals(byteArrayOf(1, 2), cipher.decrypt(byteArrayOf(1, 2)))
        val prefs = newPrefs()
        prefs.edit().putString("credential", "legacy").commit()
        assertEquals("legacy", prefs.privateString("credential", cipher))
        val encrypted = prefs.getString("credential", null)
        assertEquals("legacy", prefs.privateString("credential", cipher))
        assertEquals(encrypted, prefs.getString("credential", null))

        // Failed disk commit: retain the legacy credential, never require deletion before migration.
        val disk = newPrefs()
        disk.edit().putString("credential", "keep").commit()
        val failing = object : SharedPreferences by disk {
            override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by disk.edit() {
                override fun putString(key: String?, value: String?): SharedPreferences.Editor = this
                override fun commit() = false
            }
        }
        assertEquals("keep", failing.privateString("credential", cipher))
        assertEquals("keep", disk.getString("credential", null))
    }
}
