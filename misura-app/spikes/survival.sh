#!/bin/bash
# Task survival drills (Task 16a). Each drill: seed a waiting task W<n> and an unsure task U<n>,
# disturb the app, then have Bot<n> answer from testapp and dump both tasks.
# Usage: ANDROID_SERIAL=<serial> spikes/survival.sh [drill...]   drills: doze saver kill forcestop reboot
set -u
APP=com.misura.phone; RX=$APP/.debug.DebugReceiver
dbg() { adb shell am broadcast -n $RX "$@" >/dev/null; }
booted() { adb wait-for-device; until [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ]; do sleep 2; done; sleep 8; }
pid() { adb shell pidof $APP | tr -d '\r'; }

drill() {
  local n=$1; local name=$2
  dbg --es cmd survival --es n "$n"; sleep 2
  local before; before=$(pid)
  case $name in
    doze)      adb shell input keyevent KEYCODE_SLEEP; adb shell dumpsys deviceidle force-idle >/dev/null; sleep 60
               adb shell dumpsys deviceidle unforce >/dev/null; adb shell input keyevent KEYCODE_WAKEUP ;;
    saver)     adb shell cmd power set-mode 1; adb shell input keyevent KEYCODE_SLEEP; sleep 30; adb shell input keyevent KEYCODE_WAKEUP ;;
    kill)      adb shell am kill $APP; sleep 1; echo "  am kill: pid $before -> $(pid)"
               adb shell run-as $APP kill -9 "$before"; sleep 5 ;;
    forcestop) adb shell am force-stop $APP; sleep 5 ;;
    reboot)    adb reboot; booted; adb shell wm dismiss-keyguard ;;
  esac
  echo "== $name: pid $before -> $(pid)"
  adb logcat -c
  adb shell am broadcast -a com.misura.testapp.POST -p com.misura.testapp --es conv "bot$n" --es sender "Bot$n" --es text "answer $n" >/dev/null
  sleep 25
  dbg --es task "W$n"; dbg --es task "U$n"; sleep 2
  adb logcat -d -s MisuraDebug | grep "task [WU]$n " | sed 's/.*MisuraDebug: /  /'
  adb shell cmd power set-mode 0 >/dev/null
}

# No opt-in needed; clear a possible fixture block for these synthetic drills.
dbg --es cmd notifications --es app com.misura.testapp --ez blocked false
i=1; for d in "${@:-doze saver kill forcestop reboot}"; do for x in $d; do drill "$(date +%s | tail -c 5)$i" "$x"; i=$((i+1)); done; done
