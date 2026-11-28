#!/usr/bin/env bash
set -euo pipefail
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/build-tools/35.0.0:$PATH"
export ANDROID_AVD_HOME="$RUNNER_TEMP/upgrade-avd"
mkdir -p "$ANDROID_AVD_HOME" android/evidence/upgrade
out="$PWD/android/evidence/upgrade"
exec > >(tee "$out/upgrade.log") 2>&1
pid=''; worker_pid=''; cleanup(){ [[ -z "$worker_pid" ]] || kill "$worker_pid" 2>/dev/null || true; nginx -p "$RUNNER_TEMP/upgrade-nginx/" -c "$RUNNER_TEMP/upgrade-nginx/nginx.conf" -s stop 2>/dev/null || true; adb logcat -d > "$out/logcat.txt" 2>&1 || true; [[ -z "$pid" ]] || kill "$pid" 2>/dev/null || true; }; trap cleanup EXIT
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
adb shell iptables -I OUTPUT 1 ! -o lo -j REJECT
adb shell iptables -I OUTPUT 1 -o lo -j ACCEPT
adb shell ip6tables -P OUTPUT DROP
adb shell ip6tables -I OUTPUT 1 ! -o lo -j REJECT
adb shell ip6tables -I OUTPUT 1 -o lo -j ACCEPT
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

# The signed release has a fixed HTTPS host; route it exclusively to the
# loopback local Worker via an ephemeral test CA and an in-emulator hosts bind.
# Default-deny egress remains active throughout; no production interaction.
mkdir -p "$RUNNER_TEMP/tls" "$RUNNER_TEMP/upgrade-nginx" "$out/local-chat"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$RUNNER_TEMP/tls/ca.key" -out "$RUNNER_TEMP/tls/ca.crt" -days 1 -subj '/CN=Qingliao Offline Test CA' > /dev/null 2>&1
openssl req -newkey rsa:2048 -nodes -keyout "$RUNNER_TEMP/tls/site.key" -out "$RUNNER_TEMP/tls/site.csr" -subj '/CN=chat.lyrnox.com' > /dev/null 2>&1
printf 'subjectAltName=DNS:chat.lyrnox.com\nextendedKeyUsage=serverAuth\n' > "$RUNNER_TEMP/tls/site.ext"
openssl x509 -req -in "$RUNNER_TEMP/tls/site.csr" -CA "$RUNNER_TEMP/tls/ca.crt" -CAkey "$RUNNER_TEMP/tls/ca.key" -CAcreateserial -out "$RUNNER_TEMP/tls/site.crt" -days 1 -extfile "$RUNNER_TEMP/tls/site.ext" > /dev/null 2>&1
hash=$(openssl x509 -inform PEM -subject_hash_old -in "$RUNNER_TEMP/tls/ca.crt" | head -1)
adb push "$RUNNER_TEMP/tls/ca.crt" /data/local/tmp/test-ca.crt
adb shell 'mkdir -p /data/local/tmp/system-cacerts && cp /system/etc/security/cacerts/* /data/local/tmp/system-cacerts/ && mount -t tmpfs tmpfs /system/etc/security/cacerts && cp /data/local/tmp/system-cacerts/* /system/etc/security/cacerts/'
adb shell cp /data/local/tmp/test-ca.crt "/system/etc/security/cacerts/$hash.0"
adb shell chmod 644 "/system/etc/security/cacerts/$hash.0"
adb shell restorecon -R /system/etc/security/cacerts
printf '127.0.0.1 localhost chat.lyrnox.com\n::1 localhost\n' > "$RUNNER_TEMP/hosts"
adb push "$RUNNER_TEMP/hosts" /data/local/tmp/offline-hosts
adb shell chmod 644 /data/local/tmp/offline-hosts
adb shell mount --bind /data/local/tmp/offline-hosts /system/etc/hosts
# Restart zygote/netd so app and resolver inherit the isolated host/cert mounts.
# Egress is rejected before enabling network; assert again after service restart.
adb shell setenforce 0
adb shell svc wifi enable || true
adb shell setprop ctl.restart netd
adb shell setprop ctl.restart zygote
sleep 15
adb wait-for-device
adb root || true
adb wait-for-device
adb shell iptables -I OUTPUT 1 ! -o lo -j REJECT
adb shell iptables -I OUTPUT 1 -o lo -j ACCEPT
adb shell ip6tables -I OUTPUT 1 ! -o lo -j REJECT
adb shell ip6tables -I OUTPUT 1 -o lo -j ACCEPT
adb shell ping -c 1 -W 3 chat.lyrnox.com | tee "$out/local-chat/resolver.txt"
grep -F '127.0.0.1' "$out/local-chat/resolver.txt"
adb shell cat /system/etc/hosts | tee "$out/local-chat/hosts.txt"
adb shell iptables -S OUTPUT | tee "$out/local-chat/firewall.txt"
grep -Fx -- '-P OUTPUT DROP' "$out/local-chat/firewall.txt"
adb reverse tcp:443 tcp:8443
node node_modules/wrangler/bin/wrangler.js d1 migrations apply chat --local --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/local-release-worker" > "$out/local-chat/migrations.log" 2>&1
node node_modules/wrangler/bin/wrangler.js dev --local --ip 127.0.0.1 --port 8000 --config android/qa-fixture/wrangler.local.json --persist-to "$RUNNER_TEMP/local-release-worker" --var "APP_SECRET:$(openssl rand -hex 32)" > "$out/local-chat/worker.log" 2>&1 & worker_pid=$!
up=false; for i in $(seq 1 60); do if curl -fsS http://127.0.0.1:8000/ >/dev/null; then up=true; break; fi; kill -0 "$worker_pid"; sleep 1; done; [[ "$up" == true ]]
cat > "$RUNNER_TEMP/upgrade-nginx/nginx.conf" <<CONFIG
pid $RUNNER_TEMP/upgrade-nginx/nginx.pid;
events { worker_connections 128; }
http {
  access_log $out/local-chat/proxy-access.log;
  error_log $out/local-chat/proxy-error.log;
  server {
    listen 127.0.0.1:8443 ssl;
    ssl_certificate $RUNNER_TEMP/tls/site.crt;
    ssl_certificate_key $RUNNER_TEMP/tls/site.key;
    location / {
      proxy_pass http://127.0.0.1:8000;
      proxy_http_version 1.1;
      proxy_set_header Host \$host;
      proxy_set_header Upgrade \$http_upgrade;
      proxy_set_header Connection \$http_connection;
      proxy_read_timeout 300s;
    }
  }
}
CONFIG
nginx -p "$RUNNER_TEMP/upgrade-nginx/" -c "$RUNNER_TEMP/upgrade-nginx/nginx.conf"
curl --resolve chat.lyrnox.com:8443:127.0.0.1 --noproxy '*' --cacert "$RUNNER_TEMP/tls/ca.crt" -fsS https://chat.lyrnox.com:8443/ > /dev/null
adb shell am force-stop com.lyrnox.qingliao
# Reuse the previously verified UI checks, changing only package ID for the
# exact release APK; the local tunnel replaces its hard-coded origin.
sed "s/PKG='com.lyrnox.qingliao.qa'/PKG='com.lyrnox.qingliao'/" android/qa-fixture/visual-native.py > "$RUNNER_TEMP/release-visual.py"
export QA_EVIDENCE="$out/local-chat"
python3 "$RUNNER_TEMP/release-visual.py" | tee "$out/local-chat/test.txt"
echo 'PASS: original signed APK exercised against isolated local Worker only'
