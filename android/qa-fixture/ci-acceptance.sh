#!/usr/bin/env bash
# Run from repo root on an ephemeral Linux CI runner. No cloud credentials, no remote writes.
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
command -v adb
adb start-server
export QA_EVIDENCE="$PWD/android/evidence/ci"
mkdir -p "$QA_EVIDENCE"
export ANDROID_AVD_HOME="${RUNNER_TEMP:-/tmp}/qingliao-avd"
mkdir -p "$ANDROID_AVD_HOME"
fixture_pid=''; worker_pid=''; emulator_pid=''
cleanup() {
  adb logcat -d > "$QA_EVIDENCE/logcat.txt" 2>&1 || true
  for pid in "$fixture_pid" "$worker_pid" "$emulator_pid"; do
    if [[ -n "$pid" ]]; then kill "$pid" 2>/dev/null || true; fi
  done
  for pid in "$fixture_pid" "$worker_pid" "$emulator_pid"; do
    [[ -z "$pid" ]] || wait "$pid" 2>/dev/null || true
  done
}
trap cleanup EXIT
# The runner must really provide KVM; don't silently fall back to an unusable slow VM.
test -c /dev/kvm
sudo chmod a+rw /dev/kvm
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name qingliao-qa \
  --package 'system-images;android-29;default;x86_64' --device pixel_2 <<< no
"$ANDROID_HOME/emulator/emulator" -avd qingliao-qa -no-window -no-audio -no-snapshot \
  -no-boot-anim -gpu swiftshader_indirect -memory 2048 -cores 2 -camera-back emulated \
  > "$QA_EVIDENCE/emulator.log" 2>&1 &
emulator_pid=$!
booted=false
for i in $(seq 1 120); do
  if [[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then
    booted=true; break
  fi
  kill -0 "$emulator_pid" 2>/dev/null || { cat "$QA_EVIDENCE/emulator.log"; exit 1; }
  sleep 2
done
[[ "$booted" == true ]] || { echo 'Emulator boot timed out'; exit 1; }
# The disposable AOSP API29 image locks night-mode changes against the shell UID.
# Run ADB as root on this CI-owned debug image only, so the system configuration
# event can actually be requested; the installed application remains unprivileged.
adb root
adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]]
adb shell input keyevent 82
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb shell wm size 720x1440
adb shell wm density 320
adb reverse tcp:8000 tcp:8000
adb install -r android/app/build/outputs/apk/qa/app-qa.apk
adb install -r android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
# Offline shipped JNI tests run on the installed app's real native library.
adb shell am instrument -w -r -e network false com.lyrnox.qingliao.qa.test/com.lyrnox.qingliao.NativeInstrumentation \
  | tee "$QA_EVIDENCE/jni.txt"
grep -q '61 Android JNI assertions passed' "$QA_EVIDENCE/jni.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$QA_EVIDENCE/jni.txt"
node android/qa-fixture/mock-server.mjs > "$QA_EVIDENCE/fixture.log" 2>&1 &
fixture_pid=$!
for i in $(seq 1 20); do curl -fsS http://127.0.0.1:8000/health >/dev/null && break; sleep 1; done
python3 android/qa-fixture/ui-smoke.py | tee "$QA_EVIDENCE/ui.txt"
adb shell am instrument -w -r -e mediaLifecycle true com.lyrnox.qingliao.qa.test/com.lyrnox.qingliao.NativeInstrumentation \
  | tee "$QA_EVIDENCE/media-lifecycle.txt"
grep -q '11 Android media/file/focus assertions passed' "$QA_EVIDENCE/media-lifecycle.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$QA_EVIDENCE/media-lifecycle.txt"
adb shell am force-stop com.lyrnox.qingliao.qa
kill "$fixture_pid"; wait "$fixture_pid" || true; fixture_pid=''
# Switch to the actual Worker source with local D1, R2 and Durable Objects.
# No .dev.vars, Cloudflare account credentials or production bindings are used.
node node_modules/wrangler/bin/wrangler.js d1 migrations apply chat --local \
  --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/qa-worker-state" \
  > "$QA_EVIDENCE/migrations.log" 2>&1
node node_modules/wrangler/bin/wrangler.js dev --local --ip 127.0.0.1 --port 8000 \
  --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/qa-worker-state" \
  --var "APP_SECRET:$(openssl rand -hex 32)" \
  > "$QA_EVIDENCE/worker.log" 2>&1 &
worker_pid=$!
up=false
for i in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8000/ >/dev/null; then up=true; break; fi
  kill -0 "$worker_pid" 2>/dev/null || { cat "$QA_EVIDENCE/worker.log"; exit 1; }
  sleep 1
done
[[ "$up" == true ]]
adb shell am instrument -w -r -e network true com.lyrnox.qingliao.qa.test/com.lyrnox.qingliao.NativeInstrumentation \
  | tee "$QA_EVIDENCE/native-service.txt"
grep -q 'local Worker/D1/R2/DO assertions passed' "$QA_EVIDENCE/native-service.txt"
grep -q 'INSTRUMENTATION_CODE: -1' "$QA_EVIDENCE/native-service.txt"
# Manifest/signature facts are machine-derived, not inferred from filenames.
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging android/app/build/outputs/apk/debug/app-debug.apk \
  > "$QA_EVIDENCE/debug-manifest.txt"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs android/app/build/outputs/apk/debug/app-debug.apk \
  > "$QA_EVIDENCE/debug-signature.txt"
"$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging android/app/build/outputs/apk/qa/app-qa.apk \
  > "$QA_EVIDENCE/qa-manifest.txt"
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --print-certs android/app/build/outputs/apk/qa/app-qa.apk \
  > "$QA_EVIDENCE/qa-signature.txt"
git rev-parse HEAD > "$QA_EVIDENCE/source-commit.txt"
