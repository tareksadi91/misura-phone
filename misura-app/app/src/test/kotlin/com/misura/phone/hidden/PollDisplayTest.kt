package com.misura.phone.hidden

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** M2: launch and move polling stop at the first expected display, never succeed on null/another display, and keep the limit. */
class PollDisplayTest {
    private fun seq(vararg answers: Int?): suspend () -> Int? { var i = 0; return { answers[minOf(i++, answers.lastIndex)] } }

    @Test fun immediateSuccessAsksOnce() = runBlocking {
        assertEquals(7 to 1, pollDisplay(seq(7), want = 7, gapMs = 0))
        assertEquals(0 to 1, pollDisplay(seq(0), want = 0, gapMs = 0))
    }

    @Test fun delayedSuccessStopsAtTheFirstExpectedDisplay() = runBlocking {
        assertEquals(7 to 4, pollDisplay(seq(null, null, 0, 7, 7), want = 7, gapMs = 0))
    }

    @Test fun nullOrAnotherDisplayNeverSucceedsAndKeepsTheLimit() = runBlocking {
        assertEquals(null to 20, pollDisplay(seq(null), want = 7, gapMs = 0))
        assertEquals(0 to 20, pollDisplay(seq(0), want = 7, gapMs = 0))
        var asks = 0
        pollDisplay({ asks++; 3 }, want = 7, max = 5, gapMs = 0)
        assertEquals(5, asks)
    }
}
