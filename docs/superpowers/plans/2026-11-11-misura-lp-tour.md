# Misura landing-page tour port: implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The 3D square phone on the landing page plays the current square flow (`flow-mockup.html`) with real photos, auto-advancing and never stalling.

**Architecture:** Move the screen canvas out of `phone-viewer.js` into its own module that draws one named scene per tour state from a content table; the tour's wait logic becomes a small pure module with injected timers (the one unit-tested piece); `phone-viewer.js` keeps the 3D scene and wires the new sequence, tap targets and render-on-change.

**Tech Stack:** Plain ES modules, Canvas 2D, Three.js 0.170.0 (pinned jsDelivr, unchanged), no build step. Node 24 `node --test` for the wait logic. Python Playwright for the playback check.

**Spec:** `agent-phone/docs/superpowers/specs/2026-11-11-misura-lp-tour-design.md` (in the misura worktree). Visual source: `agent-phone/docs/design/flow-mockup.html`. Read both.

## Global Constraints

- Code lived in a separate `landing-astra/` folder with its own repository, not in this one.
- Local only. Serve with `python3 -m http.server 8736`. No deploy.
- Screen canvas 584×706 logical, drawn at 2× (1168×1412). Mockup is half size: every mockup px ×2.
- Tokens: screen #080706, surface #32302e, text #efeeec, text-2 #adaaa8, paper #efeeec, ink #110f0e, orange #f47b30, you-blue #4285f4, star #fbbc04. Hanken Grotesk. Corners 23 → 17 → 11 (×2 on canvas: 46 → 34 → 22).
- No glows, gradients, blue-tinted greys, coloured app badges, chat bubbles on the screen.
- Restaurant names invented and web-checked as not real Berlin restaurants. Friends: FriendC, FriendB, ColleagueA.
- Caption under the phone: "Concept. Places, ratings and photos are illustrative."
- Walk times stay ("6 min", "6 min walk").
- Full hands-off loop ≈ 60–75 s.

## Review Focus

1. Viewer orbits mid-tour and lets go → auto-play resumes after ~8 s and the camera returns to the screen. (Task 2 test + Task 6 playcheck)
2. Viewer starts a hold, then the pointer is cancelled / leaves the window → hold completes, tour goes on. (Task 2 test)
3. Viewer taps the screen outside the current target (or on the 3D body) → nothing advances, no orbit hijack. (Task 5 manual check in playcheck)
4. Photos fail to load (offline / 404) → card draws a surface-coloured block, no exception, tour continues. (Task 3 check)
5. Static screens (home, decide after swipe settles) → no texture upload per frame. (Task 5 test via `draw()` return value, Task 6 frame-time)

---

### Task 1: Baseline commit, names, photos

**Files:**
- Create: `photos/*.jpg`, `photos/CREDITS.md`

**Interfaces:**
- Produces: photo files named `<place-slug>-1.jpg` (dish, mosaic main), `-2.jpg` (room), `-3.jpg` (table/detail) for three places; the three names + slugs, recorded at the top of `photos/CREDITS.md`.

- [ ] **Step 1:** In `landing-astra/`, `git add -A && git commit -m "Baseline: chat-bubble tour before square-flow port"` (inner repo; `.gitignore` already excludes refs/shots/logs). Expected: one commit, `git log --oneline` shows it.
- [ ] **Step 2:** Pick three invented Korean-place names (pick first). Web-search each with "Berlin restaurant"; reject any that exists. Record names + slugs in `photos/CREDITS.md`.
- [ ] **Step 3:** Fetch 9 photos from Unsplash via the gstack browse tool (curl is blocked): per place one bibimbap/Korean dish, one calm interior, one table/detail. Free Unsplash license only (no Unsplash+). Record photographer + photo URL per file in `CREDITS.md`.
- [ ] **Step 4:** Resize: `sips -Z 600 -s formatOptions 80 photos/*.jpg`. Expected: each ≤ ~80 KB, long edge 600.
- [ ] **Step 5:** Commit `photos/`.

### Task 2: Tour waits (pure, tested)

**Files:**
- Create: `tour-waits.js`, `tour-waits.test.mjs`

**Interfaces:**
- Produces: `export function createWaits({ setTimeout, clearTimeout, now, autoMs = 3500, idleMs = 8000, onResume = () => {} })` returning:
  - `press(name: string): Promise<void>` — resolves on `targetDown(name)` or auto.
  - `hold(name: string, heardMs: number, onStart: () => void): Promise<void>` — `onStart` runs on `targetDown(name)` or auto; resolves once released (or cancelled/idle) AND `heardMs` has passed since start.
  - `targetDown(name: string): void`, `targetUp(): void`, `cancel(): void` (pointercancel / lostpointercapture / window blur), `input(): void` (any orbit/drag/wheel).
  - `current: { name, kind } | null` (what the arrow should point at).
