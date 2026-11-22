# Misura landing-page tour: square flow port

Date: 11 Nov 2026. Build-plan item SYSTEM.md §5 "Now" 2(b).

## Why

the maintainer wants a link to send to two possible collaborators (the hardware collaborator, hardware engineer; the Android collaborator, Android engineer) that explains the idea in about a minute. The 3D square phone on the landing page is the vision piece (SYSTEM.md §1, §3). Its current tour still shows the old chat-bubble model (agent replies as chat lines with coloured app badges), which contradicts the current interaction model ("screen talks, cards ask"). The tour has to show the current model, as drawn in `docs/design/flow-mockup.html`.

Audience does not change scope: port the flow as specced (his call).

## Goals

- The 3D phone's screen plays the square flow from `flow-mockup.html`, screen for screen, faithful to its layout, type and tokens.
- Works hands-off (auto-advances) and hands-on (viewer presses keys / taps the screen where the tour points).
- Real food and restaurant photos instead of the grey placeholders.
- Local only until the maintainer reviews; then deploy to the existing Vercel project (`agent-phone-concept.vercel.app`) on his go.

## Non-goals

- No new page sections, copy rewrite, or "what's built" proof section (his call: shouldn't impact the plan).
- No change to the 3D model, materials, lighting or orbit controls.
- No real agent, no network calls at runtime besides the existing CDN imports and fonts.
- The optional full-screen map (mockup screen 3c) is cut from the tour.

## Anti-goals

- Anything that reads as a claim about a real business: no real restaurant names next to invented ratings, reviews and stock photos.
- Drifting from the design system: no glows, gradients, blue-tinted greys, coloured app badges, chat bubbles.
- A tour that stalls when nobody touches it.

## Where

- Code: the former `landing-astra/` folder (not in this repository) (`phone-viewer.js`, `index.html`). No build step; served with `python3 -m http.server 8736`.
- Git: `landing-astra/` had its own repository, separate from this one.
- Photos go in `landing-astra/photos/`, resized for a 584×706 canvas (≤ ~600 px long edge, JPEG ~q80), so the page stays light.

## The run (the maintainer agreed to the playback)

Screen is the existing canvas, 584×706 at 2× (`makeScreen()`). Mockup is drawn at half size (292×353), so every mockup px value ×2.

| # | Screen | Advance |
|---|---|---|
| 0 | Off → press power → boot (particle agent + wordmark, as today) | press Power |
| 1 | Home: clock (mockup screen 9 layout, "coming up" empty or with Sat/Mon only) | auto |
| 2 | Listening: "Find the best bibimbap in Kreuzberg." types in (said style), waveform + "Listening" | hold Action key |
| 3 | Working: echo + steps appearing one by one, ● → ✓ ("Found 9 Korean places nearby", "Read reviews, kept 3 for bibimbap", "Checking who's open Friday") | auto |
| 4 | Decide: nav line, map strip on top with pins + you-dot, place carousel at the bottom; carousel auto-swipes once to card 2 and back, active pin turns orange | auto |
| 5 | Tap pick card → place detail fills the screen (‹ 3 places, mosaic, rating, hours line, two review lines, Reserve / Directions) | tap the pick card |
| 6 | Talk to the card: said text "Friday 19:30 for four, with FriendC, FriendB and ColleagueA. Let them know." + "Listening · about <place>", docked card at the bottom | hold Action key |
| 7 | Working again: echo + steps, docked card stays | auto |
| 8 | Approve: nav line, two *will* steps, question card with the exact WhatsApp message, **Book & send** / Edit | tap Book & send |
| 9 | Done: "Booked." outcome + three ✓ steps | auto |
| 10 | Home: clock + "Coming up" with the new event on top in orange, "2 of 3 said yes" | auto |
| 11 | Listening: "Build me a little step counter for my home screen." | hold Action key |
| 12 | Working: steps | auto |
| 13 | Preview card: "New widget · home screen", Steps today 6,412, bar, **Add to home** / Change | tap Add to home |
| 14 | Home with the step widget top right + coming up | auto, then Replay on Power |

Text, numbers, step labels and layout come from `flow-mockup.html` unless this doc says otherwise.

## Interaction

Keep the current tour mechanics in `runTour()`: arrow/label on the next target, `waitPress` / `waitHold`, auto-advance after ~3.5 s, Replay at the end. Fix one stall (my call, from the anti-goal): today any pointerdown sets `tookOver` for good, so a viewer who orbits the phone and stops leaves the tour stuck. New rule: orbiting/dragging never counts as taking over; pressing a tour target does, and auto-advance comes back after ~8 s with no input. Recovery must actually work (Codex review):
- Pending waits re-arm: the auto-advance timer restarts after the idle window instead of firing once; a started hold also completes on `pointercancel`, lost pointer capture, or the idle window, not only on `pointerup`.
- When auto-play resumes after the viewer orbited, the camera flies back to the screen view first (`flyTo(views.screen…)`) so playback never runs behind the phone.
- Entering `off` (start and Replay) clears the screen canvas and uploads it once, so the last screen doesn't stay lit until boot. New screen tap targets (pick card, Book & send, Add to home) use the existing `SCREEN_RECTS` hit-testing on the canvas, replacing the cat-widget rects. Keep the `?screen=state:sec` freeze param working for stills and checks.

## Look

Port from the mockup's CSS, not from memory (the maintainer rejected a past port that "doesn't look like the design"):

- Tokens: screen #080706, surface #32302e, text #efeeec, text-2 #adaaa8, paper #efeeec, ink #110f0e, orange #f47b30, you-blue #4285f4, star #fbbc04. Hanken Grotesk. Corners nest 23 → 17 → 11 (×2 on canvas).
- Remove the screen's orange radial glow and blue-tinted neutrals (BG #06080d, DIM, SOFT). Keep the fine grain only if it's invisible at normal viewing (my call: drop it if in doubt).
- Step icons: thin line icons in text-2 (the mockup's SVG paths drawn as Path2D), no app badges.
- Only the screen and the tour label that sits over it go warm-neutral; the page around the phone keeps its current colours (my call). Font link in `index.html` adds Hanken Grotesk 600–800 (the mockup uses them).
- Particle agent stays for boot and between "said" and steps (it's the page's 3D signature; it isn't on the screen canvas).

## Content changes from the mockup (my calls)

- Walk times ("6 min", "6 min walk") stay in the tour: SYSTEM.md cuts them in the app only until the phone has a real location; the tour shows the target, like the multi-effect Book & send (decisions log, 11 Nov). Noted in the decisions log on finish.
- A one-line caption under the phone on the page: "Concept. Places, ratings and photos are illustrative." (Codex review: the mockup's placeholder notice would otherwise vanish.) Plain, small, page styles; the only `index.html` copy change.

- Restaurant names fictional: three invented names, each checked by a web search to not be a real Berlin restaurant before use. Ratings, review counts, reviews, "why" lines stay invented but attached to invented places.
- Friends' names stay (FriendC, FriendB, ColleagueA); they're already fictional.
- Photos: Unsplash (free license; attribution not required). 3 per place for the mosaic (dish, room, table) + the docked thumbnail. Fetch via the gstack browse tool (plain curl is blocked). Keep a `photos/CREDITS.md` with photographer + Unsplash URL per file.

## Verification

- Playback check without `still=1` (`capture.py` always sets it, so it can't prove playback): a hands-off run loops twice with no console errors, plus one run where input is abandoned mid-hold and mid-orbit and the tour recovers. Browse tool `--headed` for WebGL (past notes) or headless Chrome via `CHROME_PATH=~/.local/bin/chrome-nokeychain`.
- Performance: the screen texture (1168×1412) is only redrawn and uploaded while something on it is changing (typing, steps, swipes, clock minute); static screens upload nothing. Frame-time check at mobile size during playback.
- Freeze-frame stills of each screen (`?screen=…&still=1`) placed side by side with the matching mockup screen; the maintainer's eye decides.
- Mobile width: no horizontal overflow (existing capture check).
- Codex review of the diff before handing over (standing rule).

## SYSTEM.md

No contradiction with SYSTEM.md. On finish: mark §5 2(b) done, add a decisions-log line (tour uses fictional place names + Unsplash photos; map full-screen cut from the tour), republish the artifact.

## Left to the builder

Exact timings per screen (aim: full loop ≈ 60–75 s hands-off), easing, how steps animate in, carousel swipe animation, how the arrow points at on-screen targets, code structure inside `makeScreen()`.
