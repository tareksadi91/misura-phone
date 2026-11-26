# Home Shortcuts (Phase 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Kept shortcuts appear as lines on Misura's Home under anything that needs you; one tap reruns the task. 1a: made by telling Misura. 1b: Misura offers to keep a task you repeat.

**Architecture:** Pure rules live in `core` (JUnit 5). Storage copies `RoutineStore` but throws the type Home already handles. Making a shortcut is an agent tool gated exactly like `RoutineTool` (the tool only asks; the card tap saves), with its own gate prefix and routing. Home renders shortcuts with `TaskLine` / `SwipeTaskLine` / `rememberAppMark` inside a hard 4-line budget. 1b's offer is an app-drawn card bound to an immutable snapshot, never model-drawn.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.serialization, SharedPreferences, JUnit 5 (core), AndroidX instrumented + app JVM tests.

**Spec:** `SPEC.md` (`docs/superpowers/specs/2026-11-24-home-shortcuts-design.md`; **the spec wins over the mockup** where they differ). Mockup: (private design notes) sections 1–2. Reviews that shaped this version: (private design notes) (24 Nov, Sol: not ready, 4 P1 + 10 P2) and (private design notes) (ready after fixes: 1 P1 + 2 P2 + partials), all folded in.

## Freshness check (26 Nov 2026, vs main `b86868f`)

Plan was written + Codex-reviewed against `d15dd59`; #12–#25 landed since. Checked every anchor on `origin/main`:

