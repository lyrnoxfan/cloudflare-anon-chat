#!/usr/bin/env python3
"""QA source-bound APK visual state capture, real local Worker; no SFU/production."""
import os,json,re,subprocess,time,xml.etree.ElementTree as ET
import base64,http.cookiejar,socket,urllib.request,uuid
from pathlib import Path
OUT=Path(os.environ['QA_EVIDENCE']);OUT.mkdir(parents=True,exist_ok=True)
PKG='com.lyrnox.qingliao.qa'; results=[]
def adb(*args):return subprocess.check_output(['adb',*args],timeout=30)
def sh(*args):return adb('shell',*args).decode(errors='replace').strip()
def tree():
 sh('rm','-f','/sdcard/visual.xml');r=sh('uiautomator','dump','/sdcard/visual.xml');assert 'UI hierchary dumped to' in r,r
 return ET.fromstring(adb('shell','cat','/sdcard/visual.xml'))
def find(key,attr='text',timeout=25):
 end=time.monotonic()+timeout
 while time.monotonic()<end:
  for n in tree().iter('node'):
   if attr=='contains-desc' and key in n.get('content-desc',''):return n
   if (key in n.get(attr,'') if attr=='contains-desc' else n.get(attr)==key):return n
  time.sleep(.5)
 raise AssertionError('UI missing '+attr+'='+key)
def tap(n,long=False):
 x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')));x,y=str((x1+x2)//2),str((y1+y2)//2)
 if long:sh('input','swipe',x,y,x,y,'1300')
 else:sh('input','tap',x,y)
def click(key,attr='text'):tap(find(key,attr))
def message_menu(text):
 # A send acknowledgement can replace ListView children between the dump and
 # the held touch. Retry the same gesture once against a fresh visible node;
 # never skip the required menu or weaken reply/delete assertions.
 attempts=[]
 for attempt in range(2):
  n=find(text); tap(n,long=True)
  try:
   find('回复',timeout=5); return
  except AssertionError:
   capture('menu-attempt-'+str(len(results))+'-'+str(attempt))
   attempts.append({'configuration':len(results),'attempt':attempt+1,'bounds':n.get('bounds')})
   (OUT/'menu-retry.json').write_text(json.dumps(attempts,ensure_ascii=False,indent=2))
 raise AssertionError('message menu still absent after fresh-node long press: '+text)
def back():sh('input','keyevent','4')
def keyboard_hidden(label):
 end=time.monotonic()+5
 while time.monotonic()<end:
  state=sh('dumpsys','input_method')
  if 'mInputShown=false' in state:
   (OUT/(label+'-ime.txt')).write_text(state);return
  time.sleep(.25)
 (OUT/(label+'-ime.txt')).write_text(state)
 raise AssertionError('IME still shown: '+label)
def capture(name):
 time.sleep(.3);t=tree();(OUT/(name+'.xml')).write_bytes(ET.tostring(t));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 return t
