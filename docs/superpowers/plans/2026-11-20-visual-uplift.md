# Misura Visual Uplift Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Native execution was authorized on resume and is implemented locally. See SYSTEM §5 for validation and delivery status; personal-phone installation, push and publication remain separate actions.

**Goal:** Carry the reviewed ASCII identity into the existing native app through a small, coherent visual change set, preserving working behavior.

**Architecture:** Update existing shared Compose roles and production screen/card functions. Add one home-only ASCII drawing component and a small first-use guidance component if needed. Reuse existing state, voice level, app-sheet motion, action ownership and persistence patterns; do not create a universal renderer or redesign navigation.

**Tech Stack:** Existing Kotlin/Jetpack Compose app, bundled fonts, Compose Canvas, current JVM/Android test harnesses. No new rendering dependency.

**Spec:** `../../SYSTEM.md` §5, “Native visual uplift implementation checklist”; `../../design/design-system.md`, “20 Nov visual adoption candidate”. SYSTEM owns scope and completion status. This plan contains execution steps, not another release backlog.

**State:** Execution authorized after revision27 acceptance, against source `31e69e9`. V0–V5 implemented locally; final verification/evidence and remaining hardware limits are tracked in SYSTEM §5. HTML system revision 26 was reviewed; revision27 corrections are now accepted for scoped native adoption, with native evidence required before approval-layout changes. Candidate selection and the synthetic native baseline are complete; retain the original baseline and reviewed constraints when resuming. Earlier web experiments are superseded, not implementation requirements.

## Global constraints

- Preserve direct typing, keyboard appearance, shared app-search field, held physical side-key input, existing seven bars plus Listening label, draft/focus preservation, Edit, spoken-go/Undo and exact approval binding.
- Reuse `M.gutter`, `M.m`, `M.p`, `M.gap`, `K`, `Number.u`, `Number.s` and runtime `LocalRadii`. Native uses `K = 1.5f`; never copy small preview px into dp/sp. App-sheet row/icon rules already exist separately; retain them.
- Preserve app-sheet finger following, IME ownership, Back, scroll, pin/shortcut menus, first-result launch and settings entry. Mock alignment defects do not justify rebuilding these.
- **Explicit maintainer instruction: existing swipe-down app-list reveal already looks good and is frozen.** Do not edit `APP/screen/AppSheet.kt`, `AppSheetMotion.kt`, `SheetKeyboard.kt` or `AppSheetLogic.kt`. In `HomeScreen.kt`, preserve app-sheet state identity, gesture modifiers, progress-driven transforms/opacity, clipping and inset/focus/keyboard ownership. No spring/easing/timing/drag-distance/row-lighting changes. Observe existing state; do not introduce a second transition or keyboard owner. If new decoration/type/layout causes jank or changes the reveal, correct that new work. Any necessary exception needs a concrete reproduced regression and separately agreed scope.
- Scope visual updates to production functions. Legacy generic `CardView` is a debug path, not the production renderer to refactor.
- Keep real required provider/photo/map attribution and current secret masking, screenshot protection and credential behavior.
- ASCII is decorative, excluded from accessibility semantics, absent behind task reading/approval, static when system motion is disabled and stopped when its surface is not visible.
- No new player integration, generated-widget runtime, incoming-card feed, sleep/lock behavior, provider work or action framework in this rollout.
- Keep existing unrelated edits and untracked helpers. One Gradle process at a time; name an explicit emulator/device serial for device commands. No provider calls, real sends, calendar writes, physical-phone installs, push or publication as a side effect of visual tests. After implementation authorization, fixture/app/test APK installation on the explicitly selected isolated synthetic emulator is part of native testing; personal-phone installation remains a separate delivery action. Do not use an emulator containing real accounts, chats or configured provider access.
- Prefix JVM/combined Gradle commands with `MISURA_TOKEN=` so inherited credentials cannot enable `LiveEval`. Do not inspect credential values, obtain tokens or pass live instrumentation tokens. Expected live-test skips must remain visible in evidence; a full test task alone is not an offline guarantee.

## Review focus

