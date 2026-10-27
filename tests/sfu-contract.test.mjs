// Local fake provider only. These tests verify server logic, NOT real SFU media connectivity.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import {readFileSync} from 'node:fs';
const js=path=>ts.transpileModule(readFileSync(path,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
const moduleURL=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64');
const sharedURL=moduleURL(js('src/shared.ts'));
const {CallService}=await import(moduleURL(js('src/sfu.ts').replace("'./shared'",JSON.stringify(sharedURL))));
const {capability}=await import(sharedURL);
function fixture(){const store=new Map(),requests=[];let counter=0;const env={APP_SECRET:'x'.repeat(64),SFU_APP_ID:'fake-app',SFU_APP_SECRET:'fake-secret',DB:{prepare(){return {bind(){return this},async run(){return {success:true}}}}},GUARD:{idFromName:x=>x,get:()=>({fetch:async()=>new Response('{}')})}};const state={storage:{get:async key=>structuredClone(store.get(key)),put:async(key,value)=>store.set(key,structuredClone(value)),setAlarm:async()=>{}},getWebSockets:()=>[{deserializeAttachment:()=>({sid:'bob'})}]};const service=new CallService(state,env,()=>{});const alice={id:'alice',nickname:'甲',expires:Date.now()+100000},bob={id:'bob',nickname:'乙',expires:Date.now()+100000},other={id:'outsider',nickname:'丙',expires:Date.now()+100000};return {env,state,store,requests,service,alice,bob,other,fetch:async(url,options)=>{const path=new URL(url).pathname;const body=options.body?JSON.parse(options.body):undefined;requests.push({path,method:options.method,body});if(path.endsWith('/sessions/new'))return Response.json({sessionId:'fake-'+(++counter)});if(options.method==='GET')return Response.json({tracks:[{mid:'0',status:'active'}]});if(path.endsWith('/tracks/close'))return Response.json({tracks:body.tracks});if(path.endsWith('/tracks/new'))return Response.json({tracks:body.tracks.map((t,i)=>({...t,mid:String(i)})),sessionDescription:{type:body.sessionDescription?'answer':'offer',sdp:'v=0\r\n'},requiresImmediateRenegotiation:!body.sessionDescription});return Response.json({})}}}
async function operation(f,name,s,body={},cap){const req=new Request('https://chat.local/api/r/lobby/call-'+name,{method:'POST',headers:{'Content-Type':'application/json',...(cap?{'x-call-cap':cap}:{})},body:JSON.stringify(body)});return (await f.service.handle('call-'+name,req,s,'lobby')).json()}
test('SFU adapter uses actual documented paths, authorizes peer targets and keeps cleanup state',async()=>{
 const f=fixture(),oldFetch=globalThis.fetch;globalThis.fetch=f.fetch;
 try{
 const start=await operation(f,'start',f.alice,{kind:'video',target:'bob'}),callId=start.call.id;
 await assert.rejects(()=>operation(f,'join',f.other,{callId}),e=>e.status===403);
 const a=await operation(f,'join',f.alice,{callId});const b=await operation(f,'join',f.bob,{callId});
 await assert.rejects(()=>operation(f,'publish',f.alice,{callId,sessionDescription:{type:'offer',sdp:'v=0\r\n'},tracks:[{mid:'0',kind:'audio'}]}),e=>e.status===403);
 await operation(f,'publish',f.alice,{callId,sessionDescription:{type:'offer',sdp:'v=0\r\n'},tracks:[{mid:'0',kind:'audio'},{mid:'1',kind:'video'}]},a.call.cap);
 await assert.rejects(()=>operation(f,'subscribe',f.bob,{callId,members:['alice']},b.call.cap),e=>e.status===403);
 await operation(f,'ready',f.alice,{callId},a.call.cap);
 const out=await operation(f,'subscribe',f.bob,{callId,members:['alice']},b.call.cap);assert.equal(out.sessionDescription.type,'offer');
 await operation(f,'answer',f.bob,{callId,sessionDescription:{type:'answer',sdp:'v=0\r\n'}},b.call.cap);
 assert.ok(f.requests.some(r=>r.path.endsWith('/sessions/new')&&r.method==='POST'));
 const pull=f.requests.find(r=>r.path.endsWith('/tracks/new')&&r.body.tracks[0].location==='remote');assert.equal(pull.body.tracks[0].sessionId,'fake-1');assert.equal(pull.body.tracks[0].trackName,'alice-audio');assert.ok(f.requests.some(r=>r.path.endsWith('/renegotiate')&&r.method==='PUT'));
 await operation(f,'leave',f.bob,{callId},b.call.cap);assert.ok(f.store.get('call').ended);assert.equal(f.store.get('call').cleanup.length,0);assert.ok(f.requests.some(r=>r.path.endsWith('/tracks/close')&&r.body.force===true&&r.method==='PUT'));
 assert.ok(!f.requests.some(r=>r.method==='DELETE'));
 }finally{globalThis.fetch=oldFetch}
});
test('uncertain SFU publish cannot blindly retry; failed close stays durably queued',async()=>{
 const f=fixture(),oldFetch=globalThis.fetch;globalThis.fetch=f.fetch;
 try{const start=await operation(f,'start',f.alice,{kind:'audio'}),callId=start.call.id;const a=await operation(f,'join',f.alice,{callId});const real=f.fetch;globalThis.fetch=async(url,options)=>{if(url.endsWith('/tracks/new')||url.endsWith('/tracks/close'))throw new Error('synthetic timeout');return real(url,options)};
 const publish={callId,sessionDescription:{type:'offer',sdp:'v=0\r\n'},tracks:[{mid:'0',kind:'audio'}]};await assert.rejects(()=>operation(f,'publish',f.alice,publish,a.call.cap),e=>e.status===502);assert.ok(f.store.get('call').members.alice.pub.uncertain);await assert.rejects(()=>operation(f,'publish',f.alice,publish,a.call.cap),e=>e.status===409);const left=await operation(f,'leave',f.alice,{callId},a.call.cap);assert.equal(left.cleanupPending,true);assert.equal(f.store.get('call').cleanup.length,1);
 }finally{globalThis.fetch=oldFetch}
});
