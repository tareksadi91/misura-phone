package com.misura.phone.hands

import com.misura.phone.hidden.UiNode
import org.junit.Assert.assertEquals
import org.junit.Test

class FlattenTest {
    private fun n(text: String? = null, b: IntArray = intArrayOf(0, 0, 100, 100), clickable: Boolean = false, editable: Boolean = false, desc: String? = null, vararg kids: UiNode) =
        UiNode(text, desc, null, b, clickable, editable, kids.toList())

    @Test fun numbersWhatCanBeSeenOrUsedAndSkipsTheRest() {
        val root = n(null, kids = arrayOf(
            n("Alarm", clickable = true),
            n(null), // nothing to say, nothing to do: skipped
            n("Search", editable = true),
            n("Off screen", b = intArrayOf(0, 2000, 100, 2100)),
            n(null, clickable = true, desc = "Add alarm"),
            n("Empty box", b = intArrayOf(10, 10, 10, 10)),
        ))
        val seen = flatten(root, 720, 1600)
        assertEquals(listOf("[0] button \"Alarm\"", "[1] field \"Search\"", "[2] button \"Add alarm\""), seen.map { it.line })
        assertEquals(listOf("Alarm", "Search", "Add alarm"), seen.map { it.words })
        assertEquals(true, seen[1].editable)
    }

    @Test fun aSwitchSaysWhetherItIsOn() {
        val sw = { on: Boolean -> UiNode(null, null, null, intArrayOf(600, 0, 700, 100), false, false, emptyList(), checked = on) }
        val root = n(null, kids = arrayOf(n("Sound on keypress", clickable = true), sw(false), n("Vibrate", clickable = true), sw(true)))
        assertEquals(listOf("[0] button \"Sound on keypress\"", "[1] switch \"\" off", "[2] button \"Vibrate\"", "[3] switch \"\" on"), flatten(root, 720, 1600).map { it.line })
    }

    @Test fun capsTheList() = assertEquals(5, flatten(n(null, kids = Array(20) { n("row $it") }), 720, 1600, max = 5).size)

    /** The gate reads whole words: a long label can't push "Submit" past the cut the model sees (Codex audit 21 Nov). */
    @Test fun aLongLabelKeepsItsWholeWordsForTheGate() {
        val long = "x".repeat(120)
        val seen = flatten(n(null, kids = arrayOf(n(long, clickable = true, desc = "Submit"))), 720, 1600).single()
        assertEquals("submit", commitWord(seen.words))
        assertEquals("[0] button \"$long\"", seen.line) // the model's line stays cut at 120
    }

    @Test fun aFieldShowingOnlyItsHintIsEmptyAndSearchBoxesAreKnown() {
        val f = { text: String, id: String?, hint: String?, showing: Boolean, y: Int -> UiNode(text, null, id, intArrayOf(0, y, 100, y + 100), true, true, emptyList(), hint = hint, showingHint = showing) }
        val seen = flatten(n(null, kids = arrayOf(
            f("Note", "app:id/note", "Note", true, 0),              // only the hint shows: empty
            f("Note", "app:id/note", "Note", false, 100),           // the hint's own words typed: they count (Codex review)
            f("lamps", "app:id/search_box", "Search", false, 200),
            f("", null, "Suchen", true, 300),
            f("hi", "com.research.chat:id/message", "Message", false, 400), // "research" is no search box (Codex review)
            UiNode("x", null, null, intArrayOf(0, 500, 100, 600), true, true, emptyList(), cls = "android.widget.SearchView\$SearchAutoComplete"),
        )), 720, 1600)
        assertEquals(listOf("", "Note", "lamps", "", "hi", "x"), seen.map { it.text })
        assertEquals(listOf(false, false, true, true, false, true), seen.map { it.search })
    }
}