1. Large fonts and long/grouped drafts: every exact message and its recipients remain reachable, with no association lost during scrolling. Exercise in V4.
2. Hold voice during an existing typed draft and app search: release/cancel restores the correct draft, focus and input owner without invisible edits or unintended submission. Exercise in V1/V3.
3. Home task count changes while hints or ASCII are active: task links and Earlier remain readable, reachable and truthful. Exercise in V2/V5.
4. Motion disabled, app backgrounded, task opened or screen switched off: decoration stops drawing; real working/listening status remains. Exercise in V3.
5. Partial sends contain both unknown and known-unsent recipients: label matches the current retry subset and no confirmed send is prepared again. Exercise in V4 without a real send.

## File ownership map

Paths are relative to the project root unless expanded below. `APP` below means `misura-app/app/src/main/kotlin/com/misura/phone`; `AT` means `misura-app/app/src/androidTest/kotlin/com/misura/phone`.

| Files | Responsibility / intended reach |
|---|---|
| `APP/cards/CardViews.kt`; `misura-app/app/src/main/res/font/`; `THIRD_PARTY_NOTICES.md` | Shared `M`, `T`, `ST`, `Field`, font families and notices. Preserve sizing and radius machinery. |
| `APP/HomeScreen.kt` | Empty/populated home composition, headline/date/Earlier, atmosphere placement, existing input/voice and outcome rendering integration. |
| Read only: `APP/screen/AppSheet.kt`, `AppSheetMotion.kt`, `SheetKeyboard.kt`, `AppSheetLogic.kt` | Protected existing reveal/list/keyboard behavior. Baseline and diff-check only; no implementation edits in this uplift. HomeScreen integration is equally protected. |
| Create `APP/screen/HomeAtmosphere.kt` | Home-only drawing and visibility/motion lifecycle. No input or task execution ownership. |
| `APP/screen/PlaceCards.kt`, `ItemCards.kt`, `PlaceMap.kt`, `Docked.kt` | Existing media/card hierarchy, map contrast, optional images and context affordances. |
| `APP/screen/QuestionCard.kt`; relevant `HomeScreen.kt` branches | Approval constraints and recovery action labels, only where native evidence requires change and candidate is accepted. |
| Create `APP/screen/FirstUseHints.kt` if UI extraction is needed | Small centered swipe cue and supported-key hint, with local once-only acknowledgment. No tour or replacement setup. |
| `AT/HomeFlowTest.kt`, `AT/screen/PlaceCardsTest.kt`, `AT/screen/ScreenPiecesTest.kt`; existing app-sheet, voice, side-key and approval tests | Regressions of behavior affected by styling. Add focused tests for new state only; do not assert decorative pixel constants in unit tests. |
| `docs/SYSTEM.md`, `docs/design/design-system.md`, generated local views | Contract, task status and review evidence move with implementation. |

## V0: Freeze one candidate and capture native baseline

**Deliverable:** Agreed delta map, with baseline screenshots and no silent promotion of mock concepts.

- [ ] Review revision 26 plus the revision 27 correction proposal. Record which proposed corrections are accepted. Resolve exact native font roles and whether preview ground/secondary-ink differences should carry over; do not silently replace global `M` colors.
- [ ] Capture current native fixture views: empty/populated home, Earlier, app list/search/keyboard, working, approval, listening/docked, done, place/map, item and settings. Use synthetic content. Also record accepted swipe reveal: slow full drag, short flick, mid-drag reversal/cancel, close/Back, keyboard rise/fall, list scroll dropping IME and system motion off. Preserve source fingerprints for the four protected files. If a native scenario already works, mark preserve/verify instead of inventing a change.
- [ ] Record final reference snapshots, source revision, screen size/density/font scale, changed roles and open limitations alongside execution evidence. Update SYSTEM status. No code work while material contract choices remain unresolved.

## V1: Shared fonts, surfaces and prompt

**Files:** `CardViews.kt`, bundled font resources/notices; existing `HomeFlowTest.kt` input flows and `screen/SheetKeyboardTest.kt` where behavior is affected.

**Interfaces:** Preserve `Field(...)` call contracts and `M`/`T`/`ST` role ownership. Add bundled Jost and IBM Plex Mono families only after V0 role/notice review; keep Hanken for messages, choices, controls and reading.

