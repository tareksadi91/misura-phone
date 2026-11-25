package com.misura.phone.screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.misura.core.agent.ItemFact
import com.misura.phone.cards.MisuraSurface
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/** Item cards in a set look alike: photos all or none (owner, 25 Nov), and every card the same height whatever it holds. */
class ItemCardsTest {
    @get:Rule val rule = createComposeRule()

    private val withPhoto = ItemFact("I1", "Blue jacket", "€35", "Size M · good condition", photo = "/nonexistent/a.jpg", app = "Sample shop")
    private val bare = ItemFact("I2", "Grey jacket")

    @Test fun oneItemWithoutAPhotoMeansNoCardInTheSetShowsOne() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(listOf(withPhoto, bare), null, null, 0, {}, {}) } } }
        rule.onAllNodesWithTag(ITEM_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().size shouldBe 0
    }

    @Test fun aSetWhereEveryItemHasAPhotoShowsThePhotoSlot() {
        rule.setContent { MisuraSurface { Box(Modifier.fillMaxSize()) { ItemScreen(listOf(withPhoto, withPhoto.copy(id = "I3")), null, null, 0, {}, {}) } } }
        rule.onAllNodesWithTag(ITEM_PHOTO_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() shouldBe true
    }

    @Test fun aFullCardAndAnEmptyCardAreTheSameHeight() {
        val full = withPhoto.copy(photo = null, detail = "A long detail line that wraps over more than two lines on a narrow card, so it has to be cut off at two.")
        rule.setContent {
            MisuraSurface { Column {
                Box(Modifier.width(300.dp).testTag("full")) { ItemCard(full, true) {} }
                Box(Modifier.width(300.dp).testTag("bare")) { ItemCard(bare, false) {} }
            } }
        }
        val a = rule.onNodeWithTag("full").getBoundsInRoot()
        val b = rule.onNodeWithTag("bare").getBoundsInRoot()
        (b.bottom - b.top) shouldBe (a.bottom - a.top)
    }
}
