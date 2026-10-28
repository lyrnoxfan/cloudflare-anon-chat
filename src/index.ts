import {AwsClient} from 'aws4fetch';
import {Env,Session,LIMITS,Fault,need,id,now,text,cid,json,body,session,roomAuth,rate,origin,sha,password,equal,signed,capability,verifyCap,mediaKind,publicMessage} from './shared';
export {ChatRoom} from './room';
export class Guard {
 constructor(private state:DurableObjectState){}
 async fetch(req:Request){const {max,ms,cost}=await req.json() as any;return this.state.blockConcurrencyWhile(async()=>{let c=await this.state.storage.get<{n:number;exp:number}>('c');if(!c||c.exp<=now())c={n:0,exp:now()+ms};if(c.n+cost>max)return json({},429);c.n+=cost;await this.state.storage.put('c',c);await this.state.storage.setAlarm(c.exp);return json({})})}
 async alarm(){await this.state.storage.deleteAll()}
}
export async function sweep(env:Env){
 const old=await env.DB.prepare("SELECT * FROM uploads WHERE expires<? AND status!='complete' LIMIT 100").bind(now()-300000).all<any>();
 for(const u of old.results){await env.MEDIA.delete([u.staging_key,u.final_key]);await env.DB.prepare('DELETE FROM uploads WHERE id=?').bind(u.id).run()}
 const garbage=await env.DB.prepare('SELECT object_key FROM garbage WHERE created<? LIMIT 100').bind(now()).all<any>();for(const g of garbage.results){await env.MEDIA.delete(g.object_key);await env.DB.prepare('DELETE FROM garbage WHERE object_key=?').bind(g.object_key).run()}
 const completed=await env.DB.prepare("SELECT id,staging_key FROM uploads WHERE status='complete' AND expires<? LIMIT 100").bind(now()-300000).all<any>();for(const u of completed.results){await env.MEDIA.delete(u.staging_key);await env.DB.prepare('DELETE FROM uploads WHERE id=?').bind(u.id).run()}
 await env.DB.prepare('DELETE FROM room_sessions WHERE expires<?').bind(now()).run();
 // Preserve stable authorship on long-lived messages; expired sessions are not renewed.
}
export default {
 async scheduled(_e:ScheduledController,env:Env,ctx:ExecutionContext){ctx.waitUntil(sweep(env))},
 async fetch(req:Request,env:Env):Promise<Response>{try{
 const url=new URL(req.url),p=url.pathname;
 if(p.startsWith('/api/')){
   need(env.APP_SECRET?.length>=32,503,'服务端安全密钥尚未配置');
   if(req.method!=='GET'||req.headers.get('upgrade')==='websocket')origin(req);
   const ip=req.headers.get('cf-connecting-ip')||'local';
   if(p==='/api/session'&&req.method==='POST'){
     await rate(env,'session:'+ip,30,3600000);const b=await body(req);const nickname=text(b.nickname,LIMITS.nickname);let s:Session|null=null;try{s=await session(req,env)}catch{}
     if(s){await env.DB.prepare('UPDATE anonymous_sessions SET nickname=? WHERE id=?').bind(nickname,s.id).run();return json({...s,nickname,limits:LIMITS,sfu:!!(env.SFU_APP_ID&&env.SFU_APP_SECRET)})}
     const token=id()+id();s={id:id(),nickname,expires:now()+LIMITS.sessionMs};await env.DB.prepare('INSERT INTO anonymous_sessions(id,token_hash,nickname,created,expires) VALUES(?,?,?,?,?)').bind(s.id,await sha(token),nickname,now(),s.expires).run();
     const secure=url.protocol==='https:'?'; Secure':'';need(secure||env.DEV_LOCAL==='true',503,'生产环境必须使用 HTTPS');return json({...s,limits:LIMITS,sfu:!!(env.SFU_APP_ID&&env.SFU_APP_SECRET)},200,{'Set-Cookie':`chat=${token}; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800${secure}`})
   }
   const s=await session(req,env);
   if(p==='/api/session'&&req.method==='GET')return json({...s,limits:LIMITS,sfu:!!(env.SFU_APP_ID&&env.SFU_APP_SECRET)});
   if(p==='/api/rooms'&&req.method==='POST'){
     await rate(env,'create:ip:'+ip,5,3600000);await rate(env,'create:s:'+s.id,5,3600000);const b=await body(req);const name=text(b.name,LIMITS.roomName),pw=text(b.password,LIMITS.passwordMax,LIMITS.passwordMin),salt=id(),hash=await password(pw,salt);
     // Private room IDs double as shareable numbers. Existing UUID room IDs remain valid.
     for(let attempt=0;attempt<8;attempt++){
       const n=crypto.getRandomValues(new Uint32Array(2));const room=String(1000000000+Number(((BigInt(n[0])<<32n)|BigInt(n[1]))%9000000000n));
       try{await env.DB.batch([env.DB.prepare("INSERT INTO rooms(id,name,kind,password_hash,salt,iterations,created) VALUES(?,?,'private',?,?,?,?)").bind(room,name,hash,salt,100000,now()),env.DB.prepare('INSERT INTO room_sessions(room_id,session_id,expires) VALUES(?,?,?)').bind(room,s.id,now()+LIMITS.grantMs)]);return json({id:room,name,kind:'private'},201)}catch(e){if(!/UNIQUE constraint failed: rooms\.id/i.test(String(e)))throw e}
     }
     throw new Fault(503,'暂时无法创建房间，请稍后重试');
   }
   const m=p.match(/^\/api\/r\/([a-zA-Z0-9-]{1,80})\/([a-z-]+)(?:\/([a-zA-Z0-9-]{1,80}))?$/);need(m,404,'接口不存在');const [,rid,op,arg]=m;
   if(op==='join'&&req.method==='POST'){
     await rate(env,'join:ip:'+ip,40,60000);await rate(env,'join:ip:'+ip+':'+rid,10,60000);await rate(env,'join:s:'+s.id+':'+rid,10,60000);const r=await env.DB.prepare("SELECT * FROM rooms WHERE id=? AND status='active'").bind(rid).first<any>();need(r,404,'房间不存在');if(r.kind==='private'){const b=await body(req);const pw=text(b.password,LIMITS.passwordMax,LIMITS.passwordMin);need(equal(await password(pw,r.salt,r.iterations),r.password_hash),403,'房间口令不正确');await env.DB.prepare('INSERT INTO room_sessions(room_id,session_id,expires) VALUES(?,?,?) ON CONFLICT(room_id,session_id) DO UPDATE SET expires=excluded.expires').bind(rid,s.id,now()+LIMITS.grantMs).run()}return json({id:r.id,name:r.name,kind:r.kind})
   }
   await roomAuth(env,rid,s);
   if(op==='upload-content'&&req.method==='PUT'){
     const u=await env.DB.prepare("SELECT * FROM uploads WHERE id=? AND room_id=? AND author=? AND status='pending' AND expires>?").bind(arg,rid,s.id,now()).first<any>();need(u,403,'上传授权无效或已过期');
     need(Number(req.headers.get('content-length'))===u.size,413,'上传大小与授权不符');need(req.headers.get('content-type')===u.mime,415,'上传类型与授权不符');need(req.body,400,'上传内容缺失');
     await rate(env,'proxy-put:'+s.id,10,3600000);await rate(env,'proxy-putip:'+ip,30,3600000);await rate(env,'proxy-bytes:'+s.id,200*1024**2,3600000,u.size);await rate(env,'proxy-bytesip:'+ip,500*1024**2,3600000,u.size);
     const attemptKey='staging/'+u.id+'/'+id();let committed=false;
     try{
       const stored=await env.MEDIA.put(attemptKey,req.body,{httpMetadata:{contentType:u.mime}});need(stored.size===u.size,413,'实际文件大小与授权不符');
       const update=await env.DB.prepare("UPDATE uploads SET staging_key=? WHERE id=? AND room_id=? AND author=? AND status='pending' AND expires>? AND staging_key=?").bind(attemptKey,u.id,rid,s.id,now(),u.staging_key).run();need(update.meta.changes===1,409,'上传状态已变化，请重新上传');committed=true;
       await env.MEDIA.delete(u.staging_key);return json({})
     }catch(e){if(!committed)await env.MEDIA.delete(attemptKey).catch(()=>{});throw e}
   }
   if(op==='download'&&req.method==='GET'){
     const media=await env.DB.prepare('SELECT * FROM messages WHERE id=? AND room_id=? AND deleted=0 AND object_key IS NOT NULL').bind(arg,rid).first<any>();need(media,404,'文件不存在或已被删除');const scope=`download:${s.id}:${rid}:${arg}`;
     if(!url.searchParams.has('ticket'))return json({url:`${p}?ticket=${await capability(env,scope,now()+60000)}`});
     await verifyCap(env,url.searchParams.get('ticket'),scope);const object=await env.MEDIA.get(media.object_key,{range:req.headers});need(object,404,'文件不存在');
     const h=new Headers({'Content-Type':media.kind==='file'?'application/octet-stream':media.mime,'Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff','Content-Disposition':`${media.kind==='file'?'attachment':'inline'}; filename*=UTF-8''${encodeURIComponent(media.filename||'file')}`,'Accept-Ranges':'bytes','Cross-Origin-Resource-Policy':'same-origin','Content-Security-Policy':"default-src 'none'; sandbox"});
     let status=200;if(object.range){const range=object.range as {offset?:number;length?:number;suffix?:number};const start=range.suffix!=null?Math.max(0,object.size-range.suffix):(range.offset||0),length=range.suffix!=null?Math.min(object.size,range.suffix):(range.length||object.size-start);h.set('Content-Range',`bytes ${start}-${start+length-1}/${object.size}`);h.set('Content-Length',String(length));status=206}else h.set('Content-Length',String(object.size));return new Response(object.body,{status,headers:h})
   }
   if(op==='local-upload'&&req.method==='PUT'){
     need(env.DEV_LOCAL==='true'&&['localhost','127.0.0.1'].includes(url.hostname),404);const u=await env.DB.prepare("SELECT * FROM uploads WHERE id=? AND room_id=? AND author=? AND status='pending' AND expires>?").bind(arg,rid,s.id,now()).first<any>();need(u,403);need(req.headers.get('content-type')===u.mime,415);need(Number(req.headers.get('content-length'))===u.size,413);await env.MEDIA.put(u.staging_key,req.body,{httpMetadata:{contentType:u.mime}});return json({})
   }
   // One persistent authority per room orders writes and broadcasts. Client headers are replaced.
   const headers=new Headers(req.headers);headers.set('x-chat-session',JSON.stringify(s));headers.set('x-chat-room',rid);headers.set('x-chat-ip',ip);
   return await env.ROOMS.get(env.ROOMS.idFromName(rid)).fetch(new Request(req,{headers}));
 }
 const res=await env.ASSETS.fetch(req);const h=new Headers(res.headers);h.set('X-Content-Type-Options','nosniff');h.set('Referrer-Policy','no-referrer');h.set('Permissions-Policy','camera=(self), microphone=(self)');h.set('Content-Security-Policy',"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob:; media-src 'self' blob:; connect-src 'self' wss: https://*.r2.cloudflarestorage.com; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");return new Response(res.body,{status:res.status,headers:h});
 }catch(e){console.error('chat request failure',new URL(req.url).pathname,e instanceof Error?e.name+': '+e.message:'unknown');if(e instanceof Fault)return json({error:e.message},e.status);return json({error:'服务暂时不可用，请稍后重试'},500)}}
} satisfies ExportedHandler<Env>;
export async function uploadURL(env:Env,u:any,base:string){if(env.DEV_LOCAL==='true'&&['localhost','127.0.0.1'].includes(new URL(base).hostname))return new URL(`/api/r/${u.room_id}/local-upload/${u.id}`,base).href;
 if(!env.R2_ACCOUNT_ID||!env.R2_ACCESS_KEY_ID||!env.R2_SECRET_ACCESS_KEY)return new URL(`/api/r/${u.room_id}/upload-content/${u.id}`,base).href;
 const client=new AwsClient({accessKeyId:env.R2_ACCESS_KEY_ID,secretAccessKey:env.R2_SECRET_ACCESS_KEY,service:'s3',region:'auto'});
 const target=`https://${env.R2_ACCOUNT_ID}.r2.cloudflarestorage.com/${env.R2_BUCKET}/${u.staging_key}?X-Amz-Expires=120`;
 const signed=await client.sign(target,{method:'PUT',headers:{'Content-Type':u.mime,'Content-Length':String(u.size)},aws:{signQuery:true,allHeaders:true}});return signed.url}
