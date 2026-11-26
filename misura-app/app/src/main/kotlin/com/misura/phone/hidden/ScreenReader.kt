package com.misura.phone.hidden

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Rect
import android.view.KeyEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** Snapshot of one view on a screen. Bounds are left, top, right, bottom in that display's pixels. */
data class UiNode(
    val text: String?, val desc: String?, val viewId: String?, val bounds: IntArray,
    val clickable: Boolean, val editable: Boolean, val children: List<UiNode>,
    val cls: String? = null,
    /** A switch or checkbox: on or off. Null = not something that can be on or off. */
    val checked: Boolean? = null,
    /** A field's placeholder ("Search", "Message"). */
    val hint: String? = null,
    /** The field shows its placeholder, not typed words (Android's isShowingHintText): typing the hint's own words still counts as typed. */
    val showingHint: Boolean = false,
    /** Has input focus: where a key press like Enter goes. */
    val focused: Boolean = false,
)

fun UiNode.find(pred: (UiNode) -> Boolean): UiNode? = if (pred(this)) this else children.firstNotNullOfOrNull { it.find(pred) }
fun UiNode.count(pred: (UiNode) -> Boolean): Int = (if (pred(this)) 1 else 0) + children.sumOf { it.count(pred) }

/**
 * The phone's side key as hold-to-talk. [listener] gets true on press, false on release (main thread); the home screen sets it
 * while it is in front. Pressed anywhere else, the key brings Misura up, and home starts listening if the key is still [held].
 */
object SideKey {
    private var scan = -1
    @Volatile var listener: ((Boolean) -> Unit)? = null
    @Volatile var held = false
    /** This phone has such a key (one press was seen, remembered): home then drops its Hold to talk button. */
    var seen by androidx.compose.runtime.mutableStateOf(false)
    private fun prefs(ctx: android.content.Context) = ctx.applicationContext.getSharedPreferences("keys", android.content.Context.MODE_PRIVATE)
    fun load(ctx: android.content.Context) { val p = prefs(ctx); scan = p.getInt("scan", -1); seen = p.getBoolean("side", false) }
    fun matches(ctx: android.content.Context, e: KeyEvent): Boolean {
        if (e.keyCode != KeyEvent.KEYCODE_UNKNOWN || e.scanCode <= 0) return false
        if (scan == -1 && e.action == KeyEvent.ACTION_DOWN) {
            scan = e.scanCode
            prefs(ctx).edit().putInt("scan", scan).apply()
        }
        return e.scanCode == scan
    }
    fun press(ctx: android.content.Context, down: Boolean) {
        held = down
        if (!seen) { seen = true; prefs(ctx).edit().putBoolean("side", true).apply() }
        val l = listener
        if (l != null) l(down)
        else if (down) ctx.startActivity(Intent(ctx, com.misura.phone.HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The system binds this when the user enables Misura under Accessibility. It only reads and acts on request. */
class MisuraAccessibility : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        SideKey.load(this)
        // The system keeps this service bound, so it also holds the wake watcher when WorkingService could not start.
        if (com.misura.phone.sleep.SleepMode.get(this) != com.misura.phone.sleep.Variant.OFF) com.misura.phone.sleep.WakeWatcher.register(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Windows came or went: every app the user now has on the main screen is told to the hand, which lets go of a screen it parked on that app.
        // (The window list is read here, not the event's package: at the state-changed event the list can still lag.)
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOWS_CHANGED && event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val cb = ScreenReader.userOpened ?: return
        ScreenReader.mainApps().forEach(cb)
    }
    /** Learns the first vendor key with a scan code but no Android key code. Auto-repeats while held are dropped. */
    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!SideKey.matches(this, e)) return false
        if (e.repeatCount == 0) SideKey.press(this, e.action == KeyEvent.ACTION_DOWN)
        return true
    }
    override fun onInterrupt() = Unit
    override fun onUnbind(intent: Intent?): Boolean { instance = null; SideKey.held = false; return super.onUnbind(intent) }

    companion object { @Volatile var instance: MisuraAccessibility? = null }
}

/** Reads the hidden display's app window, and tells whether the user has an app open on the main screen. */
object ScreenReader {
    /** Called with the package of an app that just came up on the main screen (event thread). */
    @Volatile var userOpened: ((String) -> Unit)? = null
    fun connected() = MisuraAccessibility.instance != null

