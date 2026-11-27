# Epic: Home shortcuts (and what grows from them)

Status: spec, not built. Written 24 Nov 2026 from the design exploration in this folder ((private design notes) = v4 mockup, the accepted one; (private design notes) = rejected steps; (private design notes) = Codex Astra review of the idea).
Repo at writing: main `d15dd59`. When the build starts, copy this file and `PLAN.md` into the repo (`docs/superpowers/specs/`, `docs/superpowers/plans/`) and add the epic to `docs/SYSTEM.md` §5 in the same commit.

## Why

Misura's promise is a Home that fits how you use your phone. The cheapest real version of that: a chore you already did once through Misura comes back as one tap on Home. No context guessing, no generated code, no new runtime. It reuses the hand, the task lines and the card rules that exist.

## Phases

| Phase | What | Plan |
|---|---|---|
| **1a · Shortcuts** | Tell Misura to keep a task; shortcuts as lines on Home; tap runs it; swipe removes it | `PLAN.md` phase 1a (detailed, ready) |
| **1b · It offers** | After repeats of one app task, offer to keep it | `PLAN.md` phase 1b (rules + tests, separate PR) |
| 2 · Show it | “Watch me”: Misura notes your own taps in an app and keeps them as a shortcut | own plan when phase 1 is merged |
| 3 · Widget | One compact screen piece right of the clock, built on request | own plan; needs the built-app runtime |
| 4 · Built apps | “Build me a …” → one locked HTML page in Pinned, AI only through Misura bridge calls | own plan; scope draft (private design notes) |

Phases 2–4 are written down here so the design stays coherent; none of them blocks phase 1.

## Phase 1 requirements

### Home (accepted mockup: (private design notes), section 1)

1. **Headline rule unchanged in spirit:** the headline “What should we do?” shows only when Home has no lines. A kept shortcut counts as a line.
2. **Field placeholder:** when Home shows any line (task or shortcut), the request field's placeholder reads **“What should we do?”**. Empty Home keeps “Type a request…”. While searching apps it stays “Search apps”.
3. **One list:** task lines first (ring = ready for your go, ✕ = failed, orange dot = running; existing order and rules), then shortcut lines. Same `TaskLine` style, same `ST.step` text, all `M.text` white. No highlight, no orange, no hint text on shortcuts.
4. **Left sign tells them apart:** tasks keep their orange status signs; a shortcut shows its app's monochrome mark (`rememberAppMark(pkg)`, tinted `text-2`, 16 u). No mark available → the existing line-icon fallback.
5. **Budget:** at most **4 lines** above “Earlier” in total, **tasks included** (today Home can show 3 yours + 2 failed + 1 running = 6; this caps it, with a truthful “Earlier · n still open”). Priority yours → failed → running → shortcuts; shortcuts fill what's left, at most **3**, in the order they were kept. The existing measured-height fitting (`slots`) still applies on short screens.
6. **No context:** location and time never reorder, highlight, add or hide a shortcut.
7. **Atmosphere:** a Home with only shortcuts counts as populated (texture at `HOME_QUIET` 35%), same as a Home with tasks.
8. **Tap a shortcut** = start a new task with its saved prompt, exactly as if typed: full tools (not the routine deny set), opens the task screen. It gets exactly a typed request's protection, including its documented gaps (`docs/OSS_AUDIT.md:55`: label-based approval can miss an effectful “Continue”). Never claim more.
9. **Swipe a shortcut** = remove it (existing `SwipeTaskLine`, action word **Remove**). TalkBack offers the same.

### Making a shortcut

10. **Tell it:** “make a shortcut: check in at the sports-club app”. The model first does the task in this session, then calls `shortcut` `add` with a short title and the exact prompt. The tool refuses unless this session already has a step with an app that finished ✓ and no later failed step (“only a run that worked can be kept”). The tool's own card asks: label `Shortcut · <app name>`, title = the title, body = the exact prompt in quotes, choices **Keep** (commit, left) and **Don't**. Only the tap saves (gate pattern of `RoutineGate`, digest of title + prompt + app).
11. **It spots it (1b):** when a single-message task settles and worked (controller-normalized, so confirmed sends count), wasn't started from a shortcut or routine, and the same app has worked in **3 such sessions on 3 different local days within the last 30 days**, and that app has no kept shortcut and was never declined, the finished task shows one offer card under the outcome: label `<n>th time · <d> days`, title “Keep this on Home?”, body = the prompt it would keep (this session's own words), buttons **Keep** (commit, left) | **No**. Below: none in phase 1 (“Widget” arrives with phase 3). **No** = never offered again for that app. The card is drawn by app code from stored data, never by the model.
12. **Full:** with 3 kept, both cards say in their body which shortcut the Keep replaces (the least recently used one). The tap replaces exactly that one.

