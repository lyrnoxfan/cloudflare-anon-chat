import {Env,Session,LIMITS,Fault,need,id,now,text,json,body,rate,capability,verifyCap,roomAuth} from './shared';
type Role={id:string;tracks:{mid:string;trackName:string;kind?:string}[];pending?:number;uncertain?:boolean;ready?:boolean};
type Member={id:string;nickname:string;lease:number;pub?:Role;sub?:Role};
type Call={id:string;room:string;kind:string;owner:string;target:string|null;created:number;members:Record<string,Member>;invited:string[];cleanup:Member[];ended?:number};
export class CallService {
 constructor(private state:DurableObjectState,private env:Env,private notify:(v:any)=>void){}
 private async get(){return this.state.storage.get<Call>('call')}
 private async save(c:Call){await this.state.storage.put('call',c);await this.state.storage.setAlarm(now()+15000);this.notify({type:'call-changed'})}
 private async provider(path:string,method='POST',value?:unknown){need(this.env.SFU_APP_ID&&this.env.SFU_APP_SECRET,503,'音视频服务未配置：需要 SFU App ID 和 Secret');let res:Response;try{res=await fetch(`https://rtc.live.cloudflare.com/v1/apps/${this.env.SFU_APP_ID}${path}`,{method,headers:{Authorization:`Bearer ${this.env.SFU_APP_SECRET}`,'Content-Type':'application/json'},...(value===undefined?{}:{body:JSON.stringify(value)}),signal:AbortSignal.timeout(15000)})}catch{throw new Fault(502,'媒体服务响应不明，请退出后重新加入；不会自动重放请求')}
 const b:any=await res.json();need(res.ok&&!b.errorCode,502,'媒体服务拒绝请求，请退出后重新加入');return b}
 private checkTracks(b:any){need(Array.isArray(b.tracks)&&b.tracks.every((t:any)=>!t.errorCode),502,'部分媒体轨道建立失败，请退出后重新加入')}
 private async cleanMember(m:Member){for(const role of [m.pub,m.sub]){if(!role)continue;const info=await this.provider(`/sessions/${role.id}`,'GET');const tracks=(info.tracks||[]).filter((t:any)=>t.status!=='inactive');if(tracks.length){const result=await this.provider(`/sessions/${role.id}/tracks/close`,'PUT',{tracks:tracks.map((t:any)=>({mid:t.mid})),force:true});this.checkTracks(result)}/* Empty sessions expire at the provider; there is no session DELETE endpoint. */}}
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
 if(op==='call-ready'){need(m.pub&&!m.pub.uncertain,409);m.pub.ready=true;await this.save(c);return json({})}
 if(op==='call-reset-subscriber'){if(m.sub){const role=m.sub;await this.cleanMember({id:m.id,nickname:m.nickname,lease:m.lease,sub:role});delete m.sub;await this.save(c)}return json({})}
 if(op==='call-heartbeat'){m.lease=now()+LIMITS.callLeaseMs;await this.save(c);return json({call:await this.snapshot(s)})}
 if(op==='call-leave'){await this.remove(c,s.id);if(c.target||!Object.keys(c.members).length)await this.end(c,'hangup');return json({cleanupPending:c.cleanup.length>0})}
 if(op==='call-publish'){
   need(!m.pub,409,'媒体发布会话已存在，请退出后重连');need(Array.isArray(b.tracks)&&b.tracks.length>=1&&b.tracks.length<=2,400);need(b.tracks.every((t:any)=>['audio','video'].includes(t.kind)&&typeof t.mid==='string'&&t.mid.length<10),400);need(new Set(b.tracks.map((t:any)=>t.kind)).size===b.tracks.length,400);need(c.kind==='video'||b.tracks.every((t:any)=>t.kind==='audio'),400);this.sdp(b.sessionDescription,'offer');
   const created=await this.provider('/sessions/new');m.pub={id:created.sessionId,tracks:[],uncertain:true};await this.save(c);
   const tracks=b.tracks.map((t:any)=>({location:'local',mid:t.mid,trackName:`${s.id}-${t.kind}`}));const out=await this.provider(`/sessions/${m.pub.id}/tracks/new`,'POST',{sessionDescription:b.sessionDescription,tracks});
   m.pub.tracks=(out.tracks||[]).filter((t:any)=>!t.errorCode).map((t:any)=>({mid:t.mid,trackName:t.trackName,kind:b.tracks.find((x:any)=>`${s.id}-${x.kind}`===t.trackName)?.kind}));await this.save(c);this.checkTracks(out);m.pub.uncertain=false;await this.save(c);return json(out)
 }
 if(op==='call-subscribe'){
   need(Array.isArray(b.members)&&b.members.length>0&&b.members.length<LIMITS.members&&new Set(b.members).size===b.members.length,400);const tracks:any[]=[];for(const sid of b.members){need(sid!==s.id&&c.members[sid]?.pub&&!c.members[sid].pub!.uncertain&&c.members[sid].pub!.ready,403,'参会者媒体尚未就绪');for(const t of c.members[sid].pub!.tracks)tracks.push({location:'remote',sessionId:c.members[sid].pub!.id,trackName:t.trackName})}need(tracks.length,409,'尚无可订阅媒体');
   if(!m.sub){const created=await this.provider('/sessions/new');m.sub={id:created.sessionId,tracks:[]};await this.save(c)}need(!m.sub.pending&&!m.sub.uncertain,409,'请先完成接收协商或重新加入');m.sub.uncertain=true;await this.save(c);
   const out=await this.provider(`/sessions/${m.sub.id}/tracks/new`,'POST',{tracks});m.sub.tracks.push(...(out.tracks||[]).filter((t:any)=>!t.errorCode).map((t:any)=>({mid:t.mid,trackName:t.trackName})));await this.save(c);this.checkTracks(out);m.sub.pending=now();m.sub.uncertain=false;await this.save(c);return json(out)
 }
 if(op==='call-answer'){
   need(m.sub?.pending&&!m.sub.uncertain,409,'没有等待完成的协商');this.sdp(b.sessionDescription,'answer');m.sub.uncertain=true;await this.save(c);const out=await this.provider(`/sessions/${m.sub.id}/renegotiate`,'PUT',{sessionDescription:b.sessionDescription});delete m.sub.pending;m.sub.uncertain=false;await this.save(c);return json(out)
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
