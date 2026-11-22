# Misura Phone

Misura is an Android home screen that is an agent: you say what you want, and it works the apps for you on a hidden screen. Approval cards are meant to stop sends, payments and other commits until you confirm; the gate is label-based and has known gaps (below), so it is not a complete barrier.

## What it can do today

- Find places and show map cards with details from Places.
- Open and drive any installed app with a launch screen on a hidden display, when its screen is readable. Success varies by app.
- Record things found in apps as cards, with titles, prices and cropped photos; open their pages when tapped.
- Send messages through any unblocked chat app with a reply button behind an approval card (one-to-one MessagingStyle notification with an app-owned chat id); otherwise use the app on the hidden screen. New cross-app behavior and real Telegram/Signal/SMS replies remain unverified.
- Take voice input through a learned side key, or Hold to talk on phones without one.
- Show an app list with search and saved pins. Swipe down on home to open it.
- Keep dated notes from things you tell it. Ask it to remember or forget something; there is no notes screen yet.

## How it works

Choose Vertex, Gemini, OpenAI or Anthropic for the brain, memory checks and app-control model. Each active turn keeps its captured account, model and optional-service settings; changes apply next turn. It calls tools for places and web look-ups, or hands a goal to the general app hand. The hand reads screen elements as text and asks the model which action to take next. A commit gate stops recognized actions that need approval. The controller owns task state and binds message approval to the content shown on its card.

Modules: `misura-app/core` holds JVM logic; `misura-app/app` holds Android code; `misura-app/testapp` is a fake chat app for tests. [SYSTEM.md](docs/SYSTEM.md) is the source of truth.

Visible conversation history can continue after a provider change. Each provider receives the visible context needed for the next request. Provider-specific reasoning and signatures are reused only with the matching client. OpenAI requests use `store:false`; provider retention policies still apply. Interrupted tool batches preserve recorded results. If an action started and its outcome is unknown, check the target app before retrying.

## Safety

The intended safety rule: sending, paying, booking or deleting requires an approval card. This prototype has known limits:

- Messages also accept an exact spoken “go” through Hold to talk, followed by three seconds of Undo. Real-voice go/no recognition is **UNVERIFIED**.
- The commit gate checks button words and labels, and Enter with typed text is gated. It can still miss unlabeled controls, page-supplied labels such as “Search” or “Continue” that actually commit, and web editors Android does not mark editable. Do not treat it as a complete barrier. Banking and payments are out of scope.
- **Protected apps.** The agent never operates Shizuku, Misura itself, or the authenticator and password-manager apps on its built-in list. Banking and payment apps on its built-in list are off until you turn one on in **Misura settings → Apps Misura can use**, where you can also block any other app that has a launcher icon. Lists are exact package names (Android has no finance category), so an app missing from them is not protected until you block it. Enforced when the agent opens an app, starts or resumes work on the hidden screen, moves a screen to the front, replies or sends through an app, reads notifications or offers chats. Not enforced: an allowed app that redirects into a protected one mid-task (links, share sheets, login handoffs), Contacts/Calendar provider data, saved history from before a block, and a hidden task already running when you block its app (it finishes; the next one is refused). Notification switches below are separate: they stop reading, not app control. You can still open every app yourself.
- Memory changes are authorized in a separate model call using only your own utterances and the current date. Outside text, model replies, briefing and learned notes are excluded from that authorization across turns. Forgetting/correcting an old note can use a separate read-only match; interpreting your words and selecting the right old note still depend on the model. No notes screen yet.

Screen reading happens locally through Android accessibility. Screen text is then sent to the selected model provider for decisions, so this is not an offline assistant. Item photos are cropped and stored locally. Briefing and notes are included in model prompts; web look-ups send the question/date to the selected search service, or to the selected model provider for native search.

Credentials, briefing and notes use AES-GCM in app-private preferences when the per-install Android Keystore key is available. Encryption failure falls back to plaintext. Existing plaintext preferences migrate on first read. Local setup stores credentials for Vertex, Gemini, OpenAI and Anthropic, plus optional Google Cloud and external search credentials. All model roles use the selected provider. No extra search key is required. Native search uses the selected model provider; optional Exa, Tavily or Context.dev runs first when selected. On operational failure, the same model provider is tried, then an installed browser through app control. A complete no-results response stops further search. Google results requiring unsupported search-suggestion display return browser fallback rather than losing required attribution. Native search can incur provider charges and depends on account/model access. Debug adb provisioning remains shell-protected; backup and device transfer remain excluded. Prefer short-lived tokens on a dedicated test phone with a trusted adb host. Setup validates locally. First use can reveal unavailable models, missing permissions, quotas or billing limits. Do not distribute configured builds. See [SECURITY.md](SECURITY.md).

