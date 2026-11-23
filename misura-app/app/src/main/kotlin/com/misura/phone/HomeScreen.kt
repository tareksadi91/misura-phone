package com.misura.phone

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import com.misura.phone.screen.Atmosphere
import com.misura.phone.screen.AppSheet
import com.misura.phone.screen.AppSheetState
import com.misura.phone.screen.appSheetGesture
import com.misura.phone.screen.launchSheetEntry
import com.misura.phone.screen.rememberAppSheetState
import com.misura.phone.screen.sheetRows
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.runtime.key
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import com.misura.core.agent.Step
import com.misura.core.agent.shownSteps
import com.misura.core.exec.Controller
import com.misura.core.exec.TaskStore
import com.misura.core.task.Task
import com.misura.core.task.TaskState
import com.misura.phone.cards.Card
import com.misura.phone.cards.cardFor
import com.misura.phone.screen.ApproveCard
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.misura.core.agent.Agent
import com.misura.core.agent.Nav
import com.misura.core.agent.ItemFact
import com.misura.core.agent.Looking
import com.misura.core.agent.PlaceFact
import com.misura.core.agent.itemFacts
import com.misura.core.agent.looking
import com.misura.core.agent.Say
import com.misura.core.agent.Screen
import com.misura.core.agent.Session
import com.misura.core.agent.SessionStatus
import com.misura.core.agent.SessionStore
import com.misura.core.agent.Turn
import com.misura.core.agent.backLabel
import com.misura.core.agent.homeLine
import com.misura.core.agent.placeFacts
import com.misura.core.agent.stale
import com.misura.phone.cards.Field
import com.misura.phone.cards.Kind
import com.misura.phone.cards.M
import com.misura.phone.cards.MButton
import com.misura.phone.cards.MisuraSurface
import com.misura.phone.cards.ST
import com.misura.phone.cards.u
import com.misura.phone.screen.AnswerText
import com.misura.phone.screen.DecisionScreen
import com.misura.phone.screen.ItemScreen
import com.misura.phone.screen.Docked
import com.misura.phone.screen.Echo
import com.misura.phone.screen.NavLine
import com.misura.phone.screen.Past
import com.misura.phone.screen.PhotoLoader
import com.misura.phone.screen.PlaceDetail
import com.misura.phone.screen.ProblemCard
import com.misura.phone.screen.QuestionCard
import com.misura.phone.screen.Steps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.text.DateFormat
import java.util.Date

const val ASK_FIELD = "What should we do?"
/** After a spoken "go", how long Undo stays before the messages leave. */
const val UNDO_MS = 3_000L
/** Places content older than this renders as name + why only (Places terms). */
const val PLACES_MAX_AGE_MS = 30L * 24 * 3_600_000

/** A request to open one task (from a notification); [nonce] makes the same id open again. */
data class OpenRequest(val sessionId: String, val nonce: Long)

/** One open task as the screen needs it, rebuilt from the store on every poll. */
/** [turn] = how many things you said in this task; the screen shows only that turn's steps. */
/** [steps] = the session's steps as shown (sends follow their tasks); [tasks] = the send tasks those steps and the card point at. */
private data class Open(
    val s: Session, val asked: String, val echo: String, val facts: Map<String, PlaceFact>, val stale: Boolean, val turn: Int, val items: Map<String, ItemFact> = emptyMap(),
    val steps: List<Step> = s.steps, val tasks: Map<String, Task> = emptyMap(), val checking: Set<String> = emptySet(),
    /** Everything said in this task so far, oldest first; the last one is the exchange in front. */
    val thread: List<com.misura.core.agent.Exchange> = emptyList(),
)

/** What the screen needs to show and send a drafted message. Sends run on [scope] (app lifetime): a send outlives the screen. */
class Sends(val tasks: TaskStore, val controller: Controller, val scope: CoroutineScope)
/** [line] = the task as the Earlier list shows it; [name] + [kind] = how home shows it while it is open; [fresh] = from the last day. */
private data class Row(val id: String, val line: String, val kind: com.misura.core.agent.HomeKind = com.misura.core.agent.HomeKind.DONE, val name: String = line, val fresh: Boolean = true)
private val Row.visibleOnHome get() = fresh && kind != com.misura.core.agent.HomeKind.DONE
/** How many tasks the Earlier list shows; open ones further back are added to it. */
private const val EARLIER_ROWS = 30

private val NavSaver = Saver<Nav, String>(
    save = { n -> (listOf("${n.index}") + n.stack.map { when (it) { Screen.Root -> "r"; Screen.FullMap -> "m"; Screen.History -> "h"; is Screen.Detail -> "d:${it.placeId}" } }).joinToString("|") },
    restore = { s -> s.split("|").let { p -> Nav(p.drop(1).map { when { it == "m" -> Screen.FullMap; it == "h" -> Screen.History; it.startsWith("d:") -> Screen.Detail(it.drop(2)); else -> Screen.Root } }, p[0].toInt()) } },
)

/**
 * Home and the open task (design-system §6). Screen talks (echo, steps, answers), cards ask (places, questions, problems).
 * The text field stands in for the side key until the day-one key check (§7).
 */
