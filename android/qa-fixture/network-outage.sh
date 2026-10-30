#!/usr/bin/env bash
# Standalone acceptance of the exact already-published QA APK; no build/deploy/SFU.
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
export QA_EVIDENCE="$PWD/android/evidence/outage"
mkdir -p "$QA_EVIDENCE"
export ANDROID_AVD_HOME="$RUNNER_TEMP/outage-avd"
mkdir -p "$ANDROID_AVD_HOME"
emulator_pid=''; worker_pid=''
cleanup() {
 adb logcat -d > "$QA_EVIDENCE/logcat.txt" 2>&1 || true
 for pid in "$emulator_pid" "$worker_pid"; do [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
test -c /dev/kvm; sudo chmod a+rw /dev/kvm
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name outage --package 'system-images;android-29;default;x86_64' --device pixel_2 <<< no
emulator -avd outage -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 2048 -cores 2 > "$QA_EVIDENCE/emulator.log" 2>&1 &
emulator_pid=$!
booted=false
for i in $(seq 1 120); do
 if [[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi
 kill -0 "$emulator_pid" 2>/dev/null || { cat "$QA_EVIDENCE/emulator.log"; exit 1; }; sleep 2
done
[[ "$booted" == true ]]
adb root; adb wait-for-device
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]]
adb shell input keyevent 82
for setting in window_animation_scale transition_animation_scale animator_duration_scale; do adb shell settings put global "$setting" 0; done
adb shell wm size 720x1440; adb shell wm density 320
adb reverse tcp:8000 tcp:8000
apk='qingliao-android-0.3.3-qa-4cbbff5d923499215a07df835430311f358527a6.apk'
printf '20f23fea8c403c3ec4ca3ca293f1dc1f0e23f7f4d9bdcc25b72f87c7a75c4d2a  %s\n' "$apk" | sha256sum -c - | tee "$QA_EVIDENCE/apk-checksum.txt"
adb install "$apk"
node node_modules/wrangler/bin/wrangler.js d1 migrations apply chat --local --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/outage-worker" > "$QA_EVIDENCE/migrations.log" 2>&1
node node_modules/wrangler/bin/wrangler.js dev --local --ip 127.0.0.1 --port 8000 --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/outage-worker" --var "APP_SECRET:$(openssl rand -hex 32)" > "$QA_EVIDENCE/worker.log" 2>&1 &
worker_pid=$!
up=false
for i in $(seq 1 60); do if curl -fsS http://127.0.0.1:8000/ >/dev/null; then up=true; break; fi; kill -0 "$worker_pid"; sleep 1; done
[[ "$up" == true ]]
git rev-parse HEAD > "$QA_EVIDENCE/harness-commit.txt"
printf '%s\n' '4cbbff5d923499215a07df835430311f358527a6' > "$QA_EVIDENCE/app-source-commit.txt"
python3 android/qa-fixture/network-outage.py | tee "$QA_EVIDENCE/outage.txt"