- [ ] Map Jost to accepted display/card-title roles and Plex to accepted metadata/clock/ASCII roles. Preserve native size scaling; check long names and fallback scripts. Do not replace all body copy or app icons with mono text.
- [ ] Add chosen subtle input edge to the existing sunk `Field`, using runtime tile shape; keep cards solid without that edge. Give focused state a stronger neutral edge. Revision 27 reference is `#aaa49b` at 2 web px, not a mandate to hard-code that thickness in dp.
- [ ] Visually compare all shared-role callers, including settings, docked context, long answers, options, sending/disabled/selected states and app search. Fix verified regressions at the shared role, not per-screen padding patches.
- [ ] Run existing input/edit/search tests. If native listening allows harmful invisible edits, first add `holdingVoicePreservesDraftAndRestoresVisibleInput` regression, then make the smallest correction that retains mounted state/focus/IME behavior. Opacity alone in HTML is not evidence to disable or remount the native field.
- [ ] Update design role mapping, changed files, screenshots and SYSTEM status; review this slice before adding motion.

## V2: Home composition

**Files:** `HomeScreen.kt`, existing `HomeFlowTest.kt`.

**Interfaces:** Keep `Home(...)`, `TaskLine(...)` task ownership and current Earlier routing/count semantics. Reuse current layout gutters and task precedence.

- [ ] Add “What should we do?” in the accepted empty-home composition; pair field with “Type a request…”. Preserve app-search “Search apps” and existing conditional Earlier behavior. Do not create an empty Earlier link when native has no history.
- [ ] Format clock/date from actual locale/time. Date should have the prominence selected in the mock without copying 12 web px into native sp or hard-coding its example date.
- [ ] Verify zero tasks, ready/failed/running tasks, multiple running tasks and hidden-open count. Headline must yield before task rows crowd; task links and Earlier remain left aligned on the existing rail.
- [ ] Compare protected reveal against baseline immediately after changing home composition. Keep its existing HomeScreen transforms and state wiring verbatim; do not modify them to fit new headline/date/background. Fix new content layout if it alters list/input geometry or gesture response.
- [ ] Use existing HomeFlowTest fixtures to cover `emptyHomeOffersDirectInput` and `populatedHomeRetainsEarlierAndTaskRouting`; preserve dismiss versus permanent delete and existing accessible actions. Update SYSTEM and screenshot evidence.

## V3: ASCII atmosphere, then live listening response

**Files:** New `screen/HomeAtmosphere.kt`, `HomeScreen.kt`; new `AT/screen/HomeAtmosphereTest.kt` for lifecycle/state behavior where useful.

**Interface:** `@Composable fun HomeAtmosphere(listening: Boolean, level: () -> Float, quiet: Boolean, modifier: Modifier = Modifier)`. Consume existing listening state/voice level; own no microphone or interaction handlers. HomeScreen mounts/unmounts this decoration in the plain-home branch only. `HomeAtmosphere` owns its lifecycle and observable system-motion gate. Observe sheet context in a small leaf and pass a discrete quiet state; do not subscribe the whole HomeScreen to frame-by-frame sheet progress. Reuse Android/Compose lifecycle primitives; AppSheetMotion's settle-time animator check is not an observable decoration gate and must not be edited to become one.

- [ ] First render the selected soft texture statically on home: bottom anchored, locally irregular, no directional travelling wave. Draw behind opaque input; keep task/detail/approval reading surfaces free of decoration. Dim the whole decorative layer for populated home/app-list context without text-shaped black masks.
- [ ] Add local brightness/micro-displacement motion using cached glyph/layout work. Use the actual native voice level to gather a broader band higher while listening; existing Listening bars/label remain unchanged. Do not port synthetic speech into the app or equate decoration with task status.
- [ ] Tie drawing to actual visible home/app-sheet state, lifecycle and system animation scale. `HomeAtmosphere` must observe OS animator enablement changes and lifecycle resume/pause, stopping and restarting its own frame loop appropriately without a new preference store. Stop work when hidden, backgrounded, screen off or motion disabled; retain a static state and real listening/status labels. Avoid pushing frame-time state through the whole screen composition.
- [ ] Add atmosphere as a non-interactive sibling behind existing content. It must not consume touch, wrap/replace `.appSheetGesture`, recreate `AppSheetState`, request focus/IME or animate sheet progress. Check accepted reveal with ASCII enabled and disabled; remove work from the frame path if responsiveness changes.
- [ ] Exercise `disabledMotionKeepsStaticAtmosphere`, `leavingHomeStopsAtmosphereWork` and `voiceAmplitudeChangesOnlyDecoration`. Lifecycle/motion tests must establish that frame work stops, not merely that sampled pixels remain unchanged; also check resume restarts exactly one loop. Check real voice silence/noise and keyboard/draft preservation on a device before calling response calibrated.
- [ ] Compare text contrast at bright frames and record frame timing on target hardware, including app-sheet dragging. Reduce renderer complexity if it interferes with input/gesture responsiveness; do not add a renderer framework or silently weaken text contrast. Update SYSTEM/evidence.