@Composable
fun HomeScreen(agent: Agent, sessions: SessionStore, photos: PhotoLoader?, openRequest: OpenRequest?, sends: Sends? = null, homeTap: Long = 0, voice: com.misura.phone.voice.Voice? = null,
    apps: List<Pair<String, String>> = emptyList(), onOpenItem: ((ItemFact) -> Unit)? = null, appsProvider: () -> List<Pair<String, String>> = { apps }, onOpenSettings: (() -> Unit)? = null, openApp: (String) -> Unit = {}) {
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var nav by rememberSaveable(stateSaver = NavSaver) { mutableStateOf(Nav()) }
    var open by remember { mutableStateOf<Open?>(null) }
    var rows by remember { mutableStateOf<List<Row>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // The card you were looking at when you started typing, and its task; both survive recreation.
    var targetId by rememberSaveable { mutableStateOf<String?>(null) }
    var targetSession by rememberSaveable { mutableStateOf<String?>(null) }
    var cleared by rememberSaveable { mutableStateOf(false) }
    var detailRevision by rememberSaveable { mutableStateOf(-1) }
    var pressed by remember { mutableStateOf(false) }
    // Tapped Send, waiting for the controller to move the task: blocks a second tap before the state shows it.
    var tapped by remember { mutableStateOf<String?>(null) }
    // Said "go" on the card in front: it is armed for a few seconds (Undo) before the sends start. Null = nothing armed.
    var armed by remember { mutableStateOf<Any?>(null) }
    // What a spoken "go" does right now: set while a card of routine messages is in front and waits; null otherwise.
    // [key] = exactly which card that is (its tasks and the digest of each message). A go counts only for the card that was
    // in front when the talk button went down AND is still in front, unchanged, when the words arrive (Codex P1).
    val go = remember { object { var now: (() -> Unit)? = null; var key: Any? = null; var heldOn: Any? = null } }
    var listening by remember { mutableStateOf(false) }
    // The list of all tasks, finished ones included: one tap away from home, never on it.
    var earlier by rememberSaveable { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val parentScope = rememberCoroutineScope()
    val scope = remember(parentScope) { com.misura.phone.store.storageChildScope(parentScope) }
    val nativeKeyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val sheet = rememberAppSheetState()
    val plainHome = openId == null && !earlier
    val view = LocalView.current
    val requestIme = remember(view, nativeKeyboard, fieldFocus, focus) {
        com.misura.phone.screen.RequestKeyboard(view, nativeKeyboard,
            { openId == null && !earlier && !sheet.engaged && !listening },
            { fieldFocus.requestFocus() }, { focus.clearFocus() })
    }
    val animateRequest = plainHome && !sheet.engaged && !listening
    val keyboard = if (animateRequest) requestIme else nativeKeyboard
    androidx.compose.runtime.DisposableEffect(requestIme) { onDispose { requestIme.focusLost(); requestIme.cancel() } }
    LaunchedEffect(animateRequest, focused, sheet.engaged) {
        if (!animateRequest) requestIme.cancel(transfer = sheet.engaged)
        else if (focused) requestIme.show() else requestIme.focusLost()
    }
    val ime = WindowInsets.ime
    val density = LocalDensity.current
    val imeOnScreen by remember(ime, density) { derivedStateOf { ime.getBottom(density) > 0 } }
    val requestKeyboard = plainHome && (focused || imeOnScreen) && !sheet.engaged && !listening
    LaunchedEffect(focused, sheet.engaged) {
        if (focused && !sheet.engaged) {
            // Wait for opening before treating zero insets as dismissal; the sheet keeps its own focus owner.
            snapshotFlow { ime.getBottom(density) > 0 }.first { it }
            snapshotFlow { ime.getBottom(density) == 0 && !listening }.first { it }
            focus.clearFocus()
        }
    }
    var currentApps by remember { mutableStateOf(apps) }
    val currentAppsProvider by rememberUpdatedState(appsProvider)
    val markPx = with(LocalDensity.current) { 20.dp.roundToPx() }
    androidx.lifecycle.compose.LifecycleResumeEffect(sheet, apps, markPx) {
        val load = scope.launch {
            val loaded = withContext(Dispatchers.IO) { currentAppsProvider() }
            sheet.refreshStored()
            currentApps = loaded
            val firstApps = sheetRows(loaded, sheet.pins, sheet.counts, "").entries
                .distinctBy { it.pkg }.take(40)
            withContext(Dispatchers.IO) {
                firstApps.forEach { com.misura.phone.screen.appMark(context, it.pkg, markPx) }
            }
        }
        onPauseOrDispose { load.cancel() }
    }
    fun openFirstApp() {
        if (!listening && sheet.typed.isNotBlank()) sheetRows(currentApps, sheet.pins, sheet.counts, sheet.typed).entries
            .firstOrNull()?.let { launchSheetEntry(context, sheet, it, openApp) }
    }
    LaunchedEffect(plainHome, homeTap, openRequest) { if (sheet.engaged || sheet.searching) sheet.close() }

    LaunchedEffect(openRequest) { openRequest?.let { openId = it.sessionId; nav = Nav() } }
    LaunchedEffect(openId) {
        var lastPlaces: List<String>? = null
        val continued = mutableSetOf<String>()
        val continuedFromHome = mutableSetOf<String>()
        while (true) {
            // Defer the home poll too: its clock/row writes would recompose HomeScreen mid-drag or settle.
            if (sheet.moving) { delay(250); continue }
            now = System.currentTimeMillis()
            val id = openId
            if (id == null) {
                open = null
                // Open tasks are looked for far past what Earlier lists, so a draft never drops out of sight behind 30 newer tasks (Codex P1).
                // ponytail: 200 tasks back; past that an open task is only reachable from its notification.
                rows = sessions.recent(200).mapIndexedNotNull { i, s ->
                    val draft = (s.say as? Say.Approve)?.let { a -> com.misura.core.agent.cardState(a.all.map { sends?.tasks?.load(it)?.state }) }
                    val kind = com.misura.core.agent.homeKind(s, draft)
                    if (i >= EARLIER_ROWS && kind == com.misura.core.agent.HomeKind.DONE && s.dismissedAt == null) return@mapIndexedNotNull null
                    // A list whose message went out but whose next step never started (the process died in between): start it from here too.
                    (s.say as? Say.Approve)?.let { a -> if (a.more && s.dismissedAt == null && s.status != SessionStatus.THINKING && draft in SENT && continuedFromHome.add(a.taskId)) agent.sent(s.id, a.taskId) }
                    val sent = (s.steps.mapNotNull { it.taskId } + listOfNotNull((s.say as? Say.Approve)?.taskId)).toSet().filter { sends?.tasks?.load(it)?.state in SENT }.toSet()
                    val asked = firstText(sessions.turns(s.id)) ?: "Earlier task"
                    Row(s.id, homeLine(s, asked, stale = now - s.createdAt > PLACES_MAX_AGE_MS, sent = sent), kind, com.misura.core.agent.homeName(s, asked), com.misura.core.agent.onHome(s, now, kind))
                }
            } else sessions.load(id)?.let { s ->
                val turns = sessions.turns(id)
                val stale = now - s.createdAt > PLACES_MAX_AGE_MS
                val facts = placeFacts(turns).let { f -> if (stale) f.mapValues { it.value.stale() } else f }
                // New places came in: back to the carousel's first card. Frozen while a finger is on a card (§9.2).
                if (!pressed) {
                    val shown = s.places?.placeIds ?: s.items?.itemIds
                    if (shown != lastPlaces && lastPlaces != null) nav = nav.reset()
                    lastPlaces = shown
                    val ids = (s.steps.mapNotNull { it.taskId } + (s.say as? Say.Approve)?.all.orEmpty()).toSet()
                    val tasks = sends?.let { x -> ids.mapNotNull { x.tasks.load(it) }.associateBy { it.id } }.orEmpty()
                    val checking = ids.filter { sends?.controller?.isChecking(it) == true }.toSet()
                    // More on Misura's list and the message on the card went out (also when a re-check confirmed it later): it hears so, once, and carries on.
                    // The controller tells the agent itself when a send is confirmed; this covers a confirm the process died after.
                    (s.say as? Say.Approve)?.let { a -> if (a.more && s.status != SessionStatus.THINKING && a.all.all { tasks[it]?.state in SENT } && continued.add(a.taskId)) agent.sent(id, a.taskId) }
                    open = Open(s, firstText(turns) ?: "Earlier task", lastText(turns) ?: "", facts, stale, com.misura.core.agent.turnNumber(turns), itemFacts(turns),
                        shownSteps(s.steps, s.say, { tasks[it] }, { it in checking }), tasks, checking,
                        // Old answers can carry old hours (Places terms): past 30 days the thread keeps your words only.
                        com.misura.core.agent.thread(turns).let { th -> if (stale) th.map { it.copy(answer = null) } else th })
                }
            }
            delay(250)
        }
    }

    fun goHome() { openId = null; nav = Nav(); targetId = null; targetSession = null; cleared = false; earlier = false }
    LaunchedEffect(homeTap) { if (homeTap != 0L) goHome() }
    // A task whose message went out is finished: nothing on it needs you (design-system §8 "done, then folds into home").
    val finished = open?.takeIf { it.s.id == openId }?.let { o -> (o.s.say as? Say.Approve)?.let { a -> !a.more && a.all.all { o.tasks[it]?.state in SENT } } } == true
    // It folds when you leave (screen off, another app), never on a timer that could pull it away while you read.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { if (finished) goHome() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (focused && openId == null && !earlier && !sheet.engaged && !listening) {
            requestIme.focusLost(); requestIme.cancel(); focus.clearFocus()
        }
    }
    BackHandler(enabled = openId != null || earlier) { if (openId == null) earlier = false else nav = nav.back() ?: run { goHome(); nav } }
    BackHandler(enabled = animateRequest && focused) { requestIme.hide() }

    MisuraSurface {
        Box(Modifier.fillMaxSize().pointerInput(requestKeyboard) {
            if (requestKeyboard) detectTapGestures { requestIme.hide() }
        }) {
        HomeDecoration(sheet, listening && plainHome, { voice?.level ?: 0f }, atmosphereOf(
            plainHome, earlier, requestKeyboard, listening, rows.any { it.visibleOnHome }, open?.takeIf { it.s.id == openId }, nav, openId != null))
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()
            .onGloballyPositioned { sheet.gestureOffset = it.positionInWindow() }
            .appSheetGesture(sheet, plainHome).padding(M.m), verticalArrangement = Arrangement.spacedBy(M.p)) {
            // Set again further down only while a waiting card of messages is on screen: on home, on another screen or while
            // Misura works there is nothing a go could mean.
            go.now = null; go.key = null
            val o = open?.takeIf { it.s.id == openId }
            val places = o?.s?.places?.placeIds?.mapNotNull { o.facts[it] }.orEmpty()
            val items = o?.s?.items?.itemIds?.mapNotNull { o.items[it] }.orEmpty()
            val front: PlaceFact? = when (val t = nav.top) {
                is Screen.Detail -> o?.facts?.get(t.placeId)
                else -> places.getOrNull(nav.index)
            }
            val thinking = o?.s?.status == SessionStatus.THINKING
            // A target only counts inside its own task (a notification can switch tasks while you type).
            val target: Looking? = targetId?.takeIf { targetSession == openId }?.let { o?.facts?.get(it)?.looking() ?: o?.items?.get(it)?.looking() }
            val lookingFront = front?.looking() ?: items.getOrNull(nav.index)?.looking()
            val say = o?.s?.say?.let { if (o.stale && it is Say.Answer && it.citations.isEmpty()) Say.Answer("Older answer. Ask again for current details.") else it }

            if (openId == null && earlier) Column(Modifier.weight(1f)) {
                NavLine("Home", "Earlier", 0, onBack = { earlier = false }, onSteps = {}, Modifier.padding(horizontal = M.gutter - M.m))
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = M.gutter - M.m)) {
                    // Open ones first, each with its sign, then everything else newest first.
                    rows.sortedBy { it.kind == com.misura.core.agent.HomeKind.DONE }.forEach { r -> key(r.id) { TaskLine(r.line, sign = when (r.kind) {
                        com.misura.core.agent.HomeKind.YOURS -> ({ com.misura.phone.screen.YourGo() }); com.misura.core.agent.HomeKind.RUNNING -> ({ com.misura.phone.screen.WorkingDot() })
                        com.misura.core.agent.HomeKind.FAILED -> ({ Text("✕", style = ST.step, color = M.orange) }); else -> null }, maxLines = 2, action = "Delete", onRemove = { agent.delete(r.id).await(); rows = rows.filter { it.id != r.id } }) { openId = r.id; nav = Nav() } } }
                }
            }
            else if (openId == null) Box(Modifier.weight(1f).fillMaxWidth()) {
                Box(Modifier.fillMaxSize().semantics { if (sheet.searching) hideFromAccessibility() }.graphicsLayer {
                    val p = sheet.progress.value
                    alpha = (1f - p / .45f).coerceIn(0f, 1f)
                    translationY = p * 130.dp.toPx()
                }) {
                    if (!requestKeyboard) Home(rows, now, Modifier.fillMaxSize(), onEarlier = { if (!sheet.searching) earlier = true }, onDismiss = { ids ->
                        if (!sheet.searching) { ids.map { agent.dismiss(it) }.forEach { it.await() }; rows = rows.map { if (it.id in ids) it.copy(fresh = false) else it } }
                    }, hint = { com.misura.phone.screen.FirstUseHints(sheet, !focused && !listening && rows.none { it.visibleOnHome }) }) {
                        if (!sheet.searching) { openId = it; nav = Nav() }
                    }
                }
                AppSheet(sheet, currentApps, openApp, onSettings = onOpenSettings)
            }
            else if (o != null) {
                val openHistory = { if (nav.top != Screen.History) nav = nav.open(Screen.History) }
                // At the root, your words already show as the echo while Misura works or asks: the nav line doesn't repeat them.
                val echoed = thinking || (say is Say.Approve && !finished) || (places.isEmpty() && items.isEmpty() && say !is Say.Approve)
                NavLine(backLabel(o.s, o.facts, nav), when (val t = nav.top) { is Screen.Detail -> o.facts[t.placeId]?.label ?: ""; Screen.FullMap -> "Map"; Screen.History -> "Steps"; Screen.Root -> if (echoed) "" else o.asked },
                    o.steps.size, onBack = { nav = nav.back() ?: run { keyboard?.hide(); focus.clearFocus(); goHome(); nav } }, onSteps = openHistory,
                    Modifier.padding(horizontal = M.gutter - M.m))
                Box(Modifier.weight(1f).fillMaxWidth().pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); pressed = true; waitForUpOrCancellation(); pressed = false } }) {
                    // The exchange in front, with the steps from before a tap on its question (a look-up keeps its sources line).
                    val turnSteps = (o.thread.lastOrNull()?.let { it.turn..it.last } ?: o.turn..o.turn).let { r -> o.steps.filter { it.turn in r } }
                    // A drafted message takes the screen: your words, the steps with their will line, the card under them.
                    val drafting = say is Say.Approve
                    when (val t = nav.top) {
                        Screen.History -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = M.gutter - M.m), verticalArrangement = Arrangement.spacedBy(16.u)) {
                            // The whole task, oldest first: what you said, what Misura did, what it said.
                            if (o.thread.isEmpty()) o.steps.groupBy { it.turn }.forEach { (_, st) -> Steps(st, now) }
                            else o.thread.forEach { x -> Past(x.asked, o.steps.filter { it.turn in x.turn..x.last }, x.answer, now, cited = x.citedAnswer) }
                        }
                        is Screen.Detail -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(M.p)) {
                            if (say is Say.Approve && !thinking) Drafted(o, say, turnSteps, now, openHistory)
                            else if (thinking) Working(o, turnSteps, now, openHistory, thinking = true)
                            else (say as? Say.Answer)?.takeIf { o.s.revision > detailRevision }?.let { AnswerText(it.line, Modifier.padding(horizontal = M.gutter - M.m), it) }
                            if (say !is Say.Approve) front?.let { PlaceDetail(it, photos) }
                        }
                        Screen.FullMap, Screen.Root -> when {
                            t == Screen.FullMap || (!thinking && places.isNotEmpty() && items.isEmpty() && !drafting && (say as? Say.Answer)?.citations.isNullOrEmpty()) ->
                                DecisionScreen(places, o.s.places?.pick, (say as? Say.Answer)?.line, nav.index, { nav = nav.select(it) },
                                    { id -> detailRevision = o.s.revision; nav = nav.open(Screen.Detail(id)) }, photos,
                                    onMapTap = { if (nav.top != Screen.FullMap) nav = nav.open(Screen.FullMap) }, expanded = t == Screen.FullMap)
                            thinking || (places.isEmpty() && items.isEmpty()) || drafting || (say as? Say.Answer)?.citations?.isNotEmpty() == true -> androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                                // The thread: earlier exchanges sit above, a scroll away. The one in front fills the screen from the top,
                                // so a long answer starts where you read and the next words you say start a clean screen.
                                val scroll = rememberScrollState()
                                val full = maxHeight
                                val past = o.thread.dropLast(1)
                                var pastPx by remember { mutableStateOf(0) }
                                // To the start of the exchange in front (never the end: a long answer is read from its first line).
                                // Two frames: the first lays the new exchange out, so the height above it is known by the second.
                                LaunchedEffect(o.s.id, o.turn) { repeat(2) { androidx.compose.runtime.withFrameNanos {} }; scroll.scrollTo(pastPx) }
                                Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                                    Column(Modifier.onSizeChanged { pastPx = it.height }) {
                                        past.forEach { x -> Past(x.asked, o.steps.filter { it.turn in x.turn..x.last }, x.answer, now, Modifier.padding(horizontal = M.gutter - M.m).padding(bottom = 28.u), cited = x.citedAnswer) }
                                    }
                                    Column(Modifier.heightIn(min = full)) {
                                        if (say is Say.Approve && !thinking) Drafted(o, say, turnSteps, now, openHistory) else Working(o, turnSteps, now, openHistory, thinking)
                                        if (!thinking) (say as? Say.Answer)?.let {
                                            if (o.stale && it.citations.isNotEmpty()) Text("Older answer", style = ST.nav, modifier = Modifier.padding(horizontal = M.gutter - M.m))
                                            AnswerText(it.line, Modifier.padding(horizontal = M.gutter - M.m, vertical = 12.u), it)
                                        }
                                    }
                                }
                            }
                            items.isNotEmpty() -> ItemScreen(items, o.s.items?.pick, (say as? Say.Answer)?.line, nav.index, { nav = nav.select(it) }, onOpenItem ?: { item: ItemFact -> agent.openItem(o.s.id, item); Unit })
                        }
                    }
                }
                if (!thinking) when (say) {
                    is Say.Question -> {
                        val choose = { i: Int -> scope.launch { agent.choose(o.s.id, i, o.s.revision).await() }; Unit }
                        // A message typed in an app (the card names the chat and shows the exact words) is a message card like any
                        // other: a spoken "go" sends it, with the same Undo window. Every other stopped commit (pay, delete…) takes a tap.
                        // A titled card (a routine to save, remove or run) is not a message: it takes a tap (Codex P1, 22 Nov).
                        val gateKey = say.gate?.takeIf { say.label != null && say.title == null && say.choices.size == 2 }?.let { it to o.s.revision }
                        if (gateKey != null) { go.key = gateKey; if (armed == null) go.now = { armed = gateKey } }
                        val isArmed = gateKey != null && armed == gateKey
                        // Undo clears [armed] at once but this effect only stops at the next frame: check again before the yes (Codex P1).
                        if (isArmed) LaunchedEffect(gateKey) { delay(UNDO_MS); if (armed == gateKey) { armed = null; choose(0) } }
                        key(o.s.id, o.s.revision) { QuestionCard(say, armed = isArmed, onUndo = { armed = null }, onChoice = choose) }
                    }
                    is Say.Problem -> ProblemCard(say) { agent.retry(o.s.id) }
                    is Say.Approve -> {
                        // Every message on the card, each its own send task. The card shows nothing until all of them are loaded.
                        val ts = say.all.mapNotNull { o.tasks[it] }.takeIf { it.size == say.all.size && it.all { t -> t.payload != null } }.orEmpty()
                        val cardApps = ts.map { it.payload!!["app"] ?: say.app }.distinct()
                        val cardApp = cardApps.joinToString(" · ")
                        val msgs = ts.map { t ->
                            val p = t.payload!!
                            val name = p["name"] ?: say.name
                            (if (cardApps.size > 1) "$name (${p["app"] ?: say.app})" else name) to p["content"].orEmpty()
                        }
                        val sendAll = {
                            tapped = say.taskId
                            // The go approves exactly what this card drew: each message's own digest. One go, one tracked send per message.
                            sends?.scope?.launch {
                                try {
                                    agent.resumeDraft(o.s.id, say.taskId).await()
                                    ts.forEach { t -> sends.controller.approve(t.id, t.payload!!.digest())?.let { sends.controller.execute(t.id) } ?: run { tapped = null } }
                                } catch (_: com.misura.phone.store.StorageUnavailableException) {
                                    withContext(Dispatchers.Main) {
                                        tapped = null
                                        android.widget.Toast.makeText(context, "Misura couldn't save progress. Check this task before trying again.", android.widget.Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                            Unit
                        }
                        val cardKey = ts.map { it.id to it.payload!!.digest() }
                        val approvalScroll = key(cardKey) { rememberScrollState() }
                        if (ts.isNotEmpty() && ts.all { it.state == TaskState.NEEDS_APPROVAL } && tapped != say.taskId) { go.key = cardKey; if (armed == null) go.now = { armed = cardKey } }
                        when {
                            ts.isEmpty() -> {}
                            // Said "go": a few seconds to take it back before anything leaves.
                            armed == cardKey && ts.all { it.state == TaskState.NEEDS_APPROVAL } -> {
                                LaunchedEffect(cardKey) { delay(UNDO_MS); if (armed == cardKey) { armed = null; sendAll() } }
                                ApproveCard(cardApp, msgs, sending = true, onSend = {}, onEdit = {}, onUndo = { armed = null }, scroll = approvalScroll)
                            }
                            // Keyed by tasks + digests: if a draft changes under a finger, the pending press dies with the old card.
                            ts.all { it.state == TaskState.NEEDS_APPROVAL } && tapped != say.taskId -> key(cardKey) {
                                ApproveCard(cardApp, msgs, sending = false, onSend = sendAll, onEdit = { if (sheet.engaged) sheet.showKeyboard() else runCatching { fieldFocus.requestFocus() } }, scroll = approvalScroll) }
                            ts.all { it.state in SENT } -> {}
                            ts.any { it.state in SENDING || it.state == TaskState.OUTCOME_UNKNOWN && it.id in o.checking } || tapped == say.taskId && ts.any { it.state == TaskState.NEEDS_APPROVAL } ->
                                ApproveCard(cardApp, msgs, sending = true, onSend = {}, onEdit = {}, scroll = approvalScroll)
                            // Never a blind resend: Try again asks Misura again, which drafts a new card you tap yourself.
                            ts.size == 1 -> ProblemCard(Say.Problem(sendProblem(ts[0], ts[0].id in o.checking)),
                                if (ts[0].state == TaskState.OUTCOME_UNKNOWN || ts[0].state == TaskState.PARTIAL) "Prepare new draft" else "Try again") { tapped = null; agent.retry(o.s.id) }
                            else -> {
                                val went = ts.indices.filter { ts[it].state in SENT }
                                // Unsure is not "not sent": a message that may have gone out is never drafted again behind your back (Codex P1).
                                val unsure = ts.indices.filter { ts[it].state == TaskState.OUTCOME_UNKNOWN || ts[it].state == TaskState.PARTIAL }
                                val not = ts.indices - went.toSet() - unsure.toSet()
                                fun names(l: List<Int>) = l.joinToString { msgs[it].first }
                                val line = listOfNotNull("${went.size} of ${ts.size} messages went out.", names(not).takeIf { not.isNotEmpty() }?.let { "Not sent: $it." },
                                    names(unsure).takeIf { unsure.isNotEmpty() }?.let { "Not confirmed: $it. Check the chat before sending again." }).joinToString(" ")
                                // Only what surely did not go out is drafted again. With nothing but unsure ones left, Try again drafts those,
                                // and the card has already said to check first: the new card still needs your own tap.
                                ProblemCard(Say.Problem(line), if (not.isEmpty()) "Prepare new draft" else "Retry ${not.size} unsent") { tapped = null; agent.retryRest(o.s.id, went + if (not.isEmpty()) emptyList() else unsure, not.ifEmpty { unsure }) }
                            }
                        }
                    }
                    else -> {}
                }
                // The card you're talking about docks while you type, and stays docked while Misura works on it.
                val docked = target?.takeIf { focused || thinking }
                if (docked != null && nav.top != Screen.FullMap) {
                    val label = if (thinking) "Working on" else "Asking about"
                    val clear = if (thinking) null else ({ targetId = null; cleared = true })
                    o.facts[docked.id]?.let { Docked(it, label, photos, clear) } ?: o.items[docked.id]?.let { Docked(it, label, clear) }
                }
            } else Spacer(Modifier.weight(1f))
            // An armed go belongs to one exact card. If that card is not what is on screen now (redrafted, another task, home,
            // Misura working), the go is void: it never waits around to fire later (Codex P2).
            androidx.compose.runtime.SideEffect { if (armed != null && armed != go.key) armed = null }

            // Typed or spoken, words go the same way. Words on a finished task start a new one: there's no card in front to talk to.
            fun submit(t: String, note: Looking?) {
                val id = openId
                scope.launch {
                    if (id == null || cleared || finished) { val new = agent.start(t); openId = new; nav = Nav(); targetId = null; targetSession = null; cleared = false }
                    else agent.followUp(id, t, note)
                }
            }
            // Nothing typed: the button is hold-to-talk (stands in for the side key, like the field).
            // Spoken only: "go" (and nothing but go) on a waiting card of messages sends what the card shows. The app matches the
            // words itself; the model never decides that you meant go. Anything else you say goes to Misura as before.
            val currentSubmit by rememberUpdatedState<(String) -> Unit> { said ->
                val g = go.now
                // "go" for a card that is no longer the one you were looking at when you started talking goes nowhere: not sent, not passed on.
                if (com.misura.core.agent.isGo(said) && go.heldOn != null) { if (g != null && go.key == go.heldOn) g() } else submit(said, target)
            }
            val talk = remember(voice) { voice?.let { v -> com.misura.phone.voice.HoldToTalk(v, { /* words are not shown while you talk: the Listening sign is; they show as the echo after */ }, { listening = it }) { currentSubmit(it) } } }
            // Like focusing the field: what you say is about the card in front right now.
            val talkDown by rememberUpdatedState { targetId = lookingFront?.id; targetSession = openId; cleared = false; go.heldOn = go.key; talk?.down(); Unit }
            // The side key does what the button does. Pressed in another app it brings this screen up; if it is still down by then, listen.
            androidx.lifecycle.compose.LifecycleResumeEffect(talk) {
                val key = com.misura.phone.hidden.SideKey
                if (talk != null) { key.listener = { down -> if (down) talkDown() else talk.up() }; if (key.held) talkDown() }
                onPauseOrDispose { key.listener = null; talk?.cancel() } // leaving mid-hold drops what was heard: no ask, no go
            }
            // A phone with a side key has no talk button (his call, 15 Nov); the Listening sign in the field's place says it hears you.
            val sideKey = com.misura.phone.hidden.SideKey.seen
            HomeInput(sheet, plainHome, text, onTextChange = { text = it }, listening = listening,
                requestIme = if (animateRequest) requestIme else null,
                level = { voice?.level ?: 0f }, fieldFocus = fieldFocus, onFocus = { isFocused ->
                    if (!(plainHome && sheet.searching) && isFocused && !focused) { targetId = lookingFront?.id; targetSession = openId; cleared = false }
                    focused = isFocused
                    if (!isFocused) requestIme.focusLost()
                }, openFirstApp = { openFirstApp() }) {
                if (talk != null && !sideKey && (listening || text.isBlank() && !focused)) TalkButton(listening, enabled = true,
                    onDown = { talkDown() },
                    onUp = { talk.up() }, onCancel = { talk.cancel() })
                else if (sideKey && text.isBlank()) Unit
                else MButton("Ask", Kind.Forward, enabled = text.isNotBlank() && !listening) { if (!listening) {
                    val t = text.trim(); text = ""
                    if (sheet.engaged) sheet.hideKeyboard() else { keyboard?.hide(); focus.clearFocus() }
                    submit(t, target)
                }
                }
            }
        }
    }
}
}

