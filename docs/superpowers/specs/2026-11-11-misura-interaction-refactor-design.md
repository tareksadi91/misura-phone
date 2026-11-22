# Misura interaction-model refactor (emulator) — design

Date: 11 Nov 2026. Sits under `docs/SYSTEM.md` (§2 interaction model, §4 "decided, not built", §5 item 2a) and `docs/design/design-system.md`. Those win; this doc says what this refactor builds and how. Scope reviewed by Codex (11 Nov); its findings are folded in.

## 1. Intent

Today a session is one card the model paints via `show_card`. That breaks the core rule "screen talks, cards ask": state shows up as cards, and the model decides what the UI looks like. This refactor makes the emulator app match the decided model for everything up to *deciding*: you ask, Misura's steps stream on the screen as plain text, and cards appear only when you have something to do (pick a place, answer a question, fix a problem). Approve-and-send comes later with the messaging tools (after phone Phase B).

Success = Run 1 up to the decision, on the emulator, looking like `design/design-system.html` side by side: "best bibimbap in Kreuzberg" → steps stream (● then ✓ with results) → map on top + place carousel at the bottom with Misura's pick marked → tap → place detail ("‹ 6 places") → back → same card → type "is it open Friday?" with the card docked → answer from stored hours, no new search.

## 2. Goals, non-goals, anti-goals

