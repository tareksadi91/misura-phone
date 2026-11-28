package com.misura.core.routine

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test

/** Card ids carry their gate's prefix, so the app can route a tap to the tool that asked. Routines keep "rt:". */
class RoutineGatePrefixTest {
    @Test fun idsCarryPrefix() {
        val sc = RoutineGate(prefix = "sc").act("keep", "d1", "s1") { "Kept" } as RoutineGate.Result.Ask
        sc.id shouldStartWith "sc:"
        val rt = RoutineGate().act("remove", "r1", "s1") { "Removed" } as RoutineGate.Result.Ask
        rt.id shouldStartWith "rt:"
        sc.id.startsWith("rt:") shouldBe false
    }
}
