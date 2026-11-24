package com.misura.phone.hands

import io.kotest.matchers.shouldBe
import org.junit.Test

class TileDistanceTest {
    @Test fun oneDistanceOnATileIsReadAsWritten() {
        tileDistance(listOf("Gym North", "Fitness · 2.6 km")) shouldBe "2.6 km"
        tileDistance(listOf("Fitness · 400 m")) shouldBe "400 m"
        tileDistance(listOf("Fitness · 2,1 km")) shouldBe "2,1 km"
        tileDistance(listOf("2.6 km", "2.6 km")) shouldBe "2.6 km" // the same one twice is still one
    }

    @Test fun noneOrTwoDifferentAreUnknown() {
        tileDistance(listOf("Fitness")) shouldBe null
        tileDistance(listOf("Fitness · 1.2 km · 3 km to centre")) shouldBe null
        tileDistance(listOf("Open 10 min", "Class 45 min")) shouldBe null // minutes are not metres
        tileDistance(listOf("Studio 21km")) shouldBe "21km"
        tileDistance(listOf("Room 3.5m2 big")) shouldBe null
        tileDistance(listOf("Fitness · 1 200 m")) shouldBe null // a grouped number is not "200 m" (Codex P2)
        tileDistance(listOf("Fitness · 1\u00A0200 m")) shouldBe null // no-break space grouping too
        tileDistance(listOf("Fitness · 1,2\u00A0km")) shouldBe "1,2 km"
        tileDistance(listOf("Fitness · 1,200 m")) shouldBe null // 1.2 m or 1200 m: unknown
        tileDistance(listOf("Fitness · 1.200 m")) shouldBe null
        tileDistance(listOf("Fitness · 1  200 m")) shouldBe null
        tileDistance(listOf("Fitness · 1 200 m · 400 m to centre")) shouldBe null
    }

    @Test fun metresCompareAcrossUnits() {
        meters("400 m") shouldBe 400.0
        meters("2,1 km") shouldBe 2100.0
        (meters("0.9 km")!! < meters("1.8 km")!!) shouldBe true
        meters("1 mi") shouldBe 1609.344
    }
}
