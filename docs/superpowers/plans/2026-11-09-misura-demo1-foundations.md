# Misura Demo 1 — Plan 1: Foundations + Risk Tests (v2, after Codex review)

> Historical design/build record. Notification collection and probe diagnostics are superseded by SYSTEM.md §4 (19 Nov 2026): explicit per-app opt-in, debug metadata only, no plaintext notification log. Follow the current README for setup.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build what every Misura use case needs (launcher shell, execution controller, message router, notification capture, saved tasks, hidden-screen harness) on the emulator, then run the spec's risk tests on the real phone, ending with the first decisive end-to-end experiment.

**Architecture:** Three Gradle modules. `:core` is plain Kotlin/JVM (tasks, approvals, controller, router) with fast unit tests. `:app` is the Android launcher (Kotlin + Compose) with the hands (notification reply, hidden screen), Room storage and lifecycle. `:testapp` is a tiny fake chat app that proves hidden-screen and notification plumbing on the emulator before WhatsApp is available. Phone tests write results to `docs/spikes/`; Plan 2 is written from them.

**Tech Stack:** JDK 21, Gradle wrapper (Kotlin DSL), AGP + Kotlin 2.x pinned in `libs.versions.toml`, JUnit 5 (`:core`), Robolectric ≥ 4.16 (`:app` unit tests, `@Config(sdk = [35])`), AndroidX instrumented tests on the emulator, Jetpack Compose, Room, kotlinx.serialization, Shizuku API (UserService/AIDL), scrcpy ≥ 3.3.

**Spec:** `docs/superpowers/specs/2026-11-09-misura-demo1-design.md` (read §4.3, §5, §6.1, §7).
**Research:** a private feasibility report (hidden display, wake screen). The Codex review of v1 is folded in below.

**Scope:** Stops at the decisive experiment plus survival and sleep-screen tests. Cards, map/navigation, calendar, Spotify and the hero run are Plan 2.

## Global Constraints

