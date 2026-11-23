# Misura design system

Status: rewritten 11 Nov 2026 for the "screen talks, cards ask" model. Sits under `docs/SYSTEM.md` §2–3 (that file wins). Visual reference: `design-system.html` (3a flow + component sheet); square flow: `flow-mockup.html`. Both are built by `scripts/build-mockups.py`. The v1 card pages (`archive/card-system-v1.html`, `archive/component-guide-v1.html`) describe the cards built so far in `app/.../cards/`; they are history, not the target. **20 Nov: §12 records the accepted visual uplift and its native implementation mapping. Working behavior, native colors/grid/radii and the app-list transition remain protected. Native verification is recorded in SYSTEM §5.**

## 1. Principles

1. **Screen talks, cards ask.** What Misura says or does is plain text on the screen: your words, its steps, outcomes, home. A card appears only when you have something to do: pick, decide, answer, approve. State is never a card.
2. **✓ means done, nothing else.** ● orange = Misura working (also marks its reasons). *will* = happens on your tap. ✕ = failed, always followed by a card that offers the fix.
3. **One tap commits.** Solid orange sends, books or creates. Everything that tap does is on screen at the moment of the tap: the card says it, and *will* steps above it list only what the tap does beyond the card (several effects); a single-effect card has none. Voice and typing start, steer and edit; they never commit.
4. **Back walks the cards.** Nav line on top: ‹ back one card; where you are and "n steps ›" (history). The system back gesture does the same.
5. **Talk to the card in front.** While you speak, that card docks small at the bottom; your words are about it.
6. **One card, one surface. Tiles mean tap. Buttons say the verb.** Text inside a card sits on the card, never boxed. Only tappable things are tiles.
7. **Keep decoration scoped.** No gradients, glows or filler copy ("Answer ready"). The accepted §12 exception is decorative ASCII: non-interactive, stopped offscreen or with system motion disabled. Built 21 Nov: it extends from home through the whole task (working, cards, done, answer, Earlier) at quiet strength, behind solid cards (see §12 Atmosphere).

## 2. Tokens

### Color (dark only)

| Token | Hex | Use |
|---|---|---|
| `screen` | #080706 | Behind everything; the screen layer's ground |
| `surface` | #262423 | Cards (was #32302e until 14 Nov: too loud on black) |
| `surface-sunk` | #1c1a19 | Input field and inset controls |
| `text` | #efeeec | Primary text |
| `text-2` | #adaaa8 | Labels, steps in progress, line icons |
| `paper` | #efeeec | Option tiles, light controls |
| `ink` | #110f0e | Text on paper and on orange |
| `orange` | #f47b30 | Commit fill, forward outline + label, active pin, selected edge, Misura's dot, failed ✕ |
| `outline` | #82807e | Grey outline button edge |
| `you` | #4285f4 | Your location on maps, nothing else |
| `star` | #fbbc04 | Rating star, nothing else |

One orange replaces v1's two (`#f65400` fill, `#fe843d` text). Contrast (WCAG): text/surface 11.4, text-2/surface 5.7, ink/orange 7.0, orange/surface 4.8, orange/screen 7.4, ink/paper 16.5, outline/surface 3.3 (non-text).

### Type

Jost owns display/card titles; Hanken Grotesk owns reading/controls; IBM Plex Mono Medium owns source metadata/clock. All are bundled with OFL notices. Sizes below are role units multiplied by native K=1.5; Android font scale still applies. See §12 for the scoped adoption.

| Layer | Role | Size / weight | Example |
|---|---|---|---|
| Screen | `said` | 19 / 300 | "Find the best bibimbap in Kreuzberg." |
| Screen | `echo` | 16 / 300, `text-2` | “Friday 19:30 for four…” |
| Screen | `outcome` | 34 / 300 | "Booked." |
| Screen | `step` | 13.5 / 400 | "Table for 4 free at 19:30" |
| Screen | `nav` | 12 / 600, `text-2` (‹ part in `text`) | "‹ 3 places" |
| Screen | `clock` | 46 / 500 | "16:42" |
| Screen | `home` | 30 / 500, Jost | "What should we do?" |
| Screen | `date` | 12 / 500, Hanken | actual localized date |
| Card | `label` | 11.5 / 500, `text-2` | "WhatsApp · FriendC, FriendB, ColleagueA" |
| Card | `body` | 14 / 400, line 1.35 | the exact message |
| Card | `title` | 17 / 700 | "Namsan" |
| Card | `big` | 28 / 800, tracking −0.045em | "19:30" |
| Card | `button` | 14 / 700 | "Book & send" |

