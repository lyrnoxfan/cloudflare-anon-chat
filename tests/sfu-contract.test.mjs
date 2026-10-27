// Local fake provider only. These tests verify server state/contract logic, NOT real SFU media connectivity.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import ts from 'typescript';
import {readFileSync} from 'node:fs';
const js=path=>ts.transpileModule(readFileSync(path,'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ESNext}}).outputText;
const moduleURL=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64');
const sharedURL=moduleURL(js('src/shared.ts'));
const {CallService}=await import(moduleURL(js('src/sfu.ts').replace("'./shared'",JSON.stringify(sharedURL))));
const {capability}=await import(sharedURL);
function fixture(){
 const store=new Map(),requests=[],sessions=new Map(),errors=[];let counter=0,trackStatus='active',nextFailure=null;
 const env={APP_SECRET:'x'.repeat(64),SFU_APP_ID:'fake-app',SFU_APP_SECRET:'fake-secret',SFU_DIAGNOSTICS:'true',DB:{prepare(){return {bind(){return this},async run(){return {success:true}}}}},GUARD:{idFromName:x=>x,get:()=>({fetch:async()=>new Response('{}')})}};
 const state={storage:{get:async key=>structuredClone(store.get(key)),put:async(key,value)=>store.set(key,structuredClone(value)),setAlarm:async()=>{}},getWebSockets:()=>[{deserializeAttachment:()=>({sid:'bob'})}]};
 const service=new CallService(state,env,()=>{}),alice={id:'alice',nickname:'甲',expires:Date.now()+100000},bob={id:'bob',nickname:'乙',expires:Date.now()+100000},other={id:'outsider',nickname:'丙',expires:Date.now()+100000};
 async function fetch(url,options){
  const u=new URL(url),path=u.pathname.replace('/v1/apps/fake-app',''),body=options.body?JSON.parse(options.body):undefined;requests.push({path,method:options.method,body});
  if(nextFailure&&nextFailure.match(path,options.method,body)){const f=nextFailure;nextFailure=null;return f.response}
  if(path==='/sessions/new'){const id='fake-'+(++counter);sessions.set(id,[]);return Response.json({sessionId:id})}
  const session=path.match(/^\/sessions\/([^/]+)/)?.[1];
  if(options.method==='GET'&&session)return Response.json({tracks:(sessions.get(session)||[]).map(t=>({...t}))});
  if(path.endsWith('/tracks/new')){
   const remote=body.tracks.every(t=>t.location==='remote');
   const outTracks=body.tracks.map((t,i)=>({mid:remote?`r${i}`:t.mid,trackName:t.trackName}));
   sessions.set(session,outTracks.map(t=>({...t,status:trackStatus})));
   return Response.json({tracks:outTracks,sessionDescription:{type:remote?'offer':'answer',sdp:'v=0\\r\\n'},requiresImmediateRenegotiation:remote});
  }
  if(path.endsWith('/tracks/close')){const current=sessions.get(session)||[];for(const t of current)if(body.tracks.some(x=>x.mid===t.mid))t.status='inactive';return Response.json({tracks:body.tracks.map(t=>({...t}))})}
  if(path.endsWith('/renegotiate'))return Response.json({sessionDescription:{type:'answer',sdp:'v=0\\r\\n'}});
  return Response.json({});
 }
 function fail(match,response){nextFailure={match,response}}
 return {env,state,store,requests,sessions,errors,service,alice,bob,other,fetch,fail,setTrackStatus(v){trackStatus=v;for(const tracks of sessions.values())for(const t of tracks)t.status=v}};
}
async function operation(f,name,s,body={},cap){const req=new Request(`https://chat.local/api/r/lobby/call-${name}`,{method:name==='state'?'GET':'POST',headers:{'Content-Type':'application/json',...(cap?{'x-call-cap':cap}:{})},body:name==='state'?undefined:JSON.stringify(body)});return (await f.service.handle('call-'+name,req,s,'lobby')).json()}
async function withProvider(f,fn){const previous=globalThis.fetch;globalThis.fetch=f.fetch;try{return await fn(f)}finally{globalThis.fetch=previous}}
async function joinedPair(f,kind='video'){
 const start=await operation(f,'start',f.alice,{kind,target:'bob'}); if(!start.call) throw new Error('START_RESULT '+JSON.stringify(start)); const callId=start.call.id,a=await operation(f,'join',f.alice,{callId}),b=await operation(f,'join',f.bob,{callId});
 return {callId,a,b,publish:async tracks=>operation(f,'publish',f.alice,{callId,sessionDescription:{type:'offer',sdp:'v=0\\r\\n'},tracks},a.call.cap),ready:()=>operation(f,'ready',f.alice,{callId},a.call.cap),subscribe:()=>operation(f,'subscribe',f.bob,{callId,members:['alice']},b.call.cap),answer:()=>operation(f,'answer',f.bob,{callId,sessionDescription:{type:'answer',sdp:'v=0\\r\\n'}},b.call.cap)};
}
const AV=[{mid:'0',kind:'audio'},{mid:'1',kind:'video'}];

test('publish becomes ready only after matching provider tracks are active; subscribe uses exact session and names',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);await pair.publish(AV);await pair.ready();
 const state=f.store.get('call');assert.equal(state.members.alice.pub.ready,true);assert.equal(state.members.alice.pub.uncertain,false);
 const out=await pair.subscribe();assert.equal(out.requiresImmediateRenegotiation,true);assert.equal(out.sessionDescription.type,'offer');
 const pubId=state.members.alice.pub.id,pull=f.requests.find(r=>r.path.endsWith('/tracks/new')&&r.body.tracks[0].location==='remote');
 assert.equal(pull.body.tracks.length,2);assert.deepEqual(pull.body.tracks.map(t=>[t.sessionId,t.trackName]),[[pubId,'alice-audio'],[pubId,'alice-video']]);
 const activeCheck=f.requests.filter(r=>r.method==='GET'&&r.path===`/sessions/${pubId}`);assert.ok(activeCheck.length>=2,'publisher state checked before ready and again before subscribe');
 const subState=f.store.get('call').members.bob.sub;assert.equal(subState.uncertain,false);assert.ok(subState.pending);
 await pair.answer();assert.equal(f.store.get('call').members.bob.sub.uncertain,false);assert.equal(f.store.get('call').members.bob.sub.pending,undefined);
}));

