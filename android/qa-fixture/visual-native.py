#!/usr/bin/env python3
"""Frozen QA APK visual state capture, real local Worker; no SFU/production."""
import os,json,re,subprocess,time,xml.etree.ElementTree as ET
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
def back():sh('input','keyevent','4')
def capture(name):
 time.sleep(.3);t=tree();(OUT/(name+'.xml')).write_bytes(ET.tostring(t));(OUT/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 return t
def start():sh('am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
def menu(key):click('更多房间操作','content-desc');click(key)
try:
 start();find('进入聊天');capture('gate');click('昵称','content-desc');sh('input','text','Visual-Android');back();click('进入聊天');find('在线','content-desc')
 for name,w,h,dark in [('small',640,1136,False),('mobile',720,1440,False),('dark',720,1440,True)]:
  checks=[];sh('am','force-stop',PKG);sh('wm','size',str(w)+'x'+str(h));sh('cmd','uimode','night','yes' if dark else 'no');start();find('消息内容','content-desc');find('在线','content-desc');capture(name+'-lobby');checks.append('lobby and connected state')
  click('打开房间导航','content-desc');find('房间与成员');capture(name+'-drawer');back()
  menu('创建私密房间');find('房间名称');capture(name+'-create');click('取消');checks.append('drawer/create dialog')
  menu('通过房号加入');find('房号');capture(name+'-join');click('取消');checks.append('join dialog')
  click('更改昵称','content-desc');capture(name+'-rename');click('取消');checks.append('rename dialog')
  click('人在线；点击选择一对一通话对象','contains-desc');capture(name+'-members');click('知道了');checks.append('member range dialog')
  click('打开表情','content-desc');find('插入表情 😀','content-desc');capture(name+'-emoji');click('打开表情','content-desc');checks.append('emoji viewport')
  click('消息内容','content-desc');sh('input','text','visual-'+name+'-message');capture(name+'-keyboard');click('发送消息','content-desc');back();msg=find('visual-'+name+'-message');capture(name+'-sent');tap(msg,long=True);find('回复');capture(name+'-message-menu');click('回复');find('取消回复','content-desc');capture(name+'-reply');click('取消回复','content-desc');start();find('消息内容','content-desc')
  # The next menu is on the visible own message, not the reply preview.
  tap(find('visual-'+name+'-message'),long=True);click('删除');find('删除这条消息？其他成员也会看到删除状态。');capture(name+'-delete');click('删除');checks.append('send/menu/reply/delete')
  results.append({'name':name,'physicalSize':[w,h],'density':320,'checks':checks,'scope':'API29 AOSP emulator, frozen4cbbff5 QA APK, local real Worker'})
 (OUT/'native-visual.json').write_text(json.dumps({'status':'passed','results':results,'notCovered':['physical handset','SFU/call media','media codec and audio routes','complete pixel parity']},ensure_ascii=False,indent=2))
except BaseException as e:
 try:capture('failure')
 except BaseException:pass
 (OUT/'native-visual.json').write_text(json.dumps({'status':'failed','results':results,'error':str(e)},ensure_ascii=False,indent=2));raise