/**
 * Which texture strength the screen in front gets (design-system §12 Atmosphere). Mirrors the render branches below
 * (full map wins, a place detail keeps its card while working, a bottom card wins over Steps) without changing them.
 */
private fun atmosphereOf(plainHome: Boolean, earlier: Boolean, requestKeyboard: Boolean, listening: Boolean, populated: Boolean,
    o: Open?, nav: Nav, taskOpen: Boolean): Atmosphere {
    if (plainHome) return when {
        requestKeyboard -> Atmosphere.HIDDEN
        listening -> Atmosphere.LISTENING
        populated -> Atmosphere.HOME_QUIET
        else -> Atmosphere.HOME
    }
    if (o == null) return if (earlier && !taskOpen) Atmosphere.READING else Atmosphere.HOLD
    val thinking = o.s.status == SessionStatus.THINKING
    val say = o.s.say
    val places = o.s.places?.placeIds?.mapNotNull { o.facts[it] }.orEmpty()
    val items = o.s.items?.itemIds?.mapNotNull { o.items[it] }.orEmpty()
    val drafting = say is Say.Approve
    val cited = (say as? Say.Answer)?.citations?.isNotEmpty() == true
    val bottomCard = !thinking && when (say) {
        is Say.Question, is Say.Problem -> true
        is Say.Approve -> say.all.mapNotNull { o.tasks[it] }.let { ts -> ts.size == say.all.size && ts.all { it.payload != null } && !ts.all { it.state in SENT } }
        else -> false
    }
    val cardInFront = when (val t = nav.top) {
        Screen.FullMap -> true
        is Screen.Detail -> say !is Say.Approve && o.facts[t.placeId] != null
        Screen.Root -> (!thinking && places.isNotEmpty() && items.isEmpty() && !drafting && !cited) ||
            !(thinking || (places.isEmpty() && items.isEmpty()) || drafting || cited) && items.isNotEmpty()
        else -> false
    }
    // A sent card has no bottom card left, so a finished task reads as done text.
    return when {
        cardInFront || bottomCard -> Atmosphere.CARD
        nav.top == Screen.History -> Atmosphere.READING // the Steps list wins over the working view
        thinking -> Atmosphere.WORKING
        else -> Atmosphere.READING
    }
}

