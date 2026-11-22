# Misura Phone — Demo 1 design ("the errand runner")

> Historical design/build record. Notification collection and probe diagnostics are superseded by SYSTEM.md §4 (19 Nov 2026): explicit per-app opt-in, debug metadata only, no plaintext notification log. Follow the current README for setup.

Date: 2026-11-09 · Device: Nothing Phone (3a) · Owner: the maintainer (sole user)
Setup: the (3a) is a **secondary prototype phone**. His iPhone stays primary and keeps his number, banking and everything sensitive. No target date; riskiest things first.
Sketches: `.superpowers/brainstorm/43688-1794258066/content/` (`sketches-v1b`, `sketches-v2b`, `holistic-v1b`, `holistic-v3`, `rich-cards-v1`)
Background research: private platform explainer and deep-research report (not published)

## 1. Intent

Misura is an agent-first phone for people who want to spend **less** time on their phone. You say what you want, the agent gets it done across your apps, and shows you only the small interactive screen ("card") you need. Then you put the phone down.

The trap it removes: a tiny task (answer a text, check a reply, find a place) forces you into an app whose feed keeps you there. Today the maintainer works around this by screenshotting conversations to his Instinct agent and copy-pasting places into Google Maps. Demo 1 proves that an agent living **on** the phone needs no screenshots and no copy-paste: it already sees the conversation, and it acts in the right app, under the hood.

Positioning (context, not a requirement): the mindful consumer, not the busy professional (Raven's angle). "Calm without giving up capability." Openness and portable memory are part of the long-term vision; Demo 1 only takes a thin slice of it.

Demo 1 is a personal demo the maintainer uses himself. It is a fun/portfolio project, not a startup bet.

## 2. Definition of done

Demo 1 is done when the maintainer can run this scenario end to end on his own Nothing Phone (3a), with real accounts, without opening any app by hand and without any app flashing into the foreground:

**Hero run (coffee with friends):**
1. "Find me a cool place for coffee near Kreuzberg on Saturday." → running card ("Asking Instinct…").
2. Suggestions arrive → list card, ~4–6 picks, with why each fits his taste.
3. "Show them on a map." → the same card becomes an interactive map with pins.
4. He narrows by talking or tapping filter chips, picks one.
5. "Message FriendB and ColleagueA: coffee Saturday 11 at [place]?" → draft card per channel → he taps Send.
6. When they reply yes → card "Both said yes" → tap → calendar event created with the place.
7. On the day: "Take me there." → the card expands into turn-by-turn navigation inside Misura; swiping down shrinks it to a live card.

**Second run (scheduling a meeting):**
8. A client writes (WhatsApp or email) asking to meet. "Send them my Calendly" → draft reply on the same channel → Send. Or, if they proposed times: card with his free slots → tap one → reply sent + calendar invite.

Steps 5–7 of the hero run are the builder's interpretation of "run a message to my friends, schedule things, run with it"; the exact order may be adjusted as long as every capability is shown.

Also in scope but not showpieces: send a message to anyone ("tell FriendB I'm 10 min late"), quick directions ("how do I get to ColleagueB's"), play music he likes ("something calm for cooking") with player controls on a card.

## 3. Goals, non-goals, anti-goals

**Goals**
- Every use case above works under the hood from the agent screen.
- Cards render real, interactive UI (maps, lists, slots, player), not text.
- Every consequential action is a button.
- It feels like his: picks reflect his taste (coffee, shops) from his existing memory.

**Non-goals (later or never for Demo 1)**
- "Your space" as a product: capture, voice notes, screenshot filing, collections, memory write-back. That is project 2, brainstormed separately.
- Open platform plumbing (MCP server so other agents can use the phone, hosted backend). Design so it can be added; don't build it.
- Banking, Google Wallet, ID apps, Deutsche Bank. They do not need to work on this phone at all.
- Play Store distribution. Sideload only.
- Other users, onboarding, settings screens beyond what the maintainer needs.
- On-device AI models. Internet is required; offline = unusable is accepted.
- Rental cars and other "tap through many screens" flows (only if the hidden screen works and time allows).
- Cab booking (Uber, Bolt, FreeNow) is **not in the definition of done**. Build it after the hero run works. If the hidden screen works for WhatsApp but not for the cab app, the cab card falls back to "open in app".

**Anti-goals (would make Demo 1 a failure even if it technically works)**
- Apps visibly flashing into the foreground while the agent acts. The Misura screen always stays in front.
- A chat log or terminal-style text UI. The agent communicates through cards.
- Voice as the only way to confirm something important. Voice is imprecise; buttons decide.
- Notification noise. He should not see every notification; the agent consumes them.
- Feeds, badges, or anything that pulls him into scrolling.
- A Siri-style "I opened Google Maps for you." The agent does the thing.

## 4. UX (decided)

### 4.1 Three places, one direction
- **Sleep screen:** Misura's own full-screen design, shown over the lock screen when the phone wakes (via the overlay-permission exception to background launch limits, or an accessibility overlay; root/SystemUI only if both miss wakes): the particle-sun agent plus live, time-sensitive cards (navigation, a friend's reply, driver distance). Glance and put down. Acting on anything sensitive still requires unlocking. Nothing's always-on display can't be redesigned by an app; Android's ongoing "live update" notifications show there as the fallback. Optional extra: the Glyph lights via Nothing's developer kit (e.g. a filling light strip for ETA), if the kit supports the (3a).
- **Agent screen (home):** the main surface. Running tasks as cards, one hold-to-speak control. The particle-sun agent from the existing Misura concept is the visual identity (see `landing-astra/phone-viewer.js` `makeAgent()`).
- **Space (swipe right):** search bar on top, an empty slot reserved for "things worth keeping" (filled later, project 2), then all Android apps as a **plain text list**: no icons, no badges.

