# 00 — Day-one setup (Nothing Phone 3a)

Plan Task 11. Do it in this order; step 1 wipes the phone, so nothing before it is worth setting up.
Tick boxes and fill the blanks as you go; this file becomes the record.

## Record, 15 Nov 2026 (phone A059, serial YOUR_PHONE_SERIAL)

- Build: `Nothing/AsteroidsEEA/Asteroids:16/BQ2A.250721.001-BP2A.250605.031.A3/2609281204` — **Android 16**, not 17 as the spec assumed. No OS update until Phase B is through.
- §1 bootloader unlocked (`ro.boot.flash.locked=0`, boot state orange).
- §4 done over adb: Misura, testapp, Shizuku fork installed (fork APK kept locally); listener, accessibility, overlay, mic grants; Misura = home; doze whitelist for Misura + Shizuku; screen stays on while plugged in. Key, briefing and Exa key pushed.
- Shizuku started **over adb** (the `libshizuku.so` starter), not yet by wireless debugging: it will not come back by itself after a reboot until §4's pairing and §6 are done.
- §5 hidden display: testapp landed on display 2 ("misura-hidden"), probe stayed on display 0. Pass.
- Live look-up ("weather in Berlin right now"): answer in 14 s, service-account sign-in works on the phone.
- §5b `use_app`: "set a timer for 2 minutes" → 5 taps in Clock on the hidden screen, 30 s, `TIMER_EXPIRED` alarm present. Pass (asked by adb, not by voice).
- Real voice: hold-to-talk heard him (place search + follow-up by voice). Pass.
- Essential Key: raw key 250 on `/dev/input/event0` (gpio-keys), no key code in the layout file; `NtEssentialKeyImpl` in the system server opens Essential Space. Misura's accessibility key filter sees press and release (log tag `MisuraKey`); with `com.nothing.ntessentialrecorder` disabled nothing else opens. Asked "what time is it in Tokyo" by key: pass. `sendevent` to that device is refused for shell, so the key can't be faked from the laptop.
- WhatsApp (linked as companion): "what is the last message from FriendA on WhatsApp" → `use_app` on the hidden screen, 1 tap + done, 20 s, answer on screen. Pass (he still has to confirm the words were right).
- Key from another app + button gone: built, on the phone, his check pending.
- §4 Shizuku paired by wireless debugging (the notification stayed on "Pairing in progress" although it had worked; Start from the app proved it). Start on boot ON, Watchdog ON, TCP mode ON (default).
- §6 reboot test: `Background started FGS: Allowed`, `AdbStartWorker` SUCCESS, `shizuku_server` up 25 s after boot-complete, before unlock. Misura home, accessibility and the Essential handler's disabled state all survived. Hidden WhatsApp read right after: pass.
- He confirmed the WhatsApp answer was her real last message.
- Open, need his hands: §3 notifications, WhatsApp send gate, Nothing battery setting, PIN + fingerprint, spoken go/no, PIN + fingerprint (skipped for now, his call), key from another app.

## 0. Before the phone arrives (laptop)

- [ ] `brew install --cask android-platform-tools scrcpy` (adb, fastboot, scrcpy ≥ 3.x for `--new-display`)
- [ ] Build APKs: `cd agent-phone/misura-app && ./gradlew :app:assembleDebug :testapp:assembleDebug`
- [ ] Shizuku fork APK (v13.7.0 thedjchi) copied from the session scratchpad to a safe local folder outside git
- [ ] iCloud app-specific password for DAVx5 ready (appleid.apple.com → Sign-In and Security)
- [ ] FriendA available for ~15 min to send test messages

## 1. Unlock the bootloader (wipes)

- [ ] Skip setup quickly (no account), Settings → About phone → tap Build number 7× → Developer options
- [ ] Developer options → OEM unlocking ON (needs internet), USB debugging ON
- [ ] `adb reboot bootloader` → `fastboot flashing unlock` → confirm with volume keys → phone wipes and reboots
- [ ] Build number: `__________` (`adb shell getprop ro.build.fingerprint`)

## 2. Accounts and messaging

- [ ] Google account; Advanced Protection OFF
- [ ] WhatsApp → "Link as companion device" to the iPhone's account
- [ ] FriendA's WhatsApp is the test counterpart (test traffic comes from her account, never from the iPhone)
- [ ] DAVx5 with the iCloud app-specific password; contacts synced (check FriendA and Instinct appear)
- [ ] Instinct moved to WhatsApp

