import assert from 'node:assert/strict';
import {spawn,execFileSync} from 'node:child_process';
import {mkdtempSync,rmSync,writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {chromium} from 'playwright';
const out=process.env.OUTAGE_OUT;assert.ok(out);
const persist=mkdtempSync(join(tmpdir(),'qingliao-outage-'));
const base='http://127.0.0.1:8788';let server,browser;const checks=[],logs=[];
const pass=s=>{checks.push(s);console.log('PASS '+s)};
const api=(p,path,data)=>p.evaluate(async({path,data})=>{let r=await fetch(path,{method:data?'POST':'GET',headers:{'Content-Type':'application/json'},body:data?JSON.stringify(data):undefined});if(!r.ok)throw Error('HTTP '+r.status);return r.json()},{path,data});
try{
 execFileSync(process.execPath,['node_modules/wrangler/bin/wrangler.js','d1','migrations','apply','chat','--local','--config','android/qa-fixture/wrangler.local.json','--persist-to',persist],{stdio:'pipe'});
 server=spawn(process.execPath,['node_modules/wrangler/bin/wrangler.js','dev','--local','--ip','127.0.0.1','--port','8788','--config','android/qa-fixture/wrangler.local.json','--persist-to',persist,'--var','APP_SECRET:ephemeral-local-outage-test-secret-only'],{stdio:['ignore','pipe','pipe']});
 for(const s of [server.stdout,server.stderr])s.on('data',b=>logs.push(b.toString()));
 let up=false;for(let i=0;i<60;i++){try{if((await fetch(base)).ok){up=true;break}}catch{}await new Promise(r=>setTimeout(r,1000))}assert.ok(up,logs.join(''));
 browser=await chromium.launch({headless:true,args:['--no-sandbox']});const a=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true}), b=await browser.newContext();
 const pa=await a.newPage(),pb=await b.newPage();for(const [p,name] of [[pa,'outage-A'],[pb,'outage-B']]){await p.goto(base);await p.locator('#nickname').fill(name);await p.locator('#entryForm button').click();await p.locator('#entry').waitFor({state:'hidden'});await p.waitForFunction(()=>document.querySelector('#connection').textContent.includes('已建立'))}
 const send=async(p,text)=>{await p.locator('#messageText').fill(text);await p.locator('#send').click()};
 const before='before-'+crypto.randomUUID();await send(pb,before);await pa.getByText(before,{exact:true}).waitFor();pass('independent browser message reaches mobile UI');
 await a.setOffline(true);await pa.waitForFunction(()=>navigator.onLine===false);await pa.waitForFunction(()=>document.querySelector('#connection').textContent.includes('离线'));
 const draft='offline-draft-'+crypto.randomUUID();await send(pa,draft);await pa.locator('#pending .pending button').waitFor({state:'visible'});
 const missed='missed-'+crypto.randomUUID();await send(pb,missed);await pb.getByText(missed,{exact:true}).waitFor();await pa.waitForTimeout(2000);assert.equal(await pa.getByText(missed,{exact:true}).count(),0);assert.ok(!(await api(pb,'/api/r/lobby/history')).messages.some(m=>m.text===draft));
 await pa.screenshot({path:out+'/web-offline.png',fullPage:true});pass('offline browser cannot send or receive while independent peer can');
 const start=Date.now();await a.setOffline(false);await pa.getByText(missed,{exact:true}).waitFor({timeout:45000});let recoveryMs=Date.now()-start;pass('restoring browser network reconnects WebSocket and fills missed history');
 const failed=pa.locator('#pending .pending').filter({hasText:draft});await failed.getByRole('button',{name:'重试',exact:true}).click();await pb.getByText(draft,{exact:true}).waitFor();assert.equal((await api(pb,'/api/r/lobby/history')).messages.filter(m=>m.text===draft).length,1);pass('failed draft retry persists exactly once');
 await pa.reload();await pa.getByText(missed,{exact:true}).waitFor();await pa.getByText(draft,{exact:true}).waitFor();assert.ok(await pa.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await pa.screenshot({path:out+'/web-recovered.png',fullPage:true});pass('reload preserves session/history without horizontal mobile overflow');
 writeFileSync(out+'/web-outage.json',JSON.stringify({status:'passed',checks,recoveryMs,scope:'Chromium mobile viewport, network offline emulation, isolated local Worker/D1/R2/DO; not Android or SFU'},null,2));
}catch(e){writeFileSync(out+'/web-outage.json',JSON.stringify({status:'failed',checks,error:String(e)},null,2));throw e}
finally{await browser?.close();server?.kill('SIGTERM');if(server)await new Promise(r=>server.once('exit',r));writeFileSync(out+'/local-worker.log',logs.join(''));rmSync(persist,{recursive:true,force:true})}
