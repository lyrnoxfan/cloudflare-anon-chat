import {Env,Session,LIMITS,Fault,need,id,now,text,json,body,rate,capability,verifyCap,roomAuth} from './shared';
type Role={id:string;tracks:{mid:string;trackName:string;kind?:string}[];pending?:number;uncertain?:boolean;ready?:boolean};
type Member={id:string;nickname:string;lease:number;pub?:Role;sub?:Role};
type Call={id:string;room:string;kind:string;owner:string;target:string|null;created:number;members:Record<string,Member>;invited:string[];cleanup:Member[];ended?:number};
export class CallService {
 constructor(private state:DurableObjectState,private env:Env,private notify:(v:any)=>void){}
 private async get(){return this.state.storage.get<Call>('call')}
 private async save(c:Call){await this.state.storage.put('call',c);await this.state.storage.setAlarm(now()+15000);this.notify({type:'call-changed'})}
 private diagnosticsEnabled(){return this.env.SFU_DIAGNOSTICS==='true'}
 private hasErrorCode(value:any){return value!==undefined&&value!==null&&value!==0&&value!==''}
 private redact(value:string){
   let safe=value;const secret=this.env.SFU_APP_SECRET;if(secret)safe=safe.split(secret).join('[REDACTED]');
   return safe.replace(/\bBearer\s+[^\s"',}]+/gi,'Bearer [REDACTED]').replace(/("(?:authorization|access_token|refresh_token|token|secret|password|api[_-]?key|app[_-]?secret|secret_access_key)"\s*:\s*)"[^"]*"/gi,'$1"[REDACTED]"')
 }
 private meta(b:any,path:string){return b?.__sfuMeta||{path,httpStatus:null,responseBody:this.redact(JSON.stringify(b))}}
 private diagnostic(meta:any,failureKind:string,b:any,extra:Record<string,unknown>={}){
   const tracks=Array.isArray(b?.tracks)?b.tracks:[];
   const trackErrors=tracks.filter((t:any)=>this.hasErrorCode(t?.errorCode)).map((t:any)=>({mid:t.mid,trackName:t.trackName,errorCode:t.errorCode,errorDescription:t.errorDescription==null?undefined:this.redact(String(t.errorDescription))}));
   const details:Record<string,unknown>={failureKind,path:meta.path,httpStatus:meta.httpStatus,responseBody:meta.responseBody,...extra};
   if(b?.errorCode!==undefined)details.errorCode=b.errorCode;
   if(b?.errorDescription!==undefined)details.errorDescription=this.redact(String(b.errorDescription));
   if(trackErrors.length)details.trackErrors=trackErrors;
   return details
 }
 private providerFault(meta:any,failureKind:string,b:any,message:string,extra:Record<string,unknown>={}){
   const details=this.diagnostic(meta,failureKind,b,extra);console.error('SFU_PROVIDER_FAILURE',JSON.stringify(details));
   // Keep diagnostics on the internal Fault so cleanup can classify a dead session even when client diagnostics are disabled.
   return new Fault(502,message,details)
 }
 private async provider(path:string,method='POST',value?:unknown){
   need(this.env.SFU_APP_ID&&this.env.SFU_APP_SECRET,503,'音视频服务未配置：需要 SFU App ID 和 Secret');
   let res:Response|undefined,raw='';try{res=await fetch(`https://rtc.live.cloudflare.com/v1/apps/${this.env.SFU_APP_ID}${path}`,{method,headers:{Authorization:`Bearer ${this.env.SFU_APP_SECRET}`,'Content-Type':'application/json'},...(value===undefined?{}:{body:JSON.stringify(value)}),signal:AbortSignal.timeout(15000)});raw=await res.text()}
   catch(e){const meta={path,httpStatus:res?.status??null,responseBody:raw?this.redact(raw):null};const details=this.diagnostic(meta,'network_outcome_unknown',null,{networkError:this.redact(e instanceof Error?e.message:String(e))});console.error('SFU_PROVIDER_FAILURE',JSON.stringify(details));throw new Fault(502,'媒体服务响应不明，请退出后重新加入；不会自动重放请求',details)}
   let b:any;let parseError=false;try{b=JSON.parse(raw)}catch{parseError=true}
   const meta={path,httpStatus:res.status,responseBody:this.redact(raw)};
   if(!res.ok||parseError||!b||typeof b!=='object'||Array.isArray(b)||this.hasErrorCode(b.errorCode)){
     throw this.providerFault(meta,parseError||!b||typeof b!=='object'||Array.isArray(b)?'invalid_provider_response':'top_level_error',b,'媒体服务拒绝请求，请退出后重新加入')
   }
   Object.defineProperty(b,'__sfuMeta',{value:meta,enumerable:false});return b
 }
 private checkTracks(b:any,path:string,required=false){
   if(!Array.isArray(b?.tracks)){if(required)throw this.providerFault(this.meta(b,path),'missing_tracks',b,'媒体服务未返回轨道结果');return}
   const failed=b.tracks.some((t:any)=>this.hasErrorCode(t?.errorCode));if(failed)throw this.providerFault(this.meta(b,path),'track_error',b,'部分媒体轨道建立失败，请退出后重新加入')
 }
 private isExpiredSessionError(error:unknown){return error instanceof Fault&&error.details?.httpStatus===410&&error.details?.errorCode==='session_error'}
 private async closeRole(role:Role){
   const path=`/sessions/${role.id}`;let info:any;try{info=await this.provider(path,'GET')}catch(error){
     // Cloudflare documents 410/session_error as an expired or closed connection that cannot be reconnected.
     // The stored session ID came from this app; release only this dead reference and rebuild on next join.
     if(this.isExpiredSessionError(error)){console.warn('SFU_EXPIRED_SESSION_CLEANUP',JSON.stringify({path,httpStatus:(error as Fault).details?.httpStatus,errorCode:(error as Fault).details?.errorCode,errorDescription:(error as Fault).details?.errorDescription}));return}
     throw error
   }
   if(!Array.isArray(info.tracks))throw this.providerFault(this.meta(info,path),'invalid_session_tracks',info,'无法确认 SFU 会话轨道状态');
   const tracks=info.tracks.filter((t:any)=>t.status!=='inactive');if(tracks.length){const closePath=`${path}/tracks/close`,result=await this.provider(closePath,'PUT',{tracks:tracks.map((t:any)=>({mid:t.mid})),force:true});this.checkTracks(result,closePath,true)}
 }
 private async discardRole(c:Call,m:Member,key:'pub'|'sub'){
   const role=m[key];if(!role)return true;
   try{await this.closeRole(role);delete m[key];await this.save(c);return true}
   catch{role.uncertain=true;await this.save(c);console.error('SFU_SESSION_CLEANUP_PENDING',JSON.stringify({role:key,path:`/sessions/${role.id}`}));return false}
 }
 private publisherMissing(pub:Role,tracks:any[]){return pub.tracks.filter(expected=>!tracks.some((actual:any)=>actual.mid===expected.mid&&actual.trackName===expected.trackName&&actual.status==='active'))}
 private async assertPublisherActive(c:Call,pub:Role){
   const path=`/sessions/${pub.id}`,info=await this.provider(path,'GET'),tracks=Array.isArray(info.tracks)?info.tracks:[],missing=this.publisherMissing(pub,tracks);
   if(missing.length){pub.ready=false;await this.save(c);const meta=this.meta(info,path),details=this.diagnostic(meta,'publisher_tracks_not_active',info,{expectedTracks:pub.tracks.map(t=>({mid:t.mid,trackName:t.trackName})),activeTracks:tracks.map((t:any)=>({mid:t.mid,trackName:t.trackName,status:t.status}))});console.error('SFU_PUBLISHER_TRACKS_NOT_ACTIVE',JSON.stringify(details));throw new Fault(409,'发布端的 SFU 媒体轨道尚未全部激活',this.diagnosticsEnabled()?details:undefined)}
 }
 private async waitForPublisherActive(c:Call,pub:Role){
   let info:any,path=`/sessions/${pub.id}`,tracks:any[]=[],missing=pub.tracks;
   for(let attempt=0;attempt<5;attempt++){info=await this.provider(path,'GET');tracks=Array.isArray(info.tracks)?info.tracks:[];missing=this.publisherMissing(pub,tracks);if(!missing.length)return;if(attempt<4)await new Promise(resolve=>setTimeout(resolve,300))}
   pub.ready=false;await this.save(c);const details=this.diagnostic(this.meta(info,path),'publisher_tracks_not_active',info,{expectedTracks:pub.tracks.map(t=>({mid:t.mid,trackName:t.trackName})),activeTracks:tracks.map((t:any)=>({mid:t.mid,trackName:t.trackName,status:t.status})),missingTracks:missing.map(t=>({mid:t.mid,trackName:t.trackName}))});console.error('SFU_PUBLISHER_TRACKS_NOT_ACTIVE',JSON.stringify(details));throw new Fault(409,'发布端的 SFU 媒体轨道尚未全部激活',this.diagnosticsEnabled()?details:undefined)
 }
 private async cleanMember(m:Member){for(const role of [m.pub,m.sub])if(role)await this.closeRole(role)}
 private async remove(c:Call,sid:string){const m=c.members[sid];if(!m)return;delete c.members[sid];c.cleanup.push(m);await this.env.DB.prepare('UPDATE call_participants SET left_at=? WHERE call_id=? AND session_id=?').bind(now(),c.id,sid).run();await this.save(c);try{await this.cleanMember(m);c.cleanup=c.cleanup.filter(v=>v!==m);await this.save(c)}catch{/* Retain failed cleanup durably, retry from alarm, never claim complete. */}}
 private async end(c:Call,reason:string){for(const sid of Object.keys(c.members))await this.remove(c,sid);c.ended=now();await this.env.DB.prepare("UPDATE calls SET status='ended',ended=?,reason=? WHERE id=?").bind(c.ended,reason,c.id).run();await this.save(c)}
 async snapshot(s:Session){const c=await this.get();if(!c||c.ended)return null;const m=c.members[s.id];return {id:c.id,kind:c.kind,owner:c.owner,created:c.created,target:c.target,invited:c.invited.includes(s.id),joined:!!m,members:Object.values(c.members).map(v=>({id:v.id,nickname:v.nickname,tracks:(v.pub?.ready?v.pub.tracks:[]).filter(t=>t.kind).map(t=>({trackName:t.trackName,kind:t.kind}))||[]})),...(m?{cap:await capability(this.env,`call:${s.id}:${c.id}`)}:{})}}
 async handle(op:string,req:Request,s:Session,rid:string){
 let c=await this.get();
 if(op==='call-state'&&req.method==='GET')return json({call:await this.snapshot(s)});
 need(req.method==='POST',405);await rate(this.env,'callop:'+s.id,120);const b=await body(req);
 if(op==='call-start'){
   need(this.env.SFU_APP_ID&&this.env.SFU_APP_SECRET,503,'音视频服务未配置：需要 SFU App ID 和 Secret');await rate(this.env,'callstart:'+s.id,6,60000);need(!c||c.ended,409,'房间已有通话');need(!c||c.cleanup.length===0,409,'上一通话资源仍在清理，请稍后重试');need(['audio','video'].includes(b.kind),400);
   const target=b.target?text(b.target,80):null;need(target!==s.id,400);if(target){const online=this.state.getWebSockets().some(ws=>(ws.deserializeAttachment() as any)?.sid===target);need(online,409,'对方已离线')}
   c={id:id(),room:rid,kind:b.kind,owner:s.id,target,created:now(),members:{},invited:target?[target]:[],cleanup:[]};
   await this.env.DB.prepare("INSERT INTO calls(id,room_id,kind,owner,started,status) VALUES(?,?,?,?,?,'ringing')").bind(c.id,rid,c.kind,s.id,c.created).run();await this.save(c);return json({call:await this.snapshot(s)})
 }
 need(c&&!c.ended&&c.room===rid,404,'通话已结束');need(b.callId===c.id,409,'通话已改变');
 if(op==='call-decline'){need(c.invited.includes(s.id),403);c.invited=c.invited.filter(v=>v!==s.id);if(c.target)await this.end(c,'declined');else await this.save(c);return json({})}
 if(op==='call-join'){
   need(!c.target||s.id===c.owner||s.id===c.target,403,'这是一对一通话');need(!c.members[s.id],409,'当前会话已在通话中，请先退出旧标签页');need(Object.keys(c.members).length<LIMITS.members,409,'通话人数已满');
   c.members[s.id]={id:s.id,nickname:s.nickname,lease:now()+LIMITS.callLeaseMs};c.invited=c.invited.filter(v=>v!==s.id);await this.save(c);await this.env.DB.prepare('INSERT INTO call_participants(call_id,session_id,nickname,joined,left_at) VALUES(?,?,?,?,NULL) ON CONFLICT(call_id,session_id) DO UPDATE SET joined=excluded.joined,left_at=NULL').bind(c.id,s.id,s.nickname,now()).run();await this.env.DB.prepare("UPDATE calls SET status='active' WHERE id=?").bind(c.id).run();return json({call:await this.snapshot(s),iceServers:await this.ice()})
 }
 if(op==='call-cancel'){need(c.owner===s.id,403);await this.end(c,'cancelled');return json({})}
 const m=c.members[s.id];need(m,403,'请先接听或加入通话');await verifyCap(this.env,req.headers.get('x-call-cap'),`call:${s.id}:${c.id}`);
 if(op==='call-ready'){need(m.pub&&!m.pub.uncertain,409);await this.waitForPublisherActive(c,m.pub);m.pub.ready=true;await this.save(c);return json({})}
 if(op==='call-reset-subscriber'){if(m.sub){const role=m.sub;await this.cleanMember({id:m.id,nickname:m.nickname,lease:m.lease,sub:role});delete m.sub;await this.save(c)}return json({})}
 if(op==='call-heartbeat'){m.lease=now()+LIMITS.callLeaseMs;await this.save(c);return json({call:await this.snapshot(s)})}
 if(op==='call-leave'){await this.remove(c,s.id);if(c.target||!Object.keys(c.members).length)await this.end(c,'hangup');return json({cleanupPending:c.cleanup.length>0})}
 if(op==='call-publish'){
   need(!m.pub,409,'媒体发布会话已存在，请退出后重连');need(Array.isArray(b.tracks)&&b.tracks.length>=1&&b.tracks.length<=2,400);need(b.tracks.every((t:any)=>['audio','video'].includes(t.kind)&&typeof t.mid==='string'&&t.mid.length<10),400);need(new Set(b.tracks.map((t:any)=>t.kind)).size===b.tracks.length,400);need(c.kind==='video'||b.tracks.every((t:any)=>t.kind==='audio'),400);this.sdp(b.sessionDescription,'offer');
   const created=await this.provider('/sessions/new');need(typeof created.sessionId==='string'&&created.sessionId.length>0,502,'SFU 未返回发布会话标识');m.pub={id:created.sessionId,tracks:[],uncertain:true};await this.save(c);
   const path=`/sessions/${m.pub.id}/tracks/new`,tracks=b.tracks.map((t:any)=>({location:'local',mid:t.mid,trackName:`${s.id}-${t.kind}`}));
   try{
     const out=await this.provider(path,'POST',{sessionDescription:b.sessionDescription,tracks});m.pub.tracks=(out.tracks||[]).filter((t:any)=>!this.hasErrorCode(t?.errorCode)).map((t:any)=>({mid:t.mid,trackName:t.trackName,kind:b.tracks.find((x:any)=>`${s.id}-${x.kind}`===t.trackName)?.kind}));await this.save(c);
     this.checkTracks(out,path,true);if(out.sessionDescription?.type!=='answer')throw this.providerFault(this.meta(out,path),'publish_answer_missing',out,'SFU 未返回有效发布应答');
     m.pub.uncertain=false;await this.save(c);return json(out)
   }catch(e){await this.discardRole(c,m,'pub');throw e}
 }
 if(op==='call-subscribe'){
   need(Array.isArray(b.members)&&b.members.length>0&&b.members.length<LIMITS.members&&new Set(b.members).size===b.members.length,400);const tracks:any[]=[],publishers:Role[]=[];for(const sid of b.members){need(sid!==s.id&&c.members[sid]?.pub&&!c.members[sid].pub!.uncertain&&c.members[sid].pub!.ready,403,'参会者媒体尚未就绪');const pub=c.members[sid].pub!;publishers.push(pub);for(const t of pub.tracks)tracks.push({location:'remote',sessionId:pub.id,trackName:t.trackName})}need(tracks.length,409,'尚无可订阅媒体');
   for(const pub of publishers)await this.assertPublisherActive(c,pub);
   if(!m.sub){const created=await this.provider('/sessions/new');need(typeof created.sessionId==='string'&&created.sessionId.length>0,502,'SFU 未返回接收会话标识');m.sub={id:created.sessionId,tracks:[]};await this.save(c)}need(!m.sub.pending&&!m.sub.uncertain,409,'请先完成接收协商或重新加入');m.sub.uncertain=true;await this.save(c);
   const path=`/sessions/${m.sub.id}/tracks/new`;
   try{const out=await this.provider(path,'POST',{tracks});m.sub.tracks.push(...(out.tracks||[]).filter((t:any)=>!this.hasErrorCode(t?.errorCode)).map((t:any)=>({mid:t.mid,trackName:t.trackName})));await this.save(c);this.checkTracks(out,path,true);
     if(out.requiresImmediateRenegotiation!==true||out.sessionDescription?.type!=='offer')throw this.providerFault(this.meta(out,path),'subscription_offer_missing',out,'SFU 未返回需要的接收端 offer');
     m.sub.pending=now();m.sub.uncertain=false;await this.save(c);return json(out)
   }catch(e){await this.discardRole(c,m,'sub');throw e}
 }
 if(op==='call-answer'){
   need(m.sub?.pending&&!m.sub.uncertain,409,'没有等待完成的协商');this.sdp(b.sessionDescription,'answer');m.sub.uncertain=true;await this.save(c);const path=`/sessions/${m.sub.id}/renegotiate`;
   try{const out=await this.provider(path,'PUT',{sessionDescription:b.sessionDescription});this.checkTracks(out,path);delete m.sub.pending;m.sub.uncertain=false;await this.save(c);return json(out)}catch(e){await this.discardRole(c,m,'sub');throw e}
 }
 throw new Fault(404,'通话操作不存在')
 }
 private sdp(s:any,type:string){need(s?.type===type&&typeof s.sdp==='string'&&s.sdp.length<60000&&s.sdp.startsWith('v=0'),400,'SDP 无效')}
 private async ice(){if(this.env.TURN_KEY_ID&&this.env.TURN_API_TOKEN){const r=await fetch(`https://rtc.live.cloudflare.com/v1/turn/keys/${this.env.TURN_KEY_ID}/credentials/generate-ice-servers`,{method:'POST',headers:{Authorization:`Bearer ${this.env.TURN_API_TOKEN}`,'Content-Type':'application/json'},body:JSON.stringify({ttl:3600}),signal:AbortSignal.timeout(15000)});need(r.ok,502,'TURN 凭证获取失败');const v:any=await r.json();return v.iceServers}return [{urls:'stun:stun.cloudflare.com:3478'}]}
 async alarm(){const c=await this.get();if(!c)return;
   if(!c.ended){for(const m of Object.values(c.members)){let allowed=true;try{const s=await this.env.DB.prepare('SELECT id,nickname,expires FROM anonymous_sessions WHERE id=? AND expires>?').bind(m.id,now()).first<Session>();need(s,401);await roomAuth(this.env,c.room,s)}catch{allowed=false}if(!allowed||m.lease<now()||(m.sub?.pending&&m.sub.pending+20000<now()))await this.remove(c,m.id)}
   if((c.target&&c.created+30000<now()&&Object.keys(c.members).length<2)||(!Object.keys(c.members).length&&c.created+30000<now()))await this.end(c,'timeout')}
   for(const m of [...c.cleanup]){try{await this.cleanMember(m);c.cleanup=c.cleanup.filter(v=>v!==m)}catch{}}
   await this.state.storage.put('call',c);if(!c.ended||c.cleanup.length)await this.state.storage.setAlarm(now()+15000);this.notify({type:'call-changed'});
 }
}
