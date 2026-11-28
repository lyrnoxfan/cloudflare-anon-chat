#!/usr/bin/env bash
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/build-tools/35.0.0:$PATH"
export ANDROID_AVD_HOME="$RUNNER_TEMP/upgrade-avd"
mkdir -p "$ANDROID_AVD_HOME" android/evidence/upgrade
out="$PWD/android/evidence/upgrade"
exec > >(tee "$out/upgrade.log") 2>&1
pid=''; cleanup(){ adb logcat -d > "$out/logcat.txt" 2>&1 || true; [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; }; trap cleanup EXIT
old=input/old/qingliao-android-0.3.3-release-a92dd46.apk
new=input/new/qingliao-liaoji-release-fb27f3b.apk
sha256sum "$old" "$new"
apksigner verify --print-certs "$old" | tee "$out/old-signer.txt"
apksigner verify --print-certs "$new" | tee "$out/new-signer.txt"
grep -Fx 'Signer #1 certificate SHA-256 digest: 76c1f82df878d1712f5ca20f25f76ed8f3b6d135c3e004f9e7830f73a3f1be50' "$out/old-signer.txt"
grep -Fx 'Signer #1 certificate SHA-256 digest: 76c1f82df878d1712f5ca20f25f76ed8f3b6d135c3e004f9e7830f73a3f1be50' "$out/new-signer.txt"
aapt dump badging "$old" | head -3; aapt dump badging "$new" | head -3
test -c /dev/kvm; sudo chmod a+rw /dev/kvm
"$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager" create avd --force --name release-upgrade --package 'system-images;android-29;default;x86_64' --device pixel_2 <<< no
emulator -avd release-upgrade -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 2048 -cores 2 > "$out/emulator.log" 2>&1 & pid=$!
booted=false
for i in $(seq 1 120); do if [[ "$(timeout 5 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; then booted=true; break; fi; kill -0 "$pid"; sleep 2; done
[[ "$booted" == true ]]
for i in $(seq 1 12); do timeout 10 adb wait-for-device || true; [[ "$(timeout 5 adb shell id -u 2>/dev/null | tr -d '\r')" == 0 ]] && break; timeout 10 adb root || true; sleep 2; done
[[ "$(adb shell id -u | tr -d '\r')" == 0 ]]
adb shell svc wifi disable || true
adb shell svc data disable || true
# Device-wide egress is blocked before either release app starts; no production requests.
adb shell iptables -P OUTPUT DROP
adb shell iptables -I OUTPUT 1 -o lo -j ACCEPT
adb shell iptables -C OUTPUT -o lo -j ACCEPT
adb shell iptables -S OUTPUT | tee "$out/firewall.txt"
grep -Fx -- '-P OUTPUT DROP' "$out/firewall.txt"
adb shell input keyevent 82
adb install "$old"
adb shell dumpsys package com.lyrnox.qingliao > "$out/old-package.txt"
# A deterministic test-only nickname in the previous app's real preference file;
# this checks package-data persistence, not a claim that the UI created the value.
adb shell mkdir -p /data/data/com.lyrnox.qingliao/shared_prefs
uid=$(adb shell stat -c %u /data/data/com.lyrnox.qingliao | tr -d '\r')
printf '<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map><string name="nickname">UpgradeCanary</string></map>\n' > "$RUNNER_TEMP/MainActivity.xml"
adb push "$RUNNER_TEMP/MainActivity.xml" /data/local/tmp/upgrade-prefs.xml
adb shell cp /data/local/tmp/upgrade-prefs.xml /data/data/com.lyrnox.qingliao/shared_prefs/MainActivity.xml
adb shell chown "$uid:$uid" /data/data/com.lyrnox.qingliao/shared_prefs /data/data/com.lyrnox.qingliao/shared_prefs/MainActivity.xml
adb shell chmod 700 /data/data/com.lyrnox.qingliao/shared_prefs
adb shell chmod 600 /data/data/com.lyrnox.qingliao/shared_prefs/MainActivity.xml
adb shell restorecon -R /data/data/com.lyrnox.qingliao/shared_prefs
adb shell cat /data/data/com.lyrnox.qingliao/shared_prefs/MainActivity.xml | tee "$out/prefs-before.xml"
grep -q 'UpgradeCanary' "$out/prefs-before.xml"
adb install -r "$new" | tee "$out/install.txt"
grep -q Success "$out/install.txt"
adb shell cat /data/data/com.lyrnox.qingliao/shared_prefs/MainActivity.xml | tee "$out/prefs-after.xml"
grep -q 'UpgradeCanary' "$out/prefs-after.xml"
adb shell dumpsys package com.lyrnox.qingliao > "$out/new-package.txt"
adb shell am start -W -n com.lyrnox.qingliao/.MainActivity | tee "$out/launch.txt"
grep -q 'Status: ok' "$out/launch.txt"
sleep 5
adb shell uiautomator dump /sdcard/upgrade.xml
adb shell cat /sdcard/upgrade.xml > "$out/screen.xml"
adb exec-out screencap -p > "$out/screen.png"
python3 - "$out/screen.xml" <<'PY'
import sys,xml.etree.ElementTree as ET
r=ET.parse(sys.argv[1]).getroot()
ns=list(r.iter('node'))
assert any(n.get('text') == 'UpgradeCanary' for n in ns), 'saved nickname not visible after upgrade'
assert any(n.get('text') == '进入聊天' for n in ns), 'updated chat entry screen not visible'
print('PASS: signed upgrade succeeded, test preference preserved and visible on entry screen; no network exercised')
PY
