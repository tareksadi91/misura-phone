package com.misura.phone.store

import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.misura.phone.BuildConfig
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Versioned fields/files; legacy plaintext stays readable and is encrypted on the next write. */
class StorageCipher(
    private val key: () -> SecretKey = ::storageKey,
    private val warning: () -> Unit = { if (BuildConfig.DEBUG) Log.w("MisuraStorage", "Encrypted storage unavailable or damaged; plaintext writes may be used") },
) {
    private val warned = AtomicBoolean()
    internal fun warn() { if (warned.compareAndSet(false, true)) warning() }

    fun encrypt(value: String): String = seal(value.toByteArray(Charsets.UTF_8))?.let { PREFIX + Base64.getEncoder().encodeToString(it) } ?: value
    fun decrypt(value: String): String? = if (!value.startsWith(FAMILY)) value else read {
        require(value.startsWith(PREFIX))
        open(Base64.getDecoder().decode(value.removePrefix(PREFIX))).toString(Charsets.UTF_8)
    }

    fun encrypt(value: ByteArray): ByteArray = seal(value)?.let { PREFIX.toByteArray(Charsets.UTF_8) + it } ?: value
    fun decrypt(value: ByteArray): ByteArray? = if (!value.startsWith(FAMILY.toByteArray(Charsets.UTF_8))) value else read {
        val prefix = PREFIX.toByteArray(Charsets.UTF_8)
        require(value.startsWith(prefix))
        open(value.copyOfRange(prefix.size, value.size))
    }

    private fun seal(value: ByteArray): ByteArray? = read {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key()) // Keystore supplies a fresh random IV for every write.
        cipher.updateAAD(PREFIX.toByteArray(Charsets.UTF_8))
        cipher.iv + cipher.doFinal(value)
    }

    private fun open(value: ByteArray): ByteArray {
        require(value.size >= 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, value.copyOfRange(0, 12)))
        cipher.updateAAD(PREFIX.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(value, 12, value.size - 12)
    }

    // Failed authentication/key access never turns ciphertext into plaintext. Callers leave unreadable records intact.
    private inline fun <T> read(block: () -> T): T? = try { block() } catch (_: Exception) { warn(); null }

    companion object {
        const val PREFIX = "misura:enc:v1:"
        private const val FAMILY = "misura:enc:"
        val shared = StorageCipher()
    }
}

private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

@Synchronized private fun storageKey(): SecretKey {
    val alias = "misura.storage.v1"
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    // Never replace an existing alias: doing so would strand previously encrypted data.
    if (store.containsAlias(alias)) return store.getKey(alias, null) as SecretKey
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
        init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
    }.generateKey()
}

/** Migrate only after successful encryption; a failed commit leaves the old disk value available. */
fun SharedPreferences.privateString(name: String, cipher: StorageCipher = StorageCipher.shared): String? = synchronized(this) {
    val stored = getString(name, null) ?: return@synchronized null
    val plain = cipher.decrypt(stored) ?: return@synchronized null
    if (stored == plain) {
        val encrypted = cipher.encrypt(plain)
        if (encrypted != stored && !edit().putString(name, encrypted).commit()) cipher.warn()
    }
    plain
}

fun SharedPreferences.putPrivateString(name: String, value: String, cipher: StorageCipher = StorageCipher.shared) = synchronized(this) {
    edit().putString(name, cipher.encrypt(value)).apply()
}
