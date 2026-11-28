// Entirely local: a loopback CONNECT proxy maps reserved .test names to a
// loopback TLS fixture. It never resolves/connects to an external host.
// Count at the TLS server, not Playwright's request events (which fire even
// when a request is aborted before transmission).
import {test, before, after} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import https from 'node:https';
import net from 'node:net';
import {createHash} from 'node:crypto';
import {readFileSync, mkdtempSync, rmSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {chromium} from 'playwright';
import {newConfinedRemoteContext} from './remote-confinement.mjs';

let browser, server, proxy, directory, proxyURL, mode='normal';
const qa='https://isolated-qa.example.test';
const forbidden='https://blocked-production.example.test';
const wire=[], connects=[], sockets=new Set();
const listen = server => new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const track = socket => {sockets.add(socket);socket.on('close',()=>sockets.delete(socket));};
const json = (response, value, status=200) => {response.writeHead(status,{'content-type':'application/json'});response.end(JSON.stringify(value));};
before(async()=>{
 directory=mkdtempSync(join(tmpdir(),'remote-confinement-'));
 execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=*.example.test','-keyout',join(directory,'key.pem'),'-out',join(directory,'cert.pem')],{stdio:'ignore'});
 server=https.createServer({key:readFileSync(join(directory,'key.pem')),cert:readFileSync(join(directory,'cert.pem'))},async(req,res)=>{
  const body=[];for await(const chunk of req)body.push(chunk);
  wire.push({host:req.headers.host,path:req.url,method:req.method,body:Buffer.concat(body).toString()});
  const path=new URL(req.url,qa).pathname;
  if(path.startsWith('/redirect/')) {res.writeHead(Number(path.split('/').at(-1)),{location:forbidden+'/redirected'});res.end();return;}
  if(path==='/' && mode==='reload-redirect') {res.writeHead(307,{location:forbidden+'/reload'});res.end();return;}
  if(path==='/api/session') {
   if(req.method==='POST' && mode==='session-redirect') {res.writeHead(307,{location:forbidden+'/api/session'});res.end();return;}
   json(res,{error:'请先输入昵称'},401);return;
  }
  if(path==='/cookie') {res.writeHead(200,{'set-cookie':'qa_session=fixture; Secure; HttpOnly; SameSite=Lax; Path=/'});res.end('ok');return;}
  if(path==='/echo') {json(res,{method:req.method,body:Buffer.concat(body).toString(),cookie:req.headers.cookie||''});return;}
  if(path==='/plain') {res.writeHead(200,{'content-type':'text/html'});res.end('<!doctype html><title>offline QA fixture</title>');return;}
  try {
   const file=resolve('public',path==='/'?'index.html':'.'+path);
   assert.ok(file.startsWith(resolve('public')+'/'));
   res.writeHead(200,{'content-type':file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'text/html'});res.end(readFileSync(file));
  } catch {res.writeHead(404);res.end();}
 });
 server.on('connection',track);
 server.on('upgrade',(req,socket)=>{
  wire.push({host:req.headers.host,path:req.url,method:'WS'});
  if(req.url==='/ws-redirect') {socket.end('HTTP/1.1 302 Found\r\nLocation: wss://blocked-production.example.test/ws\r\nContent-Length: 0\r\n\r\n');return;}
  const accept=createHash('sha1').update(req.headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+'\r\n\r\n');
  socket.on('data',()=>socket.end());
 });
 await listen(server);
 proxy=http.createServer((req,res)=>{res.writeHead(403);res.end();});
 proxy.on('connection',track);
 proxy.on('connect',(req,client,head)=>{
  connects.push(req.url);
  // A defense for the regression runner itself, independent of the code under test.
  if(!/^[a-z-]+\.example\.test:443$/.test(req.url)){client.end('HTTP/1.1 403 Forbidden\r\n\r\n');return;}
  const upstream=net.connect(server.address().port,'127.0.0.1',()=>{client.write('HTTP/1.1 200 Connection Established\r\n\r\n');if(head.length)upstream.write(head);client.pipe(upstream);upstream.pipe(client);});
  track(upstream);upstream.on('error',()=>client.destroy());client.on('error',()=>upstream.destroy());
 });
 await listen(proxy);proxyURL=`http://127.0.0.1:${proxy.address().port}`;
 browser=await chromium.launch({headless:true,args:['--no-sandbox'],proxy:{server:proxyURL}});
});
after(async()=>{await browser?.close();for(const socket of sockets)socket.destroy();await Promise.all([new Promise(r=>server?.close(r)),new Promise(r=>proxy?.close(r))]);if(directory)rmSync(directory,{recursive:true,force:true});});

async function fixture(run, options={}) {
 mode='normal';wire.length=0;connects.length=0;
 const {context,guard}=await newConfinedRemoteContext(browser,qa,{ignoreHTTPSErrors:true,...options});
 try {const page=await context.newPage();await page.goto(qa+'/plain');await run({context,page,guard});}
 finally {await context.close();}
 assert.equal(wire.filter(r=>r.host!=='isolated-qa.example.test').length,0,JSON.stringify(wire));
 assert.equal(connects.some(host=>host.startsWith('blocked-production.')),false,JSON.stringify(connects));
}
const fetched = (page,url,method='GET') => page.evaluate(async({url,method})=>{try{const r=await fetch(url,{method,body:method==='PUT'||method==='POST'?'offline fixture':undefined});return {status:r.status,text:await r.text()};}catch{return {blocked:true};}},{url,method});
const socketResult = (page,url) => page.evaluate(url=>new Promise(resolve=>{const w=new WebSocket(url);w.onopen=()=>{w.close();resolve('open')};w.onerror=()=>resolve('error');w.onclose=()=>resolve('closed');setTimeout(()=>{w.close();resolve('timeout')},3000);}),url);

test('confinement permits approved HTTPS, cookies and write body without changing the app',async()=>fixture(async({page,guard})=>{
 assert.equal((await fetched(page,qa+'/cookie')).status,200);
 const response=await fetched(page,qa+'/echo','POST');assert.equal(response.status,200);
 assert.deepEqual(JSON.parse(response.text),{method:'POST',body:'offline fixture',cookie:'qa_session=fixture'});
 guard.assertClean();
}));
test('all HTTP redirect status codes are stopped before a second hop, including POST and PUT',async()=>fixture(async({page,guard})=>{
 for(const status of [301,302,303,307,308])for(const method of ['GET','POST','PUT'])assert.equal((await fetched(page,qa+'/redirect/'+status,method)).blocked,true);
 assert.equal(guard.violations.filter(x=>x.kind==='http-redirect').length,15);assert.throws(()=>guard.assertClean(),/confinement blocked/);
}));
test('actual public app nickname POST cannot escape through a mocked 307',async()=>fixture(async({page,guard})=>{
 mode='session-redirect';await page.goto(qa);await page.locator('#nickname').fill('offline-review-fixture');await page.locator('#entryForm button').click();
 await page.waitForFunction(()=>document.getElementById('entryError').textContent.length>0);
 assert.equal(wire.filter(r=>r.path==='/api/session'&&r.method==='POST').length,1);
 assert.ok(guard.violations.some(x=>x.kind==='http-redirect'));
}));
test('backend-provided upload/download URLs deny cross-origin, credentials and insecure schemes',async()=>fixture(async({page,guard})=>{
 for(const url of [forbidden+'/media','http://isolated-qa.example.test/media','https://user:pass@isolated-qa.example.test/media']) {
  assert.equal((await fetched(page,url,'PUT')).blocked,true);assert.equal((await fetched(page,url)).blocked,true);
 }
 assert.equal(guard.violations.filter(v=>v.kind==='http-origin').length,2);
 // Mixed-content HTTP and credential-bearing fetch are rejected by Chromium before routing.
}));
test('navigation, reload and popup redirects cannot leave the approved origin',async()=>fixture(async({page,context,guard})=>{
 const direct=await context.newPage();await assert.rejects(direct.goto(forbidden+'/navigation'));await direct.close();
 const redirected=await context.newPage();await assert.rejects(redirected.goto(qa+'/redirect/307'));await redirected.close();
 const refreshed=await context.newPage();await refreshed.goto(qa);mode='reload-redirect';await assert.rejects(refreshed.reload());await refreshed.close();mode='normal';
 const next=context.waitForEvent('page');await page.evaluate(url=>window.open(url),forbidden+'/popup');const popup=await next;
 await popup.waitForLoadState().catch(()=>{});await popup.close();
 assert.equal(guard.violations.filter(v=>v.kind==='http-redirect').length,2);
 assert.equal(guard.violations.filter(v=>v.kind==='http-origin').length,2);
}));
test('element media URLs and sendBeacon are confined, not only helper fetch',async()=>fixture(async({page,guard})=>{
 await page.evaluate(async forbidden=>{const img=new Image();img.src=forbidden+'/image';document.body.append(img);navigator.sendBeacon(forbidden+'/beacon','offline fixture');await Promise.all(['video','audio'].map(kind=>new Promise(resolve=>{const media=document.createElement(kind);media.preload='auto';media.onerror=resolve;media.src=forbidden+'/'+kind;document.body.append(media);media.load()})));},forbidden);
 await page.waitForFunction(()=>[...document.images].every(img=>img.complete));
 await fetched(page,qa+'/echo');
 for(const kind of ['image','video','audio'])assert.ok(guard.violations.some(v=>v.url.endsWith('/'+kind)),kind);
 assert.ok(guard.violations.some(v=>v.url.endsWith('/beacon')));
}));
test('WebSocket denies unapproved origins before connect and permits approved handshake',async()=>fixture(async({page,guard})=>{
 assert.equal(await socketResult(page,'wss://isolated-qa.example.test/ws'),'open');
 assert.notEqual(await socketResult(page,'wss://blocked-production.example.test/ws'),'open');
 assert.ok(wire.some(r=>r.method==='WS'&&r.path==='/ws'));
 assert.equal(guard.violations.filter(v=>v.kind==='websocket-origin').length,1);
}));
test('WebSocket redirect handshakes do not follow Location to another origin',async()=>fixture(async({page})=>{
 assert.notEqual(await socketResult(page,'wss://isolated-qa.example.test/ws-redirect'),'open');
 assert.ok(wire.some(r=>r.method==='WS'&&r.path==='/ws-redirect'));
}));
test('factory forces service workers off even when callers request allow',async()=>fixture(async({page,context,guard})=>{
 const registration=await page.evaluate(async()=>{try{return Boolean(await navigator.serviceWorker.register('/worker.js'))}catch{return false}});
 assert.equal(registration,false);
 assert.deepEqual(await page.evaluate(()=>navigator.serviceWorker.getRegistrations().then(r=>r.map(x=>x.scope))),[]);
 assert.equal(context.serviceWorkers().length,0);
 assert.equal(wire.some(r=>r.path==='/worker.js'),false);
 guard.assertClean();
},{serviceWorkers:'allow'}));
