package com.misura.phone.setup

import android.Manifest
import com.misura.phone.access.AppAccess
import com.misura.phone.access.ProtectedApps
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.misura.phone.cards.*
import com.misura.phone.brain.*
import com.misura.phone.hidden.ShizukuBridge
import com.misura.phone.notify.MisuraListener
import com.misura.phone.notify.NotificationAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/** No secret prefill, saved-instance-state keys, agent calls or screenshots. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val credentials = CredentialSettings(getSharedPreferences("brain", Context.MODE_PRIVATE))
        setContent { SetupScreen(this, credentials) { finish() } }
    }
}

@Composable
fun SetupScreen(activity: ComponentActivity, settings: CredentialSettings, onDone: () -> Unit) {
    var credentialStatus by remember(settings) { mutableStateOf<CredentialStatus?>(null) }
    var phoneStatus by remember(activity) { mutableStateOf<PhoneStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val access = remember(activity) { NotificationAccess(activity) }
    remember(activity) { AppAccess.init(activity) }
    val native = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh++ }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    LaunchedEffect(settings) {
        credentialStatus = withContext(Dispatchers.IO) { settings.status() }
    }
    LaunchedEffect(activity, refresh) {
        activity.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                val generation = refresh
                val next = withContext(Dispatchers.IO) { readPhoneStatus(activity, access) }
                if (generation == refresh) phoneStatus = next
                delay(1000)
            }
        }
    }
    val saved = credentialStatus
    val phone = phoneStatus
    if (saved == null || phone == null) {
        MisuraSurface {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(M.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Misura settings", style = ST.said, modifier = Modifier.semantics { heading() })
                Text("Loading settings…", style = ST.nav)
            }
        }
        return
    }
    // Secrets deliberately use remember, never rememberSaveable or a Bundle.
    var sa by remember { mutableStateOf("") }
    var providerChoice by remember { mutableStateOf(saved.provider) }
    val provider = providerChoice
    var model by remember(provider) { mutableStateOf(provider?.let { saved.models[it] }.orEmpty()) }
    var apiKey by remember(provider) { mutableStateOf("") }
    var searchChoice by remember { mutableStateOf(saved.search) }
    val search = searchChoice
    var searchKey by remember(search) { mutableStateOf("") }
    var project by remember { mutableStateOf(saved.project) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun open(intent: Intent) {
        try { native.launch(intent) }
        catch (_: RuntimeException) { message = "Android could not open this setting." }
    }

    MisuraSurface {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState())
            .padding(M.gutter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Misura settings", style = ST.said, modifier = Modifier.semantics { heading() })
            Picker("Model provider", provider?.label ?: "Unsupported selection", ModelProvider.entries.map { it.label }, !saving) { label ->
                providerChoice = ModelProvider.entries.first { it.label == label }
            }
            Text("Your selected provider handles model requests, including memory checks and app control. Settings changes apply to the next turn.", style = ST.nav)
            if (provider != null) {
                Text("Model ID", style = ST.nav)
                Field(model, { model = it }, "Model ID", singleLine = true)
            }
            if (provider != null && provider != ModelProvider.VERTEX) SecretField(apiKey, { apiKey = it }, "${provider.label} API key",
                if (provider in saved.apiKeys) "Saved; leave blank to keep" else "Enter ${provider.label} API key", !saving)
            if (provider != null && provider != ModelProvider.VERTEX) Text("Add Google Cloud for direct Maps and Places lookups, which may be faster than opening an app.", style = ST.nav)
            Text("Service-account JSON", style = ST.nav)
            SecretField(sa, { sa = it }, "Service-account JSON", if (saved.serviceAccount) "Saved; leave blank to keep" else "Paste service-account JSON", !saving)
            Text("Vertex project", style = ST.nav)
            Field(project, { project = it }, "Vertex project", singleLine = true,
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii))
            Text("Leave blank to use the key’s project.", style = ST.nav)
            Picker("Web search", search?.label ?: "Unsupported selection", SearchProvider.entries.map { it.label }, !saving) { label ->
                searchChoice = SearchProvider.entries.first { it.label == label }
            }
            Text("An optional search service receives the question first. If it fails, your selected model provider can search. If direct search is unavailable, Misura may use your browser. Providers may charge for searches.", style = ST.nav)
            if (search != null && search != SearchProvider.NATIVE) SecretField(searchKey, { searchKey = it }, "${search.label} key",
                if (search in saved.searchKeys) "Saved; leave blank to keep" else "Enter ${search.label} key", !saving)
            if (provider == null || search == null) Text("Choose a supported model provider and search service to save.", style = ST.nav)
            MButton("Save keys", Kind.Commit, Modifier.fillMaxWidth(), enabled = provider != null && search != null, working = saving) {
                if (provider == null || search == null) return@MButton
                val input = CredentialInput(provider, model, apiKey, sa, project, "", search, searchKey)
                saving = true; message = null
                scope.launch {
                    try {
                        credentialStatus = withContext(Dispatchers.IO) {
                            settings.saveConfiguration(input)
                            settings.status()
                        }
                        sa = ""
                        apiKey = ""
                        searchKey = ""
                        message = "Keys saved on this phone. Access was not checked."
                    } catch (e: IllegalArgumentException) { message = e.message }
                    catch (e: IllegalStateException) { message = e.message }
                    catch (e: RuntimeException) {
                        if (e is java.util.concurrent.CancellationException) throw e
                        message = "Could not save credentials. Try again."
                    }
                    finally { saving = false }
                }
            }
            message?.let { Text(it, style = ST.nav, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            Text("Phone access", style = ST.said, modifier = Modifier.padding(top = 12.dp))
            MButton(if (phone.notificationOn) "Notification access · On" else "Allow notification access", Kind.Forward, Modifier.fillMaxWidth()) {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            MButton(if (phone.accessibilityOn) "App control · On" else "Enable app control", Kind.Forward, Modifier.fillMaxWidth()) {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            MButton(if (phone.shizukuOn) "Shizuku · On" else "Connect Shizuku", Kind.Forward, Modifier.fillMaxWidth()) {
                try {
                    if (!phone.shizukuOn && Shizuku.pingBinder()) Shizuku.requestPermission(ShizukuBridge.PERMISSION_REQUEST)
                    else activity.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")?.let { open(it) }
                        ?: run { message = "Install and start Shizuku with wireless debugging, then return here." }
                } catch (_: RuntimeException) { message = "Start Shizuku, then try again." }
            }
            Text("Shizuku needs its own pairing and start. Android may ask you to allow restricted settings for Misura.", style = ST.nav)
            MButton(if (phone.homeOn) "Home app · On" else "Use Misura as home", Kind.Forward, Modifier.fillMaxWidth()) {
                val roles = activity.getSystemService(RoleManager::class.java)
                open(if (roles.isRoleAvailable(RoleManager.ROLE_HOME)) roles.createRequestRoleIntent(RoleManager.ROLE_HOME) else Intent(Settings.ACTION_HOME_SETTINGS))
            }
            MButton(if (phone.voiceAlerts) "Voice and alerts · On" else "Allow voice and alerts", Kind.Forward, Modifier.fillMaxWidth()) {
                permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
            }
            MButton(if (phone.location) "Location · On" else "Location for places near you", Kind.Secondary, Modifier.fillMaxWidth()) {
                permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            }
            Text("Optional. Read only when you ask for places near you; approximate is enough. If Android stops asking, allow it in Misura's app settings.", style = ST.nav)
            MButton("Contacts and calendar", Kind.Secondary, Modifier.fillMaxWidth()) {
                permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALENDAR))
            }
            Text("Optional, read-only access.", style = ST.nav)
            Text("Notifications", style = ST.said, modifier = Modifier.padding(top = 12.dp))
            Text("All apps are on by default. Turn one off to stop Misura reading its notifications. Saved history stays.", style = ST.nav)
            if (phone.apps.isEmpty()) Text("Apps appear here when they post a notification.", style = ST.nav)
            phone.apps.forEach { (pkg, label) ->
                val allowed = pkg !in phone.blocked
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(allowed, role = Role.Switch) { on ->
                    try {
                        access.setBlocked(pkg, !on)
                        phoneStatus = phoneStatus?.let { current ->
                            current.copy(blocked = if (on) current.blocked - pkg else current.blocked + pkg)
                        }
                        refresh++
                    }
                    catch (_: RuntimeException) { message = "Could not save notification setting." }
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, style = ST.step, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(allowed, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = M.ink, checkedTrackColor = M.orange,
                        uncheckedThumbColor = M.text2, uncheckedTrackColor = M.surface))
                }
            }
            Text("Apps Misura can use", style = ST.said, modifier = Modifier.padding(top = 12.dp))
            Text("Misura never operates Shizuku, itself, or the authenticator and password-manager apps on its list. Banking and payment apps on its list are off until you turn one on. An app not on a list is allowed until you block it. Checked when Misura opens, resumes or replies through an app; a link that switches apps mid-task is not caught. You can still open every app yourself.", style = ST.nav)
            fun saveAccess(pkg: String, allowed: Boolean?) {
                try { AppAccess.set(pkg, allowed); refresh++ }
                catch (_: RuntimeException) { message = "Could not save app setting." }
            }
            phone.agentApps.forEach { row ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(row.allowed, role = Role.Switch) { on ->
                    // A known bank back on its default (off) is simply forgotten; any other app turned back on drops off the list.
                    saveAccess(row.pkg, if (on) (if (row.finance) true else null) else (if (row.finance) null else false))
                }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(row.label, style = ST.step, modifier = Modifier.weight(1f).padding(end = 12.dp))
                    Switch(row.allowed, onCheckedChange = null, colors = SwitchDefaults.colors(checkedThumbColor = M.ink, checkedTrackColor = M.orange,
                        uncheckedThumbColor = M.text2, uncheckedTrackColor = M.surface))
                }
            }
            // Two apps with the same name show their package; the pick is taken by position, so it always maps to the row's own package.
            val names = phone.blockable.map { (pkg, label) -> if (phone.blockable.count { it.second == label } > 1) "$label ($pkg)" else label }
            if (names.isNotEmpty()) Picker("Block an app", "choose", names, enabled = true, onPickIndex = { i -> phone.blockable.getOrNull(i)?.let { saveAccess(it.first, false) } }) { }
            Text("Routines", style = ST.said, modifier = Modifier.padding(top = 12.dp))
            Text("Requests Misura runs by itself at a set time. Ask Misura to add one. A run can search, look things up and draft messages for your approval; it never operates other apps.", style = ST.nav)
            if (phone.routines.isEmpty()) Text("No routines yet. Try: “every weekday at 8, tell me if my train is late”.", style = ST.nav)
            phone.routines.forEach { r ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(r.title, style = ST.step)
                        Text(com.misura.phone.routine.describe(r.trigger as com.misura.core.routine.Trigger.Time) + " · “" + r.prompt + "”", style = ST.nav, maxLines = 2)
                    }
                    MButton("Remove", Kind.Secondary) {
                        com.misura.phone.MisuraGraph.scope.launch {
                            val ok = runCatching { com.misura.phone.routine.RoutineStore(activity).remove(r.id); com.misura.phone.routine.Routines.reconcile() }.isSuccess
                            withContext(Dispatchers.Main) { if (ok) refresh++ else message = "Could not remove the routine." }
                        }
                    }
                }
            }
            MButton("Done", Kind.Secondary, Modifier.fillMaxWidth(), enabled = saved.configured && !saving, onClick = onDone)
        }
    }
}

private data class CredentialStatus(
    val provider: ModelProvider?, val search: SearchProvider?, val models: Map<ModelProvider, String>,
    val project: String, val configured: Boolean, val serviceAccount: Boolean,
    val apiKeys: Set<ModelProvider>, val searchKeys: Set<SearchProvider>,
)

private fun CredentialSettings.status() = CredentialStatus(
    providerSelection(), searchSelection(), ModelProvider.entries.associateWith { model(it) }, project(), configured(),
    hasServiceAccount(), ModelProvider.entries.filter { it != ModelProvider.VERTEX && hasApiKey(it) }.toSet(),
    SearchProvider.entries.filter { it != SearchProvider.NATIVE && hasSearchKey(it) }.toSet(),
)

private data class PhoneStatus(
    val notificationOn: Boolean, val accessibilityOn: Boolean, val homeOn: Boolean, val shizukuOn: Boolean,
    val voiceAlerts: Boolean, val location: Boolean, val apps: List<Pair<String, String>>, val blocked: Set<String>,
    /** Installed known banks plus apps the owner blocked; [blockable] = other launcher apps, (package, label). */
    val agentApps: List<AgentAppRow> = emptyList(), val blockable: List<Pair<String, String>> = emptyList(),
    val routines: List<com.misura.core.routine.Routine> = emptyList(),
)