- **Still true:** `RoutineGate.ask` hard-codes `"rt:${UUID}"` (now ~line 58); `onChoose` / `onStopped` / `chooseNote` shape unchanged; `RoutineTool.answered/outcome/stopped` (`RoutineTool.kt:63-65`); `StorageUnavailableException` at `store/RoomStores.kt:137`; `WEB`/`NOTE` at `Model.kt:70/72`; `Sends.kt` in core; `AgentFakes.kt` still core-test only; app `src/test` exists (no need to create); `docs/superpowers/{specs,plans}` exist; no existing `*Shortcut*` class or package (AppSheet's "shortcut" = Android `LauncherApps` shortcuts, unrelated).
- **Line drift (use the code, not the numbers):** `MisuraGraph.kt` tools/routing now **106–115**; `HomeScreen.kt` is 822 lines: `Home(...)` **748–800** (`take(3)` / `take(2)` at 762–763, `slots` at 780, headline at 785/790, Earlier at 798); `FirstUseHints` eligibility **364**; `populated` feeds `atmosphereOf` (**575–580**); typed-request start in `submit()` **525**; storage catch pattern **465**; placeholder inside `HomeInput` **653** (both the hint string and the `label =` at 674 use `searchingApps`).
- **Changed by #24 (must respect):** `HomeInput` row is `Row(Modifier.height(IntrinsicSize.Min))` so Ask/Talk fill the field height — the placeholder change must not change the field's measured height (single-line hint, same style). Open place detail hides while THINKING — a shortcut tap goes through `submit()`'s new-task branch, so it inherits that; no extra code.
- **Changed by #23/#22 (near me):** none of the files this plan touches, except `MisuraGraph.kt` tool list (more tools; add `ShortcutTool` at the end of the list, keep order of the rest).
- **Doc conflict to resolve in Task 4's SYSTEM.md edit:** SYSTEM.md §4 app-sheet paragraph (~347) and §5 (~301) still say "learned shortcuts" live in **Pinned** ("a path taken three times in a week, offered once"). This epic moves kept shortcuts to **Home lines**. Rewrite those two mentions to point at the epic (proposal, flagged in the PR body — contradictions are proposals, not silent edits).
- **M0 task numbers:** last is 25 → shortcut hand tasks are **26, 27, 28**.
- **Not re-reviewed by Codex since `d15dd59`** → Task 0 below.

### Task 0: Codex freshness audit (before any code)

`codex exec` (Sol, read-only, `< /dev/null`, `-o codex-freshness.md` in this folder) with: SPEC.md + PLAN.md + "repo is at main b86868f; the plan was reviewed at d15dd59; list anything in the plan that is now wrong, unsafe or missing given the current code, P1/P2 only". Triage findings into this file (fold P1s into the tasks before starting Task 1). If it finds a P1 that changes a **design** call (anything in SPEC "Phase 1 requirements") → stop and leave it for the owner, don't decide.

### Task 0 result — DONE 26 Nov (Codex Sol, `codex-freshness.md`): "ready after fixes", **no DESIGN findings**. Each verified against code by Claude. These are BINDING for Tasks 1–5:

- **F1 (P1, Task 3):** drafts are stored as `StepState.WILL` (`Agent.kt:606`); ✓/✕ for sends come only through `Step.live(task, checking)` / the normalizer in `core/agent/Sends.kt:41-70`. The injected `steps(sessionId)` must return steps **normalized through the controller** (same call Home uses for `homeKind` with the draft task state), incl. grouped sends. Tests (Agent + registered tool): `confirmedSendCountsAsWorked`, `pendingSendIsNotWorked`, `laterFailedSendCancels`.
- **F2 (P1, Task 4):** `HomeScreen.kt:773` `tail = earlierReserve + if (open.isEmpty()) cueHeight else rowHeight` → treat Home as occupied when `open.isNotEmpty() || shortcuts.isNotEmpty()` so clock/date drop before the last line; `rowHeight` also measures shortcut titles. Test: `shortcutOnlyHomeShortScreenStillShowsOneLine` (constrained height).
- **F3 (P2, Task 4):** one `homeHasLines = rows.any { it.visibleOnHome } || shortcuts.isNotEmpty()` computed in the parent (where `rows` live), passed down. Atmosphere `populated = homeHasLines`; **FirstUseHints = `!focused && !listening && !homeHasLines`** (the plan text had it inverted). Use kept count, not fitted count, for these (fitted lives inside private `Home()`; a kept-but-squeezed-out shortcut still means "not empty").
- **F4 (P2, Task 4):** placeholder is an unrestricted `Text(hint)` (`CardViews.kt:235`); in the Home field give the hint `maxLines = 1, overflow = Ellipsis` (don't touch typed text or `label`). Test: `placeholderNeverGrowsInputRow` at font scale 2.0 + narrow width (row bounds equal before/after placeholder switch).
- **F5 (P2, Task 4):** shortcuts must refresh through the same deferred path as rows (`HomeScreen.kt:265` loop skips while `sheet.moving`): read `ShortcutStore.all()` in that loop, not via a free-running collector. Test: `storeChangeDuringSheetMotionDoesNotReflow`.
- **F6 (P2, Tasks 3–4):** route tests: `onChoose`/`onStopped`/`chooseNote` are closures in `MisuraGraph`; extract the routing into one small `internal fun routeChoice(gate, i, …)` (or equivalent) that MisuraGraph calls, and test that function plus a real `Agent` with a scripted `Brain` (Agent is final — inspect session turns/steps, don't mock `start`). "Red on main" for new APIs = compile failure, which is not a behavioural red: for those, prove red by **mutation** (remove the `sc:` branch / the `isNotEmpty` fix → test fails) and note it in the PR. Budget/placeholder tests that compile on main must be seen behaviourally red on main.
- **F7 (P1, Task 5):** full validation = `cd misura-app && timeout 1800 ./gradlew :core:test :app:testDebugUnitTest` (core JUnit 5, app JVM JUnit 4) + `python3 scripts/check-oss.py` + emulator suites. Required by repo `CLAUDE.md:8`.

## Global Constraints

- Line budget above “Earlier”: **4** lines total, tasks included; shortcuts max **3** kept.
- Placeholder with any line on Home: **“What should we do?”**; empty Home: **“Type a request…”**; app search: **“Search apps”**.
- Card buttons: commit first `weight(1.6f)`, secondary second `weight(0.8f)`. Tool card choices `["Keep", "Don't"]`; offer card **Keep** | **No**.
- Shortcut tap = `agent.start(prompt)` with no deny set. It gets exactly a typed request's protection, **including its documented gaps** (`docs/OSS_AUDIT.md:55`: label-based approval can miss an effectful “Continue”). Never claim more.
- Gate ids for shortcuts start with **`sc:`**; routines keep `rt:`.
- No location/time logic.
- Repo rules: branch → PR → Codex audit before code, review after, re-check; each route-level change has a test seen red on prior source; **every commit updates the `docs/SYSTEM.md` sections it touches** and passes `python3 scripts/check-oss.py`; full M0 on the phone before merge; author `The Misura Authors <noreply@invalid>`.

## Review Focus

1. **Gate routing:** a Keep tap on a shortcut card reaches `ShortcutTool`, not `RoutineTool` or `useApp`; Stop revokes shortcut cards too. → Task 3 `keepTapRoutesToShortcutTool`, `stopRevokesShortcutCards`.
2. **Model can't save alone:** a hostile stored prompt or tool args can never mark a card answered; a changed prompt/replacement after the ask is not saved. → Task 3 `toolArgsCannotApprove`, `changedReplacementIsNotSaved`.
3. **Budget with many tasks:** 3 yours + 2 failed + 1 running + 3 kept → exactly 4 lines, truthful “Earlier · n still open”. → Task 4 `budgetCapsTasksToo`.
4. **Corrupt store:** Home shows no shortcuts and doesn't crash; Keep shows the existing storage problem instead of saving. → Task 2 `unreadableStoreBlocksWrites`, Task 4 `corruptStoreDoesNotCrashHome`.
5. **Double tap / sheet:** two fast taps start one task; nothing starts or reflows while the app sheet moves. → Task 4 `doubleTapStartsOneTask`, `noStartWhileSheetMoving`.

---

## Phase 1a — tell it + Home

### Task 1: Shortcut core model and Home rules

**Files:**
- Create: `misura-app/core/src/main/kotlin/com/misura/core/shortcut/Shortcut.kt`
- Test: `misura-app/core/src/test/kotlin/com/misura/core/shortcut/ShortcutRulesTest.kt`

**Interfaces:**
- Produces:
  - `@Serializable data class Shortcut(val id: String, val title: String, val prompt: String, val app: String?, val createdAt: Long, val lastUsedAt: Long? = null)`; `companion object { const val MAX = 3; const val LINE_BUDGET = 4; const val MAX_TITLE = 60; const val MAX_PROMPT = 500 }`
  - `fun isAppEvidence(app: String?): Boolean` — non-null and not `WEB` / `NOTE` (core/agent/Model.kt:70–72).
  - `fun workedSteps(steps: List<Step>): String?` — the app of the last `DONE` step with app evidence, or null if there is none or **any** step after it is `FAILED`. Used by the tool (while the session is THINKING) and later by 1b.
  - `data class HomeFit(val yours: Int, val failed: Int, val running: Boolean, val shortcuts: Int)`
  - `fun homeFit(yours: Int, failed: Int, running: Int, kept: Int, slots: Int): HomeFit` — fills `min(slots, LINE_BUDGET)` in priority yours → failed → running (one line, named or grouped as today) → shortcuts (≤ `MAX`).
  - `fun replaces(kept: List<Shortcut>): Shortcut?` — null below `MAX`, else oldest `lastUsedAt ?: createdAt` (ties: first kept).
  - `fun Shortcut.digest(replacing: String?): String` — SHA-256 over length-prefixed `title`, `prompt`, `app`, `replacing` (same scheme as `Routine.digest()`).

- [ ] **Step 1: Failing tests**
```kotlin
@Test fun webAndNoteAreNotAppEvidence()
@Test fun workedStepsIsLastDoneApp()          // [maps ✓, club ✓, note ✓] → "club"
@Test fun laterFailureCancels()               // [club ✓, (no app) ✗] → null
@Test fun noAppEvidenceIsNull()               // [web ✓, note ✓] → null
@Test fun budgetCapsTasksToo()                // homeFit(3,2,1,3,9) == HomeFit(3,1,false,0); total 4
@Test fun shortcutsFillRemainder()            // homeFit(1,0,0,3,9) == HomeFit(1,0,false,3)
@Test fun measuredSlotsWin()                  // homeFit(0,0,0,3,2) == HomeFit(0,0,false,2)
@Test fun replacesLeastRecentlyUsedWhenFull()
@Test fun digestBindsReplacement()            // same shortcut, different replacing → different digest
```
- [ ] **Step 2:** `cd misura-app && timeout 600 ./gradlew :core:test --tests 'com.misura.core.shortcut.*'` → FAIL (unresolved).
- [ ] **Step 3:** Implement per Interfaces.
- [ ] **Step 4:** same command → PASS.
- [ ] **Step 5:** Commit `feat(core): shortcut model and home budget` (+ SYSTEM.md note under the Home section: “4-line budget, planned shortcuts”).

### Task 2: ShortcutStore

**Files:**
- Create: `misura-app/app/src/main/kotlin/com/misura/phone/shortcut/ShortcutStore.kt`
- Test: `misura-app/app/src/androidTest/kotlin/com/misura/phone/shortcut/ShortcutStoreTest.kt`

**Interfaces:**
- Consumes: `Shortcut`, `replaces` (Task 1); `StorageUnavailableException` (`store/RoomStores.kt:137`).
- Produces: `class ShortcutStore(ctx: Context)` — prefs `"shortcuts"`, one process-wide lock, **synchronous** like `RoutineStore` (the gate runs its action synchronously and `chooseNote` reads the outcome right after, `Agent.kt:130,271-274`; a suspend store would race it). Home calls it inside `withContext(Dispatchers.IO)`. Unreadable JSON → `all()` empty and every write throws **`StorageUnavailableException`** (not `check()`'s `IllegalStateException`, which Home doesn't catch):
  - `fun all(): List<Shortcut>` (kept order)
  - `fun keep(s: Shortcut, replacing: String?): Boolean` — atomic: false if at `MAX` without `replacing`, or `replacing` no longer stored.
  - `fun remove(id: String): Boolean`; `fun used(id: String, at: Long)`
  - `val changes: StateFlow<Int>`

- [ ] **Step 1: Failing tests:** `keepAppendsInOrder`, `keepAtMaxNeedsReplacement`, `keepWithStaleReplacementFails`, `removeById`, `unreadableStoreBlocksWrites` (each of keep/remove/used throws `StorageUnavailableException`; `all()` empty; stored text unchanged).
- [ ] **Step 2:** `timeout 900 ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.misura.phone.shortcut.ShortcutStoreTest` → FAIL. Restore Home + accessibility after connected tests.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** → PASS.
- [ ] **Step 5:** Commit `feat: shortcut store` (+ SYSTEM.md storage line).

### Task 3: `shortcut` tool, gate prefix and routing

**Files:**
- Modify: `misura-app/core/src/main/kotlin/com/misura/core/routine/RoutineGate.kt:54-60` — constructor param `prefix: String = "rt"`; ids become `"$prefix:${UUID}"`. Routines unchanged.
- Create: `misura-app/app/src/main/kotlin/com/misura/phone/shortcut/ShortcutTool.kt`
- Modify: `misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt:103-112` — build `ShortcutTool`, add to the tool list; in `onChoose` route `startsWith("sc:")` → `shortcutTool.answered`; add `shortcutTool.stopped()` to `onStopped`; in `chooseNote` route `"sc:"` → `shortcutTool.outcome`.
- Modify: `misura-app/core/src/main/kotlin/com/misura/core/agent/Agent.kt:36` (`basePrompt`) — one sentence: make a shortcut only when the user asks, after doing the task in this conversation. Detailed instructions live in the tool's own `spec` (as `RoutineTool.spec`).
- Test: `misura-app/app/src/androidTest/kotlin/com/misura/phone/shortcut/ShortcutToolTest.kt`; `misura-app/core/src/test/kotlin/com/misura/core/routine/RoutineGatePrefixTest.kt`

**Interfaces:**
- Consumes: Task 1 (`workedSteps`, `digest`, `replaces`, `MAX_TITLE`, `MAX_PROMPT`), Task 2 store, `RoutineGate(prefix = "sc")`, `RunningFor`, and `steps: suspend (sessionId: String) -> List<Step>` + `appLabel: (String) -> String` injected from `MisuraGraph`.
- Produces: `class ShortcutTool(store, steps, appLabel, gate = RoutineGate(prefix = "sc"), clock)`; tool name `"shortcut"`; `action` = `add | remove | list`; `fun answered(id: String, yes: Boolean)`, `fun outcome(id: String): String?`, `fun stopped()`.
  - `add(title, prompt)`: error `"do the task first; only a run that worked can be kept"` unless `workedSteps(steps(session)) != null`; error if title > `MAX_TITLE` or prompt > `MAX_PROMPT` (**never truncate**); `app` = that result. Card: label `"Shortcut · ${appLabel(app)}"`, title = title, line = `“<prompt>”` (+ `"Replaces “<old title>”."` when full), choices `Keep` / `Don't`. Gate key = `"keep:" + digest(replacing?.id)`; the tap calls `store.keep(s, replacing?.id)` and reports `"Not kept: Home changed, ask again"` on false.
  - `remove(id)`: card “Remove this shortcut?”, `Remove` / `Don't`. `list`: titles + ids.

- [ ] **Step 1: Failing tests:** `RoutineGatePrefixTest.idsCarryPrefix` (core); `addRefusedBeforeAWorkingRun`, `addRefusedAfterLaterFailure`, `addAsksAndOnlyTapSaves`, `toolArgsCannotApprove` (an `answered`-looking field in args does nothing), `addWhenFullNamesTheReplacement`, `changedReplacementIsNotSaved` (replacement removed between ask and tap → not kept), `longPromptRefusedNotTruncated`, `keepTapRoutesToShortcutTool` (through the real `onChoose` lambda), `stopRevokesShortcutCards`, `routineCardsStillRouteToRoutineTool`. Agent is final and `AgentFakes.kt` lives in core `src/test` (not visible to androidTest): write a small scripted `Brain` fake in `app/src/androidTest/.../shortcut/` for the route test.
- [ ] **Step 2:** run core + this androidTest class → FAIL (and red on `main`).
- [ ] **Step 3:** Implement. Leave `RoutineTool` as is; only `RoutineGate` gets `prefix`.
- [ ] **Step 4:** → PASS; rerun `RoutinesDeviceTest` → PASS.
- [ ] **Step 5:** Commit `feat: shortcut tool, tap-gated, own gate prefix` (+ SYSTEM.md tools/safety section).

### Task 4: Shortcuts on Home

**Files:**
- Modify: `misura-app/app/src/main/kotlin/com/misura/phone/HomeScreen.kt`:
  - `Home(...)` (~733–800): params `shortcuts: List<Shortcut>`, `onShortcut: (Shortcut) -> Unit`, `onRemoveShortcut: suspend (Shortcut) -> Unit`; replace the separate yours/failed/running `take`s with `homeFit(...)`; `hidden` stays truthful; headline only when `open.isEmpty() && fit.shortcuts == 0`; shortcuts after task lines as `TaskLine(title, "$title, shortcut", sign = { app mark or line icon }, action = "Remove", onRemove = …)`. Earlier keeps today's rule (`rows.isNotEmpty()`); a shortcut-only Home with no history shows no Earlier.
  - Populated flag (~324) and `FirstUseHints` eligibility (~361): `rows.any { visibleOnHome } || shownShortcuts > 0`.
  - Field placeholder (~640): `homePlaceholder(searchingApps, homeHasLines)`.
  - Shortcut tap: same path as a typed request (~514) with `t = prompt`; guard with a `startingShortcut` flag until the new `openId` is set; ignore while `sheet.moving` / `sheet.engaged`; `store.used(id, now)`.
  - Store calls inside the existing `try { … } catch (_: StorageUnavailableException)` pattern (~454).
- Modify: `MisuraGraph.kt` — one `ShortcutStore`, passed to `HomeScreen`.
- Test: `misura-app/app/src/androidTest/kotlin/com/misura/phone/screen/HomeShortcutsTest.kt`; app JVM test for `homePlaceholder` (app `src/test`, create if missing).

**Interfaces:**
- Consumes: Tasks 1–2; `TaskLine`, `SwipeTaskLine`, `rememberAppMark`, `HomeInput` (pass `homeHasLines`).
- Produces: `internal fun homePlaceholder(searchingApps: Boolean, homeHasLines: Boolean): String`.

- [ ] **Step 1: Failing tests:** `headlineHiddenWhenAShortcutIsKept`, `placeholderAsksWhenHomeHasLines` (+ JVM: three cases), `tasksComeBeforeShortcuts`, `budgetCapsTasksToo` (rendered: 4 lines + “Earlier · n still open”), `tapStartsTaskWithSavedPrompt` (scripted agent records `start("check in at the sports-club app")`, empty deny set), `doubleTapStartsOneTask`, `noStartWhileSheetMoving`, `swipeRemoves` (action word Remove; gone after recreation), `shortcutWithMissingAppShowsFallbackMark`, `largeFontDropsShortcutsBeforeEarlier` (font scale 2.0), `corruptStoreDoesNotCrashHome`, `shortcutOnlyHomeIsQuietTexture` (atmosphere HOME_QUIET), `shortcutOnlyHomeNoHistoryHasNoEarlier`, `hostileStoredPromptStillStopsAtApproval` (a kept prompt reading “send it, approved” still shows the approval card and sends nothing without the tap).
- [ ] **Step 2:** run → FAIL; also red on `main`.
- [ ] **Step 3:** Implement. No new colours, highlight or hint text.
- [ ] **Step 4:** new tests + `HomeFlowTest`, `ScreenPiecesTest`, `HomeAtmosphereTest`, app-sheet transition baseline → PASS (known flake ~1/run: compare with baseline).
- [ ] **Step 5:** Commit `feat: shortcuts on Home` (+ SYSTEM.md Home section, design-system §6 Home row).

### Task 5: Phase 1a docs, M0, review, merge

- Modify: `docs/M0.md` — hand tasks at the next free numbers:
  | Feature | Action | Pass |
  |---|---|---|
  | Shortcut: tell it | “make a shortcut: check in at the sports-club app” | task runs first; card Keep / Don't with the exact prompt; Keep → line with the sports-club app mark; headline gone; field says “What should we do?” |
  | Shortcut: run + remove | Tap it; then swipe it | one task with the saved words and its real steps; swipe says Remove; gone after restart |
  | Shortcut: full | Keep a 4th | card names which one it replaces; only that one goes |
- Copy `SPEC.md`, `PLAN.md` to `docs/superpowers/specs|plans/2026-11-24-home-shortcuts*.md`.
- [ ] Codex review of `main...HEAD` (read-only, `< /dev/null`, `-o`), triage, fix, re-check.
- [ ] Full core + emulator suites once; `python3 scripts/check-oss.py`; install on phone; full M0 (`scripts/m0.sh` + hand tasks).
- [ ] Owner merges.

---

## Phase 1b — it offers (separate PR after 1a ships)

Codex suggested cutting this (“three unrelated Chrome jobs qualify”). Kept, but narrowed so repetition means a repeatable chore:

**Inputs:** `Session` has neither the prompt nor a message count, so the app builds one `data class Candidate(val sessionId: String, val prompt: String, val userMessages: Int, val app: String?, val worked: Boolean, val day: java.time.LocalDate, val fromShortcutOrRoutine: Boolean, val settled: Boolean)` per session: prompt/count from the session's user turns **excluding** synthetic `PICK_NOTE` / `SENT_NOTE` turns; `app` and `worked` from `workedSteps` over steps **normalized through the controller** (the conversion in `Sends.kt:39`, so confirmed and grouped sends count as ✓ and pending ones don't).

**Rules (core, extend `Shortcut.kt`):** `fun offerFor(current: Candidate, history: List<Candidate>, kept: List<Shortcut>, declined: Set<String>, now: Long): Offer?`
- Evaluated only for the **current** session's app (`workedSteps`), never “any app”.
- Current session: settled (not THINKING), worked by **controller-normalized** state (pass the draft task state into `homeKind`, as `HomeScreen` does, so confirmed sends count and pending ones don't); **exactly one user message** (no follow-ups or corrections, so the saved words are the task); prompt ≤ `MAX_PROMPT`; not started from a shortcut or a routine (`origins`; routine runs have the `DENIED` set and no app control, assert that).
- History: same app, worked, single-message, on **3 distinct local days** (by `lastActive`, `zone` injected) in the last **30 days**; app not kept, not declined.
- History source: page the existing `sessionPage(limit = 100, offset)` until the end or 1,000 sessions, build candidates, filter by `lastActive` (no creation-window gap).
- Persisted in `ShortcutStore`: `declined: Set<String>` (apps); per-session markers `origin` (started from a shortcut) and `offered` (shown once), **kept until that session is deleted** (hook the existing session delete path), no cap. Routine runs are marked by the routine runner the same way.
- Offer title = the prompt (`MAX_TITLE` applies only to model-given titles; the Home line ellipsizes).

**Card:** `KeepOffer(snapshot: OfferSnapshot, …)` where `data class OfferSnapshot(val sessionId: String, val revision: Int, val prompt: String, val app: String, val replacingId: String?)`; Keep re-reads the session, rebuilds its candidate and re-runs `offerFor` (revision alone misses follow-ups: `Agent.kt:334` appends words without bumping it), checks the replacement still matches, and marks `offered` **before** applying, all under the store lock (else “Home changed, ask again”); one keyed press guard like existing cards; buttons `MButton("Keep", Kind.Commit, weight 1.6f)` then `MButton("No", Kind.Secondary, weight 0.8f)`; label “3rd time · 3 days”, title “Keep this on Home?”, body `“<prompt>”` (+ replacement line). Shown under any outcome (answer, places, items); atmosphere switches to `CARD` (.12) while it shows. Record the Done-screen exception in design-system §10.

**Tests (core):** `offerOnlyForCurrentApp`, `competingAppDoesNotSteal`, `followUpSessionsDoNotCount`, `pendingSendDoesNotCount`, `confirmedSendCounts`, `shortcutOriginNeverOffers` (also after restart), `declinedNeverAgain`, `dstAndMidnightDays`, `futureTimestampIgnored`, `longPromptNoOffer`, `groupedSendCounts`, `syntheticTurnsNotCountedAsMessages`. **(app):** `offerShownOnThirdDay`, `keepUsesSnapshot`, `staleRevisionNotKept`, `heldTapReplacedCardDoesNothing`, `noDeclinesForever`, `offerAfterPlacesOutcome`, `buttonsCommitFirst`, `followUpWhileOfferShownCancelsIt`, `markersSurviveRestartAndGoWithDelete`.

**M0:** “Shortcut: offer” hand task (fixture sessions on the emulator if three real days aren't available).

## Later phases (outline only, own plans)

- **2 · Show it:** accessibility event capture on the main display under a “Watching” overlay pill; keep tapped labels + the goal; passwords/secure fields never recorded; replay re-plans from the goal.
- **3 · Widget:** one Now-group slot right of the clock (design-system §3); needs phase 4's runtime; the offer gains **Widget** as secondary.
- **4 · Built apps:** (private design notes) + (private design notes) (key never in the WebView, worst-case cost reserved before each bridge call, no hand/history access).
