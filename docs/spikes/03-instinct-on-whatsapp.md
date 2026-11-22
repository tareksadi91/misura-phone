# Spike 03a: Instinct on WhatsApp (no Nothing phone needed)

Spec §7 test 3: Instinct answers reliably over WhatsApp, and Misura can tell when a multi-message answer is complete. This half checks Instinct's behavior from the maintainer's current phone. The capture half (companion notifications on the 3a) is Phase B.

Why it matters: Misura treats an answer as complete after **20 s of silence** (`AnswerReady.DEFAULT_QUIET_MS`). If Instinct pauses longer mid-answer, the card would show half an answer.

## Setup
1. Move Instinct to WhatsApp as its one channel (spec §5). Note how: number, bot, or contact.
2. Confirm it recognises you (answers as your agent, with your context).

## Three requests (send each, don't type while it answers)
1. "Find me a cool place for coffee near Kreuzberg on Saturday."
2. "Top 3 dinner places near Prinzenstraße tonight, with why each fits me."
3. One of your own, the kind you'd really ask.

## Note for each (fill the table)

| # | Time to first message | Messages in the answer | Longest gap between messages | Links? | Lists/numbering? | Total time | Anything odd |
|---|---|---|---|---|---|---|---|
| 1 | | | | | | | |
| 2 | | | | | | | |
| 3 | | | | | | | |

Also note:
- Does it ever send "thinking…"/"one moment" first, then the real answer?
- Does it edit or delete messages after sending?
- Does it reply in English or German?
- Screenshots of one full answer (keep locally; don't commit people's messages).

## Decision this feeds
- Longest gap > 20 s → raise the quiet window or detect "done" from the content.
- Answers as one long numbered message → the brain splits it into option rows.
- Unreliable or unrecognised → spec fallback: model + Places search only.

## His report (11 Nov 2026, before the phone)
- Instinct works on WhatsApp again.
- Normal replies: 5–10 s. Research (places, artifacts): can take **minutes**.
- So the 20 s window holds for normal replies but cuts research replies short. The fix depends on whether Instinct sends a short "on it" message first: check this on the phone (Task 14) before changing `AnswerReady.DEFAULT_QUIET_MS`.
- Instinct puts an **emoji reaction on the request message** when it starts. Plan: reaction seen = "working", so wait for the real reply (long ceiling, minutes); after the first reply message, the 20 s window applies as now. Check on the phone: does WhatsApp post a notification for the reaction (Settings → Notifications → reactions on)? If not, read it via the hidden screen. Also check whether the reaction changes or disappears when it's done.
- **Test 11 Nov ("find a good deal for a Nothing Phone 4a"):** 👀 after 8 s → results 4 min 33 s later (arrived as a notification) → reaction changed to ✅ with the results.
- **Done rule (build in Task 14):** ✅ on the request = answer complete, show it. 👀 = still working, wait (ceiling ~10 min, then Problem card). Reaction not seen → fall back to the 20 s quiet window. Still to check on the phone: whether the 👀/✅ change itself shows up as a notification, or only on the hidden screen.