Notification capture **allows all packages by default** (except protected apps, see above) once Android notification-listener permission is granted; there is no per-app first-run opt-in. Misura's own notifications and group summaries are skipped; everything else is captured. A few dialer/clock packages and text that looks like a verification code are kept off automatic routing, but a notification read can still send any captured text, including codes, to the model. An optional exact-package block list stops capture/live reply access for selected apps; blocking does not erase saved history or restrict separately requested accessibility work. Disable Android notification access to stop system delivery entirely. Ask Misura to read current notifications: the model uses `read_notifications`, respects the same block list, and does not dismiss, mark read or reply. It cannot recover dismissed notifications. Requested notification text is sent to the model and kept in normal session/turn history; that history has no automatic expiry. Automatic incoming-message cards remain unfinished.

Probe logging is debug-only metadata (package, time, message count and reply flags), capped at 256 KiB. Records expire after 24 hours on the next startup/read/write; release builds write none and delete legacy probe files. Detailed agent logs and diagnostic exports can still contain personal data in debug. Room JSON payloads (inbox, sessions/turns, tasks, approvals and attempts), cropped item images and item metadata are encrypted on write. Legacy plaintext remains readable and encrypts when rewritten. Inbox answers/seen ids expire after 14 days on startup/inserts; notes and sessions have no automatic expiry. Existing item files retain their seven-day cache cleanup. If Keystore is unavailable, writes fall back to plaintext with a content-free debug warning so capture still works. Failed ciphertext authentication/key reads return no value and preserve stored data; notes and existing Room payloads refuse replacement while unreadable. Metadata diagnostics remain available in debug `-Pfast` builds; release excludes diagnostic activities, receiver and report writer. Query metadata remains visible, with conversation/seen lookup ids hashed; old SQLite pages/files are not securely erased. Clear Android app storage to remove local data. Accessibility reads and provider copies remain outside this at-rest protection. Current app-hand model calls send screen text, not screenshots; screenshots supplied to a model would also be visible to that provider.

## Status

Early prototype, tested on one Nothing Phone (3a), Android 16. Misura uses no root and these instructions do not unlock the bootloader; Shizuku runs over wireless debugging. The test phone's bootloader is unlocked, so operation on a locked phone is not yet tested.

Build and sideload an APK for testing. Store distribution and current accessibility policies have not been reviewed for release. The [setup page](https://misuraphone.com/install.html) has copy-paste instructions for a coding agent.

## Run it yourself

1. You need a phone on Android 14 (API 34) or later. Install Android Studio, its Android SDK and platform tools (`adb`), and JDK 21. Point `JAVA_HOME` at JDK 21. Clone `misura-phone` and open `misura-app` in Android Studio; install the SDK components requested by the project (the build needs compileSdk 37). Building from a terminal or a coding agent also needs the installed SDK's location: set `ANDROID_HOME` (Android Studio's default is `~/Library/Android/sdk` on macOS, `~/Android/Sdk` on Linux, `%LOCALAPPDATA%\Android\Sdk` on Windows) or put `sdk.dir=` in the ignored `misura-app/local.properties`.

2. Bring one Gemini, OpenAI or Anthropic API key, or use Vertex with Google Cloud credentials. Google Cloud is optional for API-key model routes; direct Google services need their own Cloud access. For Vertex, prefer a short-lived token or a dedicated service-account JSON key with only the access required for Vertex/Places. Use your own project with billing, spending limits and Vertex AI/Places access. The project defaults to the key's `project_id`; local setup can explicitly override it. For the adb-token route set `PROJECT` below. Do not relax an organization's key policy. Setup in a separate contributor's Cloud project is **UNVERIFIED**.

