package com.misura.phone.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** design-system §12: no abrupt background change anywhere in a flow. */
class AtmosphereSettleTest {
    private val levels = Atmosphere.entries.filter { it != Atmosphere.HOLD }.map { it.strength } + QUIET

    /** Value of a fade from [a] to [b] after [ms], as the production tween computes it. */
    private fun at(a: Float, b: Float, ms: Float): Float {
        val d = fadeMs(b)
        return a + (b - a) * SettleEasing.transform((ms / d).coerceIn(0f, 1f))
    }

    @Test fun everyScreenChangeEasesWithoutAJumpAndArrives() {
        for (a in levels) for (b in levels) {
            var prev = a
            var ms = 0f
            while (ms < fadeMs(b)) {
                ms += 33f
                val v = at(a, b, ms)
                assertTrue("$a→$b jumped ${v - prev} in one 33 ms frame", kotlin.math.abs(v - prev) <= kotlin.math.abs(b - a) * .31f + 1e-6f)
                assertTrue("$a→$b overshot", if (b >= a) v in prev..b else v in b..prev)
                prev = v
            }
            assertEquals("$a→$b must arrive", b, at(a, b, fadeMs(b)), 1e-5f)
        }
    }

    @Test fun curveStartsAtZeroAndEndsAtOne() {
        assertEquals(0f, SettleEasing.transform(0f), 1e-6f)
        assertEquals(1f, SettleEasing.transform(1f), 1e-6f)
    }

    @Test fun hidingFollowsTheKeyboardOpening() {
        assertEquals(340f, fadeMs(0f), 0f)
        assertEquals(300f, fadeMs(.18f), 0f)
    }

    @Test fun voiceFollowsSpeechWithoutSnappingAtTheRealFrameCadence() {
        // Alternating loud/quiet levels at the paced redraw interval (+ one display frame of wake-up).
        val step = TEXTURE_FRAME_MS + 16f
        var v = 0f
        for (i in 0 until 20) {
            val goal = if (i % 2 == 0) 1f else 0f
            val next = settle(v, goal, step, VOICE_SMOOTH_MS)
            assertTrue("voice snapped ${kotlin.math.abs(next - v)} toward $goal", kotlin.math.abs(next - v) <= kotlin.math.abs(goal - v) * .7f + 1e-6f)
            v = next
        }
    }

    @Test fun voiceSmoothingNeverJumpsAndIgnoresZeroTime() {
        var v = 0f
        repeat(10) { val next = settle(v, 1f, 16f, 100f); assertTrue(next - v <= .4f && next >= v); v = next }
        assertEquals(.4f, settle(.4f, 1f, 0f, 100f), 0f)
    }
}
