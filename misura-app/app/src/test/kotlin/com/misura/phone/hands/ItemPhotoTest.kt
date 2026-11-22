package com.misura.phone.hands

import com.misura.phone.hidden.UiNode
import io.kotest.matchers.shouldBe
import org.junit.Test

class ItemPhotoTest {
    private val w = 1080; private val h = 2400
    private fun b(l: Int, t: Int, r: Int, bt: Int) = intArrayOf(l, t, r, bt)
    private fun n(bounds: IntArray, text: String? = null, clickable: Boolean = false, desc: String? = null, cls: String? = null, vararg kids: UiNode) =
        UiNode(text, desc, null, bounds, clickable, false, kids.toList(), cls)
    private fun root(vararg kids: UiNode) = n(b(0, 0, w, h), kids = kids)
    private fun IntArray?.list() = this?.toList()

    /** The measured grid: photo leaf (a TextView) over a clickable block of title, rating and price; badge and video button sit on the photo. */
    private fun tile(l: Int, name: String, price: String) = n(b(l, 400, l + 480, 1100), kids = arrayOf(
        n(b(l + 20, 420, l + 460, 860), cls = "android.widget.TextView"),
        n(b(l + 20, 420, l + 120, 480), "-20%"),
        n(b(l + 380, 780, l + 460, 860), clickable = true),
        n(b(l, 880, l + 480, 1100), clickable = true, kids = arrayOf(n(b(l + 20, 890, l + 460, 950), name), n(b(l + 20, 960, l + 300, 1000), "4,5 stars"), n(b(l + 20, 1010, l + 300, 1060), price))),
    ))

    @Test fun gridTileAndItsPhotoNotTheNeighbour() {
        val left = tile(40, "Blue jacket", "12,99 €"); val right = tile(560, "Red boots", "40 €")
        val r = root(n(b(0, 380, w, 1120), kids = arrayOf(left, right)))
        val (t, p) = photoOf(r, b(60, 890, 500, 950), w, h)!!
        t.list() shouldBe left.bounds.list()
        p.list() shouldBe b(60, 420, 500, 860).list()
        photoOf(r, b(580, 890, 1020, 950), w, h)!!.second.list() shouldBe b(580, 420, 1020, 860).list()
    }

    /** Measured on a real web shopping list: an empty full-screen overlay comes first, a 3 px wrapper sits over the whole list, a tile is several clickable blocks. */
    @Test fun webListTileWithOverlayTinyWrapperAndManyClickableBlocks() {
        fun tile(top: Int, name: String, price: String) = n(b(24, top, 1056, top + 936), kids = arrayOf(
            n(b(15, top + 3, 456, top + 933), clickable = true, desc = "ref=sr_1_1?x", kids = arrayOf(n(b(27, top + 3, 444, top + 933), kids = arrayOf(n(b(51, top + 276, 420, top + 660), cls = "android.widget.TextView"))))),
            n(b(465, top + 27, 1035, top + 90), clickable = true, desc = name),
            n(b(465, top + 280, 1035, top + 360), clickable = true, desc = "4.1 out of 5 stars 10,049 ratings"),
            n(b(465, top + 380, 1035, top + 460), clickable = true, kids = arrayOf(n(b(465, top + 383, 678, top + 458), price))),
            n(b(468, top + 800, 1032, top + 900), clickable = true, desc = "Add to basket"),
        ))
        val list = n(b(12, 180, 1068, 2256), kids = arrayOf(tile(288, "Blue lamp, wireless", "12,99 €"), tile(1245, "Red lamp, wired", "40 €")))
        val r = root(n(b(0, 0, w, h)), n(b(0, 180, w, 183), kids = arrayOf(list)))
        val (t, p) = photoOf(r, b(465, 671, 678, 746), w, h)!!
        t.list() shouldBe b(24, 288, 1056, 1224).list()
        p.list() shouldBe b(51, 564, 420, 948).list()
        photoOf(r, b(465, 1272, 1035, 1335), w, h)!!.second.list() shouldBe b(51, 1521, 420, 1905).list()
    }

