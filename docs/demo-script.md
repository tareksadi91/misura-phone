# Demo 1 acceptance script

What "done" looks like, step by step, on the maintainer's Nothing Phone (3a) with real accounts. Each step says what passes and what happens if a route fails. The agent decides how; this script only checks outcomes (brain design §4: flows are tests, not code).

Rules for every step: no app opens in front; commits are meant to wait for an Approve card showing the exact text (a spoken "go" can also approve, and the gate has known gaps, see docs/OSS_AUDIT.md); every failure shows a card that says what happened and whether anything went out.

## Run 1: dinner out (the maintainer's version, 10 Nov)

| # | He does | Passes when | If a route fails |
|---|---|---|---|
| 1 | "Top restaurant recommendations near me for tonight." | Progress card, then 4–6 real places with a why line each, within ~1 min (Instinct) or ~10 s (model + Places) | Instinct silent → model + Places answers; card says which |
| 2 | "Show them on a map." | Same card becomes a map with pins | No coordinates → the list stays, says so |
| 3 | Taps a place / "opening times?" | Hours for tonight on the card | Hours unknown → says so, links Maps |
| 4 | "Take me there." | Route card: time, distance, "Open in Maps" | Embedded navigation is a later milestone |
| 5 | "Tell FriendA we'll be there in 5 minutes." | Approve card: FriendA, WhatsApp, exact text → tap → Sent with proof | No live notification for FriendA and hidden screen fails → card says "Can't reach FriendA without opening WhatsApp" + Open chat |
| 6 | Locks the phone during step 1 | Card on the sleep screen, redacted; answer still arrives | — |

## Run 2: coffee with friends (spec hero run, kept)

| # | He does | Passes when | If a route fails |
|---|---|---|---|
| 1 | "Cool place for coffee near Kreuzberg on Saturday." | Picks with why lines | as Run 1 |
| 2 | "Message FriendB and ColleagueA: coffee Saturday 11 at [place]?" | One Approve card per person; each sent and tracked on its own | as Run 1 step 5 |
| 3 | Both reply | Card "Both said yes" (or who said what) | **Milestone M3, not built yet** |
| 4 | Tap → calendar | Event with place in his calendar | Invites to others: **milestone M4** |

## Run 3: a client asks to meet

| # | He does | Passes when |
|---|---|---|
| 1 | Client writes on WhatsApp or email | Incoming card with their words |
| 2 | "Send them my Calendly" / picks a free slot | Approve card on the same channel → Sent |

## Run 4: anything on the phone (12 Nov)

No script: three jobs he makes up on the day, by voice, each in an app Misura has no special code for. Passes when the job is really done in the app (checked by opening it), no app showed on screen, and each step line said what was happening. Emulator baseline: 7 of 10 (`UseAppEval`).

## Milestones (order)

- **M0 (done, emulator):** plumbing, cards, sends with proof, no silent failures, WhatsApp-ready experiment.
- **M1 (phone arrives, first):** Phase B: hidden screen + WhatsApp, companion notifications, Instinct on WhatsApp, decisive experiment, sleep screen on device. Gate: if the decisive experiment fails, the route changes before more card work.
- **M2:** brain read-only slice (answers, places, map, hours, route summary) → then messaging tools on the routes M1 proved. Run 1 passes. *11 Nov: Run 1 up to the decision runs on the emulator in the new interaction model (steps, carousel + map, detail, docked follow-up).*
- **M3:** tracking replies from several people ("both said yes").
- **M4:** calendar events and invites to others.
- **M5:** embedded navigation (walk/drive test picks the SDK).
- **M6:** voice: Essential Key or visible talk control.
