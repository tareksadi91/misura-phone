package com.misura.phone.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.misura.core.shortcut.Shortcut
import com.misura.core.shortcut.Target
import com.misura.core.shortcut.isFull
import com.misura.core.shortcut.key
import com.misura.phone.cards.M
import com.misura.phone.cards.ST
import com.misura.phone.shortcut.KeepResult
import com.misura.phone.shortcut.ShortcutKeeper
import com.misura.phone.shortcut.ShortcutStore
import com.misura.phone.shortcut.ShortcutTargets
import com.misura.phone.store.StorageUnavailableException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * What the app list needs to put apps and their shortcuts on Home: the one keeper every mutation goes through, the store (current
 * kept list, `keptFor`) and the launcher's published shortcuts. Null in [AppSheet] = no Home items in the menu.
 */
class SheetHome(val keeper: ShortcutKeeper, val store: ShortcutStore, val targets: ShortcutTargets, private val clock: () -> Long = System::currentTimeMillis) {
    internal fun publishedFor(pkg: String): List<MenuShortcut> =
        runCatching { targets.published(pkg) }.getOrDefault(emptyList()).filter { it.enabled }.map { MenuShortcut(it.id, it.label) }
    internal fun now() = clock()
}

/** One of an app's own shortcuts as its menu lists it. */
internal data class MenuShortcut(val id: String, val label: String)

/**
 * The app menu's Home side: what is kept, an open replace picker, an inline failure and the polite "Added to Home".
 * The menu stays open in every outcome; every mutation is `keeper.keep` / `keeper.remove` inside `withContext(IO)`, then the
 * kept list is re-read so the rows show the truth, never the guess.
 */
internal class HomeMenuState(private val home: SheetHome, private val scope: CoroutineScope, private val pkg: String, private val appName: String,
    shortcuts: List<MenuShortcut>, kept: List<Shortcut>) {
    var shortcuts by mutableStateOf(shortcuts); private set
    var kept by mutableStateOf(kept); private set
    var picking by mutableStateOf<Pending?>(null); private set
    var message by mutableStateOf<String?>(null); private set
    var added by mutableStateOf(false); private set

    /** What the user asked to keep: the exact record and the check to repeat right before it is written. */
    class Pending(val shortcut: Shortcut, val validate: () -> Boolean)

    fun keptFor(t: Target): Shortcut? = kept.firstOrNull { it.target?.key() == t.key() }

    fun addShortcut(s: MenuShortcut) {
        val target = Target.AppShortcut(pkg, s.id)
        // The shortcut is still published, enabled and called what the menu showed.
        val validate = { home.targets.published(pkg).any { it.id == s.id && it.enabled && it.label == s.label } }
        attempt(Pending(draft("$appName · ${s.label}", target), validate), null)
    }

    fun addApp() {
        val target = Target.App(pkg)
        attempt(Pending(draft(appName, target)) { home.targets.resolve(target) }, null)
    }

    fun remove(t: Target) {
        scope.launch {
            message = null; added = false
            try { withContext(Dispatchers.IO) { home.store.keptFor(t)?.let { home.keeper.remove(it.id) } } }
            catch (_: StorageUnavailableException) { message = STORAGE; return@launch }
            refresh()
        }
    }

    fun pick(replace: Shortcut) { picking?.let { attempt(it, replace.id) } }
    fun cancel() { picking = null }

    private fun draft(title: String, target: Target) = Shortcut(UUID.randomUUID().toString(), title.take(Shortcut.MAX_TITLE), null, null, target, createdAt = home.now())

    private fun attempt(p: Pending, replacing: String?) {
        scope.launch {
            message = null; added = false
            val result = try { withContext(Dispatchers.IO) { home.keeper.keep(p.shortcut, replacing, p.validate) } }
                catch (_: StorageUnavailableException) { message = STORAGE; return@launch }
            refresh()
            when (result) {
                KeepResult.Kept -> { picking = null; added = true }
                KeepResult.Duplicate -> picking = null
                KeepResult.TargetChanged -> { picking = null; shortcuts = withContext(Dispatchers.IO) { home.publishedFor(pkg) } }
                // Home filled meanwhile (Full) or the line to replace is gone (Stale): the picker is rebuilt from what is kept now.
                // Nothing is written on its own: if Home has room now, the fresh menu simply offers "+ Home" again and the user taps.
                KeepResult.Full, KeepResult.StaleReplacement -> picking = if (isFull(kept)) p else null
            }
        }
    }

    private suspend fun refresh() {
        kept = try { withContext(Dispatchers.IO) { home.store.all() } } catch (_: StorageUnavailableException) { kept }
    }

    companion object { const val STORAGE = com.misura.phone.shortcut.ShortcutTool.STORAGE }
}

/** Shortcut row: the name opens (flex), a hairline, then "+ Home" / "On Home". Two separate nodes, each at least 48 dp. */
@Composable
internal fun SplitMenuRow(label: String, onHome: Boolean, onOpen: () -> Unit, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).widthIn(min = 48.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = "Open $label" }.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.CenterStart) {
            Text(label, style = ST.step.copy(fontSize = 16.sp), color = M.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.size(1.dp, 24.dp).background(M.outline.copy(alpha = .2f)))
        Box(Modifier.widthIn(min = 48.dp).heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (onHome) "Remove $label from Home" else "Add $label to Home" }.padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
            Text(if (onHome) "On Home" else "+ Home", style = ST.step.copy(fontSize = 14.sp), color = if (onHome) M.text else M.text2, maxLines = 1)
        }
    }
}

/** Home is full: the three kept ones (mark + title) to replace, or Cancel. No guess about which. */
@Composable
internal fun ColumnScope.HomePicker(menu: HomeMenuState) {
    Text("Home is full. Replace one:", style = ST.step.copy(fontSize = 14.sp), color = M.text2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
    menu.kept.forEach { s ->
        val mark = s.app?.let { rememberAppMark(it, 16.dp) }
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { menu.pick(s) }.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                if (mark != null) Image(mark, contentDescription = null, colorFilter = ColorFilter.tint(M.text2), modifier = Modifier.size(16.dp))
                else Box(Modifier.size(13.dp).border(1.2.dp, M.text2, RoundedCornerShape(5.dp)))
            }
            Text(s.title, style = ST.step.copy(fontSize = 16.sp), color = M.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    MenuItem("Cancel", secondary = true) { menu.cancel() }
}

/** The inline failure and the polite "Added to Home" (no toast; the row's flip is the visible confirmation). Lives in the popup's footer, outside the scrolling rows, so a tall menu never clips it out of the accessibility tree. */
@Composable
internal fun ColumnScope.HomeMenuStatus(menu: HomeMenuState) {
    menu.message?.let { Text(it, style = ST.step.copy(fontSize = 14.sp), color = M.text2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).semantics { liveRegion = LiveRegionMode.Polite }) }
    if (menu.added) Box(Modifier.size(1.dp).semantics { liveRegion = LiveRegionMode.Polite; contentDescription = "Added to Home" })
}
