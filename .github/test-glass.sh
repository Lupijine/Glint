#!/usr/bin/env bash
# The emulator action runs each script line in a fresh shell, so keep status handling
# and screenshot retrieval together in this single checked-in Bash invocation.
set -u

# Direct Compose test input does not count as physical device activity. Keep
# Google API emulator images awake until the later native touch/Back checks.
adb shell settings put system screen_off_timeout 1800000
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP
adb shell wm dismiss-keyguard

test_status=0
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pglint.abi=x86_64 || test_status=$?

screenshots="${RUNNER_TEMP:?}/glass-screenshots"
mkdir -p "$screenshots"
if ! adb pull /sdcard/Pictures/GlintGlassTest "$screenshots"; then
  echo '::error title=Missing render screenshots::Could not retrieve persisted MediaStore screenshots.'
  if [ "$test_status" -eq 0 ]; then test_status=1; fi
fi

for name in 01-source 02-lens 03-live-update 04-gold-material-harness 05-scroll-before 06-scroll-after; do
  if [ ! -s "$screenshots/GlintGlassTest/$name.png" ]; then
    echo "::error title=Missing render screenshot::$name.png was not produced."
    if [ "$test_status" -eq 0 ]; then test_status=1; fi
  fi
done

if [ "$test_status" -ne 0 ]; then
  python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET

for report in Path('app/build/outputs/androidTest-results/connected').glob('**/TEST-*.xml'):
    root = ET.parse(report).getroot()
    for case in root.iter('testcase'):
        for failure in list(case.findall('failure')) + list(case.findall('error')):
            print(f"GLASS_TEST_FAILURE {case.get('classname')}.{case.get('name')}")
            print((failure.text or failure.get('message', ''))[:12000])
PY
  adb logcat -d -s GlassRefraction AndroidRuntime TestRunner
fi
exit "$test_status"
