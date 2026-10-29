#!/usr/bin/env python3
"""Exercise the actual QA Activity through ADB/UIAutomator, not JNI-only view substitutes.
Only an ephemeral CI emulator and the loopback fixture are used. Never clears installed data.
"""
import urllib.request
import hashlib
import json
import os
import re
import subprocess
import struct
import time
import xml.etree.ElementTree as ET
from pathlib import Path

OUT = Path(os.environ.get('QA_EVIDENCE', 'evidence'))
OUT.mkdir(parents=True, exist_ok=True)
checks = []
PKG = 'com.lyrnox.qingliao.qa'

def adb(*args, timeout=25):
    return subprocess.check_output(['adb', *args], timeout=timeout)

def tree():
    # UIAutomator may print an idle timeout but return exit 0 and leave an old XML.
    # Delete that prior snapshot, and reject the failure instead of reading stale UI.
    adb('shell', 'rm', '-f', '/sdcard/qa-window.xml')
    result = adb('shell', 'uiautomator', 'dump', '/sdcard/qa-window.xml')
    if b'UI hierchary dumped to' not in result:
        raise AssertionError('UIAutomator did not create a fresh hierarchy: ' + result.decode(errors='replace'))
    return ET.fromstring(adb('shell', 'cat', '/sdcard/qa-window.xml'))

def locate(key, attr='text', timeout=25, contains=False, class_name=None):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = tree()
        for n in last.iter('node'):
            if class_name and n.get('class') != class_name:
                continue
            value = n.get(attr, '')
            if (key in value if contains else key == value):
                bounds = list(map(int, re.findall(r'\d+', n.get('bounds', ''))))
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    return n
        time.sleep(0.5)
    if last is not None:
        (OUT / 'failure-window.xml').write_bytes(ET.tostring(last, encoding='utf-8'))
    raise AssertionError(f'Visible node not found: {attr}={key}')

def scroll_locate(key, attr='text', direction='up', attempts=8, **kwargs):
    for _ in range(attempts):
        try:
            return locate(key, attr, timeout=1, **kwargs)
        except AssertionError:
            # Swipes stay inside the actual message list, not the composer/system bars.
            if direction == 'up':
                adb('shell','input','swipe','360','1050','360','400','400')
            else:
                adb('shell','input','swipe','360','400','360','1050','400')
    return locate(key, attr, timeout=5, **kwargs)

def tap_node(n, long=False):
    x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
    x,y = (x1+x2)//2, (y1+y2)//2
    if long: adb('shell','input','swipe',str(x),str(y),str(x),str(y),'900')
    else: adb('shell','input','tap',str(x),str(y))

def tap(key, attr='text', **kwargs):
    tap_node(locate(key, attr, **kwargs))

def capture(name):
    (OUT / f'{name}.png').write_bytes(adb('exec-out','screencap','-p'))
    (OUT / f'{name}.xml').write_bytes(ET.tostring(tree(), encoding='utf-8'))

def wait_palette(dark):
    # Configuration changes/recreation are asynchronous; an unchanged accessibility
    # tree is not proof that the new palette is painted. Sample the content background.
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        raw = adb('exec-out', 'screencap')
        width, height, fmt = struct.unpack_from('<III', raw)
        # Android releases use a 12-byte header or add a 4-byte color-space field.
        header = len(raw) - width * height * 4
        if fmt == 1 and header in (12, 16):
            offset = header + (200 * width + 3) * 4
            rgb = raw[offset:offset + 3]
            if (max(rgb) < 100) if dark else (min(rgb) > 200):
                return
        time.sleep(0.5)
    raise AssertionError('Requested palette was not actually painted: dark=' + str(dark))

def passed(name):
    checks.append(name)
    print('PASS ' + name, flush=True)