test('inactive SFU tracks block call-ready and subscription until provider reports active',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);await pair.publish(AV);f.setTrackStatus('inactive');
 await assert.rejects(()=>pair.ready(),e=>e.status===409&&e.details?.failureKind==='publisher_tracks_not_active');
 assert.equal(f.store.get('call').members.alice.pub.ready,false);
 await assert.rejects(()=>pair.subscribe(),e=>e.status===403||e.status===409);
 f.setTrackStatus('active');await pair.ready();assert.equal(f.store.get('call').members.alice.pub.ready,true);
 const out=await pair.subscribe();assert.equal(out.sessionDescription.type,'offer');
}));

test('top-level SFU errors include safe response details and clear subscription uncertain state after cleanup',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();
 f.fail((path,method)=>path.endsWith('/tracks/new')&&method==='POST',new Response(JSON.stringify({errorCode:50201,errorDescription:'remote failure fake-secret'}),{status:502,headers:{'content-type':'application/json'}}));
 await assert.rejects(()=>pair.subscribe(),e=>{assert.equal(e.status,502);assert.equal(e.details.failureKind,'top_level_error');assert.equal(e.details.path,`/sessions/fake-2/tracks/new`);assert.equal(e.details.httpStatus,502);assert.equal(e.details.errorCode,50201);assert.ok(e.details.responseBody.includes('remote failure'));assert.ok(!JSON.stringify(e.details).includes('fake-secret'));return true});
 assert.equal(f.store.get('call').members.bob.sub,undefined,'known failed subscriber session is discarded after close');
 const out=await pair.subscribe();assert.equal(out.sessionDescription.type,'offer','retry can create a clean receiver session');
}));

test('individual track errors are distinguished from top-level errors and return per-track codes',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();
 f.fail((path,method)=>path.endsWith('/tracks/new')&&method==='POST',Response.json({tracks:[{mid:'r0',trackName:'alice-audio',errorCode:41004,errorDescription:'track not found'}],sessionDescription:{type:'offer',sdp:'v=0'},requiresImmediateRenegotiation:true}));
 await assert.rejects(()=>pair.subscribe(),e=>{assert.equal(e.details.failureKind,'track_error');assert.equal(e.details.errorCode,undefined);assert.deepEqual(e.details.trackErrors,[{mid:'r0',trackName:'alice-audio',errorCode:41004,errorDescription:'track not found'}]);return true});
 assert.equal(f.store.get('call').members.bob.sub,undefined);
}));