    fun read(s: DisplaySession): UiNode? {
        require(s.displayId != 0) { "display 0 is the main screen" }
        return root(s.displayId)?.let { it.refresh(); snapshot(it, 0) }
    }

    /**
     * Only the nodes whose box holds ([x], [y]), in tree order (parents first, what is drawn on top last), each without children:
     * a fresh look at what a tap there would hit, for a few IPCs instead of the whole tree (a full read took 1.7 s on Amazon). Null = no window.
     */
    fun under(s: DisplaySession, x: Int, y: Int): List<UiNode>? {
        require(s.displayId != 0) { "display 0 is the main screen" }
        val root = root(s.displayId)?.also { it.refresh() } ?: return null
        val out = mutableListOf<UiNode>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            val r = Rect().also { n.getBoundsInScreen(it) }
            if (!r.contains(x, y)) return
            // Past the depth limit snapshot reads no children: just this node, password rules included. A wordless clickable keeps its
            // first few levels, so a button made of an icon and a "Send" text beside its centre is still read as Send.
            val wordless = n.isClickable && n.text.isNullOrBlank() && n.contentDescription.isNullOrBlank()
            val leaf = snapshot(n, depth = if (wordless) 38 else Int.MAX_VALUE)
            out += leaf
            if (depth <= 40 && !n.isPassword) (0 until n.childCount).mapNotNull { n.getChild(it) }.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** The packages with an app window on the main screen right now. */
    internal fun mainApps(): List<String> = windows(0).filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }.mapNotNull { it.root?.packageName?.toString() }
    /** True when [pkg] has a window on the main screen: the user is using it, so hands stay away. */
    fun userIsInApp(pkg: String): Boolean = windows(0).any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg }

    /**
     * A permission question is up on [displayId]: an app window there belongs to a permission controller, or is closed to screen readers
     * (Android's own permission dialog is, to a service that is not an accessibility tool: only its title shows). On such a screen the hand
     * may only press Back; [HiddenInput] and [ShizukuHiddenScreen.key] refuse everything else at injection time (owner rule 26 Nov).
     */
    fun permissionQuestion(displayId: Int): Boolean = windows(displayId).any { w ->
        w.type == AccessibilityWindowInfo.TYPE_APPLICATION && w.root.let { it == null || it.packageName?.toString().orEmpty().endsWith("permissioncontroller") }
    }

    internal fun root(displayId: Int): AccessibilityNodeInfo? =
        windows(displayId).firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root

    /** Debug spike: [pkg]'s window on the main screen as a tree, or null. */
    internal fun readMain(pkg: String): UiNode? = windows(0).firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.root?.packageName == pkg }?.root?.let { snapshot(it, 0) }

    /** What windows exist on a display, for error messages. */
    fun describe(displayId: Int) = "windows: " + windows(displayId).joinToString { "${it.title ?: "untitled"} (${it.root?.packageName ?: "closed to screen readers"})" }.ifEmpty { "none" }

    private fun windows(displayId: Int): List<AccessibilityWindowInfo> =
        MisuraAccessibility.instance?.windowsOnAllDisplays?.get(displayId).orEmpty()

    // refresh() is one IPC per node: on every node it cost 5.5 s for 1,800 Amazon nodes (1.7 s without). Only the root and fields are refreshed;
    // the rest comes from getChild(), which is fresh enough for words and boxes, but a typed text is not (setTextGruesseReadsBackExactly read the hint).
    internal fun snapshot(n: AccessibilityNodeInfo, depth: Int): UiNode {
        if (n.isEditable) n.refresh()
        val r = Rect().also { n.getBoundsInScreen(it) }
        // Password labels and descendants can contain the secret too; expose no actionable node.
        if (n.isPassword) return UiNode(null, null, null, intArrayOf(r.left, r.top, r.right, r.bottom), false, false, emptyList())
        val kids = if (depth > 40) emptyList() else (0 until n.childCount).mapNotNull { n.getChild(it) }.map { snapshot(it, depth + 1) }
        return UiNode(n.text?.toString(), n.contentDescription?.toString(), n.viewIdResourceName, intArrayOf(r.left, r.top, r.right, r.bottom), n.isClickable, n.isEditable, kids, n.className?.toString(), if (n.isCheckable) n.isChecked else null, n.hintText?.toString(), n.isShowingHintText, n.isFocused)
    }
}