    /** Two things in a row, only the first has a picture node: the second must not borrow it. */
    @Test fun aNeighboursPictureIsNotBorrowed() {
        val a = n(b(20, 400, 520, 700), kids = arrayOf(n(b(40, 420, 300, 680), cls = "android.widget.TextView"), n(b(320, 430, 520, 500), clickable = true, desc = "Blue lamp")))
        val bb = n(b(600, 430, 1040, 500), kids = arrayOf(n(b(600, 430, 1040, 500), clickable = true, desc = "Red lamp")))
        val r = root(n(b(0, 380, w, 720), kids = arrayOf(a, bb)))
        photoOf(r, b(600, 430, 1040, 500), w, h)!!.second shouldBe null
        photoOf(r, b(320, 430, 520, 500), w, h)!!.second.list() shouldBe b(40, 420, 300, 680).list()
    }

    /** Picture above, title and price as separate clickable blocks: pointing at the price still finds the photo; a neighbour beside doesn't lend its own. */
    @Test fun pictureAboveSeparateTitleAndPrice() {
        val a = n(b(20, 400, 540, 860), kids = arrayOf(n(b(40, 420, 520, 700), cls = "android.widget.TextView"), n(b(40, 710, 520, 770), clickable = true, desc = "Blue lamp"), n(b(40, 780, 300, 840), clickable = true, desc = "12 €")))
        val bb = n(b(600, 710, 1040, 770), kids = arrayOf(n(b(600, 710, 1040, 770), clickable = true, desc = "Red lamp")))
        val r = root(n(b(0, 380, w, 880), kids = arrayOf(a, bb)))
        photoOf(r, b(40, 780, 300, 840), w, h)!!.second.list() shouldBe b(40, 420, 520, 700).list()
        photoOf(r, b(40, 710, 520, 770), w, h)!!.second.list() shouldBe b(40, 420, 520, 700).list()
        photoOf(r, b(600, 710, 1040, 770), w, h)!!.second shouldBe null
    }

    @Test fun aCardBelowWithoutAPictureDoesNotBorrowTheFirstCardsPhoto() {
        val first = n(b(20, 400, 540, 860), kids = arrayOf(n(b(40, 420, 520, 700), cls = "android.widget.TextView"), n(b(40, 710, 520, 770), clickable = true, desc = "Blue lamp"), n(b(40, 780, 300, 840), clickable = true, desc = "12 €")))
        val second = n(b(20, 1000, 540, 1200), kids = arrayOf(n(b(40, 1010, 520, 1070), clickable = true, desc = "Red lamp"), n(b(40, 1080, 300, 1140), clickable = true, desc = "30 €")))
        val r = root(n(b(0, 380, w, 1220), kids = arrayOf(first, second)))
        photoOf(r, b(40, 780, 300, 840), w, h)!!.second.list() shouldBe b(40, 420, 520, 700).list()
        photoOf(r, b(40, 710, 520, 770), w, h)!!.second.list() shouldBe b(40, 420, 520, 700).list()
        photoOf(r, b(40, 1010, 520, 1070), w, h)?.second shouldBe null
    }

    @Test fun aPhotoTouchingTheEdgeOfItsScrollingListIsCutOff() {
        fun tile(top: Int, name: String) = n(b(24, top, 1056, top + 700), kids = arrayOf(n(b(51, top + 20, 420, top + 404), cls = "android.widget.TextView"), n(b(465, top + 30, 1035, top + 110), clickable = true, desc = name)))
        val inside = n(b(12, 180, 1068, 2256), cls = "android.widget.ListView", kids = arrayOf(tile(300, "Blue lamp, wireless"), tile(1800, "Red lamp, wired")))
        val r = root(n(b(0, 180, w, 2256), kids = arrayOf(inside)))
        photoOf(r, b(465, 330, 1035, 410), w, h)!!.second.list() shouldBe b(51, 320, 420, 704).list()
        // The second photo runs on to the list's bottom edge: the list shows only part of it.
        val clipped = n(b(12, 180, 1068, 2256), cls = "android.widget.ListView", kids = arrayOf(tile(300, "Blue lamp, wireless"), n(b(24, 1800, 1056, 2256), kids = arrayOf(n(b(51, 1820, 420, 2256), cls = "android.widget.TextView"), n(b(465, 1830, 1035, 1910), clickable = true, desc = "Red lamp, wired")))))
        photoOf(root(n(b(0, 180, w, 2256), kids = arrayOf(clipped))), b(465, 1830, 1035, 1910), w, h)!!.second shouldBe null
    }