try:
    adb('shell','am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
    locate('进入聊天')
    capture('01-nickname-gate')
    # Chat must be hidden from the accessibility tree while no session exists.
    assert not any(n.get('content-desc') == '消息内容' for n in tree().iter('node'))
    passed('Activity nickname gate hides chat accessibility')
    tap('昵称','content-desc')
    adb('shell','input','text','CI-Android')
    adb('shell','input','keyevent','4')
    tap('进入聊天')
    locate('打开房间导航','content-desc')
    locate('消息内容','content-desc')
    locate('2 人在线', contains=True)
    capture('02-chat-light')
    # Start at the tail as a chat should; scroll back to inspect the older grouped avatar.
    scroll_locate('小简', 'content-desc', direction='down')
    # A visible grouped-message avatar must keep its full 30dp height (CI density is 2).
    avatars = [n for n in tree().iter('node') if n.get('content-desc') == '小简']
    assert avatars, 'Peer avatar missing'
    for n in avatars:
        x1,y1,x2,y2 = map(int, re.findall(r'\d+', n.get('bounds')))
        assert y2-y1 >= 58, f'Avatar clipped: {n.get("bounds")}'
    passed('session opens real native chat and WebSocket presence; avatars are not clipped')
    scroll_locate('周末去书店，顺便喝杯咖啡 ☕', direction='up')
    tap('消息内容','content-desc')
    marker = 'native-ui-' + str(int(time.time()))
    adb('shell','input','text',marker)
    tap('发送消息','content-desc')
    adb('shell','input','keyevent','4')
    # A matching EditText is an unsent draft, not a delivered message.
    locate(marker, class_name='android.widget.TextView')
    # UIAutomator exposes EditText hint as text when the actual value is empty.
    composer = locate('消息内容', 'content-desc')
    assert composer.get('text', '') in ('', '说点什么…'), 'Composer retains a draft'
    locate('录制语音', 'content-desc')
    assert not any(n.get('content-desc') == '发送消息' for n in tree().iter('node'))
    passed('typed text is sent and rendered in Activity')
    capture('03-sent-message')
    tap_node(locate(marker, class_name='android.widget.TextView'), long=True)
    tap('回复')
    locate('取消回复','content-desc')
    passed('message long press opens reply draft')
    tap('取消回复','content-desc')
    # Cancel clears only the reply state; the keyboard may already be hidden.
    # Do not send an unconditional Back that can legitimately leave the Activity.
    tap_node(locate(marker, class_name='android.widget.TextView'), long=True)
    tap('删除')
    locate('删除这条消息？其他成员也会看到删除状态。')
    tap('删除')
    locate('消息已删除',contains=True)
    passed('own-message deletion renders tombstone')
    tap('打开表情','content-desc')
    locate('插入表情','content-desc',contains=True)
    capture('04-emoji-panel')
    passed('bounded emoji panel is reachable')
    tap('打开表情','content-desc')
    tap('打开房间导航','content-desc')
    locate('通过房号加入')
    locate('创建私密房间')
    capture('05-room-drawer')
    passed('native room drawer exposes join/create actions')
    adb('shell','input','keyevent','4')
    locate('消息内容','content-desc')
    assert not any(n.get('text') == '通过房号加入' for n in tree().iter('node'))
    passed('Back closes the room drawer without leaving Activity')
    # Inspect actual media controls, not merely the source code/card presence.
    tap_node(scroll_locate('▶  轻触播放', direction='down'))
    # A short clip may finish before UIAutomator returns, so accept either painted status.
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        labels = [n.get('text', '') for n in tree().iter('node')]
        if '视频正在播放' in labels or '视频播放结束' in labels: break
        time.sleep(0.5)
    else: raise AssertionError('Inline video did not start/decode')
    capture('06-inline-video')
    passed('fixture video starts through the actual inline Android player')
    # Move to the preceding media messages without tapping outside the scroll view.
    for _ in range(5):
        try:
            image = locate('书店.png', 'content-desc', timeout=1)
            break
        except AssertionError:
            adb('shell','input','swipe','360','400','360','850','500')
    else: raise AssertionError('Image card was not reachable by scrolling')
    tap_node(image)
    locate('关闭图片预览', 'content-desc')
    locate('图片全屏预览，可双指缩放：书店.png', 'content-desc')
    capture('06-image-viewer')
    tap('关闭图片预览', 'content-desc')
    passed('authorized fixture image decodes in native full-screen viewer')
    # The system change recreates the Activity: also exercises durable session restoration.
    night_output = adb('shell','cmd','uimode','night','yes')
    (OUT/'night-command.txt').write_bytes(night_output)
    state = adb('shell','dumpsys','uimode')
    assert b'mNightMode=2' in state, 'System rejected requested night mode; see night-command.txt'
    # API29 defers night-mode configuration while the device is active. Let the
    # system apply it through a real inactive/wake transition, not app theme overrides.
    adb('shell','input','keyevent','223')
    time.sleep(2)
    adb('shell','input','keyevent','224')
    adb('shell','input','keyevent','82')
    (OUT/'night-system.txt').write_bytes(adb('shell','dumpsys','uimode'))
    locate('消息内容','content-desc')
    locate('打开房间导航','content-desc')
    wait_palette(True)
    capture('06-chat-dark')
    passed('system dark mode recreates and restores chat session')
    # Return to light for a directly comparable screenshot.
    adb('shell','cmd','uimode','night','no')
    adb('shell','input','keyevent','223')
    time.sleep(2)
    adb('shell','input','keyevent','224')
    adb('shell','input','keyevent','82')
    locate('消息内容','content-desc')
    wait_palette(False)
    capture('07-chat-restored')
    passed('light mode restores after configuration change')
    # UIAutomator cannot dump during continuous accessibility events from the
    # player. Use the measured controls and check each state after pausing; a
    # stale pre-play hierarchy is never accepted as evidence of playback.
    play = scroll_locate('播放语音', 'content-desc', direction='down')
    seek = locate('语音播放进度', 'content-desc')
    tap_node(play)
    time.sleep(4)
    tap_node(play)
    paused = locate('继续播放语音', 'content-desc')
    capture('08-audio-paused')
    passed('actual Android audio player starts and pauses')
    tap_node(paused)
    time.sleep(2)
    tap_node(seek)
    time.sleep(2)
    tap_node(play)
    locate('继续播放语音', 'content-desc')
    # The 60-second fixture sought to the centre should show at least 20s.
    audio_tree = tree()
    audio_cards = [n for n in audio_tree.iter('node') if '语音消息，轻触播放' in n.get('content-desc','')]
    assert audio_cards, 'Audio card missing after seek'
    labels = [n.get('text','') for n in audio_cards[0].iter('node')]
    assert any(re.fullmatch(r'0:[2-5][0-9]', value) for value in labels), 'Seek did not update playback position: ' + repr(labels)
    capture('09-audio-progress')
    passed('audio resumes and seeks to the middle of the 60-second fixture')
    # Resume, then leave while playing: backgrounding must stop the player.
    tap('继续播放语音', 'content-desc')
    time.sleep(2)
    adb('shell','input','keyevent','3')
    time.sleep(1)
    adb('shell','am','start','-W','-n',PKG+'/com.lyrnox.qingliao.MainActivity')
    scroll_locate('播放语音', 'content-desc', direction='down')
    assert not any(n.get('content-desc') == '暂停语音' for n in tree().iter('node'))
    passed('Home stops audio and foreground return does not silently resume it')
    # Save through the real system document picker; compare output bytes independently.
    tap_node(scroll_locate('书店笔记.txt', direction='up'))
    locate('SAVE', timeout=25)
    assert any(n.get('package') == 'com.android.documentsui' for n in tree().iter('node'))
    capture('10-document-picker')
    tap('SAVE')
    locate('消息内容', 'content-desc')
    expected = '周末书店\n森林画册：一起去看看。\n咖啡：到店再选。\n'.encode('utf-8')
    saved = adb('exec-out','cat','/sdcard/Download/书店笔记.txt')
    assert saved == expected, 'Saved document differs from the authorized downloaded bytes'
    (OUT/'document-save.json').write_text(json.dumps({'path':'Download/书店笔记.txt','bytes':len(saved),
        'sha256':hashlib.sha256(saved).hexdigest(),'matches_fixture':True},ensure_ascii=False,indent=2))
    passed('authorized file downloads through DocumentsUI and saves byte-identical data')
    # Incoming control exists ONLY in the separate loopback fixture. This checks UI/signalling,
    # not a real SFU/media call. Existing cc2810b SFU evidence is not counted for this source.
    request=urllib.request.Request('http://127.0.0.1:8000/qa/incoming',data=b'{}',method='POST')
    with urllib.request.urlopen(request) as response: assert response.status == 200
    locate('接听'); locate('拒绝')
    capture('11-incoming-invitation')
    tap('拒绝')
    time.sleep(2)
    assert not any(n.get('text') == '接听' for n in tree().iter('node'))
    passed('foreground incoming invitation and decline operate through actual Activity and fixture API')
    (OUT / 'ui-result.json').write_text(json.dumps({'passed':True,'checks':checks},ensure_ascii=False,indent=2))
except Exception as e:
    try: capture('failure')
    except Exception: pass
    (OUT / 'ui-result.json').write_text(json.dumps({'passed':False,'checks':checks,'error':str(e)},ensure_ascii=False,indent=2))
    raise