/** Observe only discrete sheet state here, keeping frame progress and eased strength out of HomeScreen composition. */
@Composable
private fun HomeDecoration(sheet: AppSheetState, listening: Boolean, level: () -> Float, kind: Atmosphere) {
    val fullSheet by remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.progress.value >= .999f } }
    val behindSheet by remember(sheet) { androidx.compose.runtime.derivedStateOf { sheet.progress.value > .01f } }
    val home = kind == Atmosphere.HOME || kind == Atmosphere.HOME_QUIET || kind == Atmosphere.LISTENING
    val wanted = when {
        kind == Atmosphere.HOLD -> null
        home && fullSheet -> 0f
        home && behindSheet -> com.misura.phone.screen.QUIET
        else -> kind.strength
    }
    val target = remember { androidx.compose.runtime.mutableFloatStateOf((wanted ?: 0f)) }
    // Work brightens the texture only after a second of it; until then it keeps what showed (or less).
    LaunchedEffect(wanted, kind == Atmosphere.WORKING) {
        if (wanted == null) return@LaunchedEffect
        if (kind == Atmosphere.WORKING) {
            target.floatValue = minOf(target.floatValue, wanted)
            delay(com.misura.phone.screen.WORKING_DELAY_MS)
        }
        target.floatValue = wanted
    }
    com.misura.phone.screen.HomeAtmosphere(listening, level, { target.floatValue }, Modifier.fillMaxSize())
}

