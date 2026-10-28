#!/usr/bin/env node
// Isolated, in-memory API fixture for the Android .qa build only.
// Bind to loopback; use adb reverse tcp:8000 tcp:8000 from an isolated emulator.
import http from 'node:http';
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const host = '127.0.0.1';
const fixtureDir = path.dirname(fileURLToPath(import.meta.url));
function pathname(name) { return path.join(fixtureDir,name); }
const port = 8000;
const sessions = new Map();
const clients = new Set();
const now = Date.now();
const messages = [
  {id:'qa-msg-001',created:now-86400000,nickname:'小简',mine:false,kind:'text',text:'今天路过一家很棒的书店。'},
  {id:'qa-msg-002',created:now-86399900,nickname:'小简',mine:false,kind:'text',text:'想和你分享，里面有好多摄影集。'},
  {id:'qa-msg-003',created:now-86200000,nickname:'阿禾',mine:true,kind:'text',text:'听起来不错！有什么有意思的发现吗？'},
  {id:'qa-msg-004',created:now-86000000,nickname:'小简',mine:false,kind:'text',text:'找到一本关于森林的画册 🌿'},
  {id:'qa-msg-005',created:now-85999900,nickname:'小简',mine:false,kind:'text',text:'下次可以一起去看看。'},
  {id:'qa-msg-006',created:now-40000,nickname:'阿禾',mine:false,kind:'audio',filename:'语音消息.wav',mime:'audio/wav',size:44144,duration:1},
  {id:'qa-msg-007',created:now-30000,nickname:'阿禾',mine:false,kind:'image',filename:'书店.png',mime:'image/png',size:fs.statSync(pathname('bookshop.png')).size},
  {id:'qa-msg-008',created:now-20000,nickname:'阿禾',mine:false,kind:'video',filename:'森林散步.mp4',mime:'video/mp4',size:fs.statSync(pathname('forest.mp4')).size,duration:2},
  {id:'qa-msg-009',created:now-10000,nickname:'阿禾',mine:false,kind:'file',filename:'书店笔记.txt',mime:'text/plain',size:Buffer.byteLength('周末书店\n森林画册：一起去看看。\n咖啡：到店再选。\n')},
  {id:'qa-msg-010',created:now-5000,nickname:'阿禾',mine:true,kind:'text',text:'周末去书店，顺便喝杯咖啡 ☕'},
];
function json(res, code, value, headers={}) {
  const body=Buffer.from(JSON.stringify(value));
  res.writeHead(code, {'Content-Type':'application/json; charset=utf-8','Content-Length':body.length,'Cache-Control':'no-store',...headers});
  res.end(body);
}
function sessionFor(req) {
  const token=(req.headers.cookie||'').match(/(?:^|;\s*)chat=([^;]+)/)?.[1];
  return token ? sessions.get(token) : null;
}
function readBody(req) {
  return new Promise((resolve,reject)=>{
    let body='';req.on('data',c=>body+=c);req.on('end',()=>{try{resolve(body?JSON.parse(body):{})}catch(e){reject(e)}});req.on('error',reject);
  });
}
function fixtureMessages(session) {
  return messages.map(m => ({...m, nickname:m.mine && session ? session.nickname : m.nickname}));
}
function broadcast(event) {
  const payload=Buffer.from(JSON.stringify(event));
  let header;
  if(payload.length<126) header=Buffer.from([0x81,payload.length]);
  else {header=Buffer.alloc(4);header[0]=0x81;header[1]=126;header.writeUInt16BE(payload.length,2)}
  const frame=Buffer.concat([header,payload]);
  for(const client of clients) if(!client.destroyed) client.write(frame);
}
function wav() {
  const rate=22050, samples=rate, data=Buffer.alloc(samples*2);
  for(let i=0;i<samples;i++) data.writeInt16LE(Math.round(Math.sin(2*Math.PI*440*i/rate)*2500),i*2);
  const h=Buffer.alloc(44);h.write('RIFF',0);h.writeUInt32LE(36+data.length,4);h.write('WAVE',8);h.write('fmt ',12);h.writeUInt32LE(16,16);h.writeUInt16LE(1,20);h.writeUInt16LE(1,22);h.writeUInt32LE(rate,24);h.writeUInt32LE(rate*2,28);h.writeUInt16LE(2,32);h.writeUInt16LE(16,34);h.write('data',36);h.writeUInt32LE(data.length,40);return Buffer.concat([h,data]);
}
const server=http.createServer((req,res)=>{
  const url=new URL(req.url,`http://${host}:${port}`), path=url.pathname;
  if(path==='/health'){return json(res,200,{ok:true,mode:'isolated-qa-fixture'})}
  if(path==='/api/session'&&req.method==='GET'){
    const s=sessionFor(req);return s?json(res,200,{...s,limits:{nickname:32,sessionMs:604800000},sfu:false}):json(res,401,{error:'请先输入昵称'});
  }
  if(path==='/api/session'&&req.method==='POST'){
    readBody(req).then(b=>{const nickname=String(b.nickname||'').trim();if(!nickname||nickname.length>32)return json(res,400,{error:'昵称格式不正确'});const token=crypto.randomUUID().replaceAll('-','');const s={id:crypto.randomUUID(),nickname,expires:Date.now()+604800000};sessions.set(token,s);json(res,200,{...s,limits:{nickname:32,sessionMs:604800000},sfu:false},{'Set-Cookie':`chat=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800`})}).catch(()=>json(res,400,{error:'JSON 格式不正确'}));return;
  }
  if(path==='/api/r/lobby/state'&&req.method==='GET'){const s=sessionFor(req);return s?json(res,200,{room:{id:'lobby',name:'公共大厅',kind:'public'},call:null}):json(res,401,{error:'会话已过期'})}
  if(path==='/api/r/lobby/history'&&req.method==='GET'){const s=sessionFor(req);return s?json(res,200,{messages:fixtureMessages(s),next:null}):json(res,401,{error:'会话已过期'})}
  if(path==='/api/r/lobby/call-state'&&req.method==='GET') return sessionFor(req)?json(res,200,{call:null}):json(res,401,{error:'会话已过期'});
  if(path==='/api/r/lobby/send'&&req.method==='POST'){
    let body='';req.on('data',c=>body+=c);req.on('end',()=>{try{const b=JSON.parse(body),s=sessionFor(req);if(!s)return json(res,401,{error:'会话已过期'});const text=String(b.text||'').trim(),clientId=String(b.clientId||'');if(!text||text.length>4000||!clientId||clientId.length>80)return json(res,400,{error:'消息内容或标识无效'});let m=messages.find(x=>x.mine&&x.clientId===clientId);if(!m){m={id:crypto.randomUUID(),created:Date.now(),nickname:s.nickname,mine:true,author:s.id,clientId,kind:'text',text};messages.push(m);broadcast({type:'message',message:m})}json(res,200,{message:m});}catch{return json(res,400,{error:'JSON 格式不正确'})}});return;
  }
  const deleted=path.match(/^\/api\/r\/lobby\/delete\/([A-Za-z0-9-]+)$/);
  if(deleted&&req.method==='POST'){
    const s=sessionFor(req);if(!s)return json(res,401,{error:'会话已过期'});
    readBody(req).then(()=>{const m=messages.find(item=>item.id===deleted[1]);if(!m||!m.mine)return json(res,404,{error:'消息不存在或不可删除'});m.deleted=1;m.text=null;m.filename=null;m.mime=null;m.size=null;m.duration=null;broadcast({type:'deleted',id:m.id});json(res,200,{})}).catch(()=>json(res,400,{error:'JSON 格式不正确'}));return;
  }
  const download=path.match(/^\/api\/r\/lobby\/download\/([A-Za-z0-9-]+)$/);
  if(download&&req.method==='GET'){
    if(!sessionFor(req))return json(res,401,{error:'会话已过期'});
    return json(res,200,{url:`/mock/media/${download[1]}`});
  }
  const media=path.match(/^\/mock\/media\/(qa-msg-006|qa-msg-007|qa-msg-008|qa-msg-009)$/);
  if(media){
    const id=media[1];let body,type;
    if(id==='qa-msg-006'){body=wav();type='audio/wav'}
    else if(id==='qa-msg-007'){body=fs.readFileSync(pathname('bookshop.png'));type='image/png'}
    else if(id==='qa-msg-008'){body=fs.readFileSync(pathname('forest.mp4'));type='video/mp4'}
    else {body=Buffer.from('周末书店\n森林画册：一起去看看。\n咖啡：到店再选。\n');type='text/plain; charset=utf-8'}
    res.writeHead(200,{'Content-Type':type,'Content-Length':body.length,'Cache-Control':'no-store'});return res.end(body)
  }
  return json(res,404,{error:'QA fixture route not found'});
});
server.on('upgrade',(req,socket)=>{
  if(!req.url?.startsWith('/api/r/lobby/ws')){socket.end('HTTP/1.1 404 Not Found\r\n\r\n');return}
  const key=req.headers['sec-websocket-key'];if(!key){socket.destroy();return}
  const accept=crypto.createHash('sha1').update(key+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
  socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`);
  clients.add(socket);
  const send=v=>{const payload=Buffer.from(JSON.stringify(v));const hdr=payload.length<126?Buffer.from([0x81,payload.length]):Buffer.from([0x81,126,payload.length>>8,payload.length&255]);socket.write(Buffer.concat([hdr,payload]))};
  send({type:'ready'});
  const session=sessionFor(req);
  const members=[...(session?[{id:session.id,nickname:session.nickname}]:[]),{id:'qa-peer',nickname:'小简'}];
  broadcast({type:'presence',members,count:members.length});
  let buffer=Buffer.alloc(0);
  socket.on('data',chunk=>{buffer=Buffer.concat([buffer,chunk]);while(buffer.length>=2){const len0=buffer[1]&127,mask=!!(buffer[1]&128);let len=len0,at=2;if(len===126){if(buffer.length<4)return;len=buffer.readUInt16BE(2);at=4}else if(len===127){if(buffer.length<10)return;len=Number(buffer.readBigUInt64BE(2));at=10}const total=at+(mask?4:0)+len;if(buffer.length<total)return;const opcode=buffer[0]&15;let payload=buffer.subarray(at+(mask?4:0),total);if(mask){const m=buffer.subarray(at,at+4),copy=Buffer.from(payload);for(let i=0;i<copy.length;i++)copy[i]^=m[i%4];payload=copy}buffer=buffer.subarray(total);if(opcode===8){socket.end();return}if(opcode===9){socket.write(Buffer.concat([Buffer.from([0x8a,payload.length]),payload]));continue}if(opcode===1&&payload.toString()==='ping')send({type:'pong'});}});
  socket.on('close',()=>clients.delete(socket));socket.on('error',()=>clients.delete(socket));
});
server.listen(port,host,()=>console.log(`Isolated Android QA fixture listening at http://${host}:${port}`));
