package com.misura.phone.brain

import com.misura.core.agent.ABOUT_MAX
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.Test

class RememberTest {
    @Test fun notesFollowTheBriefingAndTheDefaultStaysWithoutOne() {
        aboutWithNotes("Likes ramen.", emptyList()) shouldBe "Likes ramen."
        aboutWithNotes(null, listOf("2026-11-14: vegetarian now")).let { it shouldContain com.misura.core.agent.DEFAULT_ABOUT; it shouldContain "- 2026-11-14: vegetarian now" }
    }

    @Test fun aLongBriefingAndManyNotesStillKeepTheNewestNoteInsideTheCap() {
        val notes = (1..60).map { "2026-11-14: note $it " + "x".repeat(200) }
        val about = aboutWithNotes("B".repeat(9000), notes)
        about.length shouldBeLessThanOrEqual ABOUT_MAX
        about shouldContain "note 60 "
        about shouldNotContain "note 1 "
    }
}
