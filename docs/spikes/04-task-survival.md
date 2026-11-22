# Spike 04: Task survival (emulator, Android 17, 2026-11-10)

**Verdict on the emulator: tasks survive and replies still land.** Run `misura-app/spikes/survival.sh` again on the phone; Nothing OS battery management is the real risk and the emulator doesn't have it.

## Drill
Per run: seed `W<n>` (WAITING, open expectation on Bot<n>) and `U<n>` (OUTCOME_UNKNOWN, attempt UNCERTAIN), disturb, then Bot<n> answers from testapp; dump both after 25 s (quiet window 20 s).

| Disturbance | Process | W: reply routed? | U |
|---|---|---|---|
| Doze (`deviceidle force-idle`, 60 s, screen off) | same pid | yes: PLANNING, buzzed, 1 answer | unchanged, not rechecked (no restart) |
| Battery saver (`power set-mode 1`, 30 s) | same pid | yes | unchanged |
| `am kill` | **no-op** (service running) | — | — |
| `kill -9` (stands in for a low-memory kill) | new pid | yes | recovered, rechecked, still unsure |
| `am force-stop` | new pid within 5 s | yes | recovered, rechecked, still unsure |
| Reboot (no PIN) | new pid | yes | recovered, rechecked, still unsure |

- **Never committed:** recovery only checks (`Controller.recover` has no commit path; covered by JVM tests). U stays OUTCOME_UNKNOWN because nothing was sent, which is correct.
- Rechecks leave no history line unless they find something; a "rechecked, nothing found" note would make it visible on the card. Small, not done.
- After force-stop, Android brought the process back by rebinding the notification listener / accessibility service. Alarms are cancelled by a force-stop; the answer watcher is re-armed at app start (`AnswerReady.rearm`), so it doesn't rely on alarms.
- "Lock 30 min" was replaced by forced doze (same restrictions, no wait). Run the real 30 min on the phone.
- Shizuku dies with a reboot (it is started over adb). Hidden-screen hands are unavailable until it is restarted. The sleep screen now leads with "Hidden sends paused: Shizuku is off" while Shizuku is down (checked on the emulator by killing `shizuku_server`). Community fork v13.7.0 (thedjchi) passes the full suite on Android 17; installing it needs the official app uninstalled first (different signing key). Whether the fork restarts itself after boot is untested; check on the 3a.