/** Only this input leaf switches field mode and removes the home action, after release. */
@Composable
private fun HomeInput(sheet: AppSheetState, plainHome: Boolean, text: String, onTextChange: (String) -> Unit,
    requestIme: androidx.compose.ui.platform.SoftwareKeyboardController?,
    listening: Boolean, level: () -> Float, fieldFocus: FocusRequester, onFocus: (Boolean) -> Unit,
    openFirstApp: () -> Unit, action: @Composable () -> Unit) {
    val searchingApps = plainHome && sheet.searching
    Row(horizontalArrangement = Arrangement.spacedBy(M.gap)) {
        Box(Modifier.weight(1f), contentAlignment = androidx.compose.ui.Alignment.CenterStart) {
            // While it listens the field steps aside for the Listening sign (bars + word, bottom left as in the design).
            // It stays in place, unseen, so focus and anything typed survive the hold.
            // Route the field's own tap/show requests through the same owner too.
            val fieldKeyboard = if (sheet.engaged) sheet.keyboard else requestIme ?: LocalSoftwareKeyboardController.current
            CompositionLocalProvider(LocalSoftwareKeyboardController provides fieldKeyboard) {
                Field(if (searchingApps) sheet.typed else text,
                    { if (searchingApps) sheet.typed = it else onTextChange(it) }, if (searchingApps) "Search apps" else "Type a request…",
                    Modifier.alpha(if (listening) 0f else 1f)
                        .focusRequester(fieldFocus).focusRequester(sheet.fieldFocus)
                        .onFocusChanged { onFocus(it.isFocused) }.pointerInput(searchingApps, requestIme) {
                            if (searchingApps) awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) sheet.showKeyboard()
                            }
                            else if (requestIme != null) awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (waitForUpOrCancellation(pass = PointerEventPass.Initial) != null) requestIme.show()
                            }
                        }.onPreviewKeyEvent { event ->
                            if (searchingApps && event.key == Key.Enter) {
                                if (event.type == KeyEventType.KeyUp) openFirstApp()
                                true
                            } else false
                        },
                    leading = if (searchingApps) ({ com.misura.phone.screen.LineIcon(com.misura.phone.screen.Icon.Search, Modifier.size(16.dp)) }) else null,
                    keyboardOptions = KeyboardOptions(imeAction = if (sheet.engaged) ImeAction.Go else ImeAction.Default,
                        showKeyboardOnFocus = !sheet.engaged && requestIme == null),
                    useStateField = true, label = if (searchingApps) "Search apps" else ASK_FIELD, acceptInput = !listening,
                    onKeyboardAction = if (searchingApps) ({ openFirstApp() }) else null,
                    singleLine = sheet.engaged)
                androidx.compose.runtime.SideEffect { if (sheet.engaged) sheet.keyboard.fieldReady() }
            }

            if (listening) com.misura.phone.screen.Listening(Modifier.padding(start = M.gutter - M.m), level = level)
        }
        if (!searchingApps) action()
    }
}

