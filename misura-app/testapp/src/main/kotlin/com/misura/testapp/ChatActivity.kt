package com.misura.testapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Fake chat screen built from classic views with resource ids, like WhatsApp's (id/entry, id/send).
 * testchat://chat/<name> opens that chat; no link shows the conversation list.
 */
class ChatActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); current = this; render(intent) }
    override fun onDestroy() { if (current === this) current = null; super.onDestroy() }

    /** What Enter does on the page in front (a form that submits on Enter, a search box); null = nothing. Wherever focus is. */
    private var onEnter: (() -> Unit)? = null
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val act = onEnter
        if (e.keyCode == KeyEvent.KEYCODE_ENTER && act != null) { if (e.action == KeyEvent.ACTION_UP) act(); return true }
        return super.dispatchKeyEvent(e)
    }
    /** The gate page's "Preview" button; [FlipReceiver] turns it into "Submit" in place, as a page can while the hand's model thinks. */
    private var flip: (() -> Unit)? = null
    /** Renames row A in place ([FlipReceiver] with swap=true), as a list can move another item under the same Delete. */
    private var swap: (() -> Unit)? = null
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); render(intent) }

    private fun render(i: Intent) {
        val name = i.data?.lastPathSegment
        if (name == null) showList() else showChat(name)
    }

    private fun showList() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        col.addView(TextView(this).apply { text = "TestChat"; textSize = 22f })
        Chat.convs.values.forEach { c ->
            col.addView(TextView(this).apply { id = R.id.conv_name; text = c.sender; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showChat(c.sender) } })
        }
        // A plain page of things with photos, for the hand's item recording: two tiles, each a picture over a clickable block of words.
        col.addView(TextView(this).apply { text = "Shop"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showShop() } })
        // A scrolling list like a web shop's: photo left, words right, tiles run past the bottom of the screen. The twin page shows one title twice.
        col.addView(TextView(this).apply { text = "Tall shop"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showTall(false) } })
        col.addView(TextView(this).apply { text = "Tall shop twin"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showTall(true) } })
        // The approval gate's cases: buttons whose words change or run long, forms that act on Enter, two rows with the same Delete.
        col.addView(TextView(this).apply { text = "Gate page"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showGate() } })
        col.addView(TextView(this).apply { text = "Note form"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showForm(search = false) } })
        col.addView(TextView(this).apply { text = "Search form"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showForm(search = true) } })
        // Browser-like cases: a field after 160 small labels, two forms with typed words, a table of numbered records.
        col.addView(TextView(this).apply { text = "Long form"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showLong() } })
        col.addView(TextView(this).apply { text = "Two forms"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showTwo() } })
        col.addView(TextView(this).apply { text = "Records"; textSize = 18f; setPadding(0, 24, 0, 24); setOnClickListener { showRecords() } })
        onEnter = null; flip = null; swap = null
        setContentView(col)
    }

    /** "Status: …" says what the last effect was, so a test reads off the screen whether anything went out. */
    private fun status() = TextView(this).apply { id = R.id.status; text = "Status: none"; textSize = 16f; setPadding(0, 24, 0, 24) }

    private fun showGate() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val st = status()
        col.addView(TextView(this).apply { text = "Gate"; textSize = 22f })
        val preview = Button(this).apply { text = "Preview"; isAllCaps = false; setOnClickListener { st.text = if (text == "Submit") "Status: submitted preview" else "Status: previewed" } }
        flip = { preview.text = "Submit" }
        col.addView(preview)
        // Words fill the first 120 characters; only the label says what it does.
        col.addView(Button(this).apply {
            text = "Read this whole line before you continue anywhere else on this page because it is long enough to fill every visible char"
            contentDescription = "Submit"; isAllCaps = false; setOnClickListener { st.text = "Status: submitted long" }
        })
        // A clickable labelled "Send invite" around a plain "Go"; a wordless clickable whose only words ("Share") sit left of its centre.
        col.addView(LinearLayout(this).apply {
            isClickable = true; contentDescription = "Send invite"; setPadding(0, 24, 0, 24); setOnClickListener { st.text = "Status: sent invite" }
            addView(TextView(context).apply { text = "Go"; textSize = 16f })
        })
        col.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isClickable = true; setPadding(0, 24, 0, 24); setOnClickListener { st.text = "Status: shared" }
            addView(TextView(context).apply { text = "Share"; textSize = 16f }); addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        listOf("A", "B").forEach { name ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply { text = "Item $name"; textSize = 16f; if (name == "A") swap = { text = "Item C" } }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(Button(context).apply { text = "Delete"; isAllCaps = false; setOnClickListener { st.text = "Status: deleted $name"; (parent as View).visibility = View.GONE } })
            })
        }
        col.addView(st)
        onEnter = null
        setContentView(col)
    }

    /** 160 small labels, then a focused field already holding words that Enter submits: past the hand's 150 screen lines. */
    private fun showLong() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val st = status()
        val grid = android.widget.GridLayout(this).apply { columnCount = 16 }
        repeat(160) { k -> grid.addView(TextView(this).apply { text = "${k % 10}"; textSize = 8f; setPadding(6, 2, 6, 2) }) }
        col.addView(grid)
        val field = EditText(this).apply { id = R.id.note; hint = "Message"; isSingleLine = true; setText("DEMO") }
        col.addView(field); col.addView(st)
        onEnter = { st.text = "Status: submitted ${field.text}" }
        flip = null; swap = null
        setContentView(col)
        field.requestFocus()
    }

    /** Two forms, both with typed words; Enter submits the focused one. [FlipReceiver] moves focus to form B. */
    private fun showTwo() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val st = status()
        col.addView(TextView(this).apply { text = "Replies"; textSize = 22f })
        val a = EditText(this).apply { hint = "Reply A"; isSingleLine = true; setText("yes to A") }
        val b = EditText(this).apply { hint = "Reply B"; isSingleLine = true; setText("yes to B") }
        // A focusable button: [FlipReceiver] swap=true moves focus onto it (Enter there would press it).
        val go = Button(this).apply { text = "Go on"; isAllCaps = false; isFocusableInTouchMode = true; setOnClickListener { st.text = "Status: went on" } }
        col.addView(a); col.addView(b); col.addView(go); col.addView(st)
        var sent = 0 // counts every submission, so a test sees one yes press Enter exactly once
        onEnter = { sent++; st.text = (if (b.hasFocus()) "Status: submitted B" else if (go.hasFocus()) "Status: went on" else "Status: submitted A") + " x$sent" }
        flip = { b.requestFocus() }; swap = { go.requestFocus() }
        setContentView(col)
        a.requestFocus()
    }

    /** Numbered records with a Delete each; [FlipReceiver] swap renames record 101 to 202 in place. */
    private fun showRecords() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val st = status()
        col.addView(TextView(this).apply { text = "Records"; textSize = 22f })
        listOf("101", "102").forEach { n ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                val label = TextView(context).apply { text = n; textSize = 16f }
                if (n == "101") swap = { label.text = "202" }
                addView(label, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(Button(context).apply { text = "Delete"; isAllCaps = false; setOnClickListener { st.text = "Status: deleted ${label.text}" } })
            })
        }
        col.addView(st)
        onEnter = null; flip = null
        setContentView(col)
    }

    /** One field that acts on Enter: a note form submits what was typed, a search box searches. */
    private fun showForm(search: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val st = status()
        col.addView(TextView(this).apply { text = if (search) "Find" else "Notes"; textSize = 22f })
        val field = EditText(this).apply { id = if (search) R.id.search_box else R.id.note; hint = if (search) "Search" else "Note"; isSingleLine = true }
        col.addView(field); col.addView(st)
        onEnter = { val t = field.text.toString(); if (t.isNotBlank()) { st.text = if (search) "Status: searched $t" else "Status: submitted $t"; if (!search) field.setText("") } }
        flip = null
        setContentView(col)
    }

    private fun showTall(twin: Boolean) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        val d = resources.displayMetrics.density
        col.addView(TextView(this).apply { text = "Lamps"; textSize = 22f; setPadding(0, 0, 0, 48) })
        // Fifth tile is "Gold lamp" too on the twin page. The sixth tile's photo runs past the bottom of the screen, its words do not.
        val lamps = listOf(Triple("Blue lamp", "12 €", 0xFF3355CC), Triple("Green lamp", "20 €", 0xFF33AA55), Triple("Red lamp", "30 €", 0xFFCC3333),
            Triple("Pink lamp", "40 €", 0xFFEE77AA), Triple(if (twin) "Gold lamp" else "Teal lamp", "45 €", 0xFF22AAAA), Triple("Gold lamp", "50 €", 0xFFDDAA22), Triple("Grey lamp", "60 €", 0xFF888888))
        lamps.forEach { (name, price, color) ->
            col.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ImageView(context).apply { setBackgroundColor(color.toInt()); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams((120 * d).toInt(), (120 * d).toInt()))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL; isClickable = true; setPadding(24, 0, 0, 0)
                    addView(TextView(context).apply { text = name; textSize = 16f }); addView(TextView(context).apply { text = price; textSize = 14f })
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = (16 * d).toInt() })
        }
        setContentView(ScrollView(this).apply { addView(col) })
    }

    private fun showShop() {
        onEnter = null; flip = null
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        col.addView(TextView(this).apply { text = "Lamps"; textSize = 22f; setPadding(0, 0, 0, 48) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun tile(name: String, price: String, color: Int) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Without a label a plain picture is left out of the accessibility tree; real apps' photos are in it or not, per app.
            addView(ImageView(context).apply { setBackgroundColor(color); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams(MATCH_PARENT, (150 * resources.displayMetrics.density).toInt()))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                addView(TextView(context).apply { text = name; textSize = 16f }); addView(TextView(context).apply { text = price; textSize = 14f })
            })
        }
        row.addView(tile("Blue lamp", "12 €", 0xFF3355CC.toInt()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { rightMargin = 24 })
        row.addView(tile("Red lamp", "30 €", 0xFFCC3333.toInt()), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = 24 })
        col.addView(row)
        setContentView(col)
    }

    companion object { @Volatile var current: ChatActivity? = null; fun flipNow(rows: Boolean = false) { current?.let { a -> a.runOnUiThread { (if (rows) a.swap else a.flip)?.invoke() } } } }

    private fun showChat(name: String) {
        onEnter = null; flip = null
        val conv = Chat.convs.values.firstOrNull { it.sender == name } ?: Chat.Conv(name, name).also { Chat.convs[name] = it }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        col.addView(TextView(this).apply { id = R.id.conversation_title; text = name; textSize = 22f })
        val msgs = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun fill() { msgs.removeAllViews(); conv.lines.forEach { l -> msgs.addView(TextView(this).apply { id = R.id.message_text; text = l.text; textSize = 16f }) } }
        fill()
        col.addView(ScrollView(this).apply { addView(msgs) }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        val entry = EditText(this).apply { id = R.id.entry; hint = "Message" }
        val send = ImageButton(this).apply {
            id = R.id.send; contentDescription = "Send"; setImageResource(android.R.drawable.ic_menu_send)
            setOnClickListener {
                val t = entry.text.toString()
                if (t.isBlank()) return@setOnClickListener
                Chat.replied(this@ChatActivity, conv.key, t)
                entry.setText(""); fill()
            }
        }
        col.addView(LinearLayout(this).apply {
            addView(entry, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)); addView(send, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        setContentView(col)
    }
}