    @Test fun noPhotoNodeFallsBackToTheRectAboveTheWords() {
        val t = n(b(40, 400, 520, 1100), kids = arrayOf(n(b(40, 880, 520, 1100), clickable = true, kids = arrayOf(n(b(60, 890, 500, 950), "Boots")))))
        val other = n(b(560, 400, 1040, 1100), kids = arrayOf(n(b(560, 880, 1040, 1100), clickable = true, kids = arrayOf(n(b(580, 890, 1020, 950), "Hat")))))
        photoOf(root(n(b(0, 380, w, 1120), kids = arrayOf(t, other))), b(60, 890, 500, 950), w, h)!!.second.list() shouldBe b(40, 400, 520, 890).list()
    }

    @Test fun aPhotoClippedByTheSideEdgeAndNarrowerThanItsNeighbourIsCutOff() {
        // A horizontal row: full-size photos, the last one runs off the right edge and shows only part.
        fun thing(l: Int, r: Int, name: String) = n(b(l, 400, r, 1100), kids = arrayOf(n(b(l, 420, r, 860)), n(b(l, 880, r, 1100), clickable = true, kids = arrayOf(n(b(l + 20, 890, r - 20, 950), name)))))
        val r = root(n(b(0, 380, w, 1120), kids = arrayOf(thing(0, 600, "Cap"), thing(620, 1080, "Hat"))))
        photoOf(r, b(640, 890, 1060, 950), w, h)!!.second shouldBe null
        photoCutOff(r, b(640, 890, 1060, 950), w, h) shouldBe true
        photoCutOff(r, b(20, 890, 580, 950), w, h) shouldBe false   // the wide one touches the left edge but nothing is wider
    }

    @Test fun fullWidthPhotosTouchingBothEdgesAreNotCutOff() {
        fun thing(top: Int, name: String) = n(b(0, top, w, top + 700), kids = arrayOf(n(b(0, top, w, top + 440)), n(b(0, top + 460, w, top + 700), clickable = true, kids = arrayOf(n(b(20, top + 470, w - 20, top + 530), name)))))
        val r = root(n(b(0, 300, w, 1500), kids = arrayOf(thing(300, "Lamp"), thing(1000, "Chair"))))
        photoCutOff(r, b(20, 770, 1060, 830), w, h) shouldBe false
        photoOf(r, b(20, 770, 1060, 830), w, h)!!.second.list() shouldBe b(0, 300, w, 740).list()
        photoCutOff(r, b(20, 1470, 1060, 1530), w, h) shouldBe false
    }

    @Test fun aGridThatReachesTheEdgeIsNotCutOff() {
        val left = tile(0, "Cap", "9 €"); val right = tile(600, "Hat", "12 €")   // same widths, the right one ends exactly at the screen edge
        val r = root(n(b(0, 380, w, 1120), kids = arrayOf(left, right)))
        photoCutOff(r, b(620, 890, 1060, 950), w, h) shouldBe false
    }

    @Test fun aPhotoUnderTheTopBarIsCutOff() {
        val t = n(b(40, 100, 520, 700), kids = arrayOf(n(b(60, 120, 500, 460)), n(b(40, 480, 520, 700), clickable = true, kids = arrayOf(n(b(60, 490, 500, 550), "Hat")))))
        val r = root(n(b(0, 90, w, 710), kids = arrayOf(t, n(b(560, 100, 1040, 700), kids = arrayOf(n(b(560, 480, 1040, 700), clickable = true, kids = arrayOf(n(b(580, 490, 1020, 550), "Cap"))))))))
        photoCutOff(r, b(60, 490, 500, 550), w, h) shouldBe true
    }

    @Test fun listRowWithThePhotoBesideTheText() {
        fun row(top: Int, name: String) = n(b(0, top, w, top + 260), kids = arrayOf(n(b(24, top + 20, 264, top + 240)),
            n(b(288, top + 20, 1056, top + 240), clickable = true, kids = arrayOf(n(b(288, top + 30, 1000, top + 90), name), n(b(288, top + 100, 600, top + 150), "9 €")))))
        val r = root(n(b(0, 300, w, 900), kids = arrayOf(row(300, "Lamp"), row(580, "Chair"))))
        val (t, p) = photoOf(r, b(288, 330, 1000, 390), w, h)!!
        t.list() shouldBe b(0, 300, w, 560).list()
        p.list() shouldBe b(24, 320, 264, 540).list()
    }