## 3. Notifications

- [ ] WhatsApp, Slack, Gmail: notifications ON, every channel Silent, hidden on lock screen

## 4. Misura

- [ ] Developer options → Wireless debugging ON (stays the Shizuku path)
- [ ] `adb install agent-phone/misura-app/app/build/outputs/apk/debug/app-debug.apk` and the testapp APK
- [ ] `adb install <path>/shizuku-v13.7.0-thedjchi.apk`
- [ ] Set Misura as home
- [ ] Grants:
  ```
  adb shell cmd notification allow_listener com.misura.phone/com.misura.phone.notify.MisuraListener
  adb shell settings put secure enabled_accessibility_services com.misura.phone/com.misura.phone.hidden.MisuraAccessibility
  adb shell appops set com.misura.phone SYSTEM_ALERT_WINDOW allow
  adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
  ```
- [ ] Battery: Misura and Shizuku → Unrestricted (Settings → Apps → Battery). Nothing's battery manager is the main unknown for survival.
- [ ] Start Shizuku from its app (Wireless debugging → Pair, then Start); open Misura's hidden-screen probe → Grant Shizuku → Allow all the time
- [ ] Real lock: set a PIN plus fingerprint (the emulator never tested this)

## 5. Verify

- [ ] After explicit package opt-in (README), a synthetic message appears as metadata in the debug ProbeActivity. Full notification text is no longer logged (OSS privacy fix, 19 Nov).
- [ ] Probe screen: open hidden display → launch testapp → lands on the hidden display
- [ ] Sleep screen: `adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd sleep --es variant ACTIVITY`, lock, wake → Misura's dark screen shows

## 5b. General hand and voice (added 12 Nov)

- [ ] Vertex token on the phone (service account, or pushed over adb for the first hour)
- [ ] `adb shell pm grant com.misura.phone android.permission.RECORD_AUDIO`, hold "Hold to talk", say "set a timer for two minutes" → words appear, timer exists in Clock
- [ ] "what's the last message from FriendA on WhatsApp?" → does `use_app` read WhatsApp on the hidden screen? Result: `____`
- [ ] During a long run, type in Misura's field: do the letters land in Misura? Does back still work? `____`
- [ ] Open Clock yourself, go home, ask for an alarm: does Clock stay off the screen? (worked on the emulator)
- [ ] Contacts: readable on the phone? `____`
- [ ] Commit gate on WhatsApp: ask for a message to FriendA through the app itself; does the send arrow count as "Send" (hidden label)? Card before anything goes out? `____`
- [ ] Essential Key: can Misura see press and release? `____`

## 6. The reboot test (decides how fragile hidden sends are)

- [ ] Shizuku app → enable Start on boot
- [ ] Reboot, connect Wi-Fi, unlock. Shizuku running by itself? `____` After how long? `____`
- [ ] Sleep screen shows "Hidden sends paused: Shizuku is off" until it is up, then the card goes away?

Why this might fail: Android 17 refused Misura's own boot-time start ("Background started FGS: Disallowed").
The fork's boot start may hit the same rule. If it does, every reboot needs a manual Start in the Shizuku app,
and the paused card is the only safety net. Record the result and the logcat line.

## Then

Plan Task 12 (scrcpy hidden-display test with WhatsApp). If WhatsApp fails there, stop before Task 13.

## Decisive experiment on real WhatsApp (Task 15)

1. Get a message from Instinct and from the friend (FriendA) so both chats have a live notification.
2. Open "Misura experiment" → WhatsApp → pick Instinct → type the request → Review ask → Ask Instinct.
3. When the answer card shows: pick a row → "Message someone about it" → FriendA → Send to FriendA.
4. Lock the phone mid-run once (spec: screen stays on Misura, phone locks).
5. Export the run and pull it (contains real messages: redact before committing):
   ```
   ID=$(adb logcat -d -s MisuraDebug | grep -oE "experiment task [0-9a-f-]+" | tail -1 | awk '{print $3}')
   adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd export --es task $ID
   adb pull $(adb logcat -d -s MisuraDebug | grep -oE "/storage/[^ ]+\.md" | tail -1) docs/spikes/
   ```
Only one-to-one chats with a reply button are offered; groups never are.