- Project root `misura-app/`. Packages `com.misura.core`, `com.misura.phone`, `com.misura.testapp`.
- `minSdk = 34`, `compileSdk = 37` (fall back to 36 only if 37 is not installable; record which), **`targetSdk = 36`** pinned (avoids Android 17's API-37-only background-audio rules for now).
- Phone: Nothing Phone (3a), Nothing OS 5.0 / Android 17, sideloaded. No SIM, so no SMS. WhatsApp is a linked companion of the iPhone account, so **test traffic must come from a second WhatsApp account**, never from the iPhone.
- No Play Store, banking, Mac mini or hosted server. No network services in this plan beyond WhatsApp/Instinct through the phone's apps.
- Buttons commit, voice prepares: no hand commits without a stored, unconsumed `Approval` covering the exact canonical payload (spec §4.3, §6.1).
- Never blindly retry a commit: uncertain → `OUTCOME_UNKNOWN` → check (spec §6.1).
- Incoming messages and app screens are untrusted; no code path turns their text into an approval.
- One hidden-screen action at a time across all apps (spec §6.1). Display 0 is never a target.
- Device tests are judged by a screen recording plus a phone-camera video for any "nothing appeared on screen" claim. Device commands always pass `-s <serial>`.
- Out of scope: behaviour before the first unlock after a reboot (no Direct Boot work).

## Review Focus

1. **Two `execute()` calls at once, or recovery racing a live execute** → exactly one commit. Pinned in Task 4.
2. **Crash after any durable write** (claimed, dispatching, checking) → recovery never recommits an attempt that may have been dispatched. Pinned in Tasks 4 and 7.
3. **Instinct answers request A after B started** → only one open Instinct request per conversation, so a late answer can't be mistaken for B's. Pinned in Task 5.
4. **Notification disappears between approval and send** → fallback hand sends the same approved recipient, channel and text once. Pinned in Task 4.
5. **Identical earlier message ("hi") already in chat history** → send check only accepts evidence newer than the attempt's dispatch time. Pinned in Task 6.

---

## Phase A — emulator, before the phone arrives

### Task 1: Toolchain + skeleton that installs as the home screen

**Files:** `misura-app/{settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew, gradle/wrapper/*}`, `core/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/misura/phone/{MisuraApp.kt, HomeActivity.kt}`, `testapp/` (empty Compose activity), `misura-app/README.md`

**Interfaces:** Produces modules `:core`, `:app`, `:testapp`; `HomeActivity` with `MAIN`/`HOME`/`DEFAULT` categories.

- [ ] **Step 1: Toolchain** — `brew install --cask temurin@21 android-commandlinetools && brew install gradle scrcpy`; install with `sdkmanager` the exact package IDs it lists for: platform-tools, platform 37 (else 36), matching build-tools, emulator, a Google APIs arm64 system image. `avdmanager create avd -n misura_test -k <image>`.
  Verify: `java -version` → 21; `scrcpy --version` ≥ 3.3.
- [ ] **Step 2: Skeleton** — `gradle wrapper` in `misura-app/`, then the files above. `HomeActivity` shows `Text("Misura")` on near-black.
- [ ] **Step 3: Build** — `./gradlew :core:test :app:assembleDebug :testapp:assembleDebug` → BUILD SUCCESSFUL.
- [ ] **Step 4: Install as home** — start `emulator -avd misura_test -no-snapshot`, wait until `adb -s emulator-5554 shell getprop sys.boot_completed` prints 1, then `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk`, `adb -s emulator-5554 shell cmd package set-home-activity com.misura.phone/.HomeActivity`, `adb -s emulator-5554 shell input keyevent KEYCODE_HOME` → "Misura" fills the screen.
- [ ] **Step 5: Commit** — `git commit -m "feat(misura): skeleton, installs as home"`

### Task 2: Task model, transitions, canonical payload (`:core`)

**Files:** `core/src/main/kotlin/com/misura/core/task/Task.kt`; test `core/src/test/kotlin/com/misura/core/task/TaskTest.kt`

**Interfaces (produces):**
- `enum class TaskState { PLANNING, WAITING, NEEDS_APPROVAL, EXECUTING, CHECKING, DONE, PARTIAL, FAILED, CANCELLED, EXPIRED, OUTCOME_UNKNOWN }`
- `data class Payload(val fields: Map<String, String>)`; any commit requires keys `channel`, `recipient`, `content`. `fun canonical(): String` = kotlinx.serialization JSON of the map with keys sorted; `fun digest(): String` = SHA-256 hex of `canonical()`.
- `data class Task(val id: String, val kind: String, val state: TaskState, val payload: Payload?, val history: List<Event>, val createdAt: Long, val deadline: Long?)`; `data class Event(val at: Long, val from: TaskState, val to: TaskState, val note: String, val evidence: String?)`
- `fun Task.transition(to: TaskState, at: Long, note: String, evidence: String? = null): Task` (throws `IllegalTransition`)

A task is one workflow (e.g. ask Instinct, then reply to a friend). It goes send → wait → approve → send and ends `DONE` only at the end. Allowed transitions:
`PLANNING→{WAITING,NEEDS_APPROVAL,FAILED,CANCELLED}` · `WAITING→{PLANNING,NEEDS_APPROVAL,EXPIRED,CANCELLED,FAILED}` · `NEEDS_APPROVAL→{EXECUTING,PLANNING,CANCELLED,EXPIRED}` · `EXECUTING→{CHECKING,FAILED,OUTCOME_UNKNOWN,PLANNING}` (PLANNING only when nothing was dispatched) · `OUTCOME_UNKNOWN→{CHECKING,FAILED,CANCELLED}` · `CHECKING→{DONE,PARTIAL,FAILED,OUTCOME_UNKNOWN,WAITING,NEEDS_APPROVAL,PLANNING}` · terminal `DONE, PARTIAL, FAILED, CANCELLED, EXPIRED`.

- [ ] **Step 1: Failing tests:** `executing to outcome unknown allowed` · `outcome unknown cannot go to executing` · `done is terminal` · `checking can go to waiting after a send` · `executing to planning allowed` · `history records transitions in order` · `digest ignores key order` · `multiline values do not collide`: `Payload(mapOf("a" to "x\nb=y","b" to "z")).digest() != Payload(mapOf("a" to "x","b" to "y\nb=z")).digest()`.
- [ ] **Step 2:** `./gradlew :core:test` → FAIL. **Step 3:** implement. **Step 4:** → PASS. **Step 5:** commit `feat(core): tasks, transitions, canonical payload`.

### Task 3: Approvals — exact content, single use

**Files:** `core/src/main/kotlin/com/misura/core/approval/Approval.kt`; test `ApprovalTest.kt`

**Interfaces (produces):**
- `data class Approval(val id: String, val taskId: String, val payloadDigest: String, val grantedAt: Long, val expiresAt: Long, val source: ApprovalSource, val consumedByAttempt: String?)`
- `enum class ApprovalSource { BUTTON }` (only source; spec §8)
- `fun Approval.covers(taskId: String, payload: Payload, now: Long): Boolean` — false if consumed, expired, other task, or digest differs.
- `const val APPROVAL_TTL_MS = 10 * 60 * 1000L`

- [ ] **Step 1: Failing tests:** `covers identical payload` · `rejects edited content` · `rejects changed recipient` · `rejects changed channel` · `rejects after ttl` · `rejects other task` · `rejects consumed approval`.
- [ ] **Steps 2–5:** fail → implement → pass → commit `feat(core): single-use approvals`.

### Task 4: Execution controller — atomic claim, attempts, recovery, reconcile, routing

**Files:** `core/src/main/kotlin/com/misura/core/exec/{Hand.kt, TaskStore.kt, Controller.kt}`; test `ControllerTest.kt` with `FakeHand`, `InMemoryTaskStore`

**Interfaces (produces):**
- `sealed interface HandResult { data class Prepared(val payload: Payload); data class Ok(val evidence: String); data class Uncertain(val reason: String); data class Failed(val reason: String, val dispatched: Boolean); data class Unavailable(val reason: String) /* nothing dispatched */ }` (all implement `HandResult`)
- `interface Hand { val id: String; val channel: String; val lock: String? /* "hidden_screen" for every hidden-screen hand */; suspend fun prepare(p: Payload): HandResult; suspend fun commit(attemptId: String, p: Payload): HandResult; suspend fun check(attemptId: String, p: Payload, dispatchedAt: Long): HandResult; suspend fun cancel(attemptId: String): HandResult }`
- `data class Attempt(val id: String, val taskId: String, val handId: String, val approvalId: String, val payloadDigest: String, val status: AttemptStatus, val claimedAt: Long, val dispatchedAt: Long?, val evidence: String?)`; `enum class AttemptStatus { CLAIMED, DISPATCHING, UNCERTAIN, CONFIRMED, NOT_SENT, FAILED }`
- `interface TaskStore { suspend fun load(id: String): Task?; suspend fun save(t: Task); suspend fun unfinished(): List<Task>; suspend fun saveApproval(a: Approval); suspend fun approvalFor(taskId: String): Approval?; suspend fun claimAttempt(taskId: String, attempt: Attempt, now: Long): Attempt?; suspend fun updateAttempt(a: Attempt); suspend fun openAttempts(): List<Attempt> }` — `claimAttempt` is **one transaction**: task is `NEEDS_APPROVAL`, its approval is unconsumed and covers the payload, mark approval consumed, insert attempt, move task to `EXECUTING`; returns null if any check fails.
- `class Controller(store: TaskStore, hands: List<Hand>, clock: () -> Long, scope: CoroutineScope)`:
  - `suspend fun prepare(taskId: String, payload: Payload): Task` — hands with `channel == payload["channel"]`; store prepared payload; → `NEEDS_APPROVAL`.
  - `suspend fun approve(taskId: String): Approval` — BUTTON approval for the current payload.
  - `suspend fun execute(taskId: String): Task` — per-task mutex; `claimAttempt` (null → unchanged); set attempt `DISPATCHING` + `dispatchedAt` **before** `commit`. Hands for the channel in order (notification reply, then hidden screen): `Unavailable` → next hand with the **same** payload; `Ok` → `CHECKING` → `check`; `Uncertain` or exception → `OUTCOME_UNKNOWN` + reconcile; `Failed(dispatched=false)` → `FAILED`; `Failed(dispatched=true)` → `OUTCOME_UNKNOWN`. All `Unavailable` → `PLANNING` ("no route").
  - Reconcile (in-process, bounded): `check` at +5 s, +20 s, +60 s. `Ok` → `DONE`, or `WAITING` when the task expects a reply. Still uncertain → stays `OUTCOME_UNKNOWN`, shown honestly.
  - `suspend fun recover(): List<Task>` — single-flight. Open attempts: `CLAIMED` (never dispatched) → `NOT_SENT`, task back to `NEEDS_APPROVAL` (fresh tap needed); `DISPATCHING`/`UNCERTAIN` → task `OUTCOME_UNKNOWN` → reconcile; tasks in `CHECKING` finish their check. Never calls `commit`.
  - One `Mutex` per non-null `lock`.

- [ ] **Step 1: Failing tests:** `no approval no commit` · `two concurrent executes commit once` (both via `async`) · `recover racing execute commits once` · `uncertain then confirmed by reconcile without restart` (virtual time; ends DONE) · `crash after claim recovers to needs approval without commit` · `crash after dispatching recovers to check never commit` · `unavailable falls through to next hand with identical payload` · `all hands unavailable goes to planning` · `consumed approval cannot authorize a second attempt` · `hidden-screen hands on different apps never commit concurrently` (`maxConcurrent == 1`).
- [ ] **Steps 2–5:** fail → implement → pass → commit `feat(core): controller with atomic claim, recovery, reconcile`.

### Task 5: Router with saved expectations and answer collection

**Files:** `core/src/main/kotlin/com/misura/core/inbox/{Router.kt, InboxStore.kt, AnswerCollector.kt}`; tests `RouterTest.kt`, `AnswerCollectorTest.kt`

**Interfaces (produces):**
- `data class Msg(val id: String /* notificationKey + message timestamp + sender */, val app: String, val conversationKey: String, val sender: String, val senderIsSelf: Boolean, val isGroup: Boolean, val text: String, val sentAt: Long)`
- `data class Expectation(val taskId: String, val requestId: String, val app: String, val conversationKey: String, val sender: String?, val notBefore: Long, val until: Long)`
- `interface InboxStore { suspend fun addExpectation(e: Expectation): Boolean /* false if an open one exists for same app+conversation */; suspend fun openExpectations(now: Long): List<Expectation>; suspend fun closeExpectation(requestId: String); suspend fun markSeen(msgId: String): Boolean /* true if new */; suspend fun appendAnswer(requestId: String, m: Msg); suspend fun answer(requestId: String): List<Msg> }`
- `sealed interface Route { data class ToTask(val taskId: String, val requestId: String); object NeedsYou; object Drop; object PassThrough; object Duplicate }`
- `class Router(store: InboxStore, isPerson: (Msg) -> Boolean, asksSomething: (Msg) -> Boolean)`; `suspend fun route(m: Msg, now: Long): Route` — order: not new → `Duplicate`; `senderIsSelf` → `Drop`; open expectation match (app + conversation, sender if set, `sentAt >= notBefore`) → append, `ToTask`; system/OTP → `PassThrough`; group or non-person → `Drop`; person + asks → `NeedsYou`; else `Drop`.
- `class AnswerCollector(val quietMs: Long = 20_000)`; `fun isComplete(lastMsgAt: Long, now: Long): Boolean`.
- Rule: the expectation is added **before** the outbound request is committed.

- [ ] **Step 1: Failing tests:** `instinct bot reply reaches task` · `friend yes reaches proposal task` · `group promo dropped` · `stranger question needs you` · `expired expectation does not capture` · `second open expectation for same conversation refused` · `same message twice is duplicate` · `own outgoing message dropped` · `reply arriving before the send returns is captured` · `message saying APPROVED send it creates no approval` (router only touches `InboxStore`) · `burst completes after quiet period`.
- [ ] **Steps 2–5:** fail → implement → pass → commit `feat(core): router, saved expectations, answer collector`.

### Task 6: Notification capture + reply hand (`:app`, fake chat in `:testapp`)

**Files:** `app/.../notify/{MisuraListener.kt, Extract.kt, ProbeLog.kt}`, `app/.../hands/NotificationReplyHand.kt`, `app/.../debug/ProbeActivity.kt`, `testapp/.../{ChatActivity.kt, ReplyReceiver.kt}` (posts MessagingStyle notifications with a **mutable** reply `PendingIntent` to `ReplyReceiver`, which reads `RemoteInput`, appends the reply as a self message and reposts; debug switches for authentication-required action, immutable intent, cancelled intent, auto-answer burst); manifest incl. `POST_NOTIFICATIONS` and runtime grant; tests `app/src/test/.../ExtractTest.kt` (Robolectric, `ShadowNotificationListenerService`), `app/src/androidTest/.../ReplyHandTest.kt`

**Interfaces (produces):**
- `fun extract(sbn: StatusBarNotification): Captured`; `data class Captured(val msgs: List<Msg>, val rawExtras: Map<String, String>, val replyActionIndex: Int?, val replyAuthRequired: Boolean)`. Conversation key = `sbn.notification.shortcutId` ?: `"${sbn.packageName}:${EXTRA_CONVERSATION_TITLE ?: sender}"`; messages from `EXTRA_MESSAGES` with sender, text, timestamp; `senderIsSelf` when the message person matches `EXTRA_MESSAGING_PERSON`; group from `EXTRA_IS_GROUP_CONVERSATION`; `FLAG_GROUP_SUMMARY` notifications skipped.
- `class NotificationReplyHand(listener: () -> MisuraListener?, override val channel: String) : Hand` — `id = "notification_reply:$channel"`, `lock = null`. `commit`: live notification for the recipient's conversation; missing notification/action, `replyAuthRequired` or `CanceledException` → `Unavailable`; sent → `Uncertain("dispatched")`. `check(attemptId, p, dispatchedAt)`: `Ok` only for a self message with exactly `p.content` and `sentAt >= dispatchedAt`; else `Uncertain`.

- [ ] **Step 1: Failing tests:** Robolectric: `messaging style keeps every message with timestamps` · `self messages flagged` · `summary notification skipped` · `auth required action reported`. Emulator: `reply reaches testapp receiver with exact text "Grüße 👋"` · `auth required gives unavailable` · `cancelled intent gives unavailable` · `check ignores identical older message` · `check confirms new self message`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest` and `./gradlew :app:connectedDebugAndroidTest` (emulator up, testapp installed, `adb -s emulator-5554 shell cmd notification allow_listener com.misura.phone/com.misura.phone.notify.MisuraListener`) → FAIL. **Step 3:** implement. **Step 4:** → PASS. **Step 5:** commit `feat(app): notification capture and reply hand`.

### Task 7: Room storage + recovery lifecycle

**Files:** `app/.../store/{MisuraDb.kt, Entities.kt, Daos.kt, Converters.kt, RoomTaskStore.kt, RoomInboxStore.kt}`, `app/.../work/{WorkingService.kt, BootReceiver.kt}`, `app/.../MisuraGraph.kt`; test `app/src/androidTest/.../StoreRecoveryTest.kt`

**Interfaces:** Consumes `TaskStore`, `InboxStore`. Produces `RoomTaskStore` (`claimAttempt` in `withTransaction`), `RoomInboxStore`; tables `tasks`, `approvals`, `attempts`, `expectations`, `seen_messages`, `answers` (payload/history as JSON). `MisuraGraph` holds one `Controller` on an app-lifetime `CoroutineScope(SupervisorJob())`; `MisuraApp.onCreate()` launches `recover()`; `BootReceiver` uses `goAsync()` and awaits the same single-flight `recover()`. `WorkingService` (`foregroundServiceType="specialUse"` with the `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` property; `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` permissions) runs while an attempt is `DISPATCHING`/`UNCERTAIN` or reconcile is pending, then stops.

- [ ] **Step 1: Failing emulator tests:** `file backed db reopen restores task payload history approval attempt expectation` · `claimAttempt is atomic under two coroutines` · `boot and app start recovering together run recovery once`.
- [ ] **Steps 2–4:** fail → implement → pass.
- [ ] **Step 5: Process death** — debug task with an `UNCERTAIN` attempt; note `pidof com.misura.phone`; `am kill`, relaunch, assert new PID and history shows a check, no commit. Repeat with `am force-stop`.
- [ ] **Step 6:** commit `feat(app): room storage, single-flight recovery`.

### Task 8: Hidden screen part 1 — Shizuku service and display sessions (emulator)

**Files:** `app/src/main/aidl/com/misura/phone/hidden/IShellService.aidl`, `app/.../hidden/{ShellService.kt, ShizukuBridge.kt, HiddenScreen.kt}`, `app/.../debug/HiddenScreenProbeActivity.kt`

**Interfaces (produces):**
- `data class DisplaySession(val sessionId: String, val displayId: Int, val createdAt: Long)`
- `interface HiddenScreen { suspend fun open(widthPx: Int, heightPx: Int, dpi: Int): DisplaySession; suspend fun launch(s: DisplaySession, pkg: String, activity: String? = null, deepLink: String? = null): Boolean; suspend fun taskDisplayOf(pkg: String): Int?; suspend fun close(s: DisplaySession) }`
- Implementation: Shizuku `UserService` bound with `Shizuku.bindUserService`, explicit unbind on close. Inside it, create the display as scrcpy's `NewDisplayCapture.java` does (shell-attributed context like `FakeContext`, the same API-level flag set, `ImageReader` surface, IME policy hidden). Launch via `am start --display <id>`. Guards: `displayId == 0` or unknown session → throw; after launch, `taskDisplayOf(pkg) != s.displayId` → close session, return false.

- [ ] **Step 1:** Install Shizuku on the emulator and start it over adb; grant Misura. Confirm the release includes the Android 17 fix for issue #1988 (or build from source); record version.
- [ ] **Step 2: Failing emulator tests:** `open returns non-zero display` · `launch testapp lands on hidden display` · `display zero rejected` · `stale session rejected after close` · `close destroys testapp task instead of moving it to display 0` · `service death invalidates session`.
- [ ] **Steps 3–4:** implement → pass. **Step 5:** commit `feat(app): shizuku hidden display sessions`.

### Task 9: Hidden screen part 2 — read, tap, type (emulator, fake chat)

**Files:** `app/.../hidden/{ScreenReader.kt (AccessibilityService), HiddenInput.kt, ChatScript.kt, TestChatScript.kt}`, `app/src/main/res/xml/misura_accessibility.xml` (`canRetrieveWindowContent`, `flagRetrieveInteractiveWindows`, `canPerformGestures`), `app/.../hands/HiddenChatHand.kt`

**Interfaces (produces):**
- `data class UiNode(val text: String?, val desc: String?, val viewId: String?, val bounds: IntArray, val clickable: Boolean, val editable: Boolean, val children: List<UiNode>)`
- `ScreenReader.read(s: DisplaySession): UiNode?` — `getWindowsOnAllDisplays()[s.displayId]`, application window, traverse root; null if missing. `ScreenReader.userIsInApp(pkg: String): Boolean` — package has a window on display 0.
- `HiddenInput.tap(s: DisplaySession, x: Int, y: Int): Boolean` (`GestureDescription.Builder.setDisplayId`); `HiddenInput.setText(s: DisplaySession, viewId: String, text: String): Boolean` (`ACTION_SET_TEXT`, re-read, compare).
- `interface ChatScript { suspend fun openChat(s: DisplaySession, recipient: String): Boolean; fun inputViewId(): String; fun sendButton(tree: UiNode): UiNode? }`
- `class HiddenChatHand(hidden: HiddenScreen, reader: ScreenReader, input: HiddenInput, override val channel: String, val pkg: String, script: ChatScript) : Hand` — `id = "hidden_chat:$channel"`, `lock = "hidden_screen"`. Re-reads and verifies the expected screen before each mutation; `userIsInApp(pkg)` → `Unavailable("user is in the app")`.

- [ ] **Step 1: Failing emulator tests (against `:testapp`):** `reads only the hidden display tree` · `setText "Grüße 👋" reads back exactly` · `tap send adds message to fake chat` · `unsupported field returns false` · `user opening testapp on main screen pauses hand` · `no IME window on display 0 during typing`.
- [ ] **Steps 2–4:** fail → implement → pass. **Step 5:** commit `feat(app): hidden screen read, tap, type`.

### Task 10: Experiment flow + end-to-end on emulator

**Files:** `app/.../debug/ExperimentActivity.kt`, `app/.../notify/AnswerReady.kt`

**Interfaces:** Consumes Tasks 4–9; reused in Task 15.

Flow: **Ask** card shows the exact request text and recipient (the bot) → **Ask** button = approval → expectation added → commit via notification-reply hand, else hidden-chat hand → "Waiting for answer" → answer complete (quiet period) → one "Answer ready" notification (buzz once; `buzzed` flag saved on the task) → card shows answer + draft reply to a chosen friend with exact text → **Send** button = approval → commit → card shows sent / checking / "not sure it went out". If locked and only the hidden-screen hand is available: card says "Waiting for unlock to send" and no attempt is claimed until unlock; an approval that expired meanwhile asks again.

- [ ] **Step 1: Failing emulator test** `full flow with testapp as bot and friend` — testapp auto-answers with a 3-message burst; assert one answer-ready notification, friend chat gets exactly one reply, task ends `DONE`, history has `WAITING` and two attempts.
- [ ] **Steps 2–4:** fail → implement → pass. **Step 5:** commit `feat(app): experiment flow on emulator`.

---

## Phase B — on the real phone

Each test writes `docs/spikes/<nn>-<name>.md`: date, Nothing OS build, security patch, app versions, Shizuku/root state, one pass/fail line per check, recording paths.

### Task 11: Day-one setup
**Files:** `docs/spikes/00-device-setup.md`
- [ ] Unlock the bootloader before setup (OEM unlocking → `adb -s <serial> reboot bootloader` → `fastboot flashing unlock`; wipes). Record build.
- [ ] Google account; Advanced Protection OFF; WhatsApp linked as companion; FriendA's WhatsApp as the test counterpart; DAVx5 with an iCloud app-specific password; Instinct moved to WhatsApp.
- [ ] Notifications ON, every channel Silent, hidden on lock screen (WhatsApp, Slack, Gmail).
- [ ] Install Misura + testapp; set home; grant notification access, accessibility, overlay; start Shizuku over wireless debugging.
- [ ] Verify contacts synced and a message from the second account shows in ProbeActivity. Commit.

### Task 12: Risk test 1a — WhatsApp on a hidden display via scrcpy
**Files:** `misura-app/spikes/hidden-display-scrcpy.sh`, `docs/spikes/01a-hidden-display-scrcpy.md`
- [ ] `scrcpy -s <serial> --new-display=1080x2400/420 --start-app=com.whatsapp --display-ime-policy=local --no-vd-system-decorations` (never `--no-vd-destroy-content`).
- [ ] Chat with the second account: type, send, filming the phone. Record: works on the hidden display · keyboard only there · closing scrcpy doesn't move WhatsApp to the real screen · a message into the **open** chat still notifies on the (3a) · delivered exactly once.
- [ ] If WhatsApp fails here, stop and ask the maintainer before Task 13. Commit.

### Task 13: Risk test 1b — WhatsApp through Misura's hidden screen
**Files:** `app/.../hidden/WhatsAppScript.kt`, `docs/spikes/01b-hidden-display-whatsapp.md`
- [ ] Implement `WhatsAppScript : ChatScript` from a real tree dump (search recipient, open chat, input, send).
- [ ] USB unplugged, filming: send to the second account via `HiddenChatHand`. Record Task 12's checks plus: umlauts/emoji read back · normal phone use unaffected · app killed mid-send leaves nothing on the real screen · screen off but unlocked · phone locked (expected risk) · Shizuku after reboot.
- [ ] If a step needs root: record it, root with Magisk on the exact-build `init_boot`, repeat only that step. Commit.

### Task 14: Risk tests 2+3 — companion notifications and Instinct
**Files:** `docs/spikes/02-whatsapp-companion-notifications.md`, `docs/spikes/03-instinct-whatsapp.md`
- [ ] From the second account: 1,500-character message, 5-message burst, an edit, a link; phone locked, chat muted. Compare captures with the chat (characters captured per case).
- [ ] Reply via `NotificationReplyHand` 5×, incl. after reading on the iPhone and after force-stopping WhatsApp. Record: action present · auth-gated? · exactly one message received · `check` confirmed it.
- [ ] Three real Instinct requests: reply time, messages per answer, full list + links captured, and the quiet period that would have completed all three (update the `AnswerCollector` default). Commit.

### Task 15: First decisive end-to-end experiment
**Files:** `docs/spikes/06-first-end-to-end.md`
- [ ] Task 10's flow with Instinct as the bot and the second account as the friend, filming: only Misura on screen throughout; lock after **Ask**; unlock on the buzz.
- [ ] Record: answer complete · friend got exactly one message · no app ever visible · history shows every state with evidence · which hand sent each message. Commit.

### Task 16: Risk tests 4+5 — survival and sleep screen
**Files:** `app/.../sleep/{SleepActivity.kt, WakeWatcher.kt, SleepOverlay.kt}`, `docs/spikes/04-task-survival.md`, `docs/spikes/05-sleep-screen.md`
- [ ] **Survival:** one `WAITING` task (expectation open) and one `OUTCOME_UNKNOWN` task; lock 30 min · battery saver · `am kill` (PID changed) · `am force-stop` · reboot then unlock. Record: tasks restored · recovery checked, never committed · **a reply from the second account still routes to the waiting task** after each.
- [ ] **Sleep screen:** `WakeWatcher` registered at runtime by a persistent `WorkingService` mode. Variant A: `SleepActivity` (`showWhenLocked`, no `turnScreenOn`) launched under the overlay-permission exception. Variant B: `SleepOverlay` as an accessibility overlay. Wake by power button, fingerprint, notification from: home · another app · app killed · after reboot + first unlock. Record per case: shown? delay? background-launch denials in logcat; system PIN still required past it.
- [ ] Report all spike results to the maintainer. Plan 2 is written from `docs/spikes/`.