def start():sh('am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
def menu(key):click('更多房间操作','content-desc');click(key)
def check_duplicate_nickname_ui():
 origin='http://127.0.0.1:8000'
 nick='Visual-Android'
 peers=[]; sockets=[]
 try:
  for suffix in ('alpha','beta'):
   jar=http.cookiejar.CookieJar()
   opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
   def post(path,data):
    req=urllib.request.Request(origin+path,data=json.dumps(data).encode(),headers={'Origin':origin,'Content-Type':'application/json'})
    with opener.open(req,timeout=10) as r:return json.load(r)
   who=post('/api/session',{'nickname':nick})
   assert who['nickname']==nick
   key=base64.b64encode(os.urandom(16)).decode()
   token=next(c.value for c in jar if c.name=='chat')
   ws=socket.create_connection(('127.0.0.1',8000),timeout=10); sockets.append(ws)
   ws.sendall((f'GET /api/r/lobby/ws HTTP/1.1\r\nHost: 127.0.0.1:8000\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nSec-WebSocket-Version: 13\r\nSec-WebSocket-Key: {key}\r\nOrigin: {origin}\r\nCookie: chat={token}\r\n\r\n').encode())
   with ws.makefile('rb') as headers:
    assert ' 101 ' in headers.readline().decode('ascii')
    while headers.readline() not in (b'\r\n',b''):pass
   text='same-nickname-'+suffix
   msg=post('/api/r/lobby/send',{'text':text,'clientId':str(uuid.uuid4())})['message']
   assert msg['authorId']==who['id']
   peers.append((text,nick+' · #'+who['id'][:8]))
  assert peers[0][1]!=peers[1][1]
  for text,label in peers:find(text);find(label)
  capture('nickname-messages')
  message_menu(peers[1][0]);click('回复');find('回复给 '+peers[1][1]);capture('nickname-reply')
  click('取消回复','content-desc')
  click('人在线；点击选择一对一通话对象','contains-desc')
  for _,label in peers:find(label)
  capture('nickname-call-picker');click(peers[1][1]);find('当前呼叫 '+peers[1][1],'contains-desc')
  capture('nickname-call-target')
  (OUT/'duplicate-nickname-ui.json').write_text(json.dumps({'status':'passed','checks':['messages and separate labels for two same-name peers','reply to the second peer','both peers listed separately in native call picker','picker selects the second peer'],'scope':'API 29 emulator, localhost Worker; no actual audio or video call'},ensure_ascii=False,indent=2))
 finally:
  for ws in sockets:ws.close()
try:
 start();find('进入聊天');capture('gate');click('昵称','content-desc');sh('input','text','Visual-Android');back();click('进入聊天');find('在线','content-desc')
 for name,w,h,dark in [('small',640,1136,False),('mobile',720,1440,False),('dark',720,1440,True)]:
  checks=[];sh('am','force-stop',PKG);sh('wm','size',str(w)+'x'+str(h));sh('cmd','uimode','night','yes' if dark else 'no');start();find('消息内容','content-desc');find('在线','content-desc');capture(name+'-lobby');checks.append('lobby and connected state')
  click('打开房间导航','content-desc');find('聊天室');capture(name+'-drawer');back()
  menu('创建私密房间');find('创建私人房间');capture(name+'-create');click('取消');checks.append('drawer/create dialog')
  menu('通过房号加入');find('通过房号加入');capture(name+'-join');click('取消');checks.append('join dialog')
  click('更改昵称','content-desc');capture(name+'-rename');click('取消');checks.append('rename dialog')
  click('人在线；点击选择一对一通话对象','contains-desc');capture(name+'-members');click('知道了');checks.append('member range dialog')
  click('打开表情','content-desc');find('插入表情 😀','content-desc');capture(name+'-emoji');click('打开表情','content-desc');checks.append('emoji viewport')
  click('消息内容','content-desc');sh('input','text','visual-'+name+'-message');capture(name+'-keyboard');click('发送消息','content-desc');back();msg=find('visual-'+name+'-message');sent=capture(name+'-sent');find('在线','content-desc');assert not any(n.get('text')=='正在发送…' for n in sent.iter('node')), 'stale delivery status';
  # A short history must occupy the top of the message viewport, not hug the composer.
  bounds=list(map(int,re.findall(r'\d+',msg.get('bounds'))));assert bounds[1] < h*.55, ('short history bottom anchored',bounds,h)
  message_menu('visual-'+name+'-message');capture(name+'-message-menu');click('回复');find('取消回复','content-desc');capture(name+'-reply');click('取消回复','content-desc');start();find('消息内容','content-desc')
  # The next menu is on the visible own message, not the reply preview.
  message_menu('visual-'+name+'-message');click('删除');find('删除这条消息？');capture(name+'-delete');keyboard_hidden(name+'-delete');click('删除消息');keyboard_hidden(name+'-deleted');capture(name+'-deleted');checks.append('send/menu/reply/delete, connected state retained, IME closed before and after deletion')
  results.append({'name':name,'physicalSize':[w,h],'density':320,'checks':checks,'scope':'API29 AOSP emulator, source-bound candidate QA APK, local real Worker'})
 check_duplicate_nickname_ui()
 (OUT/'native-visual.json').write_text(json.dumps({'status':'passed','results':results,'notCovered':['physical handset','SFU/call media','media codec and audio routes','complete pixel parity']},ensure_ascii=False,indent=2))
except BaseException as e:
 try:capture('failure')
 except BaseException:pass
 (OUT/'native-visual.json').write_text(json.dumps({'status':'failed','results':results,'error':str(e)},ensure_ascii=False,indent=2));raise
