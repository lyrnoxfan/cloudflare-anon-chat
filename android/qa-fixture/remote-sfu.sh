#!/usr/bin/env bash
# Three-native-session group-call check against a separately provisioned, isolated QA Worker.
# This test creates rooms and call traffic: never point it at chat.lyrnox.com or production.
# No deployment/configuration changes or production credentials are performed here.
set -euo pipefail
: "${QA_SFU_BASE_URL:?Configure QA_SFU_BASE_URL in the protected cloudflare-sfu-isolated environment}"
: "${QA_SFU_TARGET:?Configure QA_SFU_TARGET in the protected cloudflare-sfu-isolated environment}"
python3 - <<'PYVALIDATE'
from urllib.parse import urlsplit
import os, sys
u=urlsplit(os.environ['QA_SFU_BASE_URL'])
if (u.scheme != 'https' or not u.hostname or u.username or u.password or u.query or u.fragment
    or u.path not in ('', '/') or u.hostname.lower() == 'chat.lyrnox.com'
    or u.hostname.lower().endswith('.chat.lyrnox.com')):
    sys.exit('Refusing SFU QA: base URL must be an isolated HTTPS origin, not the production chat host')
if os.environ['QA_SFU_TARGET'].strip().lower() in ('', 'blue-room-test', 'production', 'prod'):
    sys.exit('Refusing SFU QA: target must identify a separate isolated test service')
PYVALIDATE
if [[ "${1:-}" == "--preflight-only" ]]; then exit 0; fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
OUT="$PWD/android/evidence/sfu"; mkdir -p "$OUT"
export ANDROID_AVD_HOME="$RUNNER_TEMP/sfu-avds"; mkdir -p "$ANDROID_AVD_HOME"
pids=()
cleanup() {
  for serial in emulator-5554 emulator-5556 emulator-5558; do
    # Runtime media logs can contain SDP/ICE or ephemeral capabilities: do not publish raw logcat.
    adb -s "$serial" emu kill >/dev/null 2>&1 || true
  done
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
}
trap cleanup EXIT
test -c /dev/kvm; sudo chmod a+rw /dev/kvm
adb start-server
for spec in 'a 5554' 'b 5556' 'c 5558'; do
  read -r name port <<< "$spec"
  "$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name "sfu-$name" --package 'system-images;android-29;default;x86_64' --device pixel_2 <<< no
  emulator -avd "sfu-$name" -port "$port" -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 1536 -cores 2 -camera-back emulated -camera-front emulated > "$OUT/emulator-$name.log" 2>&1 &
  pids+=("$!")
done
for serial in emulator-5554 emulator-5556 emulator-5558; do
  ready=false
  for i in $(seq 1 150); do
    if [[ "$(timeout 5 adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then ready=true; break; fi
    sleep 2
  done
  [[ "$ready" == true ]] || { echo "$serial boot failed"; exit 1; }
  adb -s "$serial" shell input keyevent 82
  adb -s "$serial" install -r android/app/build/outputs/apk/qa/app-qa.apk
  adb -s "$serial" install -r android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk
  adb -s "$serial" shell pm grant com.lyrnox.qingliao.qa android.permission.CAMERA
  adb -s "$serial" shell pm grant com.lyrnox.qingliao.qa android.permission.RECORD_AUDIO
  # Remove only this fresh QA package's transient handshake markers so a previous
  # instrumentation run cannot satisfy the serialized-join gates.
  adb -s "$serial" shell run-as com.lyrnox.qingliao.qa rm -f \
    files/sfu-invite.json files/sfu-ready files/sfu-publisher-ready \
    files/sfu-pair-ready files/sfu-verify-group files/sfu-metrics.json files/sfu-diagnostics.json
  # The instrumentation launches the peer Activity only after its own persistent
  # test session has been created, so its ChatNet loads the intended cookie.
 done
export QA_SFU_EVIDENCE="$OUT"
python3 android/qa-fixture/remote-sfu-run.py
sha256sum android/app/build/outputs/apk/qa/app-qa.apk android/app/build/outputs/apk/androidTest/qa/app-qa-androidTest.apk > "$OUT/SHA256SUMS"
git rev-parse HEAD > "$OUT/source-commit.txt"