### Definitions

- **App evidence** = a step `app` that is a real package (not `WEB` / `NOTE`).
- **Worked** = there is a ✓ step with app evidence and no failed step after it; for offers also settled and `homeKind == DONE` with the send's task state.
- **Day** = local calendar date of the session's last activity (`lastActive`).

## Design rules this must keep (design-system.md)

- Screen talks, cards ask: shortcuts are screen lines; keep-offers are cards.
- Card buttons: commit (solid orange) first and wide (weight 1.6), secondary grey after it (0.8). Verified in `CardViews.kt:351`, `QuestionCard.kt:67`.
- Orange only for commit / Misura working / ready / failed.
- “Done: no card” gets one deliberate exception: the keep-offer. Record it in design-system §10 Decisions in the same commit.

## Out of scope (phase 1)

Show it, widgets, built apps; renaming or reordering shortcuts (remove + keep again); asking about shortcuts unused for 30 days; usage-stats or notification triggers; any location or time logic.

## v2 amendment (27 Nov 2026, owner decision)

Plan: `docs/superpowers/plans/2026-11-27-home-shortcuts-v2.md`. **v2 wins over everything above where they differ** (tap behaviour, replacement, making a shortcut).

- A shortcut is an optional **direct target** plus optional saved words (at least one). Targets: the app itself, a shortcut the app publishes (`LauncherApps`), a fixed Settings page, or one of two reviewed link templates (Maps route to Home/Work, a Spotify page from a link in the owner's own typed or spoken words; never a saved shortcut's, routine's or debug prompt). Targets are resolved by code, never model-written strings.
- **A tap on a line with a target opens it directly**, no agent and no task. If it can't open, saved words run as before; without words the line says "Couldn't open. Try again."
- Words-only shortcuts behave as in phase 1 (tap = typed request, needs a run that worked).
- **No least-recently-used replacement.** Full Home: the app list asks which one to replace; the tool refuses ("Home is full: remove one first").
- Add from the app list long-press (+ Home per published shortcut, Add to Home for the app) or by telling Misura (Keep card shows code-resolved names). Remove by swipe, long-press a Home line, "On Home" in the app list, or the tool. One keeper owns every write and pins kept app shortcuts.
- All Home lines look the same; no marker for agent-run lines (owner, 27 Nov).
- Drag from the app list and free web/app links are out of v2.
- **Spotify pages are off (final review, 27 Nov).** The `SPOTIFY_PAGE` template failed its phone check: opening `spotify:playlist:<id>` with `setPackage` made Spotify resume its last queue on an active Spotify Connect speaker (playback started, and not even that playlist). Creating Spotify shortcuts is off pending the owner's decision. The tool refuses with a plain note and no card; the parser and URI builder stay for a later link form; a stored one still opens.

## Precedence

This spec wins over the mockup where they differ (tool card is Keep / Don't, not Keep / Change; offer is Keep / No until phase 3 adds Widget; a tapped shortcut opens its task screen).

## Open questions (owner)

- Offer threshold 3 runs / 3 days / 30 days: right, or start at 2?
- Is the asked text good enough as the shortcut's line, or should the keep card let you shorten it (needs a text edit on the card)?

## Bear case (carried from the exploration)

- A shortcut is only as good as the hand. sports-club app check-in ends at the QR scanner (you point the camera); the hand still fails some sports-club app filters. Phase 1 is honest about that: the task line shows the real steps and outcome.
- All-white lines make “needs you” and “handy” weigh the same; only the 16 u sign separates them. If waiting tasks get missed on the phone, add a gap between the groups, not a colour.
- Once a shortcut is kept, the headline rarely shows again; the field placeholder carries the question.
- “Show it” (phase 2) is a macro recorder; Tasker/MacroDroid have those. Misura's edge would be keeping the goal with the steps so it re-plans when the app changes. Unproven.