private val SENDING = setOf(TaskState.EXECUTING, TaskState.CHECKING)
private val SENT = setOf(TaskState.DONE, TaskState.WAITING)

/** A send that didn't confirm, in the words the card system uses (whether anything may have gone out). */
private fun sendProblem(t: Task, checking: Boolean): String = when (val c = cardFor(t, emptyList(), checking)) {
    is Card.Problem -> c.line
    else -> "Couldn't reach the chat. Nothing was sent." // PLANNING after every route was unavailable
}

/**
 * A drafted message. Before and while sending: echo + steps (will / sending) with the card under them.
 * Done (design-system §6, mockup frame 9): the outcome first, its ✓ steps under it, no echo, no card.
 */
@Composable
private fun Drafted(o: Open, say: Say.Approve, steps: List<Step>, now: Long, onOpen: () -> Unit) {
    val t = o.tasks[say.taskId]
    // With more on the list, a sent message is a ✓ line on the way, not the outcome.
    if (!say.more && say.all.all { o.tasks[it]?.state in SENT })
        Column(Modifier.padding(horizontal = M.gutter - M.m), verticalArrangement = Arrangement.spacedBy(16.u)) {
            AnswerText("Sent.")
            Steps(steps, now, onOpen = onOpen)
        }
    else Working(o, steps, now, onOpen)
}