- Rules: auto fires after `autoMs` unless the viewer took over (a `targetDown` during this session or `input()` within the last `idleMs`); while taken over, a timer re-arms and fires auto after `idleMs` with no input, calling `onResume()` first. Orbit `input()` alone never sets taken-over, it only delays auto to `idleMs` after the last input.

- [ ] **Step 1: Write failing tests** in `tour-waits.test.mjs` with a fake clock (`setTimeout` queue + `now`):
  - `press auto-resolves after 3500 ms with no input`
  - `targetDown resolves press immediately`
  - `orbit input delays auto until 8000 ms after the last input, then calls onResume once and resolves`
  - `after a targetDown takeover, the next press waits; 8000 ms idle → onResume + resolve`
  - `hold started then cancel() → resolves once heardMs has passed`
  - `hold started, no targetUp, 8000 ms idle → resolves`
  - `targetDown('Other') while waiting on 'PowerBtn' does nothing`
- [ ] **Step 2:** `node --test tour-waits.test.mjs` → FAIL (module missing).
- [ ] **Step 3:** Implement `createWaits` in `tour-waits.js`.
- [ ] **Step 4:** `node --test tour-waits.test.mjs` → all pass.
- [ ] **Step 5:** Commit.

### Task 3: Screen module, part 1: tokens, primitives, simple scenes

**Files:**
- Create: `flow-content.js`, `screen.js`
- Modify: `phone-viewer.js` (delete `makeScreen`, `THREADS`, cat widget, `SCREEN_RECTS`; import from `screen.js`)