Rule: **a card uses at most three roles besides `button`.** (Question card: label + body; a tool's own card can add a title: a routine card is label = schedule, title = what it will do in a few words, body = the exact prompt that runs, and it always takes a tap, never a spoken "go". Place card: title + label + body.) Outgoing content is never truncated; a long draft scrolls inside the card with the commit button kept at its end.

### Space and corners

| Token | Value | Rule |
|---|---|---|
| `screen-r` | read at runtime | `WindowInsets.getRoundedCorner()`; mockups use 20 (3a) and 23 (square) |
| `m` | 6 | Card inset from the screen edge |
| `card-r` | `screen-r − m` | Concentric with the screen |
| `p` | 6 | Tile inset inside a card |
| `tile-r` | `max(card-r − p, 4)` | Buttons, rows, photos, map, controls |
| `gutter` | 16 | Screen-layer text inset from the edge |
| tile gap | 4–6 | |

One radius per level, always nested inside the device's real display corner (3a: read at runtime from `WindowInsets.getRoundedCorner`, floor 20 → 14 → 8; square: display 6.5 mm = 32.5 → 26.5 → 20.5, so 42-tall buttons read as pills).

## 3. Screen layer

Plain text on `screen`. Tappable only to open or show more (nav line, steps → history, task row → task, event row → event, widget → its app); never to commit, which stays on a card's button. No tile look: screen pieces stay plain text, with 48 dp targets.

| Piece | What | Rules |
|---|---|---|
| **Listening** | While you hold the key | orange waveform (7 bars) + "Listening" in `text-2`, bottom left, in the field's place; your words are not shown while you speak (15 Nov, his call), they appear as the Echo after; if a card is in front it docks below (§4 Docked). The landing-page tour still shows the words: it has to tell the story without sound |
| **Echo** | Your words after you let go | `echo`, top of the screen, in quotes |
| **Step** | One line per thing Misura checks or does | app mark (16, `text-2`) · plain sentence · state on the right: ✓ done, the **orbit** while working (the orange dot with two small ones circling, 1.8 s and 2.9 s, one speed everywhere), the **ring** when it is ready for your go (empty orange ring, a second ring beating outward every 2 s; the line at full brightness), ✕ failed (orange). Each tool supplies a readable label and a one-line result; never a raw tool name |
| **Answer** | What Misura says back | `outcome` up to 24 characters ("Booked."), `said` up to 160, longer ones in `echo` size, `text` colour; plain text, as long as the question needs (a fact: one sentence; an explanation: up to ~120 words) |
| **Past exchange** | Something said earlier in this task | Echo · its steps · the answer in `echo` size, `text` colour. Quieter than the exchange in front; not announced |
| **Outcome** | The result of a run | `outcome` ("Booked.") with its ✓ steps under it; no card |
| **Nav line** | Where you are + back | Top of the screen. Root of a task: what you asked, "n steps ›". Deeper: "‹ previous", current name |
| **Event row** | Home: what's coming up | One baseline per row: day (`text-2`) + time, title; detail line under the title; newest time in orange; rows 12 apart |
| **Widget** | Something you asked Misura to build (step counter) | Screen piece, not a card: shows state. Home "now" group, compact (label, number 22/400, bar, label) |

Bars: a smooth 4–6 px bar is allowed for a quantity against a goal (steps of 10,000). Task progress is never a bar or meter; it's steps.

App marks: a step that works through an app shows **that app's own one-colour mark** (the themed-icon layer apps ship for Android 13+, read from the installed app), tinted `text-2`, 16 px. If the app has none, or the step isn't one app, it falls back to a line icon. Line icons: thin single-colour (1.4 stroke, 16 px) in `text-2`: pin (maps), search (web look-up), bookmark (a note kept or forgotten), star (reviews), bowl (restaurant), calendar, chat (messages), heart (health), grid+ (widget), car (rides). No coloured app badges. Misura itself is the orange dot.

## 4. Cards

Only for something to do. Sit at the bottom in thumb reach, sized to their content.

| Card | Anatomy | Actions | Used for |
|---|---|---|---|
| **Place card** | photo mosaic (1 big + 2 small) · PICK tag on the big photo's top-left corner for the recommendation · `title` alone on its row · facts row: ★ rating (count), `label` kind · price · walk · Misura's reason with orange dot | the card is the tap target (opens detail) | Places in a carousel |
| **Place detail** | full card under the nav line: bigger mosaic · title + rating · label with hours and walk · two review lines (star icon) | Forward (Reserve) + Secondary (Directions: opens the Maps route from where you are, not yet started; Maps chooses the mode) | One place |
| **Map** | Lighter sage plane with dark labels and dark-edged pins; paper = option, orange = current place, blue = you. Existing real map/data/credits stay. Expansion preserves the loaded native map and camera; live verification is recorded in SYSTEM §5. | tap → full screen with names on pins, current place docked | Above the place carousel (strip); full screen |
| **Question card** | `label` (who / where / what) · optional `title` (a tool's own card: what the tap does, in a few words; tap only) · `body` (the exact thing) · optional embedded preview (e.g. a widget) | ≤ 1 Commit or Forward + ≤ 1 Secondary | Approve, offers, incoming messages, problems (after ✕), "nudge ColleagueA?" |
| **Docked card** | small thumb · label · name | tap to bring it back | The card you're talking about while you speak or Misura works |
| **Live** | readout + controls | controls | Music (and later navigation, rides): the one card that's a control, not a question |
| **Option rows** | paper rows inside a card | rows are the targets | Non-place choices (which contact, which time) |

Carousel: one place per card, the next one peeks at the edge, no dots. Swiping moves the map's orange pin. Decision screen layout: map on top, carousel at the bottom.

Problem = question card after a ✕ step: what failed, whether anything went out, the fix as a Forward button ("Send as SMS"); a retry creates a fresh draft that needs a new approval.

## 5. Elements

### Buttons

| Kind | Look | Meaning |
|---|---|---|
| Commit | solid `orange`, `ink` label, trailing → | Sends, books, creates. Label names every effect ("Book & send", "Add to home"). At most one per screen |
| Forward | `orange` 1.5 px outline and label | Moves forward, nothing sent (Reserve, Draft a nudge) |
| Secondary | `outline` 1.5 px edge, `text` label | Anything else (Edit, Change, Directions, Not now) |
| Option | `paper` tile, `ink` label | One of several to pick |

### States (every tappable element)

| State | Treatment |
|---|---|
| Pressed | scale 0.97, 120 ms; paper/orange darken 6% L |
| Disabled | 40% opacity; the card says why |
| Working | label replaced by the step stream above ("Sending" step ●); button not tappable |
| Selected | 2 px orange inner edge plus ✓ (never colour alone) |
| Focused | 2 px `text` outer ring, 2 px offset |

Minimum touch target 48 × 48 dp, including pins, the nav ‹ and option tiles.

### Long-press menu

Long-press a card (and a TalkBack action with the same items): Open in app (e.g. Open in Google Maps), History, and a state-dependent third item: "Cancel" before anything is dispatched; afterwards "Stop tracking", which says plainly that it doesn't undo a send.

### Task line swipe (variant C, 16 Nov)

Only a horizontal drag starting on the line counts; the line follows the finger 1:1. Keep mandatory system gesture edge zones for Android, vertical scrolling and tap-to-open. Behind the line a `surface` band grows from the right, inset 3 u top/bottom and 6 u right, with 8 u corners, and it runs out to the screen edge (the lines sit inside the side padding). Right aligned at 18 u: an 11 u drawn cross (1.6 u stroke), 6 u gap, and Dismiss in task-line size, weight 600. Cross and word fade from 14 to 40 u travel, `text-2` until past half the line width, then `text`. No orange or new colour.

Release past halfway: slide out in 200 ms, then collapse height in 180 ms; the bottom-anchored home lines above settle. Shorter swipe returns in 180 ms with the mockup’s spring-like ease. System animator scale applies; disabled animations remove without movement. TalkBack offers Dismiss on the same line that opens the task.

Dismiss stops running agent work, keeps a waiting draft, and persists into Earlier across restart; a grouped working line dismisses its represented tasks. In Earlier, the same gesture and TalkBack action say Delete: permanently remove the conversation, saved turns and item photos, with no undo. Discard waiting drafts first; nothing is sent by either action. Already approved sends keep their controller evidence and cannot be recalled. Opening alone keeps dismissal; new words follow the existing old-task rules, and a fresh go on the reopened draft lets its list continue.

### Motion

150–250 ms, ease-out (quint). Steps fade in as they happen; a card rises from the bottom when it needs you; finished steps fold into the nav line; card swaps cross-fade in place. No decorative motion. Reduced-motion: no movement, fades only.

## 6. Layouts

| Screen | Layout |
|---|---|
| Listening | Waveform + "Listening" at the bottom (above a docked card if any); no words until release |
| Working | Echo + steps from the top; docked card at the bottom if the work is about one |
| Thread | Any task after the first thing you said: the exchange in front starts at the top of the screen (echo, steps, answer); earlier exchanges sit above it, one scroll up, 28 apart. "n steps ›" shows the whole thread from the start |
| Decision | Nav line · map (top) · place carousel (bottom) |
| Detail | Nav line (‹ back) · place detail filling the screen |
| Approve | Nav line · echo (+ *will* steps if the tap does several things) · question card at the bottom |
| Done | Nav line · outcome + ✓ steps; no card |
| Home | "Now" group on top (clock left, compact widgets right) · at the bottom: open tasks as lines (ring = ready for your go, ✕ = failed, orbit = running; one running task by name, several as "Working on 3 things"), then "Earlier ›" (all tasks) · cards above them only for what comes to you or runs on a clock |
| Locked | Sleep screen: redacted compact cards (no message text, names per setting); any control opens unlock, then the review |

**Form factors** (same pieces, same rules): 3a 1080×2392 (build, demo): taller map, bigger photo mosaic, more empty screen on approve/done with cards kept at thumb height. Square 584×706 (landing page): map is a strip, steps fit one row.

## 7. Input

- **Voice:** hold the side Essential Key (accessibility service; fallback long-press power as default assistant). Said appears; the front card docks; release sends.
- **Text:** pull down anywhere → keyboard and a field tagged to the front card; ✕ clears the tag to ask anything.
- **Target** (task + draft revision) is fixed when you start and kept until you submit. A request about something already sent becomes a new proposal, never an edit of the past send.
- **Result:** the reply updates the same card (place → approve). Only a tap commits; if the content changed after you saw it, the card asks again.

## 8. Lifecycle

Working (steps ●) → needs you (card rises, one buzz) → committing (steps ●) → done (outcome + ✓, then folds into home state) or ✕ + question card. History ("n steps ›") keeps every step with its evidence.

## 9. Safety and state contracts

1. **Approval binds what was on screen.** The commit tap sends the digest of what was rendered: the card's content and its *will* steps. The controller approves only if it equals the stored payload digest; otherwise the screen refreshes and asks again.
2. **No moving targets.** While a finger or TalkBack focus is on a card, layout freezes; a revision change between touch-down and release cancels the tap.
3. **Browsing never commits.** Carousels, rows, map pins only select or open.
4. **One commit, several effects: allowed only with per-effect tracking.** "Book & send" creates one child task per effect (booking, calendar entry, each message). Each shows as its own step (✓/✕) and is reported on its own; a failure in one never hides the others. Until the controller supports one approval covering several child tasks, the app commits one effect per tap; the landing-page tour shows the target.
5. **Renderer mapping:**

| Task state | Screen | Card |
|---|---|---|
| `PLANNING` | steps ● | place carousel / map / detail when an answer is in |
| `WAITING` | step ● with the reason ("Waiting for Instinct") | none |
| `NEEDS_APPROVAL` | *will* steps | question card |
| `EXECUTING`, `CHECKING` | step ● ("Sending", "Checking it went out") | none |
| `OUTCOME_UNKNOWN` | step ✕ "Not sure it sent" | question card: Check status (checks only, never resends) |
| `DONE` | outcome + ✓ | none |
| `PARTIAL`, `FAILED`, `EXPIRED`, `CANCELLED` | step ✕ with what went out | question card with the fix (retry = new draft) |

6. **Several cards need you:** a stable queue, one standard card in reach, the others compact; new arrivals never replace a card you're reviewing.
7. **Instinct is the one automatic outgoing channel** (preparation, no approval). Messages to people and invitations always need the tap. Message content can never change which contact counts as Instinct.
8. **Honest blockers:** offline, hidden screen paused, Shizuku off: a ✕ step says which and whether anything went out. Nothing uncertain is replayed.
9. **Places without data:** photos and pins are optional; without them the carousel falls back to option rows with names and facts; an unresolved place asks for an address.
10. **Accessibility:** TalkBack label and role per piece; reading order nav → said/echo → steps → card (label, title, body, actions); step changes announced without stealing focus; accessible actions instead of swipe and long-press; 48 dp targets; reduced motion.

## 10. Decisions

- 16 Nov (afternoon): task swipe uses variant C: grey surface band, cross + word, brightens past half width; Dismiss keeps the task in Earlier, Delete there is permanent. No undo; TalkBack actions and system gesture edges included.

- 15 Nov: approve card holds several messages: one block per distinct text (label = names, first block prefixed with the app), 11 between blocks; commit reads "Send 3". After a spoken go: the commit shows its meter and Edit becomes **Undo** for 3 s. The commit gate's go-ahead reads "Approve: Delete".
- 14 Nov: orbit = working, ring = ready for your go (no "will", no "Thinking"); still dot = Misura talking. Home = lines + "Earlier ›". Surface darkened to #262423. Card recipe: one left edge (text at card inset 6 + button padding 12 = 18), label 4 above its content, 14 from card top to text and from text to buttons; label + one focal thing + buttons; the button carries what the tap does. Working (deciding, no step yet): the orbit alone, 22, its centre dot on the left edge of your words.
- 23 Nov: sources are tags, not titles: superscript orange numbers in the answer, one line of "n site" pills (Plex, surface fill, 48 dp target) for three sources plus "+N", full title and address only in a bottom sheet with Close / Open ↗. Numbers follow the provider's list.
- 14 Nov: a task is a thread: earlier exchanges stay above the one in front; answers may be long when the question needs it; a web look-up is a step with the search line icon whose result names its source sites.
- 11 Nov (later): screen pieces tappable to open, never to commit; home shows recent tasks as rows.
- 12 Nov: no repeated lines: single-effect drafts have no *will* step; root nav hides your words while the echo shows them; carousel cards one height; map repainted in map tokens.
- 12 Nov: steps show the real app's one-colour mark, line icon as fallback (his call; still no coloured badges).
- 11 Nov: screen talks, cards ask; ✓ only done; back via nav line; decision screen map-top/carousel-bottom; one orange #f47b30; line icons; home groups; widgets are screen pieces; cards use ≤ 3 text roles; smooth bar only for quantities.
- 10 Nov (kept): input stays hidden (side key, pull down; visible talk control only if the Essential Key can't be captured); carousel stays; custom cards and rides cut from Demo 1.

## 11. Open questions

- ✕ in orange may not read as failure next to orange ● dots; fallback: a red used only for failures.
- Multi-effect approval in the controller (§9.4): build it, or keep one effect per tap in the app.
- Essential Key capture on Nothing OS (day-one check).
- Orange outline legibility in sunlight on the 3a.

## Local setup (19 Nov 2026)

A first-run/local-settings screen uses the existing screen background, Hanken roles, sunk input fields and verb buttons; no new tokens or decorative cards. Saved secrets display only saved-state hints, never values. New input is password-masked; Save is solid orange, Android settings links use orange outlines, Done uses grey. The entire screen scrolls above the keyboard and respects system bars. Notification switches have 48 dp targets and switch semantics. Setup is reached automatically when unconfigured or from the swipe-down app list; no permanent control is added to home.

## 12. 20 Nov visual adoption candidate

**Status: scoped adoption accepted on 20 Nov.** The maintainer accepted revision27 corrections and resumed V0. Native behavior remains authoritative; approval scrolling is adopted only after a native reachability failure, and recovery labels preserve existing callbacks. Progress belongs to SYSTEM §5, with execution detail in `../superpowers/plans/2026-11-20-visual-uplift.md`. Do not promote a planned/spec-only mock component into a release feature.

| Area | Adopted direction / delta | Preserve / verify |
|---|---|---|
| Foundations | Jost display/card-title direction, Hanken reading/controls, IBM Plex Mono metadata/ASCII; subtle input rim over existing sunk fill, solid rimless cards | Native role/color mapping is frozen below. Retain `K`, dp/sp scaling, grid and device-derived nested radii; font licensing and native multilingual fallback must hold. |
| Empty home | “What should we do?” headline plus distinct “Type a request…” field copy; readable actual date | Existing clock/task priority/Earlier routing, conditional history and shared Search apps field. Never hard-code sample date/content. |
| Atmosphere | Bottom soft ASCII texture with local irregular motion, quieter behind populated-home links/apps; listening gathers a voice-responsive band higher. **Built 21 Nov:** one continuous texture through a task, never cut mid-flow. Home full strength; working, done, answer and Earlier at quiet strength (bottom field only); approval, choice and place cards settle slightly quieter (about 35% of home) over 300 ms and return over 300 ms. Cards stay solid, so card text never sits on glyphs. Typing keeps hiding it, fading over the keyboard's 340 ms opening. **No abrupt background change anywhere in a flow:** every strength change, the quiet level for task links/app list, and the listening blend ease out (about 300 ms); voice level is smoothed; no on/off multiplier or formula switch may jump between frames. This also replaces today's instant cuts (quiet toggle when task links appear or the app list starts opening, listening on/off, typing focus). Working shows texture only after 1 s so short work never flickers. Redraws at 15 fps; fades run on their own clock. Listening: no band, curve or shape; voice brightens and stirs the same grain in the empty area above the headline while texture behind the Listening label and field stays faint. | No uniform travelling waves, text-shaped black cutouts or extra input animation. Actual voice level, existing bars/Listening, reduced motion, lifecycle, text contrast and frame time. Absent behind reading/approval. This is a scoped proposed exception to §1.7, not a blanket permission for decoration. |
| Cards/media | Quieter image/title/facts/reason hierarchy; stronger map/content separation; missing-media fallback | Existing card/action anatomy, exact task ownership, real map controls/data and applicable photo/map/provider credits. No new per-subject renderer. |
| Approval/recovery corrections | Bounded exact-draft scrolling with actions at its end; clear fresh-draft action wording; stronger focused input edge | Native defect not assumed from mock clipping. Verify grouped recipients/long text/IME/font scale first. Retry creates fresh approval; preserve digest binding, sent/unknown/unsent distinctions and Undo. These corrections are accepted for this rollout; native reachability checks gate layout changes. |
| First use | Centered chevrons + “Swipe down for apps”; separate supported-side-key tip | Small once-only cue, dismiss on successful use, persist acknowledgment, suppress during work/input/approval. No toast/tour. No hardware-specific pointer without known support/anchor; retain labeled fallback. |
| App-list transition | **Current implementation accepted and frozen by maintainer** | Leave app-sheet motion, spring/drag/reversal, row lighting, field/list position, IME/Back/scroll ownership and HomeScreen integration unchanged. Hints/background must not intercept input or cause jank. Compare baseline recordings; repair new visual work if it degrades transition. |

**Frozen native mapping (implemented locally):** keep all existing M colors, native K scaling, spacing and runtime radii. Jost owns T.who, T.word, ST.outcome and the new empty-home headline; IBM Plex Mono Medium owns T.src and ST.clock plus decorative glyphs. Hanken remains reading, controls, app rows, navigation and date. Preserve existing role sizes/line heights except the new headline/date roles. Input receives a subtle neutral rim and a stronger focus-only edge without changing padding/measurement. Keep screen #080706 and secondary ink #ADAAA8 rather than importing web differences. Calibrate real voice response and measure device rendering before claiming hardware acceptance. Player controls/artwork, automatic incoming cards, generated apps/widgets and sleep/notification features remain outside this visual implementation. The separate provider/settings delivery is complete and must not be redone here.


### Native adoption verification — 20 Nov

The uplift now uses the existing production functions. Both exact-message approvals and generic app-control gates have bounded scrolling with their actions at the end; an active Undo window follows the end through viewport changes. Structured approval scroll belongs to its exact task/digest key, and a generic gate revision cancels a press begun on the previous card. No send/retry callback or backend task semantics changed.

The mounted input rejects edits and app-search Enter/IME actions while listening, preserving focus, draft/query and the existing keyboard owner. A canceled fallback voice hold calls the existing cancellation path; spoken go/Undo retains independent typed text.

On plain-home request focus, immediately show only the mounted prompt row and native keyboard over the screen background. Hide clock/date, headline, task links, Earlier, guidance and ASCII throughout keyboard opening and closing; restore home once the keyboard is fully dismissed. For normal home composition, use a request-only native IME controller: 340ms open / 300ms close, gentle acceleration and deceleration. The mounted prompt keeps its existing IME padding and follows the same controlled keyboard insets; never add an independent prompt translation. Reverse from current insets, wait for control readiness, and blur after close completes. Fall back to platform behavior if control is denied. Keep reduced-motion/system duration scale behavior. While normal request owns focus or a pending opening, own platform Back before the IME can consume it; Back invokes the same controlled close. Release that callback on blur, close, cancellation and sheet/lifecycle takeover. External IME visibility changes still clear stale focus. This supersedes the earlier default-timing decision after the maintainer reported abrupt motion. An unconsumed tap outside the prompt dismisses the keyboard and clears focus; Back or the keyboard hide action also clears lingering request focus after dismissal. Retain the unsent draft and support tapping the prompt again. Do not clear focus before a newly requested keyboard has first opened. Keep task-reading/reply context, listening feedback, window fitting and accepted app-sheet/input/IME ownership unchanged. Cancel/transfer the request controller before sheet ownership takes over; never clear sheet focus from a stale callback.

Home distinguishes actually displayed open tasks from recently completed history. Cached native text measurements reserve Earlier before fitting task rows; hidden counts stay truthful. Clock/date/headline yield when a short window or large text cannot accommodate them. First-use guidance sits in remaining whitespace and acknowledges an actual settled app-list open. The pre-use side-key pointer is deferred because the app has no truthful pre-use capability/location signal.

Carousel uses one displayed photo; detail retains its mosaic. Photo result state is keyed to request identity so a replacement place cannot inherit the previous image/credit while loading. Complete author links wrap rather than ellipsize. Map changes repaint the existing native layer palette; live tile rendering and provider/distribution terms remain unverified here.

SYSTEM §5 owns completion/evidence. Physical voice calibration, daylight/frame timing and final manual TalkBack/rotation coverage remain separate validation; source/fixture checks do not certify them. All four frozen app-sheet source files remain unchanged.

### 21 Nov phone follow-up corrections

Owner approved compact required author credits inside photos. Use a 10 sp reading-face tag with 12 sp line height, paper text on a dark 82% backing, 4 dp / 2 dp inner padding, and preserved author links. Full names wrap and remain scroll-accessible at large font scales; credit only displayed photos. No separate metadata line under the image. This scoped metadata-size exception leaves global native roles/tokens unchanged. Google Places requires supplied author attribution wherever its image is displayed (https://developers.google.com/maps/documentation/places/web-service/place-photos).

Place previews reserve the same media/title/facts/reason slots, including missing media; the photo-free PICK shares the title row. Bound card scrolling on short/large-font screens rather than clipping text. Map expansion resizes the existing native map in place and preserves camera/selection. Misura settings uses the normal app row; accepted app-list motion, lighting and keyboard ownership remain frozen. Home task rows have transparent foregrounds over ASCII; reveal cue and swipe/TalkBack actions remain.