/** Hold to talk: listens while a finger is down. Same look as a forward button; it prepares, never commits. */
@Composable
private fun TalkButton(listening: Boolean, enabled: Boolean, onDown: () -> Unit, onUp: () -> Unit, onCancel: () -> Unit) {
    val shape = androidx.compose.foundation.shape.RoundedCornerShape(com.misura.phone.cards.LocalRadii.current.tile)
    val label = if (listening) "Listening" else "Hold to talk"
    // The gesture outlives recompositions; it must call the callbacks of the screen as it is now (the card in front changes).
    val down by rememberUpdatedState(onDown); val up by rememberUpdatedState(onUp); val cancel by rememberUpdatedState(onCancel)
    Box(Modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.4f)
        .border(1.5.dp, M.orange, shape) // never solid orange: solid means a tap commits
        .semantics { contentDescription = label; role = Role.Button }
        .pointerInput(enabled) { if (enabled) detectTapGestures(onPress = {
            down()
            var released = false
            try { released = tryAwaitRelease() } finally { if (released) up() else cancel() }
        }) }
        .padding(horizontal = 12.u, vertical = 13.u)) {
        Text(label, style = com.misura.phone.cards.T.btn, color = M.orange, maxLines = 1)
    }
}

/** Echo + this turn's steps (Working layout). */
@Composable
private fun Working(o: Open, steps: List<com.misura.core.agent.Step>, now: Long, onOpen: () -> Unit, thinking: Boolean = false) = Column(Modifier.padding(horizontal = M.gutter - M.m), verticalArrangement = Arrangement.spacedBy(16.u)) {
    if (o.echo.isNotEmpty()) Echo(o.echo)
    Steps(steps, now, onOpen = onOpen)
    // Between steps the model is deciding what to do next: without this line the screen sat blank for seconds.
    if (thinking && steps.none { it.state == com.misura.core.agent.StepState.RUNNING }) com.misura.phone.screen.Thinking()
}

const val EARLIER = "Earlier ›"

/**
 * Home: the clock, and only what is still open. Lines for what you started: a beating ring when it is ready for your go,
 * ✕ when it failed, the working sign for what runs (by name when it is one task, a count when it is several). Finished
 * tasks are behind "Earlier". [onEarlier] = null: a plain list, no signs.
 */
