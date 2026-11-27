package com.misura.phone.shortcut

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.net.Uri
import android.os.Process
import android.provider.Settings
import com.misura.core.shortcut.LinkTemplate
import com.misura.core.shortcut.SettingsPage
import com.misura.core.shortcut.Target
import com.misura.core.shortcut.pkg
import com.misura.core.shortcut.uri

/** The Settings action each page opens. Every action here also has a scoped `<queries>` entry in the manifest (OSS_AUDIT). */
fun settingsAction(page: SettingsPage): String = when (page) {
    SettingsPage.WIFI -> Settings.ACTION_WIFI_SETTINGS
    SettingsPage.BLUETOOTH -> Settings.ACTION_BLUETOOTH_SETTINGS
    SettingsPage.BATTERY_SAVER -> Settings.ACTION_BATTERY_SAVER_SETTINGS
    SettingsPage.DISPLAY -> Settings.ACTION_DISPLAY_SETTINGS
    SettingsPage.SOUND -> Settings.ACTION_SOUND_SETTINGS
    SettingsPage.LOCATION -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
    SettingsPage.NOTIFICATIONS -> Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS
    SettingsPage.APP_DETAILS -> Settings.ACTION_APPLICATION_DETAILS_SETTINGS
}

/**
 * Opens a shortcut's direct [Target] and reads the launcher's shortcuts. Every intent is built here from stored, code-validated fields:
 * no `parseUri`, no model-given URIs, no extras, no URI grants. Discovery and pinning need Android's shortcut host permission (Misura as
 * the Home app); starting an already pinned shortcut is attempted regardless (Android exempts pinned ones).
 * [actions] is the page-to-action table (a seam for tests; production is [settingsAction]).
 */
class ShortcutLauncher(
    private val ctx: Context,
    private val actions: (SettingsPage) -> String = ::settingsAction,
    private val openApp: (Context, String) -> Boolean = { c, p -> com.misura.phone.hands.openApp(c, p) },
) : ShortcutPins {
    /** A shortcut an app publishes, as the user would be shown it: [label] is single-line and capped. */
    data class Published(val id: String, val label: String, val enabled: Boolean)

    private val launcher: LauncherApps? get() = ctx.getSystemService(LauncherApps::class.java)
    private val me get() = Process.myUserHandle()

    private fun host(): LauncherApps? = launcher?.takeIf { runCatching { it.hasShortcutHostPermission() }.getOrDefault(false) }

    private fun query(l: LauncherApps, pkg: String, flags: Int, ids: List<String>? = null): List<android.content.pm.ShortcutInfo>? =
        runCatching { l.getShortcuts(LauncherApps.ShortcutQuery().setPackage(pkg).setQueryFlags(flags).also { q -> ids?.let { q.setShortcutIds(it) } }, me) }.getOrNull()

    /** [pkg]'s dynamic, manifest and pinned shortcuts (disabled ones marked), by rank; empty without the host permission or when the query fails. */
    fun published(pkg: String): List<Published> {
        val l = host() ?: return emptyList()
        return query(l, pkg, ALL).orEmpty().distinctBy { it.id }.sortedBy { it.rank }
            .map { Published(it.id, clean(it.shortLabel?.toString() ?: it.id), it.isEnabled) }
    }

    override fun rawPinned(pkg: String): Set<String>? {
        val l = host() ?: return null
        return query(l, pkg, LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)?.map { it.id }?.toSet()
    }

    override fun pin(pkg: String, ids: Set<String>): Boolean {
        val l = host() ?: return false
        return runCatching { l.pinShortcuts(pkg, ids.toList(), me); true }.getOrDefault(false)
    }

    private fun settingsIntent(t: Target.Settings): Intent = Intent(actions(t.page)).also {
        if (t.page == SettingsPage.APP_DETAILS) it.data = Uri.fromParts("package", t.subjectPackage, null)
    }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Package-scoped VIEW of the template's URI; null when the param fails the template. Never unscoped. */
    internal fun linkIntent(t: Target.Link): Intent? {
        val uri = t.template.uri(t.param) ?: return null
        val pkg = t.pkg ?: return null
        return Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun handlerPackage(i: Intent): String? = ctx.packageManager.resolveActivity(i, 0)?.activityInfo?.packageName

    /** True when [target] can open right now: the app has a launch screen, the shortcut still exists and is enabled, the Settings page or link has a handler. */
    fun resolve(target: Target): Boolean = when (target) {
        is Target.App -> ctx.packageManager.getLaunchIntentForPackage(target.pkg) != null
        is Target.AppShortcut -> host()?.let { l -> query(l, target.pkg, ALL, listOf(target.id))?.any { it.id == target.id && it.isEnabled } } == true
        is Target.Settings -> handlerPackage(settingsIntent(target)) != null
        is Target.Link -> linkIntent(target)?.let { handlerPackage(it) != null } == true
    }

    /**
     * Opens [target] for the user. [bringParked] runs first for the target's package (Settings: its handler's): a screen the agent parked on
     * it comes to the main screen instead of being launched again. For an app that is all there is to do; for a shortcut, Settings page or link
     * the destination is launched after the handoff (the parked app lands where the hand left off, then the deep screen opens on the real display).
     * Opening never touches a pending approval: no gate and no controller are involved. False = it did not open (never retried elsewhere).
     */
    suspend fun open(target: Target, bringParked: suspend (String) -> Boolean): Boolean = try {
        openUnguarded(target, bringParked)
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false } // an uninstall/disable race anywhere in the launch: refused, never thrown at the caller

    private suspend fun openUnguarded(target: Target, bringParked: suspend (String) -> Boolean): Boolean = when (target) {
        is Target.App ->
            if (bringParked(target.pkg)) { com.misura.phone.store.Pins(ctx).launched(target.pkg); true }
            else openApp(ctx, target.pkg)
        is Target.AppShortcut -> {
            // Refused before the handoff only when an authorized, successful query proves it missing or disabled; no permission or a failed query still attempts the launch (pinned ones work without it).
            val known = host()?.let { l -> query(l, target.pkg, ALL, listOf(target.id)) }
            if (known != null && known.none { it.id == target.id && it.isEnabled }) false
            else {
                bringParked(target.pkg)
                val ok = try {
                    checkNotNull(launcher).startShortcut(target.pkg, target.id, null, null, me); true
                } catch (_: Exception) { false } // gone, disabled, uninstalled, no longer allowed
                if (ok) com.misura.phone.store.Pins(ctx).launched(target.pkg)
                ok
            }
        }
        is Target.Settings -> start(settingsIntent(target), bringParked)
        is Target.Link -> linkIntent(target)?.let { start(it, bringParked) } ?: false
    }

    private suspend fun start(i: Intent, bringParked: suspend (String) -> Boolean): Boolean {
        val handler = handlerPackage(i) ?: return false
        bringParked(handler)
        ctx.startActivity(i); return true
    }

    private companion object {
        const val ALL = LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
        const val MAX_LABEL = 40
        /** One line, control characters gone, spaces collapsed, capped: an app's label is text to show, never to trust. */
        fun clean(s: String) = s.replace(Regex("[\\p{Cntrl}\\p{Cf}]"), " ").replace(Regex("\\s+"), " ").trim().take(MAX_LABEL)
    }
}
