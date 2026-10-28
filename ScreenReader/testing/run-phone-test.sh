#!/bin/bash
# Runs ZYLENA on an emulator with the practice lesson (built like Sabbath School) and checks that the
# paragraph being read is always fully visible and outlined. Called by the GitHub workflow.
set -u
ROOT="${GITHUB_WORKSPACE:-$(pwd)}"
OUT="$ROOT/phone-test-out"
mkdir -p "$OUT/shots"
ZAPK=$(ls "$ROOT"/zylena-apk/*.apk | head -1)
MAPK="$ROOT/ScreenReader/testing/mockapp/app/build/outputs/apk/debug/app-debug.apk"
Z=com.zylena.screenreader
M=com.zylena.mocklesson

adb wait-for-device
echo "Android $(adb shell getprop ro.build.version.release), $(adb shell wm size), $(adb shell wm density)"
# Gesture navigation and normal animations, like a real phone.
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural || true
adb shell settings put global window_animation_scale 1
adb shell settings put global transition_animation_scale 1
adb shell settings put global animator_duration_scale 1
# A slow virtual phone sometimes says "System UI isn't responding"; that box would be read instead of the lesson.
adb shell settings put global hide_error_dialogs 1 || true
# Let the phone finish starting up before anything is measured.
sleep 20

adb install -r -g "$ZAPK"
adb install -r "$MAPK"

# Test mode: speech is timed instead of spoken (the emulator has no voice), and the log goes to logcat.
printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' \
  '    <boolean name="test_mode" value="true" />' '</map>' > "$OUT/prefs.xml"
adb push "$OUT/prefs.xml" /data/local/tmp/zylena_reader.xml
adb shell "run-as $Z mkdir -p shared_prefs"
adb shell "cat /data/local/tmp/zylena_reader.xml | run-as $Z sh -c 'cat > shared_prefs/zylena_reader.xml'"
adb shell "run-as $Z cat shared_prefs/zylena_reader.xml"

# A bigger log buffer: under load Android dropped lines (a paragraph's start went missing and the check misread it).
adb logcat -G 16M || true
adb logcat -c
adb logcat -v epoch ZYLENA:I MOCK:I '*:S' > "$OUT/logcat.txt" &
LOGCAT=$!

adb shell am start -W -n $M/.MainActivity
sleep 3
adb shell settings put secure enabled_accessibility_services $Z/$Z.ReaderService
adb shell settings put secure accessibility_enabled 1

lines() { wc -l < "$OUT/logcat.txt"; }
focus() { adb shell dumpsys window | grep -m1 mCurrentFocus; }
# Makes sure the practice lesson is what's on screen (no system box or other app in front).
ensure_front() {
  for k in 1 2 3 4 5 6; do
    if focus | grep -q "$M"; then return 0; fi
    echo "not in front yet: $(focus)"
    adb shell am broadcast -a android.intent.action.CLOSE_SYSTEM_DIALOGS > /dev/null 2>&1 || true
    if focus | grep -qi "not responding\|Application Error"; then adb shell input keyevent KEYCODE_BACK; fi
    sleep 2
    adb shell am start -n $M/.MainActivity > /dev/null 2>&1 || true
    sleep 3
  done
  echo "the practice lesson never came to the front: $(focus)"
}
after() { tail -n +"$(($2 + 1))" "$OUT/logcat.txt" | grep -c -- "$1"; }
shot() { adb exec-out screencap -p > "$OUT/shots/$1.png" 2>/dev/null || true; }
mark() { adb shell log -t MOCK "$1"; sleep 1; }

# Waits for a log line (after line $3), taking a screenshot every few seconds meanwhile.
watch_until() {
  local pat="$1" secs="$2" from="$3" name="$4" n=0 start
  start=$(date +%s)
  while (( $(date +%s) - start < secs )); do
    if (( $(after "$pat" "$from") > 0 )); then return 0; fi
    shot "$name-$(printf %03d $n)"
    n=$((n + 1))
    sleep 3
  done
  echo "timed out waiting for: $pat"
  return 1
}

watch_until "TEST button at" 60 0 start || true
POS=$(grep -o "TEST button at [0-9]*,[0-9]*" "$OUT/logcat.txt" | tail -1 | awk '{print $4}')
if [ -z "$POS" ]; then
  echo "ZYLENA's floating button never showed up"
  kill $LOGCAT
  cat "$OUT/logcat.txt" | tail -50
  exit 1
fi
BX=${POS%,*}
BY=${POS#*,}
echo "floating button at $BX,$BY"
tap() { adb shell input tap "$BX" "$BY"; }
# Taps the button until ZYLENA logs that it was pressed (a tap can land while the phone is busy).
press() {
  local want="$1" from n
  for n in 0 1 2 3; do
    from=$(lines)
    adb shell input tap "$BX" "$((BY + (n % 2 == 0 ? 0 : 40) * (n > 1 ? -1 : 1)))"
    for ((w = 0; w < 6; w++)); do
      sleep 1
      if (( $(after "button: $want" "$from") > 0 )); then return 0; fi
    done
    echo "the tap didn't reach ZYLENA's button, trying again"
    ensure_front
  done
  echo "ZYLENA's button never answered"
}
# Starts reading; if the lesson wasn't really in front yet (a slow first start), the scan stops at once: try again.
start_reading() {
  local from n w
  for n in 0 1 2; do
    from=$(lines)
    press read
    for ((w = 0; w < 10; w++)); do
      sleep 1
      if (( $(after "another app or screen came up" "$from") > 0 )); then break; fi
    done
    if (( $(after "another app or screen came up" "$from") == 0 )); then return 0; fi
    echo "the scan stopped at once (the lesson wasn't in front), starting again"
    sleep 3
    ensure_front
  done
}

echo "== Scenario 1: the whole lesson, from the first page to the end"
ensure_front
mark "scenario 1"
FROM=$(lines)
start_reading
watch_until "whole page: the end" 900 "$FROM" s1 || true
sleep 2

echo "== Scenario 2: start at the bottom of Sunday's page, stop after a few paragraphs, then go on"
adb shell am start -S -W -n $M/.MainActivity --ei page 1 --ez bottom true
sleep 4
ensure_front
mark "scenario 2 until stop"
FROM=$(lines)
press read
for ((i = 0; i < 300; i++)); do
  (( $(after "TEST reading line" "$FROM") >= 5 )) && break
  (( i % 3 == 0 )) && shot "s2-$(printf %03d $i)"
  sleep 1
done
sleep 2
shot s2-stopping
press stop
sleep 3
ensure_front
mark "scenario 2 resume"
FROM=$(lines)
press read
watch_until "whole page: the end" 900 "$FROM" s2r || true
sleep 2

kill $LOGCAT
python3 "$ROOT/ScreenReader/testing/check_reading.py" "$OUT/logcat.txt" > "$OUT/report.txt"
RESULT=$?
cat "$OUT/report.txt"
exit $RESULT