@Composable
private fun Home(rows: List<Row>, now: Long, modifier: Modifier, onEarlier: (() -> Unit)? = null, onDismiss: suspend (List<String>) -> Unit = {},
    hint: @Composable () -> Unit = {}, onOpen: (String) -> Unit) = androidx.compose.foundation.layout.BoxWithConstraints(modifier.fillMaxWidth()) {
    val density = LocalDensity.current
    val measure = androidx.compose.ui.text.rememberTextMeasurer(cacheSize = 16)
    val baseStyle = androidx.compose.material3.LocalTextStyle.current
    val contentWidth = with(density) { (maxWidth - (M.gutter - M.m) * 2).roundToPx().coerceAtLeast(1) }
    fun heightOf(text: String, style: androidx.compose.ui.text.TextStyle, lines: Int = Int.MAX_VALUE, width: Int = contentWidth) = with(density) {
        measure.measure(androidx.compose.ui.text.AnnotatedString(text), baseStyle.merge(style), maxLines = lines,
            constraints = androidx.compose.ui.unit.Constraints(maxWidth = width)).size.height.toDp()
    }
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(now))
    val locale = java.util.Locale.getDefault()
    val date = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale).format(Date(now))
    val open = rows.filter { it.visibleOnHome }
    val yoursAll = open.filter { it.kind == com.misura.core.agent.HomeKind.YOURS }.take(3)
    val failedAll = open.filter { it.kind == com.misura.core.agent.HomeKind.FAILED }.take(2)
    val running = open.filter { it.kind == com.misura.core.agent.HomeKind.RUNNING }
    val runningName = if (running.size == 1) running[0].name else "Working on ${running.size} things"
    val allOpen = rows.count { it.kind != com.misura.core.agent.HomeKind.DONE }
    val earlierReserve = if (rows.isEmpty()) 0.dp else maxOf(48.dp,
        heightOf(if (allOpen > 0) "Earlier · $allOpen still open ›" else EARLIER, ST.nav) + 32.dp)
    val rowHeight = maxOf(48.dp, (yoursAll + failedAll).map { heightOf(it.name, ST.step, 1) }
        .plus(heightOf(runningName, ST.step, 1)).maxOrNull()!! + 24.dp)
    val cueHeight = maxOf(14.u, heightOf("Swipe down for apps", ST.date,
        width = (contentWidth - with(density) { 22.u.roundToPx() }).coerceAtLeast(1)))
    val tail = earlierReserve + if (open.isEmpty()) cueHeight else rowHeight
    val dateHeight = heightOf(date, ST.date)
    val clockHeight = 24.u + heightOf(time, ST.clock)
    val showDate = dateHeight + tail <= maxHeight
    val showClock = clockHeight + dateHeight + tail <= maxHeight
    val header = (if (showDate) dateHeight else 0.dp) + (if (showClock) clockHeight else 0.dp)
    // Reserve Earlier before fitting task lines; no extra home scroll competes with the accepted sheet drag.
    val slots = ((maxHeight - header - earlierReserve).value / rowHeight.value).toInt().coerceAtLeast(0)
    val yours = yoursAll.take(slots)
    val failed = failedAll.take((slots - yours.size).coerceAtLeast(0))
    val showRunning = slots > yours.size + failed.size && running.isNotEmpty()
    val hidden = allOpen - yours.size - failed.size - if (showRunning) running.size else 0
    val headlineFits = open.isEmpty() && maxHeight - header - earlierReserve >= heightOf("What should we do?", ST.home) + 24.u + cueHeight
    Column(Modifier.fillMaxSize().padding(horizontal = M.gutter - M.m)) {
        if (showClock) Text(time, style = ST.clock, modifier = Modifier.padding(top = 24.u))
        if (showDate) Text(date, style = ST.date)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) { hint() }
        if (headlineFits) Text("What should we do?", style = ST.home, modifier = Modifier.padding(bottom = 24.u))
        if (onEarlier == null) rows.forEach { r -> TaskLine(r.line, maxLines = 2) { onOpen(r.id) } }
        else {
            yours.forEach { r -> key(r.id) { TaskLine(r.name, "${r.name}, ready for your go", { com.misura.phone.screen.YourGo() }, onRemove = { onDismiss(listOf(r.id)) }) { onOpen(r.id) } } }
            failed.forEach { r -> key(r.id) { TaskLine(r.name, "${r.name}, failed", { Text("✕", style = ST.step, color = M.orange) }, onRemove = { onDismiss(listOf(r.id)) }) { onOpen(r.id) } } }
            if (showRunning && running.size == 1) key(running[0].id) { TaskLine(runningName, "$runningName, working", { com.misura.phone.screen.WorkingDot() }, onRemove = { onDismiss(listOf(running[0].id)) }) { onOpen(running[0].id) } }
            else if (showRunning) key(running.map { it.id }) { TaskLine(runningName, sign = { com.misura.phone.screen.WorkingDot() },
                onRemove = { onDismiss(running.map { it.id }) }, onClick = onEarlier) }
            if (rows.isNotEmpty()) Text(if (hidden > 0) "Earlier · $hidden still open ›" else EARLIER, style = ST.nav, color = if (hidden > 0) M.text else M.text2,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Earlier tasks", onClick = onEarlier).padding(vertical = 16.dp))
        }
    }
}

/** One tappable line of screen text, with its sign in front when it has one. Opens, never commits. */
@Composable
private fun TaskLine(text: String, says: String = text, sign: (@Composable () -> Unit)? = null, maxLines: Int = 1,
    action: String = "Dismiss", onRemove: (suspend () -> Unit)? = null, onClick: () -> Unit) {
    val line: @Composable (Modifier) -> Unit = { actions ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Open", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = says }.then(actions).padding(vertical = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.u)) {
            if (sign != null) Box(Modifier.width(14.u), contentAlignment = androidx.compose.ui.Alignment.Center) { sign() }
            Text(text, style = ST.step, maxLines = maxLines, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    if (onRemove != null) com.misura.phone.screen.SwipeTaskLine(action, onRemove, content = line) else line(Modifier)
}

private fun texts(t: Turn) = (t.content["parts"] as? JsonArray).orEmpty().mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
private fun firstText(turns: List<Turn>) = turns.firstOrNull { it.role == "user" && texts(it).isNotEmpty() }?.let { texts(it).first() }
/** The last thing you said. A tap on a card's choice isn't speech, so the echo stays on your own words. */
private fun lastText(turns: List<Turn>) = turns.lastOrNull { it.role == "user" && texts(it).let { t -> t.isNotEmpty() && com.misura.core.agent.PICK_NOTE !in t && com.misura.core.agent.SENT_NOTE !in t } }?.let { texts(it).first() }