**Goals**
1. Step stream from the agent's tool calls, with readable labels and results owned by tool code.
2. Cards only for decisions: place carousel + map, place detail, question card (agent questions and problems), docked card.
3. Nav line + back stack (app-local), "n steps ›" history.
4. Home: clock + recent tasks' state as plain text.
5. Design tokens and type from `design-system.md` §2 (one orange #f47b30, screen + card type roles, weight 300 available).
6. Place data enriched: photos, rating + count, price level.

**Non-goals (later):** *will* steps, question card as approve (Book & send), any send from the agent, widgets, voice/side key (temporary text field stays), calendar "coming up" rows, walk time (needs a reliable location; see §11), live cards, multi-effect approval.

**Anti-goals (a failure even if it works):**
- A raw tool name or JSON on screen ("find_places ✓").
- ✓ on anything that didn't succeed.
- The model painting UI: choosing layouts, card types, or inventing place facts (ratings, photos, links) not from tool data.
- State shown as a card ("Answer ready", "Thinking" card).
- Cards drifting from the HTML mockup (he rejected cards v1 for this: port from `design-system.html` CSS, compare side by side).
- User text in logs (logs end up in bug reports).

## 3. Constraints

- Modules stay: `:core` pure JVM (agent, models, validation), `:app` Android (renderer, tools, Room). New logic goes in `:core` wherever it can, with JVM tests.
- Agent loop limits stay: ordered per-session queue, ≤ 8 tool calls, 30 s per model call, 90 s per turn, failed turns close open calls.
- Places terms: coordinates and other Places content older than 30 days are not shown (render-time: such sessions show places as names + why only, no map, photos, rating, price, and an old answer line is replaced by "Older answer. Ask again for current details."), extending today's `recover` pin-age rule. Stored turns are not rewritten (today they already keep lat/lng). Photo bytes are never persisted; photo references refetch. *Builder's call, flagged:* rewriting/deleting old turns is a later privacy feature if needed.
- Controller, routes, sleep screen, experiment screen untouched.
- Existing tests: keep the 24 `AgentTest` behaviour guards (ordering, recovery, stale taps, timeouts) green, adapted to the new model; replace the ~6 rendering tests (`SpecCardTest`, parts of `SessionStoreTest`) deliberately; all other tests untouched and green.
- `SYSTEM.md` updated in the same commit as the interaction change (§10).

## 4. Data model (`:core`)

**Step** — one line on screen per tool call.
```
Step(id, turn: Int, label: String, state: RUNNING | DONE | FAILED, result: String?, startedAt, endedAt?)
```
- Created RUNNING when a tool call starts, closed DONE/FAILED when it returns. FAILED when the tool throws, times out, or returns `{"error": …}`. A turn that fails or times out closes its RUNNING steps as FAILED.
- `recover()` at app start turns any RUNNING step into FAILED ("Interrupted"), alongside today's THINKING → FAILED fix.
- Persisted with the session (it's the "n steps ›" history).

**Tool labels** — the `Tool` interface gains:
```
fun label(args: JsonObject): String        // "Searching bibimbap in Kreuzberg"
fun summary(result: JsonObject): String?   // "6 places found"
```
Labels and summaries come only from code. Internal tools (`answer`, `ask`) make no step. Logging (`onStep`) keeps logging tool names and counts only, never labels (labels contain the user's words).

**Session** — `card: CardSpec?` is replaced by two independent parts, because a follow-up answer must not wipe the cards you're deciding on (design-system §7: "the reply updates the same card"; SYSTEM §2.4 "back walks the cards"):
```
Session(id, status, steps: List<Step>, places: Places?, say: Say?, revision, createdAt)
Places(placeIds: List<String>, pick: String?)        // carousel + map; stays until a new show_places replaces it
Say =
  Answer(line: String)                               // screen text above the cards (or alone)
  Question(line: String, choices: List<Choice>)      // question card
  Problem(line: String)                              // ✕ step + question card with the fix
```
- `answer`/`ask` change only `say`; `show_places` changes `places` (and sets `say` to its `line` as an Answer, or clears it). So "is it open Friday?" answers as screen text while the carousel/detail you were on stays, with your place and carousel index intact.
- `Places` holds ids only; the renderer looks up the place data from the session's stored `find_places` results (the `Turn` log already holds them verbatim). An id that isn't in the results is dropped at validation. So every place fact on screen comes from tool data. Each place card's reason is that place's `why` from `find_places`; the pick's reason is `show_places.line` when given.
- `revision` goes up whenever `places` or `say` changes (as today).

**Migration** — sessions are stored as serialized JSON in Room. Old rows (with `card`) must decode without crashing: decode with the old field optional and map `Text`/`Problem` → `Answer`/`Problem`, `Choices`/`MapCard` → `Answer` with the title (old place lists aren't rebuilt). Test with a fixture row from the current format.

## 5. Agent tools (what the model can do)

| Tool | Args | Effect |
|---|---|---|
| `find_places` | query, near?, day? | as today + enrichment (§6). Makes a step. |
| `show_places` | placeIds (1–6, ordered), pick?, line? | sets `places` (+ `line` as Answer); ends the turn. Ids must come from earlier results in this session |
| `ask` | line, choices? (≤ 4 labels) | sets `say` = Question; ends the turn |
| `answer` | line | sets `say` = Answer; ends the turn. A plain-text model reply = `answer` |

`show_card` and `finish` go. Validation stays in `:core` (as `parseCard` does today): bad args get an error back to the model once, then the turn fails with a Problem. The system prompt is rewritten for these four tools; the taste block stays as is.

Choice taps on a question card send "I picked <label>" as the next turn, refused if `revision` changed since render (today's stale-pick guard, kept).

## 6. Place data enrichment

`FindPlaces.details` adds to its field mask: `rating`, `userRatingCount`, `priceLevel`, `primaryTypeDisplayName`, `photos` (first 3, each with its author attribution). Output per place adds `rating`, `ratingCount`, `price` (1–4), `kind`, `photos` (name + author). Photo authors are shown under the mosaic (Places terms). Photo names expire: on failure, refetch the place's photos once by place id. Places without them simply lack the field; the card shows what exists (design-system §9.9: no photos → no mosaic; no coordinates → no pin, and if no place has coordinates the map strip is hidden and the carousel still shows).

Photos render from the Places photo media endpoint with the same OAuth token, sized for the card; in-memory cache only. A failed photo shows the card without the mosaic, never a broken image.

Cost: one details call per place already happens; the field additions move it to a higher Places SKU, plus one photo fetch per shown image. He approved the spend (11 Nov).

## 7. Screens (renderer, `:app`)

Ported from `design/design-system.html` (3a), compared side by side via emulator screenshots before each is called done.

| Screen | Content |
|---|---|
| Home (no task open) | clock (46/300) top-left; recent tasks as plain text rows: what you asked + state ("6 places", "Failed") |
| Working | echo of your words (top, quotes) · steps for this turn: line icon · label · state (● orange dot while RUNNING with elapsed seconds after 5 s, ✓ + result, ✕) |
| Decision (`Places`) | nav line · map (taller on 3a) · `line` as screen text if any · place carousel at the bottom: one card per place, next one peeks, swiping moves the orange pin, PICK tag on `pick` |
| Detail | nav line "‹ 6 places" · bigger mosaic · title + ★ rating (count) · kind/price · hours today · week on expand. Actions: Directions (Secondary, opens Google Maps via `mapsUri`) |
| Answer | echo · ✓ steps · the line as `outcome`-sized or `said`-sized screen text (long lines `said` size) |
| Question | echo · steps · question card at the bottom (label + body + choice tiles) |
| Problem | ✕ step with what failed and "Nothing was sent" · question card with Try again (re-sends your last words as a new turn) |
| Docked | while the input field has focus: the front card shrinks to a docked card (thumb · label · name) at the bottom |

**Map:** MapLibre as today, made reactive: pins update when the view changes, the pin for the current carousel card is orange, others paper, the user's location blue when known. Tap → full-screen map with names on pins and the current place docked; back returns.

**Nav line + back:** app-local stack per open task: `[task root, detail(placeId)]`, carousel index remembered. ‹ and system back pop; at the task root, back goes home (decision: SYSTEM.md open question resolved to home, §10). "n steps ›" opens the history: all steps of the session, grouped by turn, as plain screen text.

**Opening place detail is local** (no model turn). The old `agent.pick` path for places goes.

**Tokens and type:** one orange #f47b30 replaces #f65400/#fe843d; the font family registers 300–800; text styles split into screen roles (said, echo, outcome, step, nav, clock) and card roles (label, body, title, big, button) per design-system §2. Scaling is set so the emulator screenshot matches the HTML mockup at the same physical size (the current `K = 1.5` is replaced, not kept by habit).

**Accessibility (design-system §9.10):** TalkBack label + role per piece, reading order nav → echo → steps → card; step changes announced politely without moving focus; carousel and pins have accessible actions (next/previous/open); 48 dp targets incl. pins and ‹; reduced motion = fades only; layout frozen while a finger or focus is on a card (§9.2).

## 8. Input and follow-ups (docked context)

The temporary text field stays (stands in for the side key). When the field gets focus, the app captures a **target**: session id, revision, and the place in front (carousel card or detail). It is kept until submit; ✕ on the docked card clears it (then the text starts a new task). On submit the follow-up goes to that session with the context attached as a short user-turn note the model sees ("Looking at: Namsan (placeId …)"). No target → new task.

Follow-ups never commit anything (nothing in this refactor can), so the captured revision is not a guard: if the view changed before submit, the follow-up still goes to the same session with the captured place note. Send stays disabled while a turn is running (as today). While the follow-up runs, the card stays docked at the bottom and the new turn's steps stream above it (design-system §6 Working); the answer then appears as screen text and the card returns to full size.

Failures that aren't a tool call (model unreachable, too many steps, timeout) add one synthetic ✕ step with what failed, then the Problem.

## 9. Testing

- `:core` JVM: step lifecycle (RUNNING → DONE/FAILED, error results, timeouts, interrupted recovery), tool validation for `show_places`/`ask`/`answer` (unknown ids dropped, limits), old-row migration fixture, docked-context note, stale choice tap refused, labels never logged.
- `:app` JVM: `FindPlaces` enrichment parsing from recorded Places responses (missing fields tolerated).
- Emulator (Compose tests, fake brain): Run 1 to the decision with a scripted brain — steps appear and close, carousel + map render, tap → detail → back restores the card, docked follow-up carries the place, Problem shows Try again, home lists the task. Plus a screenshot comparison pass against `design-system.html` reviewed by eye.
- Live eval (`LiveEval`): the real model uses `show_places` with a pick for the bibimbap query and answers hours follow-ups without a second search.

## 10. Docs in the same change

`SYSTEM.md`: §2 rule 4 (back at root = home), §4 tools list (`find_places`, `show_places`, `ask`, `answer`; `show_card` gone) and drop "decided, not built" for what ships, §5 progress, §6 decisions log, §7 remove resolved questions. Mark `superpowers/specs/2026-11-10-misura-brain-design.md` §3.3 superseded by this doc in the §8 index. Republish the artifact view.

## 11. Left to the builder

- Compose structure, file split, animation details within design-system §5 Motion.
- Exact step label wording per tool (plain, specific, uses the user's terms).
- Photo loading mechanism (existing deps first; add one only if nothing installed can do it).
- How the session's place lookup indexes `find_places` results.
- **My calls (flag to him):** answers to follow-ups show as screen text while the place cards stay (not a view history); per-place reason = the tool's `why`; old Places data hidden at render after 30 days, turns not rewritten; follow-up revision not a guard (nothing commits); walk time cut (no reliable location on the emulator; returns with location on the phone); home task rows are tappable to reopen a task (the design keeps the screen layer untappable except the nav line, but there's no other way back into a task yet — proposal for SYSTEM.md); old place-list sessions migrate to text, not rebuilt; Try again re-sends your last words as a new turn.
