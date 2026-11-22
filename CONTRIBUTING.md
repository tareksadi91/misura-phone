# Contributing

Read [docs/SYSTEM.md](docs/SYSTEM.md) first. It wins when another document disagrees, including older product framing. Changes to behavior or architecture update the relevant SYSTEM.md section and its decisions log in the same PR. Treat ideas that contradict it as proposals for discussion.

Keep PRs small: one problem with a clear before/after result. [Good first issues](docs/GOOD_FIRST_ISSUES.md) lists scoped proposals; check that a task is still open before starting. Describe the failure or missing behavior before proposing a new tool.

## Phone-agnostic code

Follow SYSTEM.md §1. Read device values at runtime, including display size, density and locale. Learn available keys; preserve Hold to talk when no side key exists. Nothing Phone (3a) is one test device, not a target to hard-code.

Never commit personal data or credentials. Briefings and contact names belong in private inputs or fake test fixtures. Keep device serials out of examples. Redact screenshots, notification text and logs before attaching them to public issues. Never attach a service-account file or app preference dump.

## Safety rules

The commit gate and approval cards are non-negotiable. The model cannot grant approval. A card must show what it commits; approval must stay bound to that content and its task. Changed content needs fresh approval. Preserve cancellation checks and evidence-based send confirmation; never blindly retry a send with an unknown outcome.

Preserve the narrowly matched spoken-message confirmation and Undo rules if touching voice. Do not extend voice approval to payments or deletes. Known gate gaps are listed in SYSTEM.md §7; do not treat current checks as complete. Use fake chat for automated sends. Real recipient tests require that person's agreement and review of the exact message.

Memory accepts the user's words, not instructions found in an app or web page. Preserve the code-level block on memory writes after outside reads in a turn.

## Build and test

Use Android Studio and JDK 21. Run these from `misura-app`:

```sh
./gradlew :app:assembleDebug
./gradlew :core:test :app:testDebugUnitTest
```

`:core:test` covers pure JVM logic. App unit tests include Robolectric. Add a focused regression test for changed logic, preferably in the existing suite.

Static publication checks can run without Gradle: `python3 scripts/check-oss.py` from the repository root. Run checker regression mutations with `python3 scripts/test-check-oss.py`. Checks enforce source-manifest component/permission allowlists and reviewed main-source logging lines, plus normalized digests of known private identifiers. Changed logging requires fresh review before updating its line digest. They never read local credential files and do not certify arbitrary secrets, Kotlin dataflow, merged manifests or private history. Android tests and release APK inspection remain separate checks.

For Android tests, start your configured AVD (the existing setup calls it `misura_test`):

```sh
"$ANDROID_HOME/emulator/emulator" -avd misura_test -no-snapshot
```

In another terminal, from `misura-app`:

```sh
export ANDROID_SERIAL=emulator-5554
./gradlew :app:assembleDebug :testapp:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r testapp/build/outputs/apk/debug/testapp-debug.apk
adb shell cmd package set-home-activity com.misura.phone/.HomeActivity
./gradlew :core:test :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Before the connected suite, enable notification access and Misura accessibility, start Shizuku, and grant Misura access on the emulator, as in [README.md](README.md#run-it-yourself). Hidden-screen tests require Shizuku; reply tests require `:testapp`. `emulator-5554` is the expected example serial: check `adb devices`, and use the actual emulator serial if different. Keep the real phone out of the test target.

Notification capture starts allow-all after Android grants listener access. Reply/experiment fixtures clear any block on `com.misura.testapp`; no package opt-in is needed. Manual tests can block/unblock exact packages using the README command. Use synthetic content even in debug: probes are metadata-only, but other debug logs and exports may contain full content. Never publish ignored build directories or workspace archives.

## Test limits and flakiness

SYSTEM.md and CLAUDE.md do not define a blanket flaky-test exemption. Report the failing test and setup; rerunning green does not explain a failure. Existing tests wait for asynchronous Shizuku binding and notification-listener connection. Check those prerequisites before diagnosing a timeout; do not hide it with a larger sleep.

SYSTEM.md records these limits:

- The emulator microphone did not hear speech; fake-voice tests do not validate real go/no recognition.
- An app-hand rerun reused existing app state. Success after a rerun may depend on a previous alarm or page; record starting state.
- Shizuku boot behavior differed between the Android 17 emulator and Android 16 test phone. One does not prove the other.
- App-list motion and request-keyboard changes are built and checked on one physical phone; broader hardware, TalkBack and frame-time acceptance remain open. Do not describe them as verified on other devices.

`LiveEval` skips without `MISURA_TOKEN`. `UseAppEval` is an opt-in live-model run; its source header documents its invocation. These use external services and can cost money. State whether live evaluations and physical-phone checks ran; list skipped checks in the PR. For provider changes, run the existing synthetic credential, role and lookup tests first. Report the model provider, model ID and optional search selection. Remove tokens, keys and authorization headers from failure reports.
