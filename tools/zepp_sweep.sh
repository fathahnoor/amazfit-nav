#!/usr/bin/env bash
# Turn OFF every app in Zepp's notification allowlist except Maps and AmazfitNav.
#
# The list re-renders after each toggle and a stray tap can open a row instead of
# flipping its switch, so this handles ONE row per dump, verifies the page after each
# tap and backs out if the list was left.
set -uo pipefail
cd "$(dirname "$0")/.."

D="${DEVICE:-$(adb devices | awk '$2=="device"{print $1; exit}')}"
iterations="${1:-60}"
LIST_ACTIVITY="AppNotificationAlertActivity"

on_list() {
  adb -s "$D" shell dumpsys activity activities 2>/dev/null \
    | grep -m1 "topResumedActivity" | grep -q "$LIST_ACTIVITY"
}

dump() {
  MSYS_NO_PATHCONV=1 adb -s "$D" shell uiautomator dump /data/local/tmp/z.xml >/dev/null 2>&1
  MSYS_NO_PATHCONV=1 adb -s "$D" pull /data/local/tmp/z.xml re/z.xml >/dev/null 2>&1
}

for i in $(seq 1 "$iterations"); do
  if ! on_list; then
    echo "not on the list (%i) -> back"
    adb -s "$D" shell input keyevent 4
    sleep 1.2
    continue
  fi
  dump || continue
  plan=$(python tools/zepp_switches.py re/z.xml)
  target=$(echo "$plan" | grep -E "would turn" | head -1)

  if [ -z "$target" ]; then
    echo "screen clear -> scroll"
    adb -s "$D" shell input swipe 600 2050 600 1250 400
    sleep 1.4
    continue
  fi

  coords=$(echo "$target" | grep -oE "\(([0-9]+),([0-9]+)\)" | tr -d '()')
  x=${coords%,*}; y=${coords#*,}
  echo "$(date +%H:%M:%S) $target"
  adb -s "$D" shell input tap "$x" "$y"
  sleep 1.3
done
echo "sweep finished"
