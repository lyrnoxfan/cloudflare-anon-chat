#!/usr/bin/env python3
import json, os, subprocess, time
from pathlib import Path
out=Path(os.environ['QA_SFU_EVIDENCE'])
runner='com.lyrnox.qingliao.qa.test/com.lyrnox.qingliao.RemoteSfuInstrumentation'
processes=[]
serials={'owner':'emulator-5554','peer':'emulator-5556','extra':'emulator-5558'}
def adb_emulator(serial, *args):
    return subprocess.check_output(['adb','-s',serial,'emu',*args],stderr=subprocess.STDOUT,timeout=10).decode(errors='replace').strip()
def read_metrics(serial):
    deadline=time.monotonic()+20
    while time.monotonic()<deadline:
        r=subprocess.run(['adb','-s',serial,'shell','run-as','com.lyrnox.qingliao.qa','cat','files/sfu-metrics.json'],capture_output=True,timeout=10)
        if r.returncode==0:
            try: return json.loads(r.stdout)
            except (ValueError,UnicodeDecodeError): pass
        time.sleep(0.5)
    raise RuntimeError('Could not read live SFU media metrics on '+serial)
def begin(serial,role,extra=()):
    f=open(out/f'{role}.txt','w')
    p=subprocess.Popen(['adb','-s',serial,'shell','am','instrument','-w','-r','-e','approvedTarget','blue-room-test','-e','role',role,*extra,runner],stdout=f,stderr=subprocess.STDOUT)
    processes.append((p,f)); return p
def wait_marker(serial, name, role, timeout):
    deadline=time.monotonic()+timeout
    path=name
    while time.monotonic()<deadline:
        r=subprocess.run(['adb','-s',serial,'shell','run-as','com.lyrnox.qingliao.qa','cat',path],capture_output=True,timeout=10)
        if r.returncode==0 and r.stdout.strip(): return
        failed=[label for (p,_),label in zip(processes,('owner','extra','peer')) if p.poll() is not None]
        if failed: raise RuntimeError('SFU participant exited before '+role+' marker; completed='+str(failed)+'; see participant logs')
        time.sleep(1)
    raise RuntimeError('Timed out waiting for '+role+' marker '+name)
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
    # Bring up one joiner at a time: concurrent three-party ICE/SFU negotiations
    # intermittently left one publisher without remote tracks. Wait for owner setup,
    # then for a stable two-way decoded stream before admitting the Activity peer.
    wait_marker('emulator-5554','files/sfu-publisher-ready','owner publisher',120)
    extra=begin('emulator-5558','extra',common)
    wait_marker('emulator-5554','files/sfu-pair-ready','owner first peer media',120)
    wait_marker('emulator-5558','files/sfu-pair-ready','extra first peer media',120)
    peer=begin('emulator-5556','peer',common)
    wait_marker('emulator-5556','files/sfu-publisher-ready','Activity peer publisher',150)
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
    if ready != expected:
        raise RuntimeError('Three clients did not all reach media-ready: '+str(sorted(ready)))
    screenshot=subprocess.check_output(['adb','-s','emulator-5556','exec-out','screencap','-p'],timeout=10)
    (out/'peer-activity-connected.png').write_bytes(screenshot)
    # Exercise a reproducible constrained path on all three emulators while the
    # actual SFU media sessions remain live. This checks media continuity under
    # 150 ms RTT-style delay and 2 Mbps symmetric bandwidth, not packet-loss recovery.
    before={role:read_metrics(serial) for role,serial in serials.items()}
    network_applied=[]
    try:
        for serial in serials.values():
            adb_emulator(serial,'network','delay','150')
            network_applied.append(serial)
            adb_emulator(serial,'network','speed','2048','2048')
        time.sleep(15)
        after={role:read_metrics(serial) for role,serial in serials.items()}
        deltas={role:{key:after[role].get(key,0)-before[role].get(key,0)
                      for key in ('audioPackets','videoBytes','publisherAudioBytes','publisherVideoBytes')}
                for role in serials}
        stale_metrics=[role for role in serials
                       if after[role].get('sampledAtMs',0) <= before[role].get('sampledAtMs',0)]
        if stale_metrics:
            raise AssertionError('Live SFU metrics were not refreshed during constrained interval: '+', '.join(stale_metrics))
        connected_ice={'CONNECTED','COMPLETED'}
        checks={role:{
            'metrics_refreshed':after[role].get('sampledAtMs',0)>before[role].get('sampledAtMs',0),
            'receiver_connected':after[role].get('connectionState')=='CONNECTED' and after[role].get('iceState') in connected_ice,
            'publisher_connected':after[role].get('publisherConnectionState')=='CONNECTED' and after[role].get('publisherIceState') in connected_ice,
            'video_progressed':deltas[role]['videoBytes']>0,
            # A silent emulator microphone can produce no new RTP audio to count. Only
            # require a receiver delta when at least one remote publisher actually sent audio.
            'remote_audio_source_active':sum(max(0,deltas[sender]['publisherAudioBytes']) for sender in serials if sender!=role)>0,
            'audio_progressed':deltas[role]['audioPackets']>0,
        } for role in serials}
        evidence={
            'conditions':{'emulator_network_delay_ms':150,'down_mbps':2,'up_mbps':2,'duration_seconds':15},
            'media_counters_before':before,'media_counters_after':after,'counter_deltas':deltas,'checks':checks,
            'audio_note':'Audio continuity is verified only for a receiver when a remote publisher emitted audio bytes during the interval; otherwise the silent emulated source makes it unverified.'
        }
        failures=[]
        for role in serials:
            if not checks[role]['receiver_connected'] or not checks[role]['publisher_connected']:
                failures.append(role+' peer connection was not connected at the end of constrained interval')
            if not checks[role]['video_progressed']:
                failures.append(role+' received no additional video bytes under constrained network')
            if checks[role]['remote_audio_source_active'] and not checks[role]['audio_progressed']:
                failures.append(role+' received no audio packets although a remote publisher sent audio bytes')
        evidence['passed']=not failures
        (out/'network-impairment.json').write_text(json.dumps(evidence,indent=2))
        if failures: raise AssertionError('; '.join(failures))
    finally:
        for serial in network_applied:
            adb_emulator(serial,'network','speed','full')
            adb_emulator(serial,'network','delay','none')
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
    (out/'result.json').write_text(json.dumps({'passed':True,'target':'blue-room-test / chat.lyrnox.com','devices':'three isolated Android 10 emulators','media':'three-way group call; each client received two independent remote video tracks; Activity rendered both remote participants; emulated Camera2 and microphone, not physical-device or subjective audio quality','network':'all three peer connections stayed connected and video counters progressed under 150 ms delay and 2 Mbps symmetric emulator bandwidth; audio continuity is assessed only when the emulated publishers emit audio bytes; no packet-loss or outage-recovery claim'},indent=2))
finally:
    for p,f in processes:
        if p.poll() is None:
            p.terminate()
            try: p.wait(timeout=10)
            except subprocess.TimeoutExpired: p.kill();p.wait()
        f.close()
