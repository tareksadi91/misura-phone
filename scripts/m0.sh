#!/usr/bin/env bash
# M0 auto tasks on a real phone (docs/M0.md). Debug build; uses the debug `ask` command (same agent route as typing).
#   export ANDROID_SERIAL=YOUR_PHONE_SERIAL; bash scripts/m0.sh /path/outside/the/repo/m0-run
# Writes <dir>/results.md. Results contain private screen text: keep them out of the repo.
# ponytail: PASS marks are coarse (expected tool / foreground app / notification count); read every row yourself.
set -uo pipefail
: "${ANDROID_SERIAL:?Select the target phone first: export ANDROID_SERIAL=...}"
dir=${1:?Give an output directory outside the repo}; mkdir -p "$dir"; out=$dir/results.md
a() { adb -s "$ANDROID_SERIAL" shell "$@"; }
log() { a logcat -d -s MisuraAgent 2>/dev/null; }
fg() { a dumpsys activity activities | grep -m1 topResumedActivity | grep -o '[a-z0-9._]*/[A-Za-z0-9._$]*'; }
screen() { a uiautomator dump /sdcard/m0.xml >/dev/null 2>&1; a cat /sdcard/m0.xml | grep -o 'text="[^"]*"' | grep -v 'text=""' | sed 's/text=//' | tr '\n' ' ' | cut -c1-600; }
# Result alerts by tag (result:<session>), never content; the routine's working notice has another tag. Home clears old
# alerts with a delay, so checks ask "did a NEW result tag appear", not counts.
notes() { a dumpsys notification | grep 'NotificationRecord.*pkg=com.misura.phone' | grep -o 'tag=result:[^ ]*' | sort -u; }
new() { comm -13 <(echo "$1") <(echo "$2") | grep -c .; }
tap() {  # tap the first element whose text is exactly $1
  local b; b=$(a cat /sdcard/m0.xml | python3 -c "
import re,sys
m=re.search(r'text=\"$1\"[^>]*bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"',sys.stdin.read())
print(f'{(int(m[1])+int(m[3]))//2} {(int(m[2])+int(m[4]))//2}' if m else '')")
  [ -n "$b" ] && a input tap $b
}
row() { printf '| %s | %s | %s |\n' "$1" "$2" "$3" >> "$out"; }
# ask <prompt> [home]: Home first unless "keep"; waits for the agent's last tool, max ~2 min
ask() {
  [ "${2:-}" = keep ] || { a input keyevent KEYCODE_HOME; sleep 2; }
  a logcat -c
  local q=${1//\'/}; a "am broadcast -n com.misura.phone/.debug.DebugReceiver --es cmd ask --es text '$q'" >/dev/null
  for _ in $(seq 1 60); do sleep 2; log | grep -q 'tool: answer\|tool: show_places\|tool: open_app\|tool: routine' && break; done
  sleep 4
  tools=$(log | grep -o 'tool: [a-z_]*\|use_app [a-z.]* #[0-9]*: [a-z_]*' | sed 's/tool: //' | tr '\n' ' ')
  front=$(fg); text=$(screen)
  printf '\n## %s\nfront: %s\nroute: %s\nscreen: %s\n' "$1" "$front" "$tools" "$text" >> "$dir/detail.md"
}
pass() { if eval "$1"; then echo PASS; else echo CHECK; fi; }

: > "$dir/detail.md"
{ echo "# M0 $(date '+%F %H:%M')"; echo; echo "Phone: $(a getprop ro.product.model) Android $(a getprop ro.build.version.release) $(a getprop ro.build.display.id)"
  echo "APK: $(a dumpsys package com.misura.phone | grep -m1 lastUpdateTime | tr -d ' ')"; echo
  echo "| # | Task | Result |"; echo "|---|---|---|"; } > "$out"

ask "Find a good cafe nearby";                    row 1 Places "$(pass '[[ $tools == *find_places* ]]') $tools"
ask "When did the Berlin TV tower open to the public"; row 2 "Web search" "$(pass '[[ $tools == *look_up* && $text == *1969* ]]') $tools"
ask "Do I have anything in my calendar on 25 December 2030"; row 3 Calendar "$(pass '[[ $tools == *calendar* ]]') $tools"
ask "Is there a contact named Zebedee Quornfield"; row 4 Contacts "$(pass '[[ $tools == *contacts* ]]') $tools"
ask "Open Maps";                                  row 5 "Open app" "$(pass '[[ $front == *maps* ]]') front=$front"
a input keyevent KEYCODE_HOME; sleep 2; n0=$(notes)   # Home clears earlier alerts; count after it
ask "Open Settings and search for battery, just show the results" keep; row 6 "App control shown" "$(pass '[[ $tools == *"use_app com.android.settings"* && $front == *settings* ]]') $tools"
sleep 3; n1=$(notes);                             k=$(new "$n0" "$n1"); row 7 "Result alert" "$(pass '[ "$k" -ge 1 ]') new result alerts: $k"
a input keyevent KEYCODE_HOME; sleep 2; n1h=$(notes)   # baseline for task 9 after Home cleared task 7
ask "How many notifications do I have? Only the number" keep; row 8 Notifications "$(pass '[[ $tools == *read_notifications* ]]') $tools"
n2=$(notes);                                      k=$(new "$n1h" "$n2"); row 9 "No alert at Home" "$(pass '[ "$k" -eq 0 ]') new result alerts: $k"
ask "Open example.com in Chrome and tell me the page heading"; row 10 "Browser" "$(pass '[[ $tools == *"use_app com.android.chrome"* ]]') $tools"
ask "Open Shizuku";                               row 11 "Protected app" "$(pass '[[ $front != *shizuku* ]]') front=$front"

at=$(a date -d @$(( $(a date +%s) + 180 )) +%H:%M 2>/dev/null || date -v+3M +%H:%M)
ask "Every day at $at, tell me today's date"
tap Save; sleep 5; text=$(screen)
row 12 "Routine save" "$(pass '[[ $text == *Saved* || $text == *saved* ]]') at=$at; $(echo "$text" | cut -c1-160)"
a am start -a android.settings.SETTINGS >/dev/null; n3=$(notes)
for _ in $(seq 1 90); do sleep 4; [ "$(new "$n3" "$(notes)")" -ge 1 ] && break; done
n4=$(notes);                                      k=$(new "$n3" "$n4"); row 13 "Routine run" "$(pass '[ "$k" -ge 1 ]') new result alerts: $k"
ask "Remove the routine that runs every day at $at"
for _ in $(seq 1 30); do text=$(screen); [[ $text == *'"Remove"'* ]] && break; sleep 2; done   # the card can follow a list step
tap Remove; sleep 4
ask "List my routines";                           row 14 "Routine remove" "$(pass '[[ $text == *"0 routines"* ]]') $(echo "$text" | cut -c1-160)"
a input keyevent KEYCODE_HOME
echo; echo "Hand tasks 15–21: see docs/M0.md. Detail: detail.md" >> "$out"
cat "$out"