private data class AgentAppRow(val pkg: String, val label: String, val allowed: Boolean, val finance: Boolean)

private fun readPhoneStatus(activity: ComponentActivity, access: NotificationAccess): PhoneStatus {
    val blocked = access.blockedPackages()
    val current = runCatching { MisuraListener.instance?.activeNotifications?.map { it.packageName }.orEmpty() }.getOrDefault(emptyList())
    val apps = (current + blocked).distinct().filter { it != activity.packageName }.map { pkg ->
        pkg to runCatching { activity.packageManager.getApplicationLabel(activity.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }.sortedBy { it.second.lowercase() }
    val pm = activity.packageManager
    fun labelOf(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    val launcher = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.activityInfo.packageName }.distinct().filter { !AppAccess.isFixed(it) }
    val choices = AppAccess.choices()
    val installedBanks = ProtectedApps.FINANCE.filter { runCatching { pm.getApplicationInfo(it, 0) }.isSuccess }
    val listed = (installedBanks + choices.filterValues { !it }.keys + choices.keys.filter { it in ProtectedApps.FINANCE })
        .distinct().filter { !AppAccess.isFixed(it) }
    val agentApps = listed.map { AgentAppRow(it, labelOf(it), AppAccess.allows(it), it in ProtectedApps.FINANCE) }.sortedBy { it.label.lowercase() }
    val blockable = (launcher - listed.toSet()).map { it to labelOf(it) }.sortedBy { it.second.lowercase() }
    fun granted(permission: String) = activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    return PhoneStatus(
        activity.packageName in NotificationManagerCompat.getEnabledListenerPackages(activity),
        activity.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == activity.packageName },
        activity.getSystemService(RoleManager::class.java).isRoleHeld(RoleManager.ROLE_HOME), ShizukuBridge.ready(),
        granted(Manifest.permission.RECORD_AUDIO) && granted(Manifest.permission.POST_NOTIFICATIONS),
        granted(Manifest.permission.ACCESS_COARSE_LOCATION) || granted(Manifest.permission.ACCESS_FINE_LOCATION), apps, blocked, agentApps, blockable,
        com.misura.phone.routine.RoutineStore(activity).all().map { it.routine }.sortedBy { it.title.lowercase() },
    )
}

@Composable
private fun Picker(label: String, selected: String, choices: List<String>, enabled: Boolean, onPickIndex: ((Int) -> Unit)? = null, onPick: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        MButton("$label · $selected", Kind.Secondary, Modifier.fillMaxWidth(), enabled = enabled) { expanded = true }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            choices.forEachIndexed { i, choice -> DropdownMenuItem(text = { Text(choice) }, onClick = {
                expanded = false
                onPickIndex?.invoke(i) ?: onPick(choice)
            }) }
        }
    }
}

@Composable
private fun SecretField(value: String, change: (String) -> Unit, label: String, hint: String, enabled: Boolean) {
    BasicTextField(value, change, enabled = enabled, singleLine = true, textStyle = T.msg, cursorBrush = SolidColor(M.orange),
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(M.sunk, RoundedCornerShape(LocalRadii.current.tile))
            .padding(horizontal = 10.u, vertical = 8.u).semantics { contentDescription = label },
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = T.msg, color = M.text2); inner() } })
}
