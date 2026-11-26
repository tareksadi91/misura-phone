package com.misura.phone.brain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Location simply there when a near-me request comes (owner 26 Nov): a fresh warm fix is reused, a stale one never. Invented coordinates. */
class WarmFixTest {
    private val at = 10.5 to 20.25
    private val min = 60_000_000_000L

    @Test fun aFreshWarmFixMeansNoSecondRequest() = runBlocking {
        var clock = 0L; var reads = 0
        val w = WarmFix { clock }
        assertEquals(at, w.get { reads++; at to clock })       // the warm-up when Misura comes to the front
        clock += 9 * min
        assertEquals(at, w.get { reads++; (1.0 to 1.0) to clock }) // the near run 9 minutes later
        assertEquals(1, reads)
    }

    @Test fun aStaleWarmFixIsNotUsed() = runBlocking {
        var clock = 0L; var reads = 0
        val w = WarmFix { clock }
        w.get { reads++; at to clock }
        clock += 11 * min
        assertNull(w.fresh())
        assertEquals(1.0 to 1.0, w.get { reads++; (1.0 to 1.0) to clock })
        assertEquals(2, reads)
    }

    /** A fix read long ago by the provider is as old as it is, not as old as the read: its own time counts. */
    @Test fun theFixesOwnTimeCounts() = runBlocking {
        var clock = 20 * min
        val w = WarmFix { clock }
        w.get { at to 5 * min } // a cached fix from 15 minutes ago
        assertNull(w.fresh())
    }

    /** A near run that comes while the warm-up is still reading waits for that read instead of asking again. */
    @Test fun aRunDuringTheWarmUpWaitsForIt() = runBlocking {
        val clock = 0L; var reads = 0
        val w = WarmFix { clock }
        val gate = CompletableDeferred<Unit>()
        val warm = async { w.get { reads++; gate.await(); at to clock } }
        yield()
        val run = async { w.get { reads++; (1.0 to 1.0) to clock } }
        yield(); gate.complete(Unit)
        assertEquals(at, warm.await()); assertEquals(at, run.await())
        assertEquals(1, reads)
    }
}
