# Misura app

Launcher prototype for the Misura Phone (Nothing Phone (3a), Android 17). Design and plan: `../docs/superpowers/`.

## Toolchain
- JDK 21: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`
- Android SDK at `~/Library/Android/sdk` (platform 37.0, build-tools 37.0.0, emulator, `system-images;android-37.0;google_apis;arm64-v8a`)
- AVD `misura_test` (Pixel 7 profile)

## Commands
```bash
./gradlew :core:test                      # pure Kotlin tests
./gradlew :app:testDebugUnitTest          # Robolectric
./gradlew :app:connectedDebugAndroidTest  # emulator/device tests
$ANDROID_HOME/emulator/emulator -avd misura_test -no-snapshot &
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 shell cmd package set-home-activity com.misura.phone/.HomeActivity
```
Always pass `-s <serial>` once a real phone is connected too.

Notification capture allows all packages by default once Android notification-listener permission is granted, with an optional exact-package block list; see [the root setup guide](../README.md#run-it-yourself). Connected reply/experiment tests use the fake app. Diagnostic activities and adb credential/notification provisioning exist only in debug builds and require an adb shell caller. The Misura settings screen (provider, key, blocked apps) exists in all builds. See [SECURITY.md](../SECURITY.md) before using real accounts.

## Calendar setup

Uses Android's own calendar store for any synced account; no API key or calendar app UI. Grant permissions over adb (no runtime prompt):

```bash
adb shell pm grant com.misura.phone android.permission.READ_CALENDAR
adb shell pm grant com.misura.phone android.permission.WRITE_CALENDAR
adb shell pm grant com.misura.phone android.permission.READ_CONTACTS
```

Without permission, the tool returns `{"error":"calendar permission not granted"}`. Creates require an Add card tap; one yes inserts the exact event once in the same session, within five minutes.

Contacts lookup is read-only (names, phone numbers and emails); without READ_CONTACTS it returns `{"error":"contacts permission not granted"}`. No runtime prompt.
