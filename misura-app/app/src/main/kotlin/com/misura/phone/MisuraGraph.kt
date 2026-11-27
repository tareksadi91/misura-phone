package com.misura.phone

import android.content.Context
import com.misura.phone.store.privateString
import com.misura.core.exec.Controller
import com.misura.core.exec.Hand
import com.misura.core.inbox.Router
import com.misura.phone.hands.HiddenChatHand
import com.misura.phone.hidden.HiddenInput
import com.misura.phone.hidden.ScreenReader
import com.misura.phone.hidden.ShizukuHiddenScreen
import com.misura.phone.hidden.TestChatScript
import kotlinx.coroutines.launch
import com.misura.phone.hands.NotificationReplyHand
import com.misura.phone.notify.MisuraListener
import com.misura.phone.notify.ProblemAlert
import com.misura.core.agent.Agent
import com.misura.phone.brain.*
import com.misura.phone.store.MisuraDb
import com.misura.phone.store.RoomInboxStore
import com.misura.phone.store.INBOX_RETENTION_MS
import com.misura.phone.store.RoomSessionStore
import com.misura.phone.store.RoomTaskStore
import com.misura.phone.work.WorkingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** One controller per process, on an app-lifetime scope. */
object MisuraGraph {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + com.misura.phone.store.storageFailureHandler)
    lateinit var db: MisuraDb; private set
    lateinit var tasks: RoomTaskStore; private set
    lateinit var inbox: RoomInboxStore; private set
    lateinit var sessions: RoomSessionStore; private set
    lateinit var controller: Controller; private set
    lateinit var router: Router; private set
    lateinit var agent: Agent; private set
    /** Kept Home shortcuts: one store, shared by the shortcut tool and Home. */
    lateinit var shortcuts: com.misura.phone.shortcut.ShortcutStore; private set
    /** The one owner of every shortcut mutation (store write + pins). */
    lateinit var shortcutKeeper: com.misura.phone.shortcut.ShortcutKeeper; private set
    private lateinit var credentials: com.misura.phone.setup.CredentialSettings
    /** Place photos sized for the 3a's cards. */
    val photoLoader: com.misura.phone.screen.PhotoLoader = { place, photo ->
        kotlinx.coroutines.withContext(Dispatchers.IO) { credentials.googleAccess().photos.bitmap(place.id, photo, 800) }
    }
    /** Installed apps with a launcher icon, as (name, package), by name. */
    // ponytail: asked of the package manager on every read (a few ms); cache on PACKAGE_ADDED/REMOVED if it ever shows in a profile.
    val apps: List<Pair<String, String>> get() = appCtx.packageManager.queryIntentActivities(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(appCtx.packageManager).toString() to it.activityInfo.packageName }.filter { it.second != appCtx.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    private lateinit var appCtx: Context
    val hands = mutableListOf<Hand>()
    /** Anything that opens [pkg] lets go of the general hand's parked screen on it (set once the hand exists). */
    @Volatile private var parkedApp: suspend (String) -> Unit = {}
    /** Open an app: a screen parked on it comes to the main screen instead of the app being launched again (see UseApp.bringParked). */
    @Volatile var bringParked: suspend (String) -> Boolean = { false }

    /** Starts an app's launch screen for [openTarget]; a seam so a test can make the launch fail (debug builds only; the setter is a no-op in release). */
    @Volatile internal var appOpener: (Context, String) -> Boolean = { c, p -> com.misura.phone.hands.openApp(c, p) }
        set(v) { if (BuildConfig.DEBUG) field = v } // release dispatch stays bound to the real opener

    /**
     * Opens a shortcut's direct target for the user (Home tap, app sheet): the parked-screen handoff first, then the destination. Never touches the
     * approval gate or the controller, so opening cannot consume or answer a pending approval. False = it did not open.
     */
    suspend fun openTarget(ctx: Context, target: com.misura.core.shortcut.Target): Boolean =
        com.misura.phone.shortcut.ShortcutLauncher(ctx, openApp = appOpener).open(target) { bringParked(it) }

    /** Resolves the handler first and pins it, so a protected app is never the one that opens; Android's chooser is left to the owner. */
    internal fun openDirections(app: Context, url: String) {
        val i = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val pm = app.packageManager
        val default = checkNotNull(pm.resolveActivity(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName) { "no handler" }
        // No default (Android's chooser): take the first allowed candidate instead of letting the chooser offer a protected one.
        val owner = if (default != "android") default
            else pm.queryIntentActivities(i, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.firstOrNull { com.misura.phone.access.AppAccess.allows(it) }
                ?: throw SecurityException("every app that opens map links is blocked for Misura")
        com.misura.phone.access.AppAccess.refusal(owner)?.let { throw SecurityException(it) }
        app.startActivity(i.setPackage(owner))
    }

    @Synchronized fun init(ctx: Context) {
        if (::controller.isInitialized) return
        val app = ctx.applicationContext
        appCtx = app
        com.misura.phone.access.AppAccess.init(app)
        com.misura.phone.hidden.HiddenSize.init(app)
        if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) com.misura.phone.hidden.hiddenTiming = { android.util.Log.i("MisuraTiming", it) }
        db = MisuraDb.open(app); tasks = RoomTaskStore(db); inbox = RoomInboxStore(db); sessions = RoomSessionStore(db, java.io.File(app.filesDir, "items"))
        hands += NotificationReplyHand({ MisuraListener.instance })
        hands += NotificationReplyHand({ MisuraListener.instance }, channel = "whatsapp", pkg = "com.whatsapp")
        hands += NotificationReplyHand({ MisuraListener.instance }, channel = "testchat", pkg = "com.misura.testapp")
        // Fallback when there is no notification to reply to: the hidden screen.
        val hidden = ShizukuHiddenScreen(app)
        hands += HiddenChatHand(hidden, ScreenReader, HiddenInput, "testchat", "com.misura.testapp", TestChatScript(hidden, ScreenReader), app.getSharedPreferences("hidden_baselines", Context.MODE_PRIVATE), onOpening = { parkedApp(it) })
        controller = Controller(tasks, hands, System::currentTimeMillis, scope).apply {
            onLiveChanged = { n -> if (n > 0) WorkingService.start(app) else WorkingService.idle(app) }
            // A failed send: its Problems alert replaces the session's "needs your OK".
            onProblem = { t -> ProblemAlert.show(app, t); com.misura.phone.notify.SessionAlerts.sendFailed(app, scope, t.id, sessions::sessionForTask) }
        }
        val brainPrefs = app.getSharedPreferences("brain", Context.MODE_PRIVATE)
        credentials = com.misura.phone.setup.CredentialSettings(brainPrefs)
        val routes = ModelRoutes(credentials::route)
        val places = RouteTool(FindPlaces(TokenProvider { null }, { "" })) { ctx -> FindPlaces(ctx.google.tokens, { ctx.google.project }, here = { com.misura.phone.brain.PhoneLocation.now(app) }) }
        val lookup = RouteTool(LookUp(TokenProvider { null }, { "" }), ::LookUp)
        val useApp = com.misura.phone.hands.UseApp(hidden, ConfiguredHandBrain, { apps }, log = { if (BuildConfig.DEBUG || BuildConfig.DIAGNOSTICS) android.util.Log.i("MisuraAgent", it) }, filesDir = app.filesDir, scope = scope,
            // One location read per near run (a fresh warm fix when Misura was opened, else one read): its city, the pins and the map's dot.
            here = { com.misura.phone.brain.PhoneLocation.now(app) }, whereNow = { at -> at?.let { com.misura.phone.brain.PhoneLocation.city(app, it) } },
            // Item 7: map pins for near-me finds through the turn's own Google route (as find_places); no Google access = no lookup.
            pins = com.misura.phone.brain.routedPins())
        parkedApp = useApp::beforeHiddenOpen; bringParked = useApp::bringParked
        ScreenReader.userOpened = useApp::appOpened
        val calendar = com.misura.phone.hands.Calendar(app)
        // A turn can hold a first call (60 s), a hand run (150 s budget + 30 s grace) and the final call (60 s) = 300 s; the 90 s default cut it off.
        // ponytail: a fixed sum; a real fix would give the hand the time that is left.
        val (alertSettled, allSent) = com.misura.phone.notify.SessionAlerts.hooks(app, sessions, scope) { ids ->
            ids.all { tasks.load(it)?.state == com.misura.core.task.TaskState.NEEDS_APPROVAL } }
        com.misura.phone.routine.Routines.init(app, start = { text, deny -> agent.start(text, deny) })
        val settled = { s: com.misura.core.agent.Session -> alertSettled(s); com.misura.phone.routine.Routines.settled(s) }
        val routineTool = com.misura.phone.routine.RoutineTool(com.misura.phone.routine.Routines.store, changed = { com.misura.phone.routine.Routines.resync() },
            runNow = { r -> com.misura.phone.routine.Routines.run(r, manual = true) }, launch = { work -> scope.launch { work() } })
        val appLabel = { pkg: String -> runCatching { app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
        // Steps as Home shows them (a send follows its task), so only a confirmed send counts as a run that worked.
        shortcuts = com.misura.phone.shortcut.ShortcutStore(app)
        shortcutKeeper = com.misura.phone.shortcut.ShortcutKeeper(shortcuts, pins = com.misura.phone.shortcut.ShortcutLauncher(app))
        val shortcutTool = com.misura.phone.shortcut.ShortcutTool(shortcuts, shortcutKeeper,
            com.misura.phone.shortcut.controllerSteps(sessions, tasks::load, controller::isChecking), appLabel)
        val gates = cardGates(calendar, routineTool, shortcutTool, useApp)
        agent = Agent(sessions, ConfiguredBrain, listOf(com.misura.phone.notify.ReadNotifications(app), places, lookup, com.misura.phone.brain.Remember(brainPrefs), useApp, com.misura.phone.hands.OpenApp(app, { apps }) { bringParked(it) }, calendar, com.misura.phone.hands.Contacts(app), com.misura.core.agent.Directions({ url -> openDirections(app, url) }), routineTool, shortcutTool), System::currentTimeMillis, scope, turnTimeoutMs = 300_000, callTimeoutMs = 60_000, // the call after a long app run carries its items and was timing out at 30 s on a slow evening
            turnContext = routes::capture,
            onSettled = settled, onAllSent = allSent, // result / needs-you alerts (SYSTEM §5 M3)
            unavailableTools = { if (ModelRouteContext.current().route.googleAvailable) emptySet() else setOf("find_places", "show_places", "directions") },
            // Route to the card's owner by its id prefix (CardGates): use_app's no also drops its parked screen.
            onChoose = gates.onChoose, onStopped = gates.onStopped, chooseNote = gates.chooseNote,
            // Who the user is: their own briefing when one was put on the phone, else the built-in lines.
            about = { com.misura.phone.brain.aboutWithNotes(brainPrefs.privateString(com.misura.phone.debug.ABOUT_KEY), com.misura.phone.brain.notes(brainPrefs)) },
            device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}",
            onProblem = { s, why -> ProblemAlert.showSession(app, s.id, why) }, onStep = { if (BuildConfig.DEBUG || (BuildConfig.DIAGNOSTICS && " failed: " !in it)) android.util.Log.i("MisuraAgent", it) }, // fast builds: counts and timings only (failure lines can carry error text)
            drafter = com.misura.phone.hands.ChatDrafter({ MisuraListener.instance?.chatsAll().orEmpty() },
                appLabel,
                tasks, controller, System::currentTimeMillis, { MisuraListener.instance?.chatPackages().orEmpty() }, installedApps = { apps }))
        // A list moves on wherever you are: the next message is drafted while you're on home, and its row says who it's for.
        controller.onSent = { t -> scope.launch { agent.taskSent(t.id) { id -> tasks.load(id)?.state.let { it == com.misura.core.task.TaskState.DONE || it == com.misura.core.task.TaskState.WAITING } } } }
        // ponytail: everyone counts as a person and "?" means a question, until the model classifies (Plan 2).
        router = Router(inbox, isPerson = { true }, asksSomething = { '?' in it.text }, maxAgeMs = INBOX_RETENTION_MS)
        MisuraListener.onCaptured = { _, c ->
            scope.launch { c.msgs.forEach { m -> runCatching { router.route(m, System.currentTimeMillis()) } } }
        }
    }
}
