package com.misura.phone.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class HoldToTalkTest {
    private class FakeVoice(var canListen: Boolean = true) : Voice {
        var final: ((String?) -> Unit)? = null; var partial: ((String) -> Unit)? = null; var starts = 0; var stops = 0
        override fun start(onPartial: (String) -> Unit, onFinal: (String?) -> Unit): Boolean { if (!canListen) return false; starts++; partial = onPartial; final = onFinal; return true }
        override fun stop() { stops++ }
    }
    private val v = FakeVoice(); private val asked = mutableListOf<String>(); private var field = ""; private var listening = false
    private val talk = HoldToTalk(v, { field = it }, { listening = it }) { asked += it }

    @Test fun asksOnReleaseNotWhenTheRecogniserStopsAtAPause() {
        talk.down(); v.partial!!("set an"); assertEquals("set an", field)
        v.final!!("set an alarm")                       // recogniser gave up at a pause, finger still down
        assertEquals(emptyList<String>(), asked); assertEquals(2, v.starts); assertEquals(true, listening)
        v.partial!!("for seven"); assertEquals("set an alarm for seven", field)
        talk.up(); assertEquals(1, v.stops)
        v.final!!("for seven")
        assertEquals(listOf("set an alarm for seven"), asked); assertEquals("", field); assertEquals(false, listening)
    }

    @Test fun silenceAsksNothingAndDoesNotRestartForever() {
        talk.down(); v.final!!(null)
        assertEquals(1, v.starts); assertEquals(false, listening)
        talk.up(); assertEquals(emptyList<String>(), asked)
    }

    @Test fun wordsHeardBeforeAnErrorStillGoOutOnRelease() {
        talk.down(); v.final!!("timer two minutes"); v.final!!(null); talk.up()
        assertEquals(listOf("timer two minutes"), asked)
    }

    @Test fun noPermissionYetDoesNothing() {
        v.canListen = false; talk.down(); talk.up()
        assertEquals(emptyList<String>(), asked); assertEquals(false, listening)
    }

    @Test fun leavingMidHoldDropsWhatWasHeard() {
        talk.down(); v.final!!("go"); talk.cancel(); assertEquals(1, v.stops) // heard "go", still listening, screen goes away
        v.final!!("now")
        assertEquals(emptyList<String>(), asked); assertEquals("", field); assertEquals(false, listening)
        talk.down(); talk.up(); v.final!!("timer"); assertEquals(listOf("timer"), asked) // the next hold works as usual
    }

    @Test fun cancelWithoutAHoldLeavesTypedWordsAlone() {
        field = "typed, not sent"; talk.cancel()
        assertEquals("typed, not sent", field); assertEquals(emptyList<String>(), asked)
    }
}
