#!/usr/bin/env python3
"""Packet-level outage on a disposable rootable AOSP emulator; local Worker only.
Not a physical-device or SFU acceptance. Does not clear app data.
"""
import http.cookiejar,json,os,re,subprocess,time,urllib.request,uuid
import xml.etree.ElementTree as ET
from pathlib import Path
OUT=Path(os.environ['QA_EVIDENCE']); OUT.mkdir(parents=True,exist_ok=True)
PKG='com.lyrnox.qingliao.qa'; checks=[]; rule=None; inbound=None

def adb(*args):
 return subprocess.check_output(['adb',*args],timeout=30)
def shell(*args): return adb('shell',*args).decode(errors='replace').strip()
def tree():
 shell('rm','-f','/sdcard/outage-window.xml')
 r=shell('uiautomator','dump','/sdcard/outage-window.xml')
 assert 'UI hierchary dumped to' in r,r
 return ET.fromstring(adb('shell','cat','/sdcard/outage-window.xml'))
def find(text,attr='text',contains=False,timeout=45):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  t=tree(); (OUT/'latest-window.xml').write_bytes(ET.tostring(t))
  for n in t.iter('node'):
   v=n.get(attr,'')
   if (text in v if contains else text==v): return n
  time.sleep(1)
 raise AssertionError('Missing UI: '+text)
def tap(n):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
 assert x2>x1 and y2>y1
 shell('input','tap',str((x1+x2)//2),str((y1+y2)//2))
def capture(name):
 (OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 (OUT/(name+'.xml')).write_bytes(ET.tostring(tree()))
def passed(s): checks.append(s); print('PASS '+s,flush=True)
jar=http.cookiejar.CookieJar(); peer=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def api(path,data=None):
 body=None if data is None else json.dumps(data).encode()
 req=urllib.request.Request('http://127.0.0.1:8000'+path,data=body,headers={'Content-Type':'application/json','Origin':'http://127.0.0.1:8000'})
 with peer.open(req,timeout=15) as r:return json.load(r)
def send(text): return api('/api/r/lobby/send',{'text':text,'clientId':str(uuid.uuid4())})
try:
 api('/api/session',{'nickname':'outage-peer'})
 shell('am','force-stop',PKG)
 shell('am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
 tap(find('昵称','content-desc')); shell('input','text','Outage-Android'); shell('input','keyevent','4'); tap(find('进入聊天'))
 find('消息内容','content-desc'); find('在线','content-desc')
 before='before-'+uuid.uuid4().hex[:10]; send(before); find(before)
 capture('01-online'); passed('real Worker text arrives in native Activity before outage')
 uid=re.search(r'userId=(\d+)',shell('dumpsys','package',PKG)).group(1)
 # Reject just this app's traffic to the isolated loopback Worker. ADB and host peer remain live.
 rule=['OUTPUT','-p','tcp','-d','127.0.0.1','--dport','8000','-m','owner','--uid-owner',uid,'-j','REJECT']
 shell('iptables','-I',*rule)
 inbound=['INPUT','-p','tcp','-s','127.0.0.1','--sport','8000','-j','DROP']
 shell('iptables','-I',*inbound)
 out='outage-send-'+uuid.uuid4().hex[:10]
 tap(find('消息内容','content-desc')); shell('input','text',out); tap(find('发送消息','content-desc')); shell('input','keyevent','4')
 find('重试',timeout=60)
 counters=shell('iptables','-L','OUTPUT','-v','-n','-x'); (OUT/'outage-packet-counters.txt').write_text(counters)
 rows=[r for r in counters.splitlines() if 'REJECT' in r and 'dpt:8000' in r and 'owner UID match '+uid in r]
 assert rows and int(rows[0].split()[0])>0,counters
 assert not any(m.get('text')==out for m in api('/api/r/lobby/history')['messages'])
 passed('UID-scoped iptables actually rejects packets; failed send is absent from server history')
 missed='missed-'+uuid.uuid4().hex[:10]; send(missed)
 capture('02-outage-failed-send')
 # During isolation the new peer message must not have reached the app.
 assert not any(n.get('text')==missed for n in tree().iter('node'))
 passed('independent peer remains online while Android is isolated')
 shell('iptables','-D',*inbound); inbound=None
 shell('iptables','-D',*rule); rule=None; restored=time.monotonic()
 find(missed,timeout=90)
 recovery_seconds=round(time.monotonic()-restored,2)
 passed('native WebSocket/history automatically catches up after packet traffic returns')
 tap(find('重试')); find(out,timeout=45)
 # Wait for pending UI to disappear, not merely matching text in the outbox card.
 end=time.monotonic()+45
 while time.monotonic()<end:
  if not any(n.get('text')=='重试' for n in tree().iter('node')):break
 else: raise AssertionError('pending retry card not cleared')
 messages=api('/api/r/lobby/history')['messages']
 assert sum(m.get('text')==out for m in messages)==1
 capture('03-recovered'); passed('retry commits exact text once and removes pending card')
 shell('am','force-stop',PKG); shell('am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
 find(out); find(missed); capture('04-restarted')
 passed('restarted Activity restores persisted missed and retried messages')
 (OUT/'network-outage.json').write_text(json.dumps({'status':'passed','appSource':'4cbbff5d923499215a07df835430311f358527a6','checks':checks,'recoverySeconds':recovery_seconds,'boundary':'AOSP API29 packet rejection, localhost Worker/D1/R2/DO; no physical device, SFU or production traffic'},ensure_ascii=False,indent=2))
 print('NETWORK OUTAGE ACCEPTANCE PASSED',flush=True)
except BaseException as e:
 try:capture('failure')
 except BaseException:pass
 (OUT/'network-outage.json').write_text(json.dumps({'status':'failed','checks':checks,'error':str(e)},ensure_ascii=False,indent=2))
 raise
finally:
 if inbound:
  try:shell('iptables','-D',*inbound)
  except BaseException:pass
 if rule:
  try:shell('iptables','-D',*rule)
  except BaseException:pass
