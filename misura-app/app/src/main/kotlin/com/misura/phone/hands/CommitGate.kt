package com.misura.phone.hands

/** Words on a button that mean the tap does something you can't take back, or that goes out to someone. English and German. */
private val COMMIT = setOf(
    "send", "pay", "buy", "purchase", "order", "book", "delete", "remove", "submit", "post", "publish", "checkout", "transfer", "call", "share", "unsubscribe", "uninstall",
    "senden", "absenden", "bezahlen", "zahlen", "kaufen", "bestellen", "buchen", "löschen", "entfernen", "posten", "veröffentlichen", "überweisen", "anrufen", "teilen",
)

/** The commit word on a button, or null. Whole words only ("Sender" and "Bookmarks" don't count). Reads the visible text and the hidden label, so an arrow-only send button counts. */
fun commitWord(words: String): String? = Regex("[\\p{L}]+").findAll(words.lowercase()).map { it.value }.firstOrNull { it in COMMIT }

/**
 * The code gate for [UseApp]: a tap on a commit button is refused until you said yes on Misura's own card.
 * One yes covers one tap on the button that card named, in that app, for a few minutes. Each question has its own id, so a
 * card only ever answers its own question, whatever other tasks are doing. The model can't grant it: [answered] is only
 * called from a card tap.
 * ponytail: a word list misses buttons with no words at all (Enter with typed text is gated by [UseApp] as "Enter"); a per-app "may act" switch would close that.
 * A yes is also tied to the goal it was asked for and to where the button sat ([at]) and the words on its row ([row]): approving "Delete" on one row does not delete another row,
 * and a later goal on the same app asks again (Codex audit 21 Nov). Empty [at] = no position known (only matches empty).
 * A yes also belongs to the task that asked (its session, from [com.misura.core.agent.RunningFor]): another task tapping the same-named
 * button in the same app does not use it, and the first task's yes stays for the first task. Null (a run outside the agent) matches only null.
 */
class CommitGate(private val clock: () -> Long = System::currentTimeMillis, private val validMs: Long = 5 * 60_000) {
    /** What a tap puts out, read off the app's screen when the run stopped: the typed text, and for a message the chat's name and where it stood ([who] empty otherwise). */
    data class Out(val who: String, val at: List<Int>, val text: String)
    private data class Tap(val pkg: String, val words: String, val out: Out?, val session: String?, val goal: String?, val at: List<Int>, val row: String)
    private val asked = HashMap<String, Tap>()
    private val granted = ArrayList<Pair<Tap, Long>>()

    /** The run stopped before tapping [words] in [pkg]; returns the id its question card carries. [out] = what the card shows will go out. */
    @Synchronized fun ask(pkg: String, words: String, out: Out? = null, session: String? = null, goal: String? = null, at: List<Int> = emptyList(), row: String = ""): String =
        java.util.UUID.randomUUID().toString().also { asked[it] = Tap(pkg, words, out, session, goal, at, row) }
    /** You tapped a choice on the card with this id. */
    @Synchronized fun answered(id: String, yes: Boolean) { val t = asked.remove(id) ?: return; if (yes) granted += t to clock() + validMs }
    /** A turn was stopped or failed: a yes it didn't use must not wait around for some later goal (Codex P1). The next commit tap asks again. */
    @Synchronized fun revoke() { granted.clear() }
    /**
     * May this tap go through? Uses the yes up. A yes given to a card that showed a message only counts while [fits] says the
     * screen still holds that same chat and text: different words or another chat on the second run = a new card.
     */
    @Synchronized fun allows(pkg: String, words: String, session: String? = null, goal: String? = null, at: List<Int> = emptyList(), row: String = "", fits: (Out) -> Boolean = { false }): Boolean {
        granted.removeAll { it.second <= clock() }
        val g = granted.firstOrNull { it.first.pkg == pkg && it.first.words == words && it.first.session == session && it.first.goal == goal && it.first.at == at && it.first.row == row && it.first.out.let { o -> o == null || fits(o) } } ?: return false
        return granted.remove(g)
    }
}