## V4: Production cards, media and review edge cases

**Files:** `PlaceCards.kt`, `ItemCards.kt`, `PlaceMap.kt`, `Docked.kt`, `QuestionCard.kt`, related `HomeScreen.kt` branches; existing HomeFlowTest/PlaceCardsTest and approval tests.

**Interfaces:** Preserve current card signatures, navigation callbacks, exact draft/revision binding and `agent.retry`/`agent.retryRest` ownership. A card subject is data, not a reason to add a new renderer class.

- [ ] Apply quieter image → title → facts → reason hierarchy to place/item cards, optional/missing media fallbacks, accepted Pick placement and a clearer map plane. Real map data, controls, current selection and required credits stay intact. Private illustrative map pixels are not a production map style.
- [ ] Inspect native long approvals first. Use a 700–900 character exact draft, different grouped messages, long participant/app names and 200% font scale with IME visible. Add `longApprovalKeepsAllContentReachable` and `groupedApprovalKeepsRecipientsWithTheirText` cases using synthetic fixtures. If native already meets the contract, record pass; otherwise constrain/scroll the existing card so every word is reachable and actions follow the reviewed content. Never truncate/shrink the draft or pin one recipient over another's text.
- [ ] If revision 27 recovery wording is accepted, let `ProblemCard` receive the correct action label while preserving existing callback semantics. Generic/known-failed retry stays “Try again”; unknown-only redrafting says “Prepare new draft”; mixed results identify the known-unsent subset. Compute state/count from existing task state, not by parsing error prose. No new Open chat/Check status route is implied.
- [ ] Add focused recovery fixtures: `unknownRetryPreparesFreshApprovalWithoutSending`, `mixedRetryExcludesConfirmedAndUnknownWhenKnownUnsentRemain`, and `unknownOnlyRetryStillNeedsFreshApproval`. Reuse existing fake hands. Preserve the existing old-draft press rejection test; do not perform real sends.
- [ ] Compare options, generic calendar/app-control gate, docked cards, Send N, sending/Undo, disabled, selected and failed states. Keep current action vocabulary and hit targets. Update SYSTEM/evidence with pass/changed/remaining classifications.

## V5: Small first-use guidance

**Files:** `HomeScreen.kt`, optional new `screen/FirstUseHints.kt`; read existing app-sheet/side-key entry points only. HomeFlowTest and new focused persistence tests if needed. Protected app-sheet files remain unchanged.

**Interfaces:** Observe existing app-sheet successful-open and supported-side-key signals. Reuse local preference storage conventions for acknowledgment. No new navigation or technical setup flow.

- [ ] Add centered chevrons plus “Swipe down for apps” on first eligible usable home. Dismiss on successful app-list use and persist acknowledgment; suppress while typing, listening, a task/approval/permission surface is active. Reduced motion leaves clear static guidance.
- [ ] Observe successful open from existing state outside protected sheet code: persist dismissal only after the open transition reaches completion. Use observable `sheet.progress.value`/animation completion together with `sheet.searching`; `searching` alone changes before settling finishes, and plain Boolean `sheet.moving` is not observable Compose state, so `snapshotFlow { searching && !moving }` is insufficient. The persisted-dismissal regression must drive the actual settling path. Cue has no drag handler or input-consuming overlay; the same accepted reveal must run whether cue is present, dismissed or never shown. No protected-state edit is needed.
- [ ] Add separate “Hold the side button to speak” cue only when supported-key identity and a truthful anchor are available. Existing `SideKey.seen` is evidence of prior use, not proof of every phone's physical key location. If capability cannot be established before use, defer this cue and retain labeled fallback Hold to talk; do not hard-code a right-edge key for all Android phones.
- [ ] Test `swipeHintDismissalSurvivesRecreation`, `hintsNeverCoverApprovalOrKeyboard` and `unsupportedHardwareGetsNoSideKeyPointer`. Verify an accessible app-list entry route; preserve existing native behavior and record any uncovered accessibility gap instead of hiding it with a gesture tutorial.
- [ ] Update SYSTEM/evidence. No toast, Got it panel, simultaneous hints or persistent explanatory copy on home.

