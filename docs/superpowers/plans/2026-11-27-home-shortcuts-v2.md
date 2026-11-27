# Home Shortcuts 1a v2: direct targets + Add to Home (rework of PR #26)

Written 27 Nov 2026, revised after Codex (Sol) audit `codex-v2-plan.md` and re-check `codex-v2-plan-recheck.md` ("ready after fixes", all applied) and link check `codex-v2-links.md` ("ready after fixes", all applied) (verdict "not ready", 3 P1 + 7 P2 + 1 P3; all folded in below, see "Audit fixes"). Branch `home/shortcuts-1a` (PR #26, head `ad5a1d4`, not merged). Replaces the tap and save half of PR #26; Home lines, budget, store core, `sc:` gate prefix and routing stay.

Inputs: owner decisions 27 Nov, `astra-shortcuts-review.md`, phone checks (`dumpsys shortcut`, USC APK `shortcuts.xml`), mockup `add-to-home.html` (accepted direction; drag deferred).

## What changes and why

Owner: a shortcut should open the app straight away, not wait for the agent, but stay flexible.

- **A shortcut = optional direct target + optional saved words**, at least one set.
  - Targets in v2 (all code-resolved, never model-authored strings):
    1. `App(pkg)`: the app itself.
    2. `AppShortcut(pkg, id)`: a shortcut the app publishes (`LauncherApps`).
    3. `Settings(page, subjectPackage?)`: a fixed enum.
  4. `Link(template: LinkTemplate, param)`: **reviewed link templates only** (owner's test set, 27 Nov). Misura builds the URI from a fixed template + a code-validated parameter, with `setPackage`; the model never writes a URI. Each template was checked on the phone to only show a screen:

     | Template | URI | Param (validated by code) | Package | Checked |
     |---|---|---|---|---|
     | `MAPS_ROUTE` | `https://www.google.com/maps/dir/?api=1&destination=<p>` | `Home` or `Work` only (Maps resolves your saved places) | `com.google.android.apps.maps` | 27 Nov: opens route preview Your location → Home, Start not pressed |
     | `SPOTIFY_PAGE` | `spotify:<kind>:<id>` | kind ∈ playlist / album / artist; id = 22 base62 chars, parsed by code from an `open.spotify.com/<kind>/<id>` link the user gives | `com.spotify.music` | ~~27 Nov: `spotify:` page opens, nothing plays~~ **Failed on the phone (final review, 27 Nov), see the note below** |

     **Template note (final review, 27 Nov):** `SPOTIFY_PAGE` failed its phone check: opening `spotify:playlist:<id>` with `setPackage` made Spotify resume its last queue on an active Spotify Connect speaker (playback started, and not even that playlist). Creating Spotify shortcuts is off pending the owner's decision: the tool refuses it with "Spotify shortcuts aren't available yet: opening Spotify can start playback." and shows no card (checked after the owner-words check). The parser, the URI builder and their tests stay for a future URI form; an already-stored `SPOTIFY_PAGE` shortcut still opens (no migration).

     The Spotify id comes from the user, not the model: in Spotify, playlist → Share → Copy link, then paste "make a shortcut: <link>" (Spotify publishes no playlist shortcuts and no "Add to Home screen"; checked in its APK). Misura can't know which playlist you play most.
  - **Not in v2: any other link** (free web URLs, other app schemes). An app link can act on open; new templates get the same phone check and review first.
- **What Keep and a tap mean:**
  - **Keep** approves saving a Home entry, nothing else.
  - **A tap on a direct target** is the user opening that app's own entry point, exactly like tapping the shortcut in any launcher. The publishing app owns what that does; Misura cannot inspect or approve it, and the app can change it after Keep. This is the same trust as the app sheet's existing shortcut menu. Documented as delegated app execution in OSS_AUDIT, never claimed safer.
  - `SYSTEM.md` "screen opens; cards commit" holds: opening an app's own entry point is a screen opening; Misura commits nothing.
- **Tap:**
  - Target set → open it on the real display through the parked-task handoff (see Task 3); no agent, no task.
  - It fails → if a prompt exists, start the agent with it (today's path). Otherwise the line shows "Couldn't open. Try again." (the existing `SwipeTaskLine` inline pattern, polite live region) and stays.
- **Making one:**
  1. **App list long-press:**
     - each published shortcut gets **+ Home**;
     - the app itself gets **Add to Home**;
     - **On Home** removes.
     - No model, no card: the tap is the intent.
  2. **Tell it:** "make a shortcut: X".
     - The `shortcut` tool's `targets` lists the app's published shortcuts and the Settings pages; the model picks by id or none.
     - The card shows code-resolved names: "Opens <app> · <shortcut label>", or "Misura does it: “X”".
     - Only the Keep tap saves.
  3. **Words-only** (no target) still needs a run that worked in this conversation. A direct target needs no run.
- **Full Home (3 kept):** no least-recently-used guess anywhere.
  - In the app list: "Home is full. Replace one:" lists the three, plus Cancel.
  - The tool refuses with "Home is full: remove one first" and shows no card.
- **Remove:** swipe (as now), long-press a Home shortcut line → "Remove from Home", "On Home" in the app list, or the tool's `remove`. All four go through one keeper (Task 2b).
- **Confirmation without toasts:**
  - The menu stays open and the row flips to **On Home**, with a polite announcement "Added to Home". This covers the case where the 4-line budget hides the new line behind tasks.
  - The Keep card path already confirms with the tool's outcome note.
- **Deferred:**
  - **Drag from the app list** (custom pointer code in the frozen sheet). Notes for later:
    - the drag starts only after a 16–24 dp move once held;
    - menu items need lift + tap, no slide-to-select;
    - on a full Home a line becomes the drop target after ~0.3 s hover, and a quick release cancels;
    - haptic feedback at the hold and at the drag start;
    - the menu path stays for TalkBack.
  - **Web and app-scheme links.**
  - **Any agent-kind marking on Home lines** (owner 27 Nov: all lines look the same).

## Global constraints (binding)

- Budget unchanged: 4 lines above Earlier, tasks first, shortcuts ≤ 3 (`homeFit`).
- **Card display per kind (snapshot fields, all bound by the digest and compared at commit):**

  | Kind | Body |
  |---|---|
  | `App` | `Opens <app>` |
  | `AppShortcut` | `Opens <app> · <shortcut>` |
  | `Settings` | `Opens Settings · <page name>` (fixed English names from the enum) |
  | `APP_DETAILS` | `Opens Settings · App info · <subject app identity>` |
  | `MAPS_ROUTE` | `Opens Maps · Route to <Home/Work>` |
  | `SPOTIFY_PAGE` | `Opens Spotify · <kind>` + a second line with the full canonical link `open.spotify.com/<kind>/<id>` (inspectable before Keep). The title is the user's/model's name for it and is never presented as Spotify metadata. |

- **Approval snapshot (tool path):** built by code when the card is asked, and bound by the gate digest. It holds:
  - target identity (kind, package, shortcut id, settings page + subject);
  - the display app label from `PackageManager` for the **target's** package (never model text);
  - the display shortcut label from `ShortcutInfo.shortLabel` at ask time;
  - the prompt and the replacing id.
  - Labels are sanitized with the existing `safeAppLabel` rules (`hands/ChatDrafter.kt:61`: drop ISO control, FORMAT, line/paragraph separators), and two apps with equal labels are disambiguated as in `ChatDrafter.kt:37` (identity). On the tap, the shortcut is re-resolved: missing, disabled or label changed → "Not kept: the app changed it, ask again".
  - Labels reach the model as tool-result data only, never as instructions, choices or grants.
- **Intents built by Misura from stored fields.** No `parseUri`, no model-given URIs, no extras except code-owned ones below, no URI grants.
  - App: through `MisuraGraph.openTarget` (Task 3) → `bringParked(pkg)` else `hands.openApp`.
  - AppShortcut: `LauncherApps.startShortcut(pkg, id, null, null, myUserHandle())` after handoff. Discovery and pinning need `hasShortcutHostPermission()`; launching a shortcut that was already pinned is attempted regardless (Android exempts pinned shortcuts).
  - Settings: `enum SettingsPage` → action:

    | Page | Action |
    |---|---|
    | `WIFI` | `ACTION_WIFI_SETTINGS` |
    | `BLUETOOTH` | `ACTION_BLUETOOTH_SETTINGS` |
    | `BATTERY_SAVER` | `ACTION_BATTERY_SAVER_SETTINGS` |
    | `DISPLAY` | `ACTION_DISPLAY_SETTINGS` |
    | `SOUND` | `ACTION_SOUND_SETTINGS` |
    | `LOCATION` | `ACTION_LOCATION_SOURCE_SETTINGS` |
    | `NOTIFICATIONS` | `ACTION_ALL_APPS_NOTIFICATION_SETTINGS` (API 33; minSdk is 34) |
    | `APP_DETAILS` | `ACTION_APPLICATION_DETAILS_SETTINGS` + `package:<subjectPackage>` (pattern at `AppSheet.kt:292`) |

    `resolveActivity == null` → refused at save, `false` at tap.
- **Package visibility:** the manifest's `<queries>` covers `MAIN`/`LAUNCHER` (`app/src/main/AndroidManifest.xml:19`), not Settings handlers (OEMs may use non-launcher packages). Task 3 adds one scoped `<intent>` query per Settings action in the table (APP_DETAILS with `<data android:scheme="package"/>`). Never `QUERY_ALL_PACKAGES`.
- **Pinning** (keeps a kept shortcut alive when the app drops it from its dynamic list):
  - Owned by the keeper.
  - Serialized under one process lock.
  - Reads the raw pinned ids with `FLAG_MATCH_PINNED`, **including disabled ones**, and writes `pinShortcuts(pkg, raw + id, user)`.
  - If the query fails, the pin mutation is skipped, never treated as an empty set.
  - The store write happens before any unpin of a replaced target.
  - A pin failure never reverses a successful save.
- **Keeper is synchronous at its core** (lock + store `commit()` + pin), like today's store, because the gate action runs synchronously and `Agent.choose` reads the outcome right after (`RoutineGate.kt:53`, `Agent.kt:271`). The card tap calls it directly on the gate's worker; UI entry points (menu, Home) call it inside `withContext(Dispatchers.IO)` and update the UI after the outcome.
- **Revalidation happens inside the keeper lock**, immediately before persisting: `keep(s, replacingId, validate: () -> Boolean)` re-resolves the target and the shown labels; false → `TargetChanged`.
- Legacy PR #26 records (prompt only) stay agent shortcuts; never silently converted.
- **App sheet is frozen:** only menu additions, plus routing the existing sheet shortcut launch through `openTarget` (same handoff gap, `AppSheet.kt:266`). No gesture, motion, keyboard or list-logic change (`AppSheetMotion.kt`, `SheetKeyboard.kt`, `SYSTEM.md:257` ownership kept).
- **Repo rules unchanged:**
  - Codex (Sol) re-check of this plan before code, review after, re-check.
  - Each route-level change gets a regression seen red on prior source.
  - Every commit updates the SYSTEM.md sections it touches and passes `check-oss.py`.
  - Full M0 on the phone; the owner merges; no personal data.

## Decided: agent-kind lines (owner, 27 Nov)

All Home lines look the same (app mark + title). No "· asks Misura" suffix, inline or trailing. Mockup `asks-misura.html`: the suffix cut "Urban Sports · Check-in" to "Che…" at 360 dp.

## Audit fixes (Codex `codex-v2-plan.md` → where handled)

| # | Finding | Resolution |
|---|---|---|
| P1-1 | Opening ≠ approval; links can act | Links dropped from v2; delegated-execution wording; no "never runs something else" claim; label-gap wording corrected (Task 7) |
| P1-2 | Keep identity incomplete | Approval snapshot above; labels from code, sanitized, bound, re-checked at tap (Task 4) |
| P1-3 | Hidden-display handoff skipped | `MisuraGraph.openTarget` with `bringParked` first (Task 3), parked-package regression |
| P2-4 | URL resolution / visibility | Links out of v2; visibility note above |
| P2-5 | Settings schema | `Settings(page, subjectPackage?)` + table above |
| P2-6 | Pin ownership / races | Keeper owns every keep/remove/replace, serialized, raw pinned ids (Task 2b) |
| P2-7 | Fixture too weak | Dynamic-shortcut fixture with controls; Maps on the real phone only (Task 3, M0) |
| P2-8 | Menu write failures | Outcome enum + IO + UI after success (Task 2b, 6) |
| P2-9 | Line hidden by budget | Menu stays open, "On Home" + announcement |
| P2-10 | Spec precedence | Repo spec gets a v2 amendment + precedence line (Task 7) |
| P3-11 | Contracts | Tool JSON schema, `keptFor`, injection points below |
| API | Host permission stricter than Android | Discovery/pinning only; pinned launch attempted, never short-circuited by discovery |
| R-1 | Display per kind | Card display table |
| R-2 | Settings visibility | Scoped `<queries>` per action (Task 3) |
| R-3 | Commit-time outcomes | `Full`/`TargetChanged` in menu; validate inside keeper lock |
| N-1 | Suspend keeper vs sync gate | Keeper synchronous; IO only at UI callers; real `Agent.choose` regression |
| N-2 | Direct double tap | Guard + double-tap-timeout window; real two-tap test |

---

### Task 0: Codex (Sol) re-check of this revision
Read-only, `< /dev/null`, `-o codex-v2-plan-recheck.md`. Fold in fixes before Task 1.

### Task 1: Core model
**Files:** `core/shortcut/Shortcut.kt`, `ShortcutRulesTest.kt`.
- `@Serializable sealed interface Target`:
  - `App(pkg)`
  - `AppShortcut(pkg, id)`
  - `Settings(page: SettingsPage, subjectPackage: String? = null)`
  - `Link(template: LinkTemplate, param: String)`; `enum LinkTemplate { MAPS_ROUTE, SPOTIFY_PAGE }`; `fun LinkTemplate.uri(param): String?` (null when the param fails validation) and `fun parseSpotifyLink(text): Link?` (only `https://open.spotify.com/(playlist|album|artist)/<22 base62>`, query ignored)
  - `enum SettingsPage` as in the table.
  - `init` checks: `subjectPackage` is required for `APP_DETAILS` and forbidden for the other pages.
- `Shortcut(id, title, prompt: String?, app: String?, target: Target? = null, createdAt, lastUsedAt)`:
  - `require(prompt != null || target != null)`;
  - when `target != null`, `app` = the target package, derived, never given (Settings → `null`).
- `fun Target.key(): String`: canonical `kind|pkg|id|page|subject|template|param` (param complete: `Home`/`Work`; Spotify `<kind>:<22-char id>`, case kept), used for equality, duplicates and the digest; the displayed destination is bound too.
- Tests add: `homeAndWorkDiffer`, `spotifyKindsAndIdsDiffer`, `idsSharingSuffixDiffer`.
- `digest(replacing, shownApp, shownShortcut)`: length-prefixed title, prompt, app, `target?.key()`, shownApp, shownShortcut, replacing (`-1` for null).
- Remove `replaces()` (LRU) and its test; add `isFull(kept)`.
- **Tests (red first):**
  - `digestBindsTargetAndShownLabels`
  - `promptOrTargetRequired`
  - `legacyJsonWithoutTargetReads`
  - `appDetailsNeedsSubject`
  - `otherPagesRejectSubject`
  - `targetKeyEquality`
  - `linkTemplatesBuildExactUris`, `spotifyParserRejectsOtherHostsKindsAndIds`
  - existing budget tests green

### Task 2: Store
**Files:** `ShortcutStore.kt`, `ShortcutStoreTest.kt`.
- `keep(s, replacing): KeepResult`, one of:
  - `Kept`;
  - `TargetChanged`;
  - `Duplicate`: same `target.key()` already kept;
  - `Full`;
  - `StaleReplacement`;
  - `StorageUnavailableException` is still thrown, as today.
- `fun keptFor(target: Target): Shortcut?`: matches **direct targets only** by key, so prompt-only records of the same app never count.
- **Tests:**
  - `legacyRecordStillReadsAsAgentShortcut`
  - `duplicateTargetRefused`
  - `promptOnlySameAppIsNotKeptFor`
  - `replaceChosenIdOnly`
  - existing corruption tests green

### Task 2b: Keeper (one owner for every mutation)
**Files:** create `app/shortcut/ShortcutKeeper.kt` + `ShortcutKeeperTest.kt` (androidTest); built once in `MisuraGraph`.
- `fun keep(s, replacingId, validate): KeepResult` and `fun remove(id): Boolean`, synchronous (see constraints). Both:
  - run under one lock; callers off the main thread;
  - write the store, then pin the new id, then unpin the replaced/removed one;
  - pin mutation follows the rules above and failures are logged and ignored.
- Every entry point calls the keeper: tool add/remove, menu add/replace/remove, Home swipe, Home long-press. The existing Home swipe (`HomeScreen.kt:389-392`) and tool remove (`ShortcutTool.kt:105-109`) switch to it.
- **Tests:**
  - `keepPinsWithoutDroppingOtherPins`
  - `removeUnpinsOnlyItsId`
  - `pinQueryFailureSkipsPinChange`
  - `pinFailureKeepsSave`
  - `concurrentKeepsBothPinned` (two coroutines, same package)
  - `labelChangeQueuedBehindLockIsRefused` (validate runs after the lock is acquired)
  - `interleavedToolAndMenuKeepHonourFull` (menu checked not-full, tool fills Home first → menu gets `Full`)
  - `delayedWriteThroughRealAgentChoose` (slow store; outcome note still reflects the write)
  - `everyRemovalRouteUnpins` (swipe, hold, menu, tool)

### Task 3: Target launcher + handoff
**Files:**
- Create `app/shortcut/ShortcutLauncher.kt`.
- `MisuraGraph.openTarget(ctx, target): Boolean`.
- The testapp fixture gets:
  1. a `ShortcutControl` broadcast receiver (debug-only) that publishes dynamic shortcuts `fx_a` / `fx_b` → non-exported `FixtureTargetActivity`, which records `opened=<id>` in a file the test reads; it can also update a label and remove dynamic shortcuts;
  2. its static launcher activity.
- `ShortcutLauncher(ctx)`:
  - `published(pkg): List<Published>`: id, sanitized label, enabled; host-permission gated;
  - `rawPinned(pkg)`;
  - `pin` / `unpin`;
  - `resolve(target): Boolean`: still available.
- `MisuraGraph.openTarget`:
  1. `bringParked(pkg)` first (Settings: its package);
  2. for `App`, true from that means done;
  3. for `AppShortcut` / `Settings` / `Link`, launch after the handoff so the deep screen opens on the real display (a successful `bringParked` still launches the destination). `Link` = resolve and start the same package-scoped `ACTION_VIEW` intent; missing/disabled handler or uninstall/update race → `false`, catching `ActivityNotFoundException` / `SecurityException`; never drop `setPackage` to retry elsewhere.
  - Opening never consumes or answers a pending approval: no gate call, no controller call.
  - `HomeActivity`'s existing app lambda and `AppSheet.launchSheetEntry`'s shortcut branch both call it.
- **Tests (connected, emulator `misura_test`, Shizuku started, Home role restored after):**
  - `opensDynamicShortcutOfFixture`
  - `pinnedCopySurvivesDynamicRemoval`
  - `labelChangeDetected`
  - `missingShortcutReturnsFalse`
  - `settingsPageOpensAndUnavailableRefused`
  - `parkedFixtureIsBroughtBackThenShortcutOpens`: hidden hand owns the fixture package (existing `HiddenScreenTest` setup); assert the foreground display and that a pending approval card is still pending.
- Maps `manifest_home` is settled only on the phone (M0).

### Task 4: Tool
**Files:** `ShortcutTool.kt`, `ShortcutToolTest.kt`, `Agent.kt` basePrompt sentence.
- **JSON schema:**
  - `{"action":"targets","package":"<pkg>"}` → `{"app":"<label>","package":"<pkg>","shortcuts":[{"id":"…","label":"…"}],"settings":["WIFI",…]}`
  - `{"action":"add","title":"…","prompt":"…"?,"target":{"kind":"app","package":"…"} | {"kind":"shortcut","package":"…","id":"…"} | {"kind":"settings","page":"…","subject":"…"?} | {"kind":"route","to":"Home"|"Work"} | {"kind":"spotify","link":"<user's open.spotify.com link, verbatim>"}?}`. The Spotify link must appear verbatim in the **owner's typed/spoken messages of this session**, read from the agent's user turns that are real owner utterances (not tool responses stored under role "user", `Agent.kt:584`, not model text or app annotations); code parses it: exact `https://open.spotify.com/(playlist|album|artist)/[A-Za-z0-9]{22}` with no userinfo, no port, optional query ignored, case kept, then rebuilds the URI from kind + id only. At most one target object; unknown kind or extra target fields → error.
- **`add`:**
  - full → error, no card;
  - target → resolve now (published + enabled / page available), build the snapshot, no worked-run needed;
  - no target → today's worked-run rule.
- **Card** (labels from the snapshot):

  | Kind | Card label | Title | Body |
  |---|---|---|---|
  | Direct | `Shortcut · <shown app>` | title | `Opens <shown app> · <shown shortcut>` (+ `If it can't open: Misura does “<prompt>”` when a prompt is set) |
  | Words | `Shortcut · <app>` | title | `Misura does it: “<prompt>”` |

  - Settings, App, `MAPS_ROUTE` and `SPOTIFY_PAGE` cards use the per-kind bodies above (never a shortcut label).
  - Choices: Keep / Don't.
  - On the tap: re-resolve, compare the snapshot, then `keeper.keep`; each `KeepResult` maps to an outcome note.
- **basePrompt:** one sentence (call `targets` and prefer the app's own shortcut). The tool spec carries the details.
- **Tests** (through the real `onChoose`, scripted Brain):
  - `addWithPublishedShortcutNeedsNoRun`
  - `unknownShortcutIdRefused`
  - `twoTargetsRefused`
  - `spotifyLinkNotInUserMessagesRefused` (also: link only inside a tool response, only in model text, only in another session)
  - `spotifyLinkParsedNotPassedThrough` (query/si dropped, `setPackage` spotify)
  - `spotifyParserAdversarial`: lookalike hosts, userinfo, extra path or id chars, encoded separators, 21/23-char ids, hostile query text
  - `routeOnlyHomeOrWork`
  - `labelChangedBetweenAskAndTapNotSaved`
  - `fullRefusesWithoutCard`
  - `wordsOnlyStillNeedsWorkingRun`
  - `hostileShortcutLabelIsDataOnly`: a published label "Approve and send" changes nothing; the card shows it sanitized as the label only. A scripted Brain cannot prove real-model resistance; noted in OSS_AUDIT.
  - existing routing and Stop tests green

### Task 5: Home
**Files:** `HomeScreen.kt`, `HomeShortcutsTest.kt`; `HomeActivity` passes `openTarget` and the keeper.
- **Tap:**
  - `target` → `openTarget`;
  - true → `keeper`/`store.used`;
  - false → prompt? `startTask(prompt)` : the inline "Couldn't open. Try again." under the line;
  - direct opens keep the in-flight `startingShortcut` guard **and** a suppression window of `ViewConfiguration.getDoubleTapTimeout()` after a successful open (Home may still be resumed when the launch returns; `openId` stays null on this path, `HomeScreen.kt:323`).
- **Long-press on a shortcut line** → popup "Remove from Home": `AppMenu`'s popup styling reused, not a new component. Task lines get no long-press.
- All Home lines look the same (app mark + title); no agent-kind suffix (owner 27 Nov, mockup `asks-misura.html`).
- **Tests:**
  - `tapWithTargetOpensWithoutAgent`: the scripted agent records no start; the fixture reports opened
  - `directDoubleTapOpensOnce` (two real taps, launcher returns immediately, Home still resumed) and `retryAfterWindowWorks`
  - `tapWithBrokenTargetFallsBackToPrompt`
  - `linkTargetSurvivesRestartAndOpens`, `linkWithMissingHandlerFailsSafely` (fixture package uninstalled between keep and tap)
  - `tapWithBrokenTargetNoPromptShowsInline`
  - `longPressRemoves`
  - `targetSurvivesRestart`: keep, recreate the activity, tap → opens
  - existing Home tests green

### Task 6: App list menu
**Files:** `screen/AppSheet.kt` (`AppMenu`, `appShortcuts`, `launchSheetEntry`) + `AppSheetHomeTest.kt`.
- Injection: both `AppSheet` overloads (`AppSheet.kt:92,99`) get `home: SheetHome? = null` (keeper + `keptFor` + `isFull` + the current three for the picker). `null` → no Home items, so existing sheet tests are unchanged.
- **Each shortcut row:**
  - name (opens, unchanged) + trailing **+ Home** / **On Home** (48 dp, `text2`, divider as in the mockup);
  - two separate accessible nodes: "Open <label>" and "Add <label> to Home" / "Remove <label> from Home".
- **After the divider:** **Add to Home** / **On Home · remove** for the app (not for Misura-made items), then Pin and App info.
- **Full:** the menu switches to "Home is full. Replace one:", the three (mark + title) and Cancel. Picking one → `keeper.keep(s, picked.id)`.
- **Outcomes** (the menu stays open in every case):

  | Result | Menu shows |
  |---|---|
  | `Kept` | the row flips to On Home; polite announcement |
  | `Duplicate` | On Home |
  | `StaleReplacement` / `Full` (Home filled meanwhile) | the replace picker is rebuilt from fresh data |
  | `TargetChanged` | the shortcut list is re-read; row shows its fresh state |
  | Storage failure | the existing storage message inline in the menu |

- **Tests:**
  - `plusHomeKeepsPublishedShortcut`
  - `addAppToHome`
  - `onHomeRemoves`
  - `fullShowsThreeAndReplacesPicked`
  - `staleReplacementRebuildsPicker`
  - `cancelKeepsAll`
  - `splitRowHasTwoAccessibleActions`
  - `storageFailureShownNotKept`
  - `addedWhileTasksFillHomeStillConfirms` (4 task lines)
  - existing sheet baseline green (transition flake known)

### Task 7: Docs, M0, review, PR
- **`docs/superpowers/specs/2026-11-24-home-shortcuts-design.md`:** add "v2 amendment (27 Nov 2026, owner decision)" with the changes above and a precedence line: v2 wins over §8, §10, §12. The plan file gets a pointer to `PLAN-v2.md` (copied in).
- **SYSTEM.md:** Home, tools, safety and the decisions log; replace the tap-runs-task and LRU text at `SYSTEM.md:33,74,166-167`; resolve the proposal notes.
- **design-system.md** §6 Home row (`:159`): tap opens the target; split menu row; Home line long-press menu.
- **OSS_AUDIT.md:**
  - direct targets are delegated app execution, which Misura can't inspect or approve, and the publisher can change them after Keep;
  - the label-based approval gap still applies to agent shortcuts and the fallback;
  - intent construction, the Settings table, pinning, label-as-data, and the scripted-Brain limit.
- **M0 hand tasks 26–28 replaced (+29):**

  | Feature | Action | Pass |
  |---|---|---|
  | Add to Home | App list → hold Urban Sports → + Home on Check-in | menu shows On Home; Home line; tap opens the USC check-in scanner directly; no Misura task created |
  | Tell it (publisher shortcut) | "make a shortcut to the Clock timer" (any app-published `AppShortcut`) | card "Opens Clock · <label>"; Keep → line; tap opens it on the real display; then remove |
  | Links | "make a shortcut to the route home in Maps"; then Work; a Spotify playlist, album and artist link | cards per kind (Spotify shows the full link); taps open the route **preview** (no navigation started) and the Spotify page (no playback), cold and with the app already open; after restart still works; remove extras, keep Home route + playlist |
  | Tell it (words) | "make a shortcut: open Settings and search for battery" | runs first; card "Misura does it"; tap runs the agent |
  | Owner's set (kept on the phone after the run) | 1. hold Urban Sports → + Home on Check-in; 2. "make a shortcut to the route home in Maps"; 3. "make a shortcut: <Spotify playlist link>" | card "Opens Maps · Route to Home" / "Opens Spotify · playlist"; taps open USC check-in, the Maps route preview (no navigation), the playlist page (no playback) |
  | Full + remove | with 3 kept, + Home in the app list; then hold a Home line | list of three, the picked one replaced; Remove from Home removes it |
- **Review and PR:**
  - Codex review of `main...HEAD`; triage, fix, re-check.
  - Full core + emulator suites; `check-oss.py`.
  - Install on the phone; full M0.
  - Update the PR #26 body. The owner merges.

## Pressure test

- **Weakest assumption:** apps publish useful shortcuts.
  - On this phone: USC Check-in, Maps Home/Work, WhatsApp per chat, Clock timer, Amazon, YouTube and Messages do.
  - Spotify publishes only Search; Vinted publishes none.
  - The fallback is today's agent path, so nothing gets worse. Free links stay out; only the two reviewed templates are in.
- **Maps `manifest_home` via the launcher API is untested on the phone** (the shell start was refused as not exported). M0 settles it.
- **Publisher changes after Keep** can retarget a shortcut. Misura says so (OSS_AUDIT) instead of promising otherwise.
- **Effort:** about the original PR's size again. Home lines, budget, store core, gate and routing are kept.
