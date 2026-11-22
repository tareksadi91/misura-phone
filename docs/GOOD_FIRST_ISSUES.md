# Good first issues

Scoped proposals from [SYSTEM.md §5](SYSTEM.md#5-build-plan) and [§7](SYSTEM.md#7-open-questions), not promises or assigned work. Check current code before starting; the app list changed on 18 November. Paths below are relative to the repo root and were checked to exist. Read [CONTRIBUTING.md](../CONTRIBUTING.md) for safety rules.

## 1. Test one real Telegram notification reply

**Why:** Generic reply extraction alone does not prove Telegram's notification bundling or send confirmation works.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/notify/Extract.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/hands/NotificationReplyHand.kt`, `misura-app/app/src/androidTest/kotlin/com/misura/phone/notify/ReplyHandTest.kt`.

**Done when:** A redacted report records Telegram/Android versions, single and bundled notifications, where the reply action lives, and confirmation behavior. Use a consenting test recipient and an explicit approval. Report unsupported behavior rather than claiming Telegram support. Generic chat-app discovery already exists; this issue is about real-app behavior.

## 2. Ask which person when chat names collide

**Why:** “Which contact?” (two chats share a first name) remains unresolved. The quick-reply picker refuses ambiguous matches; the hidden app route can open one without asking.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/hands/ChatDrafter.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/hands/UseApp.kt`, `misura-app/core/src/main/kotlin/com/misura/core/agent/Agent.kt`, `misura-app/app/src/test/kotlin/com/misura/phone/hands/PickChatTest.kt`.

**Done when:** Two matching chats produce a choice before preparing a send. Choosing one binds the draft to that conversation; the final approval card shows the recipient and exact message. Test ambiguity without sending to a real person.

## 3. Add a read-only notes screen

**Why:** Misura keeps learned notes until you change or forget them, but there is no screen to inspect them.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/brain/Remember.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/HomeScreen.kt`.

**Done when:** A reachable screen shows existing dated notes and an empty state. It reads the existing store without writing or expanding retention. Editing stays outside this first issue; existing spoken correction/forget behavior remains available.

## 4. Explain the empty home during cold start

**Why:** SYSTEM.md records about three seconds of empty home on cold start.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/HomeActivity.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/HomeScreen.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt`.

**Done when:** A recorded cold start establishes whether the gap still occurs after recent startup changes. If it does, home shows an appropriate loading state until local state arrives, without flashing “no tasks” or blocking input. Report before/after timing and device.

## 5. Hide apps in the app list

**Why:** Pinning exists; hiding apps is still listed as later work.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/screen/AppSheet.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/screen/AppSheetLogic.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/store/Pins.kt`, `misura-app/app/src/test/kotlin/com/misura/phone/AppSheetLogicTest.kt`.

**Done when:** An app can be hidden and restored through a quiet Hidden apps entry. Hidden state survives restart; hidden apps leave the ordinary list and its search. Define pinned-app behavior explicitly and test it. Hiding never disables or uninstalls an app.

## 6. Open the app list with swipe up too

**Why:** Swipe down exists; swipe up and a TalkBack action remain proposals.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/HomeScreen.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/screen/AppSheetMotion.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/screen/AppSheet.kt`.

**Done when:** Swipe up from plain home opens the same list; swipe down still works. Add a TalkBack open-apps action. Check task scrolling, system gesture edges and Back; do not introduce a second sheet or search state.

## 7. Explain vibrate mode after a sound-setting change

**Why:** Turning a sound setting on may appear ineffective while the phone is on vibrate.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/hands/UseApp.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt`, `misura-app/app/src/androidTest/kotlin/com/misura/phone/hands/UseAppEval.kt`.

**Done when:** A completed sound-setting task can say the phone is on vibrate using an observed system value. It never changes ringer mode without a request. A focused check distinguishes normal, vibrate and silent modes; no device-specific setting label is hard-coded.

## 8. File a second phone model test report

**Why:** Only one physical model has been tested. Locked-bootloader operation is unverified.

**Where:** `docs/spikes/00-device-setup.md`, `misura-app/app/src/main/kotlin/com/misura/phone/hidden/HiddenScreen.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/voice/Voice.kt`.

**Done when:** A redacted issue records another model's Android version, bootloader state and Shizuku version; hidden-screen reading, Hold to talk or side-key fallback, and behavior after reboot. Separate observed failures from untested checks. Do not unlock or wipe a phone for this task; a locked-device result is useful evidence.

## 9. Verify Keystore storage on hardware

**Why:** Shared Keystore encryption and plaintext migration cover credentials, notes, Room payloads and item files. Local credential onboarding is implemented and tested; hardware key-loss behavior and fresh Cloud-project setup remain unverified.

**Where:** `misura-app/app/src/debug/kotlin/com/misura/phone/debug/DebugReceiver.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/MisuraGraph.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/brain/ServiceAccountToken.kt`, `misura-app/app/src/test/kotlin/com/misura/phone/brain/ServiceAccountTokenTest.kt`.

**Done when:** Focused tests pass and a synthetic device upgrade verifies credential migration, debug provisioning, background reads, restart persistence and unavailable-key behavior without logging secrets. Review the intentional plaintext-write fallback separately. Keystore does not make a compromised running app safe.

## 10. Check place cards with TalkBack focus

**Why:** Carousel freezes under a finger; its behavior under TalkBack focus remains unverified.

**Where:** `misura-app/app/src/main/kotlin/com/misura/phone/screen/PlaceCards.kt`, `misura-app/app/src/main/kotlin/com/misura/phone/screen/PlaceMap.kt`, `misura-app/app/src/androidTest/kotlin/com/misura/phone/screen/PlaceCardsTest.kt`.

**Done when:** A phone report covers moving focus across cards and refreshing results after a follow-up. If focus is lost or cards move away, propose the smallest supported fix with a regression check. Do not assume Compose exposes an accessibility-focus signal; SYSTEM.md records that limitation.
