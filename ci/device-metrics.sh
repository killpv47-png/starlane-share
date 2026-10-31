#!/usr/bin/env bash
# Measures cold-start time, memory and CPU of the optimized APK on the connected emulator.
# All numbers are MEASURED on the CI emulator (x86_64, software GPU) — not on a phone.
set -u
PKG=com.starlane.share
APK=$(ls app/build/outputs/apk/optimized/*.apk | head -1)
OUT=metrics.md
adb install -r -g "$APK" >/dev/null
# Seed a profile so the app opens to the home screen instead of onboarding.
adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && echo \"<?xml version=\\\"1.0\\\" encoding=\\\"utf-8\\\"?><map><string name=\\\"username\\\">CI</string></map>\" > shared_prefs/starlane.xml'" 2>/dev/null || true

{
  echo "## On-emulator measurements (optimized APK, API ${API_LEVEL:-?})"
  echo
  echo "| Run | Cold start TotalTime (ms) | WaitTime (ms) |"
  echo "|---|---|---|"
} > $OUT
TOTALS=()
for i in 1 2 3 4 5 6; do
  adb shell am force-stop $PKG
  adb shell "echo 3 > /proc/sys/vm/drop_caches" 2>/dev/null || true
  sleep 1
  R=$(adb shell am start -W -n $PKG/.MainActivity 2>/dev/null)
  T=$(echo "$R" | grep -E '^TotalTime' | awk '{print $2}' | tr -d '\r')
  W=$(echo "$R" | grep -E '^WaitTime' | awk '{print $2}' | tr -d '\r')
  # Run 1 is a warm-up (first launch after install compiles/verifies the APK).
  [ "$i" -gt 1 ] && TOTALS+=("$T")
  echo "| $i$([ "$i" = 1 ] && echo ' (warm-up, excluded)') | ${T:-n/a} | ${W:-n/a} |" >> $OUT
  sleep 2
done
if [ ${#TOTALS[@]} -gt 0 ]; then
  MED=$(printf '%s\n' "${TOTALS[@]}" | sort -n | awk '{a[NR]=$1} END {print a[int((NR+1)/2)]}')
  echo >> $OUT
  echo "**Median cold start (runs 2-6): ${MED} ms**" >> $OUT
fi

sleep 4
echo >> $OUT
echo "### Memory after launch (idle on home screen)" >> $OUT
echo '```' >> $OUT
adb shell dumpsys meminfo $PKG | grep -E "TOTAL PSS|TOTAL RSS|Java Heap|Native Heap|Graphics|TOTAL:" | head -8 >> $OUT
echo '```' >> $OUT

echo >> $OUT
echo "### CPU while idle on home screen (10 s sample)" >> $OUT
PID=$(adb shell pidof $PKG | tr -d '\r')
if [ -n "$PID" ]; then
  T1=$(adb shell cat /proc/$PID/stat | awk '{print $14+$15}')
  sleep 10
  T2=$(adb shell cat /proc/$PID/stat | awk '{print $14+$15}')
  HZ=100
  echo "CPU time used in 10 s: $(( (T2 - T1) * 1000 / HZ )) ms (≈ $(( (T2 - T1) * 100 / HZ / 10 ))% of one core)" >> $OUT
fi
adb shell am force-stop $PKG
cat $OUT
