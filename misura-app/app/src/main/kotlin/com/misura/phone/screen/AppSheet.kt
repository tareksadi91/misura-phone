package com.misura.phone.screen

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.net.Uri
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.misura.phone.cards.M
import com.misura.phone.cards.ST

/** Keep query and stored-row reads out of HomeScreen's composition scope. */
@Composable
fun AppSheet(state: AppSheetState, apps: List<Pair<String, String>>, openApp: (String) -> Unit, modifier: Modifier = Modifier, onSettings: (() -> Unit)? = null) {
    val rows = remember(apps, state.pins, state.counts, state.typed) { sheetRows(apps, state.pins, state.counts, state.typed) }
    AppSheet(state, rows, openApp, modifier, onSettings)
}

/** Reverse list: first pin is closest to the shared field, most-used app closest to Pinned. */
@Composable
fun AppSheet(state: AppSheetState, rows: SheetRows, openApp: (String) -> Unit, modifier: Modifier = Modifier, onSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    var held by remember { mutableStateOf<String?>(null) }
    var shortcuts by remember { mutableStateOf<List<ShortcutInfo>>(emptyList()) }
    val entries = rows.entries
    val highlightedKey = entries.firstOrNull()?.key.takeIf { state.typed.isNotBlank() }
    val config = LocalViewConfiguration.current
    val holdConfig = remember(config) { object : ViewConfiguration by config { override val longPressTimeoutMillis = 450L } }
    val statusBars = WindowInsets.statusBars
    val windowTop = remember { object { var y = 0f } }
    BackHandler(state.engaged || state.searching) { state.close() }
    LaunchedEffect(state.typed, state.searching) { held = null; state.menuOpen = false }
    LaunchedEffect(state.typed) { state.list.scrollToItem(0) }
    val keyboardScroll = remember(state) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source == NestedScrollSource.UserInput && available.y != 0f) state.hideKeyboard()
            return Offset.Zero
        }
    } }
    // Place at idle too: LazyColumn otherwise defers item work until the first drag.
    val interaction = if (state.searching) Modifier.semantics {
        dismiss { state.close(); true }
        customActions = listOf(CustomAccessibilityAction("Close apps") { state.close(); true })
    } else Modifier.clearAndSetSemantics { hideFromAccessibility() }
    // Fixed window-space mask outside the moving layer: rows never cross into the clock.
    Box(modifier.fillMaxSize().then(interaction)
        .onGloballyPositioned { windowTop.y = it.positionInWindow().y }
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithCache {
            val fade = Brush.verticalGradient(listOf(Color.Transparent, Color.Black), 0f, 24.dp.toPx())
            onDrawWithContent {
                val top = (statusBars.getTop(this) - windowTop.y).coerceIn(0f, size.height)
                clipRect(top = top) {
                    this@onDrawWithContent.drawContent()
                    translate(top = top) {
                        drawRect(fade, size = androidx.compose.ui.geometry.Size(size.width, 24.dp.toPx()), blendMode = BlendMode.DstIn)
                    }
                }
            }
        }) {
        Box(Modifier.fillMaxSize().layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            layout(placeable.width, placeable.height) {
                // Offscreen at rest so the warmed rows cannot intercept home touches.
                placeable.place(if (!state.engaged && !state.searching) -2 * placeable.width else 0, 0)
            }
        }.graphicsLayer {
            val p = state.progress.value
            translationY = -(1f - p) * 200.dp.toPx()
        }) {
            CompositionLocalProvider(LocalViewConfiguration provides holdConfig) {
                LazyColumn(state = state.list, reverseLayout = true, userScrollEnabled = state.searching, modifier = Modifier.fillMaxSize()
                    .onGloballyPositioned { state.listBounds = it.boundsInWindow() }.nestedScroll(keyboardScroll)) {
                    fun androidx.compose.foundation.lazy.LazyListScope.appRows(entries: List<SheetEntry>) {
                        items(entries, key = { it.key }) { entry ->
                            Box {
                                AppSheetRow(entry, modifier = sheetLight(state), highlighted = entry.key == highlightedKey,
                                    held = state.menuOpen && held == entry.key,
                                    onOpen = { launchSheetEntry(context, state, entry, openApp) },
                                    onHold = {
                                        held = entry.key; shortcuts = appShortcuts(context, entry.pkg); state.menuOpen = true
                                    })
                                if (state.menuOpen && held == entry.key) {
                                    val pinned = state.pins.any { it.key == entry.key }
                                    AppMenu(entry, shortcuts, pinned, onDismiss = { state.menuOpen = false },
                                        onShortcut = { shortcut ->
                                            launchSheetEntry(context, state, entry.copy(shortcutId = shortcut.id,
                                                shortcutLabel = shortcut.shortLabel?.toString()), openApp)
                                        }, onPin = { state.togglePin(entry); state.menuOpen = false })
                                }
                            }
                        }
                    }
                    if (rows.filtered != null) {
                        if (entries.isEmpty()) item {
                            Text("No app called “${state.typed.trim()}”", style = ST.echo.copy(fontSize = 16.sp),
                                modifier = sheetLight(state).padding(horizontal = 10.dp, vertical = 12.dp))
                        } else appRows(entries)
                    }
                    else {
                        if (onSettings != null) item("misura-settings") {
                            AppSheetRow(SheetEntry(context.packageName, "Misura settings"),
                                highlighted = false, held = false, modifier = sheetLight(state),
                                onOpen = { state.close(); onSettings() })
                        }
                        appRows(rows.pinned)
                        if (rows.pinned.isNotEmpty()) item { SectionLabel("Pinned", state) }
                        appRows(rows.allApps)
                        item { SectionLabel("All apps", state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String, state: AppSheetState) {
    Text(label, style = ST.nav.copy(fontSize = 11.5.sp), color = M.text2,
        modifier = sheetLight(state).fillMaxWidth().padding(start = 10.dp, top = 14.dp, bottom = 2.dp).semantics { heading() })
}

/** Window geometry stays plain; only layers observe finger and spring frames. */
@Composable
private fun sheetLight(state: AppSheetState): Modifier {
    val position = remember { object { var top = 0f; var translation = 0f } }
    val travelPx = with(LocalDensity.current) { 200.dp.toPx() }
    val edgePx = with(LocalDensity.current) { 120.dp.toPx() }
    return Modifier.onGloballyPositioned {
        position.top = it.positionInWindow().y
        position.translation = -(1f - state.progress.value) * travelPx
    }.graphicsLayer {
        val p = state.progress.value
        // Layer translation need not dispatch onGloballyPositioned; account for it here.
        val centerY = position.top + size.height / 2f - (1f - p) * travelPx - position.translation
        // While dragging open, the light rises from the field (most-used apps first) and reaches the list top at p = .8,
        // moving up with the field and keyboard. Mirrored coordinates reuse the same edge function.
        val b = state.listBounds
        val line = if (state.thumbY.isFinite() && b != null) b.bottom - (p / .8f) * b.height else null
        alpha = if (line == null) sheetRowAlpha(p, state.thumbY, centerY, edgePx, state.lit.value)
            else sheetRowAlpha(p, -line, -centerY, edgePx, state.lit.value)
    }
}

@Composable
private fun AppSheetRow(entry: SheetEntry, highlighted: Boolean, held: Boolean, onOpen: () -> Unit, onHold: (() -> Unit)? = null,
    modifier: Modifier = Modifier) {
    val mark = rememberAppMark(entry.pkg, 20.dp)
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp)
        .background(if (highlighted || held) M.surface else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
        .combinedClickable(role = Role.Button, onClick = onOpen, onLongClickLabel = onHold?.let { "Shortcuts and pinning" }, onLongClick = onHold)
        .semantics(mergeDescendants = true) { contentDescription = entry.description }
        .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (entry.made) Box(Modifier.size(7.dp).background(M.orange, CircleShape))
            else if (mark != null) Image(mark, contentDescription = null, colorFilter = ColorFilter.tint(M.text2), modifier = Modifier.size(20.dp))
            else Box(Modifier.size(17.dp).border(1.4.dp, M.text2, RoundedCornerShape(6.dp)))
        }
        Text(androidx.compose.ui.text.buildAnnotatedString {
            append(entry.name)
            entry.shortcutLabel?.let { label ->
                pushStyle(androidx.compose.ui.text.SpanStyle(color = M.text2, fontSize = 16.sp))
                append(" · $label")
                pop()
            }
        }, style = ST.said.copy(fontSize = 19.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        if (highlighted) Text("↵", style = ST.step.copy(fontSize = 15.sp), color = M.text2)

    }
}

private fun appShortcuts(context: Context, pkg: String): List<ShortcutInfo> = runCatching {
    val launcher = context.getSystemService(LauncherApps::class.java) ?: return@runCatching emptyList()
    if (!launcher.hasShortcutHostPermission()) return@runCatching emptyList()
    val query = LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(
        LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
    launcher.getShortcuts(query, Process.myUserHandle()).orEmpty().filter { it.isEnabled }.distinctBy { it.id }.sortedBy { it.rank }
}.getOrDefault(emptyList())

fun launchSheetEntry(context: Context, state: AppSheetState, entry: SheetEntry, openApp: (String) -> Unit) {
    if (state.closing) return
    runCatching {
        if (entry.shortcutId == null) openApp(entry.pkg)
        else {
            // The same handoff as Home's app tap: a screen parked on this app comes back first, then the shortcut opens (it counts as a launch there).
            val toast = { Toast.makeText(context, "Couldn't open ${entry.name}", Toast.LENGTH_SHORT).show() }
            com.misura.phone.MisuraGraph.scope.launch {
                val opened = runCatching { com.misura.phone.MisuraGraph.openTarget(context, com.misura.core.shortcut.Target.AppShortcut(entry.pkg, entry.shortcutId)) }.getOrDefault(false)
                if (!opened) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { toast() }
            }
        }
    }.onFailure { Toast.makeText(context, "Couldn't open ${entry.name}", Toast.LENGTH_SHORT).show() }
    state.close()
}

@Composable
private fun AppMenu(entry: SheetEntry, shortcuts: List<ShortcutInfo>, pinned: Boolean, onDismiss: () -> Unit,
    onShortcut: (ShortcutInfo) -> Unit, onPin: () -> Unit) {
    val context = LocalContext.current
    MenuPopup(onDismiss) {
            shortcuts.forEach { shortcut -> MenuItem(shortcut.shortLabel?.toString() ?: shortcut.id) { onShortcut(shortcut) } }
            if (shortcuts.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(1.dp).background(M.outline.copy(alpha = .2f)))
            MenuItem(if (pinned) "Unpin" else "Pin", secondary = true, onClick = onPin)
            MenuItem("App info", secondary = true) {
                runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", entry.pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Toast.makeText(context, "Couldn't open app info", Toast.LENGTH_SHORT).show() }
                onDismiss()
            }
    }
}

/** The menu's popup: above its anchor, one rounded surface (the app menu's look); Home's line menu reuses it. */
@Composable
internal fun MenuPopup(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val margin = with(LocalDensity.current) { 16.dp.roundToPx() }
    val gap = with(LocalDensity.current) { 8.dp.roundToPx() }
    val position = remember(margin, gap) { object : PopupPositionProvider {
        override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset =
            IntOffset(margin, (anchorBounds.top - popupContentSize.height - gap)
                .coerceIn(margin, maxOf(margin, windowSize.height - popupContentSize.height - margin)))
    } }
    val scroll = androidx.compose.foundation.rememberScrollState()
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        Column(Modifier.width((LocalConfiguration.current.screenWidthDp.dp - 88.dp).coerceAtLeast(160.dp))
            .heightIn(max = 360.dp).shadow(18.dp, RoundedCornerShape(14.dp)).background(M.surface, RoundedCornerShape(14.dp))
            .verticalScroll(scroll).padding(vertical = 6.dp), content = content)
    }
}

@Composable
internal fun MenuItem(label: String, secondary: Boolean = false, onClick: () -> Unit) {
    Text(label, style = ST.step.copy(fontSize = 16.sp), color = if (secondary) M.text2 else M.text,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp))
}
