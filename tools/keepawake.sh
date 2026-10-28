#!/bin/bash
# Keeps the phone screen on during tests: harmless tap on the status bar every 20 s (no unlock, no keyguard input).
A=adb
S=${1:-${ADB_SERIAL:?set ADB_SERIAL=ip:port}}; END=$(( $(date +%s) + ${2:-14400} ))
while [ $(date +%s) -lt $END ]; do
  W=$(timeout 15 $A -s $S shell "dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*'" 2>/dev/null | tr -d '\r')
  case "$W" in
    *Awake*) timeout 10 $A -s $S shell "input tap 540 4" >/dev/null 2>&1 ;;
    *) echo "$(date +%T) not awake or unreachable: '$W'" ;;
  esac
  sleep 20
done
echo "$(date +%T) keepawake finished"