## V6: Final native validation and delivery handoff

- [ ] Run focused JVM checks/builds once for final consumed source. From `misura-app`: `MISURA_TOKEN= ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest`; run `MISURA_TOKEN= ./gradlew :core:test` as well if task/approval code changed. `app/build.gradle.kts` forwards this variable and `LiveEval` enables real calls when it is nonblank with a project. Expected: no failures; preserve/report live-test skips. Do not replace the empty assignment with a shell lookup or credential fetch.
- [ ] Select and record the isolated synthetic emulator serial explicitly, then run an allowlisted instrumentation pass. Example, replacing the invalid target placeholder with that selected emulator: `ANDROID_SERIAL=YOUR_ISOLATED_EMULATOR_SERIAL MISURA_TOKEN= ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.misura.phone.HomeFlowTest,com.misura.phone.screen.ScreenPiecesTest,com.misura.phone.screen.PlaceCardsTest,com.misura.phone.cards.CommitTapTest`. Add new atmosphere/first-use test classes to the recorded allowlist only after they exist; do not broaden to live evaluation classes. This command installs app/test APKs on that emulator, not the personal phone. Existing HoldToTalk, SideKey, AppSheetLogic and SheetKeyboard JVM regressions are covered by the unit task above. Never pass live instrumentation tokens or run a second Gradle process concurrently.
- [ ] Review final fixture screenshots across empty/populated home, apps/search/IME, task/approval/listening/docked, recovery, media/map, Earlier and settings at normal/200% text and small/regular widths. Exercise TalkBack, 48dp actions, system Back, external keyboard focus, motion off, rotation/recreation and locale/RTL content.
- [ ] Verify all four protected app-sheet source fingerprints are unchanged. Review HomeScreen diff explicitly for preserved state, gesture/transform/IME integration, then compare accepted transition recordings with ASCII and hints on/off. A changed spring/drag/reversal, row light, input position or keyboard synchronization fails the uplift acceptance check; fix new work rather than retuning the existing transition.
- [ ] Record physical-device voice/side-key, daylight readability and frame-time checks when an installation is explicitly authorized. Until then, label emulator evidence accurately; an HTML or emulator pass is not a phone pass.
- [ ] Obtain one independent review of the actual final native diff and evidence. Reuse existing contributors/review tooling as authorized; no need for a new subagent per pixel change. Resolve supported regressions before delivery.
- [ ] Update SYSTEM, design docs, font/dependency notices and generated local views. Run `python3 scripts/build-mockups.py docs/design`, device-specific view if applicable, and `python3 scripts/publish-system.py`; review ignored output locally. Do not publish ignored output automatically.
- [ ] Prepare the selected APK/source revision and a small verification/remaining-limits record. Keep phone installation, private push, clean public export regeneration and publication as distinct later actions under existing authorization. Runtime/native work and release milestone remain incomplete until their own evidence is recorded.

## Self-review and restart boundary

The map covers shared foundations, home/app sheet, all existing production card families, media/map, task/history, settings compatibility, first-use guidance, accessibility, motion lifecycle and delivery. HTML-only clipping/animation issues stay in the reference tooling; player/incoming/generated/sleep concepts stay outside this rollout. Five Review Focus conditions each have an owning stage and named check.

Recommended execution: one native implementation session, sequential reviewable slices with focused checks, then independent review of the full diff. A fresh session at V0 is useful after the final candidate is selected; it is not required to finish planning. Read SYSTEM and this plan first, then the selected visual reference and its constraints. Recheck current source before trusting this plan's baseline.


## Execution record — 20 Nov

V0–V5 code is implemented locally. SYSTEM §5 records per-stage partial hardware/live-map acceptance; no unchecked historical instruction above should restart completed provider or visual implementation. Native baseline, staged/final captures, source/build fingerprints, independent review, reproduced failures and repair results are in the maintainer's private native-evidence record. The four app-sheet source hashes remain unchanged. See SYSTEM for the full-suite test-clock exception and unchanged class rerun; this plan does not certify unperformed hardware/manual tests or authorize installation/publication.
