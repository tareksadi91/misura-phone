package com.misura.phone.brain

import java.net.ServerSocket
import kotlin.concurrent.thread

/** A one-file HTTP stand-in for Vertex and Places in JVM tests. [handle] returns status + body; null = never answer. */
class TinyHttp(private val responseHeaders: Map<String, String> = emptyMap(), private val handle: (Req) -> Pair<Int, String>?) : AutoCloseable {
    data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    val requests = mutableListOf<Req>()
    private val server = ServerSocket(0)
    val base = "http://127.0.0.1:${server.localPort}"

    init {
        thread(isDaemon = true) {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    s.use {
                        val input = s.getInputStream()
                        fun line() = buildString { while (true) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) append(b.toChar()) } }
                        val (method, path) = line().split(" ")
                        val headers = generateSequence { line().takeIf { it.isNotEmpty() } }
                            .associate { it.substringBefore(":").lowercase() to it.substringAfter(":").trim() }
                        // Content-Length counts bytes, so read bytes (a multi-byte character would stall a char reader).
                        val body = input.readNBytes(headers["content-length"]?.toInt() ?: 0).decodeToString()
                        val req = Req(method, path, headers, body)
                        synchronized(requests) { requests += req }
                        val reply = handle(req) ?: run { Thread.sleep(60_000); return@thread }
                        val bytes = reply.second.toByteArray()
                        s.getOutputStream().apply {
                            val extra = responseHeaders.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
                            write("HTTP/1.1 ${reply.first} X\r\n${extra}Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
    }

    override fun close() = server.close()
}
