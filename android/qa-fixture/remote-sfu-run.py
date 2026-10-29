#!/usr/bin/env python3
import json, os, subprocess, time
from pathlib import Path
out=Path(os.environ['QA_SFU_EVIDENCE'])
runner='com.lyrnox.qingliao.qa.test/com.lyrnox.qingliao.RemoteSfuInstrumentation'
processes=[]
def begin(serial,role,extra=()):
    f=open(out/f'{role}.txt','w')
    p=subprocess.Popen(['adb','-s',serial,'shell','am','instrument','-w','-r','-e','approvedTarget','blue-room-test','-e','role',role,*extra,runner],stdout=f,stderr=subprocess.STDOUT)
    processes.append((p,f)); return p
try:
    owner=begin('emulator-5554','owner')
    deadline=time.monotonic()+100
    invite=None
    while time.monotonic()<deadline and owner.poll() is None:
        try:
            r=subprocess.run(['adb','-s','emulator-5554','shell','run-as','com.lyrnox.qingliao.qa','cat','files/sfu-invite.json'],capture_output=True,timeout=10)
        except subprocess.TimeoutExpired:
            # A busy emulator may not service a concurrent shell immediately.
            # Keep waiting for the same owner; never spawn another session here.
            continue
        try:
            invite=json.loads(r.stdout)
            if invite.get('room') and invite.get('password'): break
        except (ValueError,UnicodeDecodeError): pass
        time.sleep(1)
    if not invite: raise RuntimeError('Owner did not create isolated SFU invite; see owner.txt')
    # The invite is ephemeral test data, never a session or provider credential; keep it out of logs.
    common=('-e','room',invite['room'],'-e','password',invite['password'])
    extra=begin('emulator-5558','extra',common)
    peer=begin('emulator-5556','peer',common)
    # Keep all three real clients publishing until each receiver sees two distinct
    # remote video tracks; peer additionally proves the Activity lays out both remotes.
    expected={'owner','peer','extra'}; ready=set(); deadline=time.monotonic()+300
    while time.monotonic()<deadline:
        for serial,role in [('emulator-5554','owner'),('emulator-5556','peer'),('emulator-5558','extra')]:
            r=subprocess.run(['adb','-s',serial,'shell','run-as','com.lyrnox.qingliao.qa','cat','files/sfu-ready'],capture_output=True,timeout=10)
            if r.returncode==0 and b'media-ready' in r.stdout: ready.add(role)
        if ready==expected: break
        if any(p.poll() is not None for p,f in processes): break
        time.sleep(1)
    if ready == expected:
        screenshot=subprocess.check_output(['adb','-s','emulator-5556','exec-out','screencap','-p'],timeout=10)
        (out/'peer-activity-connected.png').write_bytes(screenshot)
    # Peer exercises Home/cleanup before the other clients hang up.
    for serial in ('emulator-5556','emulator-5558','emulator-5554'):
        subprocess.run(['adb','-s',serial,'shell','run-as','com.lyrnox.qingliao.qa','mkdir','-p','files'],check=True,timeout=10)
        subprocess.run(['adb','-s',serial,'shell','run-as','com.lyrnox.qingliao.qa','touch','files/sfu-release'],check=True,timeout=10)
    for p,f in processes:
        p.wait(timeout=45); f.flush()
    for role in ('owner','peer','extra'):
        text=(out/f'{role}.txt').read_text()
        print(text)
        if role == 'peer' and 'PASS actual Activity invitation/join' not in text: raise AssertionError('Activity acceptance missing')
        if 'PASS native SFU '+role not in text or 'renderedRemoteVideoTracks=2' not in text or 'PASS native hangup' not in text or 'INSTRUMENTATION_CODE: -1' not in text:
            raise AssertionError(role+' group SFU acceptance failed')
    (out/'result.json').write_text(json.dumps({'passed':True,'target':'blue-room-test / chat.lyrnox.com','devices':'three isolated Android 10 emulators','media':'three-way group call; each client received two independent remote video tracks; Activity rendered both remote participants; emulated Camera2 and microphone, not physical-device or subjective audio quality'},indent=2))
finally:
    for p,f in processes:
        if p.poll() is None:
            p.terminate()
            try: p.wait(timeout=10)
            except subprocess.TimeoutExpired: p.kill();p.wait()
        f.close()
