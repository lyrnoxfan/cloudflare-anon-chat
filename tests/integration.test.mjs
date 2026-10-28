import {test,after,before} from 'node:test';
import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {mkdtempSync,rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from 'playwright';

let server,browser,a,b,pa,pb,privateId,persist;
const base='http://localhost:8787';
const api=(p,path,method='GET',data)=>p.evaluate(async({path,method,data})=>{const r=await fetch(path,{method,headers:data!==undefined?{'Content-Type':'application/json'}:{},body:data!==undefined?JSON.stringify(data):undefined});return {status:r.status,body:await r.json()}},{path,method,data});
before(async()=>{
 persist=mkdtempSync(join(tmpdir(),'anonymous-chat-test-'));execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','d1','migrations','apply','chat','--local','--persist-to',persist],{stdio:'pipe'});
 server=spawn('node',['node_modules/wrangler/bin/wrangler.js','dev','--port','8787','--persist-to',persist],{stdio:['ignore','pipe','pipe']});let logs='';server.stdout.on('data',d=>logs+=d);server.stderr.on('data',d=>logs+=d);
 let up=false;for(let i=0;i<60;i++){try{if((await fetch(base)).ok){up=true;break}}catch{}await new Promise(r=>setTimeout(r,1000))}assert.ok(up,logs);
 browser=await chromium.launch({headless:true,args:['--no-sandbox','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream']});a=await browser.newContext({viewport:{width:360,height:800},permissions:['microphone','camera']});b=await browser.newContext();pa=await a.newPage();pb=await b.newPage();await Promise.all([pa.goto(base),pb.goto(base)]);
 for(const [p,name] of [[pa,'测试甲'],[pb,'测试乙']]){await p.locator('#nickname').fill(name);await p.locator('#entryForm button').click();await p.locator('#entry').waitFor({state:'hidden'});await p.waitForFunction(()=>document.getElementById('connection').textContent.includes('已建立'))}
}, {timeout:90000});
after(async()=>{await browser?.close();server?.kill('SIGTERM');if(server)await new Promise(resolve=>server.once('exit',resolve));if(persist)rmSync(persist,{recursive:true,force:true})});
test('two browsers exchange real persisted text, idempotence, deletion and reload',async()=>{
 const text='真实实时消息 '+crypto.randomUUID();await pa.locator('#messageText').fill(text);await pa.locator('#send').click();await pb.getByText(text,{exact:true}).waitFor();await pa.reload();await pa.getByText(text,{exact:true}).waitFor();const clientId=crypto.randomUUID();const x=await api(pa,'/api/r/lobby/send','POST',{text:'重复检查',clientId});const y=await api(pa,'/api/r/lobby/send','POST',{text:'重复检查',clientId});assert.equal(x.status,200);assert.equal(x.body.message.id,y.body.message.id);const h=await api(pa,'/api/r/lobby/history');assert.equal(h.body.messages.filter(m=>m.id===x.body.message.id).length,1);assert.equal((await api(pb,'/api/r/lobby/delete/'+x.body.message.id,'POST',{})).status,403);assert.equal((await api(pa,'/api/r/lobby/delete/'+x.body.message.id,'POST',{})).status,200);assert.ok((await api(pa,'/api/r/lobby/history')).body.messages.find(m=>m.id===x.body.message.id).deleted);
});
test('message rendering updates incrementally and announces each new message once',async()=>{
 const historical='历史静默 '+crypto.randomUUID();assert.equal((await api(pa,'/api/r/lobby/send','POST',{text:historical,clientId:crypto.randomUUID()})).status,200);await pa.reload();await pa.getByText(historical,{exact:true}).waitFor();await pa.waitForFunction(()=>document.getElementById('messageAnnouncements')?.textContent==='');
 assert.equal(await pa.locator('#messageList').getAttribute('aria-live'),'off');assert.equal(await pa.locator('#messageAnnouncements').getAttribute('aria-atomic'),'true');
 await pa.evaluate(()=>{window.__messageAudit={added:0,removed:0,announcements:0};new MutationObserver(records=>{for(const r of records){for(const n of r.addedNodes)if(n.nodeType===1&&n.matches('.message'))window.__messageAudit.added++;for(const n of r.removedNodes)if(n.nodeType===1&&n.matches('.message'))window.__messageAudit.removed++}}).observe(document.getElementById('messageList'),{childList:true});new MutationObserver(records=>window.__messageAudit.announcements+=records.length).observe(document.getElementById('messageAnnouncements'),{childList:true,characterData:true})});
 const text='增量播报 '+crypto.randomUUID();await pa.locator('#messageText').fill(text);await pa.locator('#send').click();await pa.getByText(text,{exact:true}).waitFor();await pa.waitForFunction(()=>window.__messageAudit?.announcements===1);await pa.waitForTimeout(100);
 assert.deepEqual(await pa.evaluate(()=>window.__messageAudit),{added:1,removed:0,announcements:1});assert.match(await pa.locator('#messageAnnouncements').textContent(),new RegExp(text));
 const article=pa.locator('#messageList article.message').filter({hasText:text}),id=await article.getAttribute('data-id'),updated=pa.locator(`#messageList article[data-id="${id}"]`);await article.locator('.bubble').click({button:'right'});await pa.getByRole('menuitem',{name:'删除',exact:true}).click();await pa.locator('#confirmDelete').click();await updated.getByText('消息已删除',{exact:true}).waitFor();await pa.waitForTimeout(100);assert.equal((await pa.evaluate(()=>window.__messageAudit)).announcements,1);
});
test('private rooms protect history, websocket, call and media; correct passphrase restores session',async()=>{
 const r=await api(pa,'/api/rooms','POST',{name:'私密验证',password:'test-password-123'});assert.equal(r.status,201);privateId=r.body.id;
 for(const path of ['history','state','download/'+crypto.randomUUID()])assert.equal((await api(pb,`/api/r/${privateId}/${path}`)).status,403);
 assert.equal((await api(pb,`/api/r/${privateId}/call-start`,'POST',{kind:'audio'})).status,403);
 const denied=await pb.evaluate(rid=>new Promise(resolve=>{const w=new WebSocket(`ws://localhost:8787/api/r/${rid}/ws`);w.onopen=()=>{w.close();resolve(false)};w.onerror=()=>resolve(true)}),privateId);assert.ok(denied);
 assert.equal((await api(pb,`/api/r/${privateId}/join`,'POST',{password:'wrong-passphrase'})).status,403);assert.equal((await api(pb,`/api/r/${privateId}/join`,'POST',{password:'test-password-123'})).status,200);assert.equal((await api(pb,`/api/r/${privateId}/history`)).status,200);await pb.reload();assert.equal((await api(pb,`/api/r/${privateId}/history`)).status,200);
});
test('room number UI accepts short password while denying invalid room and password',async()=>{
 const guest=await browser.newContext();const pg=await guest.newPage();try{
  await pg.goto(base);await pg.locator('#nickname').fill('房号访客');await pg.locator('#entryForm button').click();await pg.locator('#entry').waitFor({state:'hidden'});
  await pa.locator('#moreToggle').click();await pa.locator('#createRoomMore').click();await pa.locator('#roomInput').fill('短口令房间');await pa.locator('#password').fill('x');await pa.locator('#privateForm button[type=submit]').click();await pa.locator('#roomCode').waitFor({state:'visible'});const number=(await pa.locator('#roomCode').textContent()).replace('房号 ','');assert.match(number,/^[0-9]{10}$/);
  await pg.locator('#moreToggle').click();await pg.locator('#joinRoomMore').click();await pg.locator('#joinRoomCode').fill('0000000000');await pg.locator('#joinRoomPassword').fill('x');await pg.locator('#joinRoomForm button[type=submit]').click();await pg.getByText('房间不存在',{exact:true}).waitFor();
  await pg.locator('#joinRoomCode').fill(number);await pg.locator('#joinRoomPassword').fill('wrong');await pg.locator('#joinRoomForm button[type=submit]').click();await pg.getByText('房间口令不正确',{exact:true}).waitFor();assert.equal((await api(pg,`/api/r/${number}/history`)).status,403);
  await pg.locator('#joinRoomPassword').fill('x');await pg.locator('#joinRoomForm button[type=submit]').click();await pg.locator('#joinRoomDialog').waitFor({state:'hidden'});await pg.getByText('短口令房间',{exact:true}).first().waitFor();
  const msg='房号测试 '+crypto.randomUUID();await pa.locator('#messageText').fill(msg);await pa.locator('#send').click();await pg.getByText(msg,{exact:true}).waitFor();
  const legacy=crypto.randomUUID();execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','d1','execute','chat','--local','--persist-to',persist,'--command',`INSERT INTO rooms(id,name,kind,password_hash,salt,iterations,created) SELECT '${legacy}',name,kind,password_hash,salt,iterations,created FROM rooms WHERE id='${privateId}'`],{stdio:'pipe'});
  assert.equal((await api(pg,`/api/r/${legacy}/join`,'POST',{password:'wrong'})).status,403);assert.equal((await api(pg,`/api/r/${legacy}/join`,'POST',{password:'test-password-123'})).status,200);assert.equal((await api(pg,`/api/r/${legacy}/state`)).status,200);
  assert.match(await pa.title(),/轻聊/);
 }finally{await guest.close();await pa.locator('#menuToggle').click();await pa.locator('#lobby').click()}
});
test('actual R2 emulation upload confirmation, download, replay safety and deletion',async()=>{
 const clientId=crypto.randomUUID();const init=await api(pa,'/api/r/lobby/upload','POST',{clientId,filename:'test.txt',size:5,mime:'text/plain',kind:'file'});assert.equal(init.status,200);const id=init.body.id;assert.equal((await api(pa,'/api/r/lobby/confirm-upload/'+id,'POST',{})).status,409);
 const uploaded=await pa.evaluate(async url=>(await fetch(url,{method:'PUT',headers:{'Content-Type':'text/plain'},body:'hello'})).status,init.body.url);assert.equal(uploaded,200);
 const c=await api(pa,'/api/r/lobby/confirm-upload/'+id,'POST',{});assert.equal(c.status,200);assert.equal((await api(pa,'/api/r/lobby/confirm-upload/'+id,'POST',{})).body.message.id,c.body.message.id);const ticket=await api(pa,'/api/r/lobby/download/'+c.body.message.id);assert.deepEqual(await pa.evaluate(async url=>{const r=await fetch(url,{headers:{Range:'bytes=1-3'}});return [r.status,await r.text()]},ticket.body.url),[206,'ell']);assert.deepEqual(await pa.evaluate(async url=>{const r=await fetch(url,{headers:{Range:'bytes=-2'}});return [r.status,await r.text()]},ticket.body.url),[206,'lo']);assert.equal(await pa.evaluate(async url=>(await fetch(url)).text(),ticket.body.url),'hello');assert.equal(await pb.evaluate(async url=>(await fetch(url)).status,ticket.body.url),403);await api(pa,'/api/r/lobby/delete/'+c.body.message.id,'POST',{});assert.equal(await pa.evaluate(async url=>(await fetch(url)).status,ticket.body.url),404);
});
test('same-origin R2 streaming fallback writes only authorized, exact-size media',async()=>{
 const clientId=crypto.randomUUID();const init=await api(pa,'/api/r/lobby/upload','POST',{clientId,filename:'stream.txt',size:5,mime:'text/plain',kind:'file'});assert.equal(init.status,200);const path=`/api/r/lobby/upload-content/${init.body.id}`;
 assert.equal(await pb.evaluate(async path=>(await fetch(path,{method:'PUT',headers:{'Content-Type':'text/plain'},body:'hello'})).status,path),403);
 assert.equal(await pa.evaluate(async path=>(await fetch(path,{method:'PUT',headers:{'Content-Type':'application/json'},body:'hello'})).status,path),415);
 assert.equal(await pa.evaluate(async path=>(await fetch(path,{method:'PUT',headers:{'Content-Type':'text/plain'},body:'no'})).status,path),413);
 const put=await pa.evaluate(async path=>{const r=await fetch(path,{method:'PUT',headers:{'Content-Type':'text/plain'},body:'hello'});return [r.status,await r.json()]},path);assert.equal(put[0],200);
 const saved=await api(pa,'/api/r/lobby/confirm-upload/'+init.body.id,'POST',{});assert.equal(saved.status,200);
 const ticket=await api(pa,'/api/r/lobby/download/'+saved.body.message.id);assert.equal(await pa.evaluate(async url=>(await fetch(url)).text(),ticket.body.url),'hello');
});

test('browser MediaRecorder produces actual decodable audio and layout does not overflow',async()=>{
 await pa.locator('#record').click();await pa.locator('#stopRecord').waitFor({state:'visible'});await pa.waitForTimeout(1600);await pa.locator('#stopRecord').click();await pa.locator('#recordPreview').waitFor({state:'visible'});await pa.waitForFunction(()=>document.getElementById('recordPreview').readyState>=1);assert.ok(await pa.locator('#recordPreview').evaluate(e=>e.src.startsWith('blob:')));await pa.locator('#sendRecord').click();await pa.locator('#uploadStatus').waitFor({state:'hidden'});await pb.waitForFunction(()=>[...document.querySelectorAll('#messageList audio')].some(v=>v.readyState>=1));const audio=pb.locator('#messageList audio').last();await audio.evaluate(e=>e.play());await pb.waitForFunction(()=>[...document.querySelectorAll('#messageList audio')].some(v=>v.currentTime>0));await audio.evaluate(e=>e.pause());assert.ok(await pa.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await pa.screenshot({path:'tests/mobile.png',fullPage:true});await pb.screenshot({path:'tests/desktop.png',fullPage:true});
});
test('missing SFU configuration returns explicit failure, and CSRF is blocked',async()=>{
 const r=await api(pa,'/api/r/lobby/call-start','POST',{kind:'audio'});assert.equal(r.status,503);const res=await fetch(base+'/api/session',{method:'POST',headers:{Origin:'https://evil.example','Content-Type':'application/json'},body:JSON.stringify({nickname:'bad'})});assert.equal(res.status,403);
});

test('upload sizes, cancellation, mismatched bodies, and HTML text are enforced by backend',async()=>{
 assert.equal((await api(pa,'/api/r/lobby/upload','POST',{clientId:crypto.randomUUID(),filename:'oversize.mp4',mime:'video/mp4',size:104857601})).status,413);
 const u=await api(pa,'/api/r/lobby/upload','POST',{clientId:crypto.randomUUID(),filename:'small.txt',mime:'text/plain',size:6});assert.equal(u.status,200);assert.equal(await pa.evaluate(async url=>(await fetch(url,{method:'PUT',headers:{'Content-Type':'text/plain'},body:'short'})).status,u.body.url),413);await api(pa,'/api/r/lobby/cancel-upload/'+u.body.id,'POST',{});assert.equal((await api(pa,'/api/r/lobby/confirm-upload/'+u.body.id,'POST',{})).status,410);
 const text='<img src=x onerror="window.xss=1"> '+crypto.randomUUID();await api(pa,'/api/r/lobby/send','POST',{clientId:crypto.randomUUID(),text});await pb.getByText(text,{exact:true}).waitFor();assert.equal(await pb.evaluate(()=>window.xss),undefined);
});
test('image and browser-encoded video use the real upload UI and render for another member',async()=>{
 const imageName='tiny-'+crypto.randomUUID()+'.png';await pa.locator('#file').setInputFiles({name:imageName,mimeType:'image/png',buffer:Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9ZlS8AAAAASUVORK5CYII=','base64')});const img=pb.locator('img[alt="'+imageName+'"]');await img.waitFor();await pb.waitForFunction(name=>{const i=[...document.querySelectorAll('img')].find(i=>i.alt===name);return i?.naturalWidth>0},imageName);assert.ok(await img.evaluate(e=>e.naturalWidth>0));await pa.locator('#uploadStatus').waitFor({state:'hidden'});
 const bytes=await pa.evaluate(async()=>{const stream=await navigator.mediaDevices.getUserMedia({audio:true,video:true});try{const mime=['video/webm;codecs=vp8,opus','video/webm'].find(v=>MediaRecorder.isTypeSupported(v));const rec=new MediaRecorder(stream,{mimeType:mime});const chunks=[];rec.ondataavailable=e=>chunks.push(e.data);const done=new Promise(resolve=>rec.onstop=resolve);rec.start(100);await new Promise(r=>setTimeout(r,700));rec.stop();await done;return [...new Uint8Array(await new Blob(chunks,{type:mime}).arrayBuffer())]}finally{stream.getTracks().forEach(t=>t.stop())}});
 const videoName='camera-'+crypto.randomUUID()+'.webm';await pa.locator('#file').setInputFiles({name:videoName,mimeType:'video/webm',buffer:Buffer.from(bytes)});await pb.getByText(videoName+' ·',{exact:false}).waitFor();await pb.waitForFunction(()=>[...document.querySelectorAll('#messageList video')].some(v=>v.readyState>=1));assert.ok(await pb.locator('#messageList video').last().evaluate(v=>v.videoWidth>0));
});

test('cursor history pages contain fifty newest entries without overlap',async()=>{
 for(let i=0;i<52;i++){const p=i%2?pa:pb;const r=await api(p,'/api/r/lobby/send','POST',{clientId:crypto.randomUUID(),text:'分页验证 '+i+' '+crypto.randomUUID()});assert.equal(r.status,200)}
 const first=(await api(pa,'/api/r/lobby/history')).body;assert.equal(first.messages.length,50);assert.ok(first.next);const second=(await api(pa,'/api/r/lobby/history?before='+encodeURIComponent(first.next))).body;assert.ok(second.messages.length);const ids=new Set(first.messages.map(m=>m.id));assert.ok(second.messages.every(m=>!ids.has(m.id)));const earliest=first.messages[0],latest=second.messages.at(-1);assert.ok(latest.created<earliest.created||latest.created===earliest.created&&latest.id<earliest.id);await pa.screenshot({path:'tests/mobile.png',fullPage:true});await pb.screenshot({path:'tests/desktop.png',fullPage:true});
});

test('expired private authorization is rejected on HTTP and websocket heartbeats',async()=>{
 await pb.evaluate(rid=>new Promise((resolve,reject)=>{window.expirySocket=new WebSocket(`ws://localhost:8787/api/r/${rid}/ws`);expirySocket.onopen=resolve;expirySocket.onerror=reject}),privateId);
 execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','d1','execute','chat','--local','--persist-to',persist,'--command',`UPDATE room_sessions SET expires=0 WHERE room_id='${privateId}'`],{stdio:'pipe'});
 assert.equal((await api(pb,`/api/r/${privateId}/history`)).status,403);
 assert.equal(await pb.evaluate(()=>new Promise(resolve=>{expirySocket.onclose=e=>resolve(e.code);expirySocket.send('ping')})),4003);
});

test('emoji touch swipe stays inside viewport and panel scrolls with a reduced keyboard viewport',async()=>{
 const ctx=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});const p=await ctx.newPage();try{
 await p.goto(base);await p.locator('#nickname').fill('表情检查');await p.locator('#entryForm button').click();await p.locator('#entry').waitFor({state:'hidden'});await p.locator('#emojiToggle').click();
 const cdp=await ctx.newCDPSession(p);async function swipe(){const b=await p.locator('#emojiPicker').boundingBox();await cdp.send('Input.synthesizeScrollGesture',{x:b.x+b.width/2,y:b.y+b.height*.7,yDistance:220,speed:500,gestureSourceType:'touch'});await p.waitForTimeout(400)}
 await swipe();let rect=await p.evaluate(()=>({scroll:scrollY,h:innerHeight,body:document.body.getBoundingClientRect().bottom,picker:document.getElementById('emojiPicker').getBoundingClientRect().top,top:document.querySelector('.topbar').getBoundingClientRect().bottom}));assert.ok(rect.scroll===0&&rect.body<=rect.h+1,JSON.stringify(rect));
 await p.setViewportSize({width:390,height:360});await p.waitForTimeout(200);await swipe();rect=await p.evaluate(()=>({scroll:scrollY,h:innerHeight,body:document.body.getBoundingClientRect().bottom,picker:document.getElementById('emojiPicker').getBoundingClientRect().top,top:document.querySelector('.topbar').getBoundingClientRect().bottom,overflow:getComputedStyle(document.getElementById('emojiPicker')).overflowY}));assert.ok(rect.scroll===0&&rect.body<=rect.h+1&&rect.picker>=rect.top-1,JSON.stringify(rect));assert.equal(rect.overflow,'auto');assert.ok(await p.locator('#emojiPicker').evaluate(e=>{e.scrollTop=e.scrollHeight;return e.scrollTop>0}),'emoji panel cannot scroll internally');
 // Picker uses bundled reference images while preserving Unicode text for cross-client transport.
 await p.setViewportSize({width:390,height:844});await p.locator('#emojiPicker button').first().click();assert.equal(await p.locator('#messageText').inputValue(),'😀');await p.locator('#composer').evaluate(e=>e.requestSubmit());await p.locator('.message.mine .inline-emoji').last().waitFor();assert.equal(await p.locator('.message.mine .inline-emoji').last().getAttribute('alt'),'😀');
 }finally{await ctx.close()}
});