**Interfaces:**
- Produces in `flow-content.js`: `export const PLACES` (array of 3: `{ slug, name, rating, count, kind, price, walk, why, reviews: [string, string], hoursFri, pin: [x, y] }`, pick first), `export const SAID` (`bibimbap`, `friday`, `steps` strings from the mockup), `export const STEPS` (per working scene: `[{ icon, text }]`, labels from mockup screens 2, 6, 11/12), `export const MESSAGE` (the WhatsApp line, with the pick's invented name).
- Produces in `screen.js`: `export function makeScreen({ font })` returning `s` with:
  - `s.set(state: string): void` — states: `off, boot, home0, listen1, work1, decide, detail, listen2, work2, approve, done, home1, listen3, work3, preview, home2`.
  - `s.draw(now: number): boolean` — draws only when something is changing (state changed < its animation length ago, typing/steps/swipe in progress, clock minute changed); returns true iff it uploaded the texture.
  - `s.clear(): void` — blank (#000) canvas, one upload (used for `off`).
  - `s.targets: Record<string, [x0, y0, x1, y1]>` — tap rects for the current state, logical px: `pickCard` (decide), `bookSend` (approve), `addHome` (preview); empty otherwise.
  - `s.texture`, `s.state`, `s.frozenT` (freeze support, unchanged meaning).
- This task draws: `off, boot, home0/1/2` (clock + "Coming up" per mockup 9/13; home0 without the Namsan event, home2 with step widget top right), `listen1/2/3` (said 19/300 ×2 typed at 24 cps, waveform + "Listening"; listen2 adds "· about <pick>" and the docked card), `work1/2/3` (echo 16/300 + steps 13.5/400 appearing ~1.1 s apart, ● orange dot → ✓; line icons from the mockup SVG paths as `Path2D`). Photos load via `new Image()` from `photos/`; until loaded or on error, draw a surface-coloured rounded rect.

- [ ] **Step 1:** Write `flow-content.js` from the mockup + Task 1 names.
- [ ] **Step 2:** Write `screen.js` scenes listed above; wire `phone-viewer.js` to import it (the tour still calls old states → temporarily map `home`→`home0` so the page loads).
- [ ] **Step 3:** Serve, open `?screen=work1:2.5&still=1`, `?screen=listen2:3&still=1`, `?screen=home2:1&still=1`; screenshot each; place next to mockup screens 2, 5, 13 at matching scale. Expected: same layout, type sizes, colours; no console errors.
- [ ] **Step 4:** Photo failure: rename `photos/` temporarily, load `?screen=listen2:3&still=1` → docked thumbnail is a surface block, no console error. Restore.
- [ ] **Step 5:** Commit.

### Task 4: Screen module, part 2: decision, detail, approve, done, preview

**Files:**
- Modify: `screen.js`

**Interfaces:**
- Consumes: Task 3 `PLACES`, `MESSAGE`, primitives, `s.targets`.
- Draws: `decide` (nav line "best bibimbap · Kreuzberg" / "3 steps ›"; map strip top 34→ ×2 with the mockup's road/water paths, pins 1–3, you-dot; carousel at the bottom, cards 238 px wide ×2, mosaic + name + PICK tag + star rating + kind line + orange-dot why; auto-swipes to card 2 at ~2.0 s and back at ~4.0 s with ease-out, active pin orange + larger, dots none), `detail` (nav "‹ 3 places" / name; full card: mosaic 132 ×2, name + rating, kind line with "open Fri until 22:30 · 6 min walk", two review lines with star icon, Reserve (orange outline) / Directions (grey outline)), `approve` (nav "‹ <pick>" / "Fri for 4 · 3 steps ›", two *will* steps, question card: "WhatsApp · FriendC, FriendB, ColleagueA", message 16 px, **Book & send** solid orange + Edit grey outline), `done` ("Booked." 34/300 + three ✓ steps), `preview` (steps + card "New widget · home screen", "Steps today", 6,412 big, orange bar 64 %, "Goal 10,000 · usually 7,800 by tonight", **Add to home** solid orange (creating commits, mockup 12) + Change).
- Button press feedback: the tapped button darkens for 160 ms when the tour calls `s.press(name)`. Produces `s.press(target: string): void`.

- [ ] **Step 1:** Implement the five scenes + `s.press`.
- [ ] **Step 2:** Stills `?screen=decide:1&still=1`, `decide:3`, `detail:1`, `approve:1`, `done:1.5`, `preview:2.5`; compare side by side with mockup 3a, 3b, 4, 7, 8, 12. Expected: matches; `s.targets` rects outline the right buttons (temporary `?debug=targets` stroke).
- [ ] **Step 3:** Commit.

### Task 5: Tour wiring and render-on-change

**Files:**
- Modify: `phone-viewer.js` (`runTour`, wait helpers, pointer handlers, render loop, screen-plane hit testing)

**Interfaces:**
- Consumes: `createWaits` (Task 2), `makeScreen` / `s.targets` / `s.press` / `s.clear` / `s.draw` (Tasks 3–4).
- Sequence (spec table): `off` (`s.clear()`) → press Power → `boot` (fly to screen, 3.2 s) → `home0` 0.8 s → hold Action (said `bibimbap`) → `listen1` → particle agent beat 1.2 s → `work1` ~4 s → `decide` ~5 s → tap `pickCard` → `detail` 2.5 s → hold Action (`friday`) → `listen2` → `work2` ~4 s → `approve` → tap `bookSend` → `done` 3 s → `home1` 3 s → hold Action (`steps`) → `listen3` → `work3` ~3.5 s → `preview` → tap `addHome` → `home2` 3 s → fly to buttons → press Power "Replay" (auto 6 s).
- Labels: "Turn on", "Hold to speak", "Tap <pick>", "Hold to ask", "Book & send", "Hold to ask", "Add to home", "Replay".
- Screen-tap hit testing reads the current `s.targets[pending.name]` (replaces `screenRects`); the arrow anchors to that rect's centre on the screen plane.
- Pointer rules: `pointerdown` on a target → `waits.targetDown`; any other pointerdown/move-with-buttons/wheel on the canvas → `waits.input()`; `pointerup` → `targetUp`; `pointercancel`, `lostpointercapture`, window `blur` → `cancel`. Remove `tookOver`. `onResume` → `flyTo(views.screen, 1.1, 0.68)` when the screen is on, else `views.buttons`.
- Render loop: call `s.draw(now)` every frame while on; request another frame only if it returned true, or controls/fly/agent/settling need it (today's line 330 minus `screen.state !== 'off'`).
- Keep `?screen=state:sec` freeze and `?still=1`.

- [ ] **Step 1:** Implement the wiring above.
- [ ] **Step 2:** Manual run in a headed browser: full loop hands-off; then one loop pressing every target yourself; tap the screen off-target during `decide` → nothing advances.
- [ ] **Step 3:** In devtools, log `s.draw` returns on `home1` for 3 s → all false after the entrance animation.
- [ ] **Step 4:** Commit.

### Task 6: Page touches, playback check, review, docs

**Files:**
- Modify: `index.html` (font link adds Hanken Grotesk 600;700;800; caption under the phone; `.tour.dark` colours to warm neutrals: hue 60, chroma ≤ 0.005)
- Create: `playcheck.py`
- Modify (misura worktree): `agent-phone/docs/SYSTEM.md`

**Interfaces:**
- `phone-viewer.js` exposes `window.__tour = { loops: number, state: string }` (incremented at Replay) for the check.
- `playcheck.py`: Playwright Chromium (same path/flags as `capture.py`, GPU-capable launch: `--use-angle=swiftshader` fallback), loads `/` without `still`, at 1280×800 and 390×844; collects console errors; waits until `__tour.loops >= 2` (timeout 200 s); second run: at `listen1` dispatch pointerdown on the Action key then `pointercancel`, at `decide` drag-orbit 200 px and release → asserts state reaches `approve` within 40 s; records mean frame time over 10 s of playback at 390×844 via `requestAnimationFrame` deltas. Prints PASS/FAIL per check.

- [ ] **Step 1:** `index.html` changes; caption text exactly "Concept. Places, ratings and photos are illustrative."
- [ ] **Step 2:** Write `playcheck.py`; run `python3 playcheck.py`. Expected: two loops PASS, recovery PASS, zero console errors, no horizontal overflow at 390 px, frame time reported (flag if mean > 20 ms).
- [ ] **Step 3:** Stills sheet: every scene next to its mockup screen in one PNG for the maintainer.
- [ ] **Step 4:** Commit; Codex review of the inner-repo diff since the baseline (`codex review` / `codex exec` with `< /dev/null`, model Sol). Triage findings, fix, re-run playcheck.
- [ ] **Step 5:** SYSTEM.md: §5 2(b) done (pending his review), decisions-log lines (fictional places + Unsplash photos + caption; map full-screen cut from the tour; walk times shown in the tour as target), §8 index adds the spec. `python3 scripts/publish-system.py`, republish `build/system.html` to the artifact. Commit.

---

## Amendments from Codex plan review (11 Nov) — these override the tasks above

1. **Task 3:** don't delete `THREADS`, `SCREEN_RECTS` or the old tour in Task 3. Task 3 adds `screen.js` and switches the import only behind `?newscreen=1` (old screen stays default) so the page keeps working; Task 5 removes the old code and makes the new screen default.
2. **Tasks 3/5:** call site is `makeScreen({ font: SCREEN_FONT })`.
3. **Task 5, wake-up contract:** `makeScreen({ font, onChange })`; the screen calls `onChange()` (= `requestRender`) when `set()` is called, a photo finishes loading, or `press()` runs; a 15 s interval calls `requestRender()` so the clock minute updates.
4. **Task 5:** on `cancel`, lost capture, blur and idle completion also `controls.enabled = true` and `pressVisual(name, false)`. `createWaits` takes `onRelease(name)` for this; tested in Task 2 ("cancel calls onRelease").
5. **Tasks 3/5:** add state `think` (blank screen, particle agent visible) for the 1.2 s beat after each listen. Update `makeAgent().update()`'s state checks: old `listening` → `listen1|listen2|listen3`, old `thinking` → `think`.
6. **Task 2:** `press(name, autoMs?)` and `hold(name, heardMs, onStart, autoMs?)` take a per-wait override; Replay uses 6000. Test: "press with autoMs 6000 resolves at 6000, not 3500".
7. **Task 3:** `work2` also shows the docked card ("Reserving" / pick name + thumbnail), as in mockup 6.
8. **Task 3:** said sizes on canvas: `listen1` / `listen3` 42 px (21/300 ×2), `listen2` 38 px (19/300 ×2).
9. **Task 2:** `onResume` returns a Promise (`flyTo`); the wait awaits it before resolving. Test: "resolve waits for onResume's promise".
10. **Task 6 playcheck:** recovery run presses + holds the Action key while the tour waits at the listen1 hold (before listening starts), asserts `__tour.state === 'listen1'`, dispatches `pointercancel`, asserts the tour reaches `work1`; at `decide`, drags 200 px, asserts the camera moved (`__tour.cameraAz` changed), then within 12 s asserts it returned to the screen view and the tour reaches `approve`.
11. **Task 3 step 4:** expected photo 404s are allowed; fail only on uncaught exceptions (`pageerror`), and run `?newscreen=1` playback through `decide` → `detail`, not just a still.
12. **Tasks 5/6 perf:** `screen.uploads` counter increments on each `texture.needsUpdate = true`; playcheck asserts it stays flat for 2 s once `home1` and `decide` have settled, plus mean frame time at 390×844.

`window.__tour` therefore exposes `{ loops, state, cameraAz, uploads }`.