    @Test fun twoThingsWithNothingToTellThemApartHaveNoTile() {
        photoOf(root(n(b(0, 400, w, 700), kids = arrayOf(n(b(0, 400, w, 500), "a", true), n(b(0, 500, w, 600), "b", true)))), b(0, 400, w, 500), w, h)!!.first.list() shouldBe b(0, 400, w, 500).list()
        photoOf(root(), b(5000, 0, 5001, 1), w, h) shouldBe null
    }

    @Test fun titleAndPriceMustBeOnTheSameTile() {
        val left = tile(40, "Blue   Jacket", "12,99 €"); val right = tile(560, "Red boots", "40 €")
        val r = root(n(b(0, 380, w, 1120), kids = arrayOf(left, right)))
        val t = left.bounds
        onTile(r, t, "blue jacket") shouldBe true
        onTile(r, t, "12,99 €") shouldBe true
        onTile(r, t, "red boots") shouldBe false   // the neighbour's words
        onTile(r, t, "40 €") shouldBe false
        onTile(r, t, "  ") shouldBe false
    }

    @Test fun aPriceMatchesOnlyAsAWholeNumber() {
        val t = n(b(40, 400, 520, 1100), kids = arrayOf(n(b(60, 890, 500, 950), "Blue jacket"), n(b(60, 960, 500, 1010), "120,00 € incl. shipping")))
        val r = root(t)
        onTile(r, t.bounds, "120,00 €") shouldBe true
        onTile(r, t.bounds, "20,00 €") shouldBe false
        onTile(r, t.bounds, "120,0") shouldBe false
        onTile(r, t.bounds, "0,00 €") shouldBe false
    }

    /** A web list (clips at 180..2256) with one row: photo left, words right. [top] is the row's top, [ph] the photo's height. */
    private fun webRow(top: Int, ph: Int = 370): Pair<UiNode, IntArray> {
        val row = n(b(24, top, 1056, top + ph), kids = arrayOf(
            n(b(51, top, 420, top + ph), cls = "android.widget.TextView"),
            n(b(465, top + 20, 1035, top + 90), clickable = true, desc = "Blue lamp"),
            n(b(465, top + 110, 1035, top + 180), clickable = true, kids = arrayOf(n(b(465, top + 110, 700, top + 180), "12,99 €")))))
        val list = n(b(12, 180, 1068, 2256), cls = "android.webkit.WebView", kids = arrayOf(row))
        return root(list) to b(465, top + 20, 1035, top + 90)
    }

    @Test fun aPhotoCutAtTheBottomMovesUpIntoTheVisibleBand() {
        val (r, at) = webRow(2000)
        val nudge = nudgeFor(r, at, w, h)!!
        nudge.dy shouldBe -(2370 - (2256 - 48))   // photo bottom ends 48 px above the list's edge
        check(nudge.y1 in 180..2256 && nudge.y2 in 180..2256 && nudge.y2 - nudge.y1 == nudge.dy)
    }

    /** The swipe runs down the middle of the list's own width, not the screen's: a list in a left side pane is hit. */
    @Test fun theSwipeColumnIsTheMiddleOfTheListNotTheScreen() {
        val (r, at) = webRow(2000)
        nudgeFor(r, at, w, h)!!.x shouldBe (12 + 1068) / 2
        val row = n(b(24, 2000, 520, 2370), kids = arrayOf(
            n(b(30, 2000, 200, 2370), cls = "android.widget.TextView"),
            n(b(210, 2020, 510, 2090), clickable = true, desc = "Blue lamp")))
        val pane = n(b(12, 180, 540, 2256), cls = "android.webkit.WebView", kids = arrayOf(row))
        nudgeFor(root(pane), b(210, 2020, 510, 2090), w, h)!!.x shouldBe 276
    }

    @Test fun aPhotoUnderTheTopBarMovesDownAndAShortMoveIsStretchedPastATap() {
        val (r, at) = webRow(150)
        val nudge = nudgeFor(r, at, w, h)!!
        nudge.dy shouldBe 150                      // needs 78, stretched to 150
    }

    @Test fun aWholePhotoNeedsNoMoveAndOneThatCannotFitIsLeftAlone() {
        nudgeFor(webRow(800).first, webRow(800).second, w, h) shouldBe null
        nudgeFor(webRow(2000, 2200).first, webRow(2000, 2200).second, w, h) shouldBe null
    }
}
