package com.misura.phone.notify

import com.misura.core.inbox.Msg
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProbeLogTest {
    @get:Rule val temp = TemporaryFolder()
    private val capture = Captured(
        listOf(Msg("private-id", "app.test", "private-chat", "private-sender", false, false, "private-message", 1)),
        mapOf("private-extra" to "private-value"), 0, false, "private-chat",
    )

    @Test fun metadataOmitsContentAndReleaseErasesExistingLog() {
        val file = temp.newFile()
        ProbeLog.append(file, "app.test", capture, debug = true, now = 100)
        val line = file.readText()
        assertTrue(line.contains("\"messageCount\":1"))
        assertFalse(line.contains("private-"))
        assertFalse(line.contains("extras"))
        ProbeLog.append(file, "app.test", capture, debug = false, now = 101)
        assertFalse(file.exists())
    }

    @Test fun logRotatesAtByteLimitAndExpiresFromOldestRecord() {
        val file = temp.newFile()
        repeat(4000) { ProbeLog.append(file, "app.test", capture, debug = true, now = 100) }
        assertTrue(file.length() in 1..ProbeLog.MAX_BYTES.toLong())
        assertTrue(file.readLines().size < 4000)
        ProbeLog.append(file, "app.test", capture, debug = true, now = 101)
        ProbeLog.clean(file, debug = true, now = 100 + ProbeLog.MAX_AGE_MS)
        assertFalse(file.exists()) // recent writes must not keep the oldest record alive
        file.writeText("x".repeat(ProbeLog.MAX_BYTES + 1))
        ProbeLog.clean(file, debug = true, now = 102)
        assertFalse(file.exists())
    }
}
