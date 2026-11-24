package com.misura.phone.brain

import com.misura.core.agent.BrainError
import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * The phone's own Google login: a service account that may only use Vertex and Places. From its key it makes a fresh
 * access token whenever the last one is about to run out, so the model never becomes unreachable because a laptop's
 * one-hour token expired. No key on the phone = [fallback] (the token pushed over adb), as before.
 */
private const val RETRY_MS = 30_000L
private const val NETWORK = "google_oauth_network"

// ponytail: the token call blocks the calling thread for a few hundred ms about once an hour; fine off the main thread, where every caller is.
class ServiceAccountToken(
    private val key: () -> String?,
    private val fallback: TokenProvider,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Always Google's own endpoint on the phone, whatever the key file says: a doctored file can't send the signed request elsewhere. */
    private val tokenUrl: String = "https://oauth2.googleapis.com/token",
) : TokenProvider {
    private var token: String? = null
    private var goodUntil = 0L
    private var expiresAt = 0L
    private var failedAt = Long.MIN_VALUE / 2
    private var forKey: String? = null
    private var failure: BrainError? = null

    @Synchronized override fun token(): String? {
        val k = key()?.takeIf { it.isNotBlank() } ?: return fallback.token()
        if (k != forKey) { token = null; failure = null; forKey = k; failedAt = Long.MIN_VALUE / 2 } // another key was put on the phone
        val now = clock()
        token?.takeIf { now < goodUntil }?.let { return it }
        // After a rejection, wait before asking again: a revoked key must not turn every model call into another failed exchange.
        // After a dropped connection, wait only while the old token still works; once it is out, the next call asks at once.
        val usable = token?.takeIf { now < expiresAt }
        val coolingDown = now - failedAt < RETRY_MS && (failure?.code != NETWORK || usable != null)
        val fresh = if (coolingDown) null else try {
            fetch(k).also { failure = null }
        } catch (e: Exception) {
            failure = when (e) {
                is BrainError -> e
                // A dropped connection is a network blip, not a sign-in problem: no cooldown, and callers that retry network
                // errors (the hand, once after 1.5 s) reach Google again instead of hitting a 30 s refusal.
                is IOException -> BrainError("Google sign-in unreachable", code = NETWORK)
                else -> tokenError("google_credentials_invalid")
            }
            failedAt = now
            null
        }
        // A token in its last five minutes is still a token: keep it when the early renewal failed. Then the pushed one; then "no sign-in".
        return fresh ?: usable ?: fallback.token() ?: throw requireNotNull(failure)
    }

    private fun fetch(keyJson: String): String {
        val j = Json.parseToJsonElement(keyJson).jsonObject
        fun s(n: String) = (j[n] as JsonPrimitive).content
        val now = clock() / 1000
        val b64 = Base64.getUrlEncoder().withoutPadding()
        val head = b64.encodeToString("""{"alg":"RS256","typ":"JWT"}""".toByteArray())
        val claims = b64.encodeToString("""{"iss":"${s("client_email")}","scope":"https://www.googleapis.com/auth/cloud-platform","aud":"https://oauth2.googleapis.com/token","iat":$now,"exp":${now + 3600}}""".toByteArray())
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(serviceAccountPrivateKey(s("private_key"))); update("$head.$claims".toByteArray())
        }.sign()
        val body = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", "UTF-8") + "&assertion=$head.$claims.${b64.encodeToString(sig)}"
        val conn = URL(tokenUrl).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = false
            conn.requestMethod = "POST"; conn.connectTimeout = 10_000; conn.readTimeout = 10_000; conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode != 200) throw tokenError("google_oauth_${conn.responseCode}")
            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val (t, lifetime) = try {
                val r = Json.parseToJsonElement(response).jsonObject
                val value = (r["access_token"] as JsonPrimitive).takeIf { it.isString }?.content
                require(!value.isNullOrBlank())
                val seconds = (r["expires_in"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 3600
                require(seconds in 1..86_400)
                value to seconds
            } catch (_: Exception) { throw tokenError("google_oauth_response") }
            // Five minutes early, so a call that starts now doesn't end with an expired token.
            expiresAt = clock() + lifetime * 1000
            goodUntil = expiresAt - 300_000
            token = t
            return t
        } finally { conn.disconnect() }
    }

    // OAuth error bodies can echo the assertion. Retain only a locally generated category/status, never a cause.
    private fun tokenError(code: String) = BrainError("Google sign-in failed", auth = true, code = code)
}

/** Shared by offline setup validation and the existing fixed-endpoint OAuth signer. */
internal fun serviceAccountPrivateKey(value: String): java.security.PrivateKey {
    val pem = value.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replace("\\n", "").filterNot { it.isWhitespace() }
    return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
}