3. Enable USB debugging on your test phone, connect it, and build/install from the repo root:

   ```sh
   cd misura-app
   export ANDROID_HOME="$HOME/Library/Android/sdk"   # your SDK path; skip if local.properties sets sdk.dir
   ./gradlew :app:assembleDebug
   adb devices
   export ANDROID_SERIAL=YOUR_PHONE_SERIAL
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

4. Open Misura. A fresh install opens **Misura settings**: choose your model provider, edit the model ID if needed and enter its key. For Vertex, paste your service-account JSON and optionally enter a billing project. Google Cloud is an optional integration for other model providers. Leave Web search on Native to use the model account, or select Exa, Tavily or Context.dev and enter its separate key. Without Google Cloud, places requests use an installed Maps app. Browser and Maps fallback depend on installed apps, readable screens and sign-in. Tap **Save keys**. Leave secret fields blank to keep saved keys; stored keys are never displayed. Reopen settings by swiping down for apps and tapping **Misura settings** before typing a search. The form has native permission links and a notification block editor; no adb is needed for these settings. Validation is offline, so a saved key is not proof of Cloud access. Short-lived-token debug provisioning is still available below.

   Install Shizuku on the phone. The tested setup used the `thedjchi` fork v13.7.0; other versions are **UNVERIFIED** here. Enable Developer options → Wireless debugging, then use Shizuku's Pair flow and Start. Enable Start on boot and Watchdog if provided. With the phone on USB, the tested fork can also be started from the computer: run its installed `libshizuku.so` over `adb shell`. Find it from the installed package, not another phone: the directory of the `base.apk` path from `adb shell pm path moe.shizuku.privileged.api`, then `lib/<abi>/libshizuku.so`, where `<abi>` comes from the package's `primaryCpuAbi` in `adb shell dumpsys package moe.shizuku.privileged.api` (`arm64-v8a` → `arm64`, `armeabi-v7a` → `arm`). Paths contain `==`; keep them intact. On the Android 17 test phone `dumpsys` listed only `legacyNativeLibraryDir`, not `nativeLibraryDir`. Check the file exists before running it; this route is tested only with the fork named above. Return to **Misura settings** and tap **Connect Shizuku** to grant access. Debug probe screens still require an adb shell launch. The tested fork restarted after reboot; recheck this on your phone.

5. Use **Misura settings** to grant notification access, enable app control/accessibility, and allow voice/alerts. Contacts/calendar read permissions are optional. Android may require App info → Allow restricted settings before enabling accessibility. This settings screen requests calendar read access only. The app declares calendar write permission for separately authorized event creation. Alternatively, these adb commands also grant microphone and notification permissions and allow the sleep overlay:

   ```sh
   adb shell cmd notification allow_listener com.misura.phone/com.misura.phone.notify.MisuraListener
   adb shell am start -a android.settings.ACCESSIBILITY_SETTINGS
   # In Settings, enable Misura. Keep any other accessibility services you use enabled.
   adb shell pm grant com.misura.phone android.permission.RECORD_AUDIO
   adb shell pm grant com.misura.phone android.permission.POST_NOTIFICATIONS
   adb shell appops set com.misura.phone SYSTEM_ALERT_WINDOW allow
   adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS
   ```

   Allow restricted settings for Misura if Android requires it. Set battery use to Unrestricted for Misura and Shizuku. Grant any target app's location or contacts permission by hand: system permission dialogs cannot be read on the hidden display.

   Phones with a vendor side/action key that Android reports without a standard key code (for example the Nothing Phone (3a) Essential Key): with app control enabled, press and release it once on Misura home. Misura remembers that key and drops the **Hold to talk** button; then hold the key and say a harmless request to check voice. Keys that send a standard key code are not learned, and Hold to talk stays.

   Notification sources work immediately. Optionally block one exact package (debug build):

   ```sh
   adb shell am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd notifications --es app com.whatsapp --ez blocked true
   ```

   Use `--ez blocked false` to restore it. No wildcards. Existing opt-in preferences are ignored on upgrade. All builds default to all packages; **Misura settings** lists posting and saved blocked apps for toggling. `-Pfast` disables Android app debugging, automatic agent content logs (it keeps content-free timing lines: package, counts, milliseconds) and raw notification-extra collection; debug-variant provisioning and metadata probes remain enabled. Exported diagnostic components still require `DUMP`. Shell-invoked diagnostics and exports can still contain private task/message content, including in fast builds. Release builds omit those components.

6. After a first read-only request works (step 8), tap **Use Misura as home** in local settings, or use adb:

   ```sh
   adb shell cmd package set-home-activity com.misura.phone/.HomeActivity
   adb shell input keyevent KEYCODE_HOME
   ```

7. From `misura-app`, provision your own short-lived token (requires your own `gcloud` Application Default Credentials login):

   ```sh
   PROJECT=YOUR_GCP_PROJECT_ID sh scripts/push-token.sh
   ```

   Skip this step if you use a Gemini, OpenAI or Anthropic API key, or already saved a service-account key in local settings. A project saved in local settings takes precedence over `PROJECT`. For the token route, this refreshes until stopped with Ctrl-C. Alternatively, return to the repo root and push any credential over adb instead of typing it on the phone (debug builds; needs Python 3 and `ANDROID_SERIAL`; close Misura settings first). `scripts/push-secret.sh <kind>` takes `sa` (Vertex service account), `token`, `gemini`, `openai` or `anthropic` (model API key) and `exa`, `tavily` or `contextdev` (web-search key). A pushed service account or model API key becomes the selected model provider and a pushed search key the selected search service; other saved keys stay. A pushed `token` does not select Vertex: choose Vertex in settings if another provider is selected. Prefer a downloaded key file; the clipboard route clears the clipboard afterwards but cannot clear a clipboard manager's history. Optionally push a plain-text briefing:

   ```sh
   cd ..
   bash scripts/push-secret.sh sa < /absolute/path/to/your-service-account.json   # same as scripts/push-key.sh <file>
   ( trap 'pbcopy </dev/null' EXIT; pbpaste | bash scripts/push-secret.sh openai )   # macOS: key copied to the clipboard
   sh scripts/push-about.sh /absolute/path/to/your-briefing.md
   ```

   Keep these files outside version control or name them `*.local.*` (ignored). Scripts accept explicit paths; no sample credentials are included. `push-secret.sh` and `push-token.sh` send credentials over stdin, so they stay out of this computer's process arguments and are never printed; on the phone they still pass through `am` arguments, visible to adb shell/root. Do not use shell tracing. Reopen Misura settings to see the selection and **Saved**, then prove access with a real request. If the phone cannot use Android Keystore, keys are stored unencrypted, as with the settings form. The briefing uses its first 6,000 characters. Native web lookup uses the selected model provider. The adb commands target debug builds; the local setup form and notification block editor are available in all builds.

8. Try a read-only request first, such as finding places. For tests, start an emulator, prepare Shizuku and permissions there too, and install the fake chat app. Example AVD name: `misura_test` (create it first):

   ```sh
   "$ANDROID_HOME/emulator/emulator" -avd misura_test -no-snapshot
   ```

   In another terminal, from `misura-app`:

   ```sh
   export ANDROID_SERIAL=emulator-5554
   ./gradlew :app:assembleDebug :testapp:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
   # Complete steps 4–6 on the emulator before the connected suite.
   ./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest
   ```

   See [CONTRIBUTING.md](CONTRIBUTING.md) for test prerequisites and limits. The [device setup record](docs/spikes/00-device-setup.md) is historical, includes a destructive bootloader step, and is not a general install guide.

## Setup problems

This is an experimental sideloaded app. The local setup flow has automated checks; setup with a separate contributor's Cloud project remains unverified. Sideloading alone does not configure Vertex billing/IAM or Shizuku.

A saved key confirms local storage only. Authentication failures distinguish rejected credentials (401), denied access (403), and Google token-exchange errors (`google_oauth_*`); these do not all mean an expired sign-in. Keep the exact code when reporting a failure.

For setup failures, open an issue using the repository's **Bug report** template and say which setup step failed. Include your Android version, build/commit, whether the problem occurred on first launch or after an update, and the exact error with private values removed. For sign-in issues, name the model provider, model ID and credential route (API key, Vertex service account or Vertex adb token). Remove keys, tokens and authentication headers. See [report fields](.github/ISSUE_TEMPLATE/bug.md); report security issues through [SECURITY.md](SECURITY.md).

When you leave Misura while it works, a finished result posts a normal Android notification ("Results" channel), and a task waiting for your approval or answer posts one on the "Needs you" channel. Tapping either opens that conversation; nothing can be approved or sent from the notification. Mute or hide them in Android's notification settings; turning them off never stops a task. On a lock screen that hides sensitive content they read only "Result ready" / "Misura needs you". The optional sleep screen shows no task content. Approving from the notification is [planned](docs/SYSTEM.md#5-build-plan). You can also ask Misura to do something at a time every day or on chosen weekdays ("every weekday at 8, tell me if my train is late"); you save it with a tap, and each run can search, look things up and draft messages for your approval, but does not operate other apps.

## Help wanted

Pick a scoped task from [Good first issues](docs/GOOD_FIRST_ISSUES.md). Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing behavior.

## Links

- Landing and setup: [misuraphone.com](https://misuraphone.com)
- System: [docs/SYSTEM.md](docs/SYSTEM.md)

License: [Apache-2.0](LICENSE), Copyright 2026 The Misura Authors. The Misura name and logo are not covered by the license.

[NOTICE](NOTICE) · [Third-party inventory](THIRD_PARTY_NOTICES.md) · [Security policy](SECURITY.md) · [OSS audit and remaining decisions](docs/OSS_AUDIT.md)