### 4.2 Ways into a real app (always the user's choice)
- "Open in app" escape on any card.
- Say "open Instagram."
- Type two letters in the space search.

### 4.3 The card system
- **Resting weight fits the content:** one line, line + image, small map, mini player.
- **Grow and shrink:** every card can expand to a full interactive screen and swipe back down to its resting size. Example: map card → full map with pins and filter chips → turn-by-turn navigation → shrunk live navigation card.
- **Lifecycle:** running (what it's doing + usual wait) → needs you (rises to top, buzzes once, shows its interactive UI) → live (only while something is happening; mirrored on sleep screen) → done (receipt that fades after ~1 h or on swipe).
- **Buttons commit, voice prepares:** sending, booking, creating an invite or ending navigation is always a tap on a card that shows the exact content (recipient, wording, time, price). Voice can start, steer and edit, never commit. If the content changes after he saw it, the card asks again.
- **Several tasks run in parallel**, each its own card; tasks survive the phone being put down.
- **Which incoming messages become a card (decided):** replies that belong to a running task (Instinct's answer, friends replying to a proposal, a client in a scheduling thread) always go to that task first. Otherwise: a direct message from a real person that asks something of him becomes a "needs you" card; groups, promos and newsletters are read but dropped silently; calls, alarms and two-factor codes reach him normally.

### 4.4 Visual direction
Follows the existing Misura concept: near-black/warm screen UI, Hanken Grotesk on the phone, orange reserved for the agent and for what needs you. Fat-marker sketches show structure only. Polish is the builder's job within this direction.

## 5. How each action happens (decided rule + routes)

Rule: **use a side door that needs no app UI; use the hidden screen only when there is no side door; never bring an app to the foreground unless the user asked.**

| Capability | Route |
|---|---|
| Read incoming messages (WhatsApp, Slack, LinkedIn, SMS, email apps) | WhatsApp runs on the (3a) as a linked companion device of his iPhone account. Notification access. Notifications stay ON but are hidden from the maintainer (silent, no banners, not on lock screen); the agent reads them. |
| Reply in an existing conversation | The notification's built-in reply action. Invisible. |
| Start a new conversation (no recent notification) | Hidden screen (below). No SMS fallback: the (3a) has no SIM. |
| Ask Instinct (research, recommendations) | Text it like any contact over **WhatsApp, linked to the (3a) as a companion device on his main account**, so Instinct recognises him. the maintainer moves Instinct to WhatsApp as its one channel (it keeps to one channel). Reply via the Instinct thread's notification reply action once a thread notification exists; otherwise the hidden screen. Instinct is slow (~1 min); the running card says so and the phone buzzes once when ready. Show a small "via Instinct" label. If Instinct doesn't answer, fall back to Claude + Google Places. |
| Calendar read/write, free-slot finding | Google Calendar's online API with his Google login: free/busy for slots, and events with guests created server-side so the invite is actually sent and can be checked. |
| Calendly link | Stored constant in his profile. |
| Contacts / addresses | His contacts live in iCloud (iPhone). Synced to the (3a) as normal Android contacts with DAVx5 (open-source CardDAV sync, iCloud app-specific password). WhatsApp's own name search on the hidden screen is the backup for finding a chat by name. |
| Maps, pins, place details | In-app interactive map (Google Maps SDK for Android) + place data (Google Places). |
| Navigation | Google's Navigation SDK is ruled out (commercial-only, policy updated 3 Nov 2026). Embedded candidates from research: **Ferrostar + MapLibre + Stadia Maps** (open source, Stadia free plan for non-commercial use) or **Mapbox Navigation SDK** (first 1,000 trips/month free; its terms need a separate licence for apps mainly meant for driving). One navigation session lives outside the card, so resizing or hiding the card never restarts guidance; GPS and voice run in a location foreground service started while Misura is visible. Choice made in Plan 2 by a real walk/drive test. |
| Music | Spotify App Remote, connected from the visible launcher when he asks, plays the chosen playlist or track while Spotify stays in the background; Web API with the (3a) as target device when Spotify is already available. Development-mode app owned by his Premium account (up to 5 allowlisted users). Unattended cold start while locked is unverified; a visible reconnect fallback exists. |
| Uber: price, options, confirm | Hidden screen (no public consumer ride API). Live ride status read from Uber's ongoing notification and redrawn as a Misura card. |
| Mail, docs, tasks (Google tools) | Google's online APIs (Gmail, Calendar, Drive) with his Google login, not browser automation and not tapping apps. |
| Taste profile | Read-only, compiled from his existing memory (Claude Code memory files, gbrain) into a profile the agent loads. Writing back is project 2. |

**Hidden screen:** Android can run apps on an invisible virtual display while the agent reads and taps there; this is how Gemini's app control works. It needs shell-level access (Shizuku over wireless debugging) or root. Root is acceptable: its main cost (banking, Wallet) doesn't matter for this phone. scrcpy proves apps can be launched onto a hidden display via shell. Whether WhatsApp and Uber behave correctly on it is **unproven**.

## 6. Architecture (decided)

- **Phone-only for Demo 1.** One Android app (Kotlin) that is the home screen (launcher), holds all state locally (running tasks, cards, lists), and calls Claude over the internet for thinking. No Mac mini, no hosted server.
- Keep three parts separate so they can move later: the agent's brain (planning, model calls), the card renderer (UI), and the "hands" (each route in §5 behind one interface). A future hosted brain or MCP plug should replace a part, not force a rewrite.
- **Device setup:** stock Nothing OS 5.0 / Android 17, sideloaded via USB. Advanced Protection stays OFF (Android 17 blocks non-tool accessibility when it is on). **Unlock the bootloader on day one (approved)**, before setting the phone up, because unlocking wipes the device. Root only when the hidden screen needs it.
- A foreground "Misura is working" status icon while the agent is actively working. Long waits (a friend replying in hours) are not kept alive by a running service: the task is saved and woken by the incoming message.

### 6.1 Execution controller (decided; this is what lets it scale to cab bookings)
One component owns every task and every side effect. The model proposes; cards display; the controller decides what actually runs. Minimum:
- **Saved task states** that survive the app dying or a reboot: planning, waiting (on a person or Instinct), needs approval, executing, checking, done, partly done, failed, cancelled, expired, and **outcome unknown** ("I tapped send, not sure it went through").
- **Each hand has four moves:** prepare (draft or quote), commit (send or book), check (did it really happen), cancel where possible. Each returns evidence, not just "success".
- **Approval is bound to the exact content** (recipient, channel, wording, time zone, price ceiling) and expires. Any change means asking again. Enforced in code, not by the model.
- **Never blindly retry a commit.** After an uncertain send or booking, check first ("Checking whether your message went out…"), then retry or ask him. Double taps are deduplicated.
- **One app action at a time** on the hidden screen; planning can run in parallel. If he opens the same app himself, the agent pauses.
- **Late answers don't overwrite.** An Instinct reply arriving after the fallback already answered is attached to its own request or dropped.
- **History is kept** even after a card fades: what was proposed, approved, sent, and the evidence.
- **Incoming messages and app screens are untrusted.** Text in a message can never grant the agent permission or change an approval.
Cab test case this must handle: quote expires during approval, surge changes the price, submit spins, the app dies, a payment check appears, the ride exists anyway. Right behaviour: "checking booking status", then reconcile or hand to him. Never a second booking.

## 7. Riskiest assumptions, test first (in this order)

Each test runs on the real phone and is judged by what the physical screen shows (record it), not by logs alone.

1. **Hidden screen for WhatsApp.** First with scrcpy from the Mac (is WhatsApp happy on a hidden display at all?), then on-phone through Shizuku with the USB cable unplugged: create the display, launch WhatsApp, read the screen, tap, type, send. Check that the keyboard never pops up on the real screen, that teardown doesn't throw WhatsApp onto the real screen, and that notifications still arrive while a chat is open on the hidden display. Root only for an operation that proves to need it.
2. **WhatsApp companion notifications.** Compare notification contents against the real chat for a long numbered Instinct reply with links, bursts of messages, edits, a locked phone, a muted chat. Reply through the notification repeatedly, including after the iPhone read the chat, and confirm exactly one message arrives each time.
3. **Instinct on WhatsApp:** answers reliably, and Misura can tell when a multi-message answer is complete.
4. **Task survival:** start a task, then lock, idle, battery saver, kill the app, reboot. Tasks come back and incoming replies still land. If Shizuku died with the reboot, Misura says so honestly.
5. **Sleep screen on wake:** Misura's screen appears over the lock screen when woken from home, from another app, after the app was killed, and after a reboot.
6. **Navigation stack:** research picks a licensed embedded option or confirms the Google Maps hand-off.
7. Picks quality: right branch, open on the day, fits his taste. Re-check hours before departure.

**First decisive end-to-end experiment** (after tests 1–2): a real Instinct request over WhatsApp → full answer captured → an approved WhatsApp reply to a friend, entirely on the phone, while the screen stays on Misura and the phone locks mid-run. If this fails, the hero route changes before any card work starts.

## 8. Calls made by Claude (the maintainer to check)

- **Where picks come from:** Instinct supplies the recommendations over WhatsApp (it finds fresh places). Claude turns its reply into structured picks, Google Places resolves each name to a pin, hours and address, and Claude adds the "why it fits you" line from the taste profile. If Instinct can't be reached by SMS or WhatsApp, the fallback is Claude + Places search directly, so the hero run never blocks on Instinct.
- **Messaging friends:** one draft per person, on whichever channel their most recent conversation uses. Build the notification-reply path first, so the hero run works even if the hidden screen fails, as long as each friend has a recent thread.
- **Sleep screen in Demo 1:** Misura's own screen over the lock screen (see 4.1), with live notifications on the always-on display as the fallback. The cat meds card is not in Demo 1.
- **Commit by button only** (see 4.3); voice never sends or books.
- **Calendar invites through Google's online API**, not the phone's local calendar, so a sent invite can be verified.
- **Navigation default** is the in-card route + next turn, with Google Maps hand-off for full voice guidance until research picks an embeddable option.
- **Contacts sync via DAVx5** from iCloud (ongoing sync rather than a one-off export).
- **Language:** Misura's own UI and voice are in English. Drafts are written in the language of the conversation they reply to. Detecting a "yes" works in German and English ("ja, passt" counts).

## 9. Left to the builder

- Choice of libraries (UI toolkit, speech-to-text, storage), project structure, Claude model and prompting.
- Card component set and visual polish within §4.4.
- How the taste profile is compiled and refreshed from his memory files.
- How the hidden screen is implemented (Shizuku vs root) based on test 1.
- Exact copy and timings (buzz, fade, filter chips).
- Whether music uses Spotify's web interface or links only.
- The order of building after the risk tests.

## 9. Amendment (10 Nov 2026): sequencing while the phone is in transit

Work done ahead of the §7 gate, with the maintainer's agreement after a Codex review of the sequencing:
- Compose cards from `docs/design/card-system.md` and send/alert hardening (no silent failures) were built on the emulator.
- The experiment screen now runs on real WhatsApp chats (picked by WhatsApp's own chat id), so Phase B day one is testing, not harness work.
- The brain is designed in `2026-11-10-misura-brain-design.md` (agent loop on Vertex Gemini; demo flows are test scenarios, not code). Only its phone-independent, read-only slice may be built before Phase B.

The gate stands: when the phone arrives, Phase B (Tasks 11–16) runs first. If the decisive experiment fails, the route changes before more card or brain work. Milestones and the acceptance script: `docs/demo-script.md`. Run 1 (dinner out: places → map → hours → route → "tell FriendA 5 min") is the maintainer's preferred showpiece; the coffee run stays as Run 2. Reply tracking, invites to others and embedded navigation are separate milestones (M3–M5), not part of the brain slice.