test('publish and answer deterministic failures discard partial sessions so retries do not get a stale 409',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);f.fail((path,method)=>path.endsWith('/tracks/new')&&method==='POST',Response.json({tracks:[{mid:'0',trackName:'alice-audio',errorCode:4001,errorDescription:'publish rejected'}],sessionDescription:{type:'answer',sdp:'v=0'}}));
 await assert.rejects(()=>pair.publish([{mid:'0',kind:'audio'}]),e=>e.details?.failureKind==='track_error');assert.equal(f.store.get('call').members.alice.pub,undefined);await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();
 await pair.subscribe();f.fail((path,method)=>path.endsWith('/renegotiate')&&method==='PUT',new Response(JSON.stringify({errorCode:50020,errorDescription:'renegotiation rejected'}),{status:502,headers:{'content-type':'application/json'}}));
 await assert.rejects(()=>pair.answer(),e=>e.details?.failureKind==='top_level_error');assert.equal(f.store.get('call').members.bob.sub,undefined);const retry=await pair.subscribe();assert.equal(retry.sessionDescription.type,'offer');await pair.answer();
}));

test('ambiguous network outcome remains uncertain instead of replaying or claiming cleanup',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f),real=f.fetch;
 await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();f.fail((path,method)=>path.endsWith('/tracks/new')&&method==='POST',new Response(JSON.stringify({errorCode:123,errorDescription:'provider rejected'}),{status:502,headers:{'content-type':'application/json'}}));
 // A deterministic rejection is cleaned; now simulate unknown transport failures, including cleanup.
 await assert.rejects(()=>pair.subscribe(),e=>e.status===502);
 const call=f.store.get('call');assert.equal(call.members.bob.sub,undefined);
 // A later provider request can fail after the request may have committed. Cleanup also fails, so retain uncertain.
 globalThis.fetch=async(url,opts)=>{const path=new URL(url).pathname.replace('/v1/apps/fake-app','');if(path.endsWith('/tracks/new')||path==='/sessions/fake-3'||path.endsWith('/tracks/close'))throw new Error('synthetic timeout');return real(url,opts)};
 await assert.rejects(()=>pair.subscribe(),e=>e.status===502);const sub=f.store.get('call').members.bob.sub;assert.ok(sub?.uncertain);await assert.rejects(()=>pair.subscribe(),e=>e.status===409);
}));


test('an expired Cloudflare session (410 session_error) is released during cleanup so a new call can rebuild it',async()=>withProvider(fixture(),async f=>{
 const pair=await joinedPair(f);await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();
 f.fail((path,method)=>path==='/sessions/fake-1'&&method==='GET',new Response(JSON.stringify({errorCode:'session_error',errorDescription:'Session appears to be disconnected. Please check if the PeerConnection is connected.'}),{status:410,headers:{'content-type':'application/json'}}));
 const left=await operation(f,'leave',f.alice,{callId:pair.callId},pair.a.call.cap);assert.equal(left.cleanupPending,false);
 const stored=f.store.get('call');assert.equal(stored.cleanup.length,0);assert.equal(stored.ended!==undefined,true);
 const next=await operation(f,'start',f.alice,{kind:'audio',target:'bob'});assert.ok(next.call,'a new call can be started using fresh SFU sessions');
}));

test('410 cleanup remains classifiable internally when client diagnostics are disabled',async()=>withProvider(fixture(),async f=>{
 f.env.SFU_DIAGNOSTICS='false';
 const pair=await joinedPair(f);await pair.publish([{mid:'0',kind:'audio'}]);await pair.ready();
 f.fail((path,method)=>path==='/sessions/fake-1'&&method==='GET',new Response(JSON.stringify({errorCode:'session_error',errorDescription:'Session appears to be disconnected. Please check if the PeerConnection is connected.'}),{status:410,headers:{'content-type':'application/json'}}));
 const left=await operation(f,'leave',f.alice,{callId:pair.callId},pair.a.call.cap);assert.equal(left.cleanupPending,false);
 assert.equal(f.store.get('call').cleanup.length,0);
 const next=await operation(f,'start',f.alice,{kind:'audio',target:'bob'});assert.ok(next.call);
}));
