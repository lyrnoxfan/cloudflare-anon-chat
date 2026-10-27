import {Env,Session,LIMITS,Fault,need,id,now,text,cid,json,body,roomAuth,rate,publicMessage,mediaKind} from './shared';
import {uploadURL} from './index';
import {CallService} from './sfu';
export class ChatRoom {
 private queue:Promise<any>=Promise.resolve();private calls:CallService;
 constructor(private state:DurableObjectState,private env:Env){this.calls=new CallService(state,env,(v)=>this.broadcast(v))}
 private ordered<T>(fn:()=>Promise<T>):Promise<T>{const p=this.queue.then(fn,fn);this.queue=p.catch(()=>{});return p}
 private broadcast(v:any){for(const ws of this.state.getWebSockets()){try{const a=ws.deserializeAttachment() as any;if(a.until<=now()){ws.close(4003,'授权已过期');continue}if(v.type==='message')ws.send(JSON.stringify({...v,message:publicMessage(v.message,a.sid)}));else ws.send(JSON.stringify(v))}catch{}}}
 private presence(){const people=new Map<string,any>();for(const ws of this.state.getWebSockets()){const a=ws.deserializeAttachment() as any;if(a.until<=now()){ws.close(4003,'授权已过期');continue}people.set(a.sid,{id:a.sid,nickname:a.nickname})}this.broadcast({type:'presence',members:[...people.values()],count:people.size})}
 async fetch(req:Request):Promise<Response>{return this.ordered(async()=>{try{
 const s=JSON.parse(req.headers.get('x-chat-session')||'null') as Session;const rid=req.headers.get('x-chat-room')!;need(s&&s.expires>now(),401);const r=await roomAuth(this.env,rid,s);const url=new URL(req.url);const [,op,arg]=url.pathname.match(/\/r\/[^/]+\/([^/]+)(?:\/([^/]+))?$/)!;
 if(op==='state'&&req.method==='GET')return json({room:{id:r.id,name:r.name,kind:r.kind},call:await this.calls.snapshot(s)});
 if(op==='ws'&&req.headers.get('upgrade')?.toLowerCase()==='websocket'){
   need(this.state.getWebSockets().length<200,429,'房间连接数已达上限');await rate(this.env,'ws:'+s.id,30);const pair=new WebSocketPair();this.state.acceptWebSocket(pair[1]);const grant=r.kind==='private'?await this.env.DB.prepare('SELECT expires FROM room_sessions WHERE room_id=? AND session_id=?').bind(rid,s.id).first<any>():null;pair[1].serializeAttachment({sid:s.id,nickname:s.nickname,rid,until:Math.min(s.expires,grant?.expires||s.expires)});pair[1].send(JSON.stringify({type:'ready'}));this.presence();return new Response(null,{status:101,webSocket:pair[0]})
 }
 if(op==='history'&&req.method==='GET'){
   const cursor=url.searchParams.get('before');let values:any[]=[rid],clause='';if(cursor){let c:any;try{c=JSON.parse(atob(cursor))}catch{throw new Fault(400,'分页游标无效')}need(Array.isArray(c)&&Number.isSafeInteger(c[0])&&typeof c[1]==='string',400);clause=' AND (created<? OR (created=? AND id<?))';values.push(c[0],c[0],c[1])}
   const rows=await this.env.DB.prepare(`SELECT * FROM messages WHERE room_id=?${clause} ORDER BY created DESC,id DESC LIMIT 51`).bind(...values).all<any>();const page=rows.results.slice(0,50);const last=page.at(-1);return json({messages:page.reverse().map(m=>publicMessage(m,s.id)),next:rows.results.length>50?btoa(JSON.stringify([last.created,last.id])):null})
 }
 if(op==='send'&&req.method==='POST'){
   await rate(this.env,'msg:'+s.id,40);await rate(this.env,'msgip:'+req.headers.get('x-chat-ip'),100);const b=await body(req),client=cid(b.clientId),value=text(b.text,LIMITS.text);need(!await this.env.DB.prepare("SELECT id FROM uploads WHERE room_id=? AND author=? AND client_id=? AND status='pending'").bind(rid,s.id,client).first(),409,'该消息标识已用于上传');
   await this.env.DB.prepare("INSERT INTO messages(id,room_id,author,nickname,client_id,kind,text,created) VALUES(?,?,?,?,?,'text',?,?) ON CONFLICT(room_id,author,client_id) DO NOTHING").bind(id(),rid,s.id,s.nickname,client,value,now()).run();
   const m=await this.env.DB.prepare('SELECT * FROM messages WHERE room_id=? AND author=? AND client_id=?').bind(rid,s.id,client).first<any>();this.broadcast({type:'message',message:m});return json({message:publicMessage(m,s.id)})
 }
 if(op==='delete'&&req.method==='POST'){
   await rate(this.env,'delete:'+s.id,40);const m=await this.env.DB.prepare('SELECT * FROM messages WHERE id=? AND room_id=? AND author=?').bind(arg,rid,s.id).first<any>();need(m,403,'只能删除本次浏览器会话发送的消息');const stmts=[this.env.DB.prepare('UPDATE messages SET deleted=1,text=NULL,object_key=NULL,mime=NULL,size=NULL,filename=NULL,duration=NULL WHERE id=?').bind(arg)];if(m.object_key)stmts.push(this.env.DB.prepare('INSERT OR IGNORE INTO garbage(object_key,created) VALUES(?,?)').bind(m.object_key,now()));await this.env.DB.batch(stmts);this.broadcast({type:'deleted',id:arg});return json({})
 }
 if(op==='upload'&&req.method==='POST'){
   await rate(this.env,'up:'+s.id,10,3600000);await rate(this.env,'upip:'+req.headers.get('x-chat-ip'),30,3600000);const b=await body(req),client=cid(b.clientId);need(Number.isSafeInteger(b.size)&&b.size>0,400);const mime=text(b.mime||'application/octet-stream',100),kind=mediaKind(mime,b.kind);need(b.size<=LIMITS[kind as 'file'],413,'文件超过该类型的大小限制');need(b.duration==null||(typeof b.duration==='number'&&Number.isFinite(b.duration)&&b.duration>=0&&b.duration<=7200),400);
   await rate(this.env,'bytes:'+s.id,200*1024**2,3600000,b.size);await rate(this.env,'bytesip:'+req.headers.get('x-chat-ip'),500*1024**2,3600000,b.size);
   need(!await this.env.DB.prepare('SELECT id FROM messages WHERE room_id=? AND author=? AND client_id=?').bind(rid,s.id,client).first(),409,'该消息标识已使用');let u=await this.env.DB.prepare('SELECT * FROM uploads WHERE room_id=? AND author=? AND client_id=?').bind(rid,s.id,client).first<any>();
   if(u){need(u.status==='pending',409,'该文件已发送或已取消');need(u.expires>now(),410,'上传已过期，请重新选择文件')}else{u={id:id(),room_id:rid,author:s.id,client_id:client,staging_key:'staging/'+id(),final_key:'media/'+id(),size:b.size,mime,filename:text(b.filename,180),kind,duration:b.duration??null,expires:now()+LIMITS.uploadMs};await this.env.DB.prepare('INSERT INTO uploads(id,room_id,author,client_id,staging_key,final_key,size,mime,filename,kind,duration,expires) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)').bind(u.id,rid,s.id,client,u.staging_key,u.final_key,u.size,mime,u.filename,kind,u.duration,u.expires).run()}
   return json({id:u.id,url:await uploadURL(this.env,u,req.url),mime:u.mime,expires:u.expires})
 }
 if((op==='confirm-upload'||op==='cancel-upload')&&req.method==='POST'){
   await rate(this.env,'confirm:'+s.id,30);const u=await this.env.DB.prepare('SELECT * FROM uploads WHERE id=? AND room_id=? AND author=?').bind(arg,rid,s.id).first<any>();need(u,404);
   if(u.status==='complete'){const m=await this.env.DB.prepare('SELECT * FROM messages WHERE room_id=? AND author=? AND client_id=?').bind(rid,s.id,u.client_id).first<any>();return json({message:publicMessage(m,s.id)})}
   if(op==='cancel-upload'){await this.env.DB.prepare("UPDATE uploads SET status='cancelled' WHERE id=?").bind(arg).run();await this.env.MEDIA.delete([u.staging_key,u.final_key]);return json({})}
   need(u.status==='pending'&&u.expires+300000>now(),410,'上传已失效');const obj=await this.env.MEDIA.get(u.staging_key);need(obj,409,'上传尚未完成');if(obj.size!==u.size||obj.httpMetadata?.contentType!==u.mime){await this.env.MEDIA.delete(u.staging_key);throw new Fault(413,'实际文件大小或类型不匹配，文件已清理')}
   // Commit under a fresh key that the reusable PUT URL can never overwrite. Stream, do not buffer.
   await this.env.MEDIA.put(u.final_key,obj.body,{httpMetadata:{contentType:u.mime}});
   await this.env.DB.batch([this.env.DB.prepare('INSERT INTO messages(id,room_id,author,nickname,client_id,kind,object_key,mime,size,filename,duration,created) VALUES(?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(room_id,author,client_id) DO NOTHING').bind(id(),rid,s.id,s.nickname,u.client_id,u.kind,u.final_key,u.mime,u.size,u.filename,u.duration,now()),this.env.DB.prepare("UPDATE uploads SET status='complete' WHERE id=?").bind(u.id),this.env.DB.prepare('INSERT OR IGNORE INTO garbage(object_key,created) VALUES(?,?)').bind(u.staging_key,u.expires+300000)]);
   const m=await this.env.DB.prepare('SELECT * FROM messages WHERE room_id=? AND author=? AND client_id=?').bind(rid,s.id,u.client_id).first<any>();this.broadcast({type:'message',message:m});return json({message:publicMessage(m,s.id)})
 }
 if(op.startsWith('call-'))return await this.calls.handle(op,req,s,rid);
 throw new Fault(404,'接口不存在');
 }catch(e){return json({error:e instanceof Fault?e.message:'房间服务暂时不可用'},e instanceof Fault?e.status:500)}})}
 async webSocketMessage(ws:WebSocket,message:string|ArrayBuffer){try{need(typeof message==='string'&&message.length<200,400);const a=ws.deserializeAttachment() as any;const s=await this.env.DB.prepare('SELECT id,nickname,expires FROM anonymous_sessions WHERE id=? AND expires>?').bind(a.sid,now()).first<Session>();need(s,401);await roomAuth(this.env,a.rid,s);await rate(this.env,'ping:'+s.id,12);if(message==='ping')ws.send(JSON.stringify({type:'pong'}));else need(false,400)}catch{ws.close(4003,'授权过期或消息无效')}}
 async webSocketClose(ws:WebSocket){try{ws.close()}catch{}this.presence()}
 async webSocketError(ws:WebSocket){try{ws.close()}catch{}this.presence()}
 async alarm(){await this.ordered(()=>this.calls.alarm())}
}
