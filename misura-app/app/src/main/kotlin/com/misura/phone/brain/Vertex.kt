package com.misura.phone.brain

import com.misura.core.agent.BrainError
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val VERTEX_BASE = "https://aiplatform.googleapis.com"

/** The key's project first, then the project pushed alongside an adb token. */
fun vertexProject(keyJson: String?, fallback: String?): String =
    runCatching { (Json.parseToJsonElement(keyJson.orEmpty()).jsonObject["project_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content }
        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallback?.trim()?.takeIf { it.isNotEmpty() }
        ?: throw BrainError("no sign-in project", auth = true, code = "project")

/** Model endpoint path on Vertex for [model] in [project], location global. */
fun vertexPath(model: String, project: String) = "/v1/projects/$project/locations/global/publishers/google/models/$model:generateContent"

/**
 * One HTTP call that a coroutine timeout really stops: the blocking work runs on its own thread
 * and the connection is closed when the caller is cancelled. Returns status and body.
 */
// ponytail: a thread per request; fine for a handful of calls per turn.
suspend fun http(method: String, url: String, headers: Map<String, String>, body: String? = null, timeoutMs: Int = 30_000, maxChars: Int? = null): Pair<Int, String> =
    suspendCancellableCoroutine { cont ->
        val conn = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { conn.disconnect() } }
        thread(isDaemon = true, name = "misura-http") {
            try {
                conn.requestMethod = method
                conn.instanceFollowRedirects = headers.isEmpty()
                conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) { conn.doOutput = true; conn.outputStream.use { it.write(body.toByteArray()) } }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { reader ->
                    if (maxChars == null) reader.readText() else buildString {
                        val buffer = CharArray(8192)
                        while (true) {
                            val count = reader.read(buffer)
                            if (count < 0) break
                            require(length + count <= maxChars) { "Response too large" }
                            append(buffer, 0, count)
                        }
                    }
                }.orEmpty()
                if (cont.isActive) cont.resume(code to text)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally { conn.disconnect() }
        }
    }

/** Same cancellation contract as [http], for binary bodies (photos). */
suspend fun httpBytes(url: String, headers: Map<String, String>, timeoutMs: Int = 30_000): Pair<Int, ByteArray> =
    suspendCancellableCoroutine { cont ->
        val conn = URL(url).openConnection() as HttpURLConnection
        cont.invokeOnCancellation { runCatching { conn.disconnect() } }
        thread(isDaemon = true, name = "misura-http") {
            try {
                conn.instanceFollowRedirects = headers.isEmpty()
                conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val bytes = (if (code < 400) conn.inputStream else conn.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (cont.isActive) cont.resume(code to bytes)
            } catch (e: Exception) {
                if (cont.isActive) cont.resumeWithException(e)
            } finally { conn.disconnect() }
        }
    }

/** Google auth headers for calls billed to the Vertex project. */
fun googleHeaders(token: String, project: String) = mapOf(
    "Authorization" to "Bearer $token",
    "x-goog-user-project" to project,
    "Content-Type" to "application/json",
)
