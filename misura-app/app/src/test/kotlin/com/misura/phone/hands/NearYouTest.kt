package com.misura.phone.hands

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The map's "you" dot: the run's own fix, in memory, for that session's set, at most an hour old. Invented coordinates and ids. */
class NearYouTest {
    private val at = 10.5 to 20.25
    private val hour = 3_600_000_000_000L

    @Test fun theDotBelongsToTheSessionsOwnSetForAnHour() {
        NearYou.keep("s1", at, listOf("a", "b"), nanos = 0)
        assertEquals(at, NearYou.at("s1", listOf("a", "b"), nanos = hour))
        assertNull(NearYou.at("s2", listOf("a", "b"), nanos = 1))           // another session
        assertNull(NearYou.at("s1", listOf("a", "c"), nanos = 1))           // another set
        assertNull(NearYou.at("s1", listOf("a", "b"), nanos = hour + 1))    // older than an hour: not "you"
        assertNull(NearYou.at("s1", listOf("a", "b"), nanos = 2))           // and gone
    }

    @Test fun theNextRunReplacesIt() {
        NearYou.keep("s3", at, listOf("a"), nanos = 0)
        NearYou.keep("s3", null, listOf("b"), nanos = 1)  // the next near run had no fix
        assertNull(NearYou.at("s3", listOf("a"), nanos = 2))
        NearYou.keep("s3", at, listOf("a"), nanos = 3)
        NearYou.keep("s3", 1.0 to 2.0, listOf("c"), nanos = 4)
        assertNull(NearYou.at("s3", listOf("a"), nanos = 5))
        assertEquals(1.0 to 2.0, NearYou.at("s3", listOf("c"), nanos = 5))
    }
}
