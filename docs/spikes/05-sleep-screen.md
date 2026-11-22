# Spike 05: Sleep screen on wake (emulator, Android 17, 2026-11-10)

**Verdict on the emulator: works.** Both variants show Misura's screen over the lock screen on every wake tried. Repeat on the Nothing Phone (3a) in Phase B: Nothing OS can differ on background launches and battery management.

## Setup
- `WakeWatcher` (runtime receiver for `SCREEN_ON` / `USER_PRESENT`) is held by `WorkingService`, which stays up whenever a sleep screen is chosen (`SleepMode`, debug: `am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd sleep --es variant ACTIVITY|OVERLAY|OFF`).
- Variant A: `SleepActivity`, `setShowWhenLocked(true)`, no `turnScreenOn`, launched from the background with the overlay permission (`appops set com.misura.phone SYSTEM_ALERT_WINDOW allow`).
- Variant B: `SleepOverlay`, a `TYPE_ACCESSIBILITY_OVERLAY` window from Misura's accessibility service.
- Content: dark screen, orange pulse (stand-in for the particle sun), time, up to four live task cards (needs-you first). **Superseded 22 Nov (SYSTEM §5 M3c):** task cards removed for privacy; only a content-free Shizuku-outage line remains.

## Results
| Wake case | A (activity) | B (overlay) | Notes |
|---|---|---|---|
| From home | shown, 119–135 ms | shown, 67–223 ms | launch logged as `BAL_ALLOW_SAW_PERMISSION` |
| From another app (testapp open) | shown | n/t | |
| After app kill (`kill -9`; `am kill` is a no-op while the service runs) | shown | n/t | `START_STICKY` restarts the service |
| After `am force-stop` | shown | n/t | process back within 2 s: Android rebinds Misura's accessibility / notification-listener services |
| After reboot + first unlock (PIN set) | shown | n/t | see boot finding |
| Unlock (`USER_PRESENT`) | hides | hides | |
| Mode OFF | nothing shown | nothing shown | |

- Background-launch denials in logcat: **none** in any case.
- System lock still in force behind both variants (`isKeyguardLocked` true while shown); tap on the activity asks for the system unlock, tap on the overlay drops it.
- Variant B leaves the status bar visible; A covers the full screen.
- Power button = `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` on the emulator. **Fingerprint and a real notification wake are untested** (emulator); check on the phone.

## Boot finding (fixed)
On Android 17 the foreground-service start from `Application.onCreate` **and from `BOOT_COMPLETED`** was denied (`Background started FGS: Disallowed`). Fix: start it when the notification listener connects (the system binding allows it; ~0.2 s after the denial). Backup: the accessibility service also registers the watcher when it connects. Verified with accessibility on and off.

## Tests
`SleepScreenTest` (5, emulator): activity variant, overlay variant, from another app, unlock hides, mode off. `SleepLogicTest` (5, JVM): wake decision, card lines.

## Open for the phone
- Nothing OS battery manager vs. the always-on service.
- Fingerprint wake, notification wake, AOD → wake.
- Which variant feels better in the hand (A covers the status bar; B can't be dismissed by the system bouncer gesture).
