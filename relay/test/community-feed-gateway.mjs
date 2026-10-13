import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

// Real production Gateway logic runs in workerd with SQLite, alarms, sockets and actor eviction.
// Only the Discord/account HTTP transports below are synthetic. Outbound live traffic is forbidden.
const guild='100000000000000001', chat='100000000000000002', rng='100000000000000003', bot='100000000000000004', human='100000000000000005';
const controlSecret='fixture-only-control-secret-32-bytes', bridgeSecret='fixture-only-bridge-secret-32-bytes';
const botToken='fixture-only-bot-token-32-bytes-long';
const fixture=`
import { DurableObject } from 'cloudflare:workers';
import { GlobalChatGateway, globalChatRoute } from './src/global-chat-gateway';
export { GlobalChatGateway };
export { GatewayControl } from './src/global-chat-gateway';
const guild='${guild}', chat='${chat}', rng='${rng}', bot='${bot}';
const encode=new TextEncoder();
const hex=bytes=>[...new Uint8Array(bytes)].map(value=>value.toString(16).padStart(2,'0')).join('');
export class FeedFixture extends DurableObject {
  constructor(ctx,env) { super(ctx,env); ctx.storage.sql.exec(
    'CREATE TABLE IF NOT EXISTS counters(name TEXT PRIMARY KEY,value INTEGER NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS flags(name TEXT PRIMARY KEY,value TEXT NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS posts(nonce TEXT PRIMARY KEY,id TEXT NOT NULL,channel TEXT NOT NULL,body TEXT NOT NULL,deleted INTEGER NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS outbox(id TEXT PRIMARY KEY,event TEXT NOT NULL,operation TEXT NOT NULL,message_id TEXT,claim TEXT);'+
    'CREATE TABLE IF NOT EXISTS published(id TEXT PRIMARY KEY,message_id TEXT NOT NULL,channel TEXT NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS frames(kind TEXT NOT NULL,body TEXT NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS parents(id TEXT PRIMARY KEY,body TEXT NOT NULL);'+
    'CREATE TABLE IF NOT EXISTS nonces(nonce TEXT PRIMARY KEY);'); }
  count(name) { this.ctx.storage.sql.exec('INSERT INTO counters VALUES (?,1) ON CONFLICT(name) DO UPDATE SET value=value+1',name); }
  flag(name) { return JSON.parse(this.ctx.storage.sql.exec('SELECT value FROM flags WHERE name=?',name).toArray()[0]?.value??'null'); }
  take(name) { const value=this.flag(name);this.ctx.storage.sql.exec('DELETE FROM flags WHERE name=?',name);return value; }
  async fetch(request) {
    const url=new URL(request.url), path=url.pathname;
    if(path==='/test/snapshot') return Response.json({
      counts:Object.fromEntries(this.ctx.storage.sql.exec('SELECT name,value FROM counters').toArray().map(row=>[row.name,row.value])),
      posts:this.ctx.storage.sql.exec('SELECT * FROM posts').toArray().map(row=>({...row,body:JSON.parse(row.body)})),
      outbox:this.ctx.storage.sql.exec('SELECT * FROM outbox').toArray(),
      frames:this.ctx.storage.sql.exec('SELECT * FROM frames').toArray().map(row=>({...row,body:JSON.parse(row.body)})),
      sockets:{gateway:this.ctx.getWebSockets('gateway').length,bridge:this.ctx.getWebSockets('bridge').length}});
    if(path==='/test/flags') { const input=await request.json();for(const [name,value] of Object.entries(input)) this.ctx.storage.sql.exec('INSERT OR REPLACE INTO flags VALUES (?,?)',name,JSON.stringify(value));return Response.json({ok:true}); }
    if(path==='/test/outbox') { const input=await request.json();this.ctx.storage.sql.exec('INSERT OR REPLACE INTO outbox VALUES (?,?,?,?,NULL)',input.id,JSON.stringify(input.event??{}),input.operation??'publish',input.messageId??null);return Response.json({ok:true}); }
    if(path==='/test/clear-outbox') { this.ctx.storage.sql.exec('DELETE FROM outbox');return Response.json({ok:true}); }
    if(path==='/test/parents') { const input=await request.json();this.ctx.storage.sql.exec('INSERT OR REPLACE INTO parents VALUES (?,?)',input.id,JSON.stringify(input));return Response.json({ok:true}); }
    if(path==='/test/inject') { const input=await request.json();for(const ws of this.ctx.getWebSockets(input.kind)) { if(ws.readyState!==1)continue;try { if(input.close)ws.close(input.close,'Fixture shutdown');else ws.send(JSON.stringify(input.packet)); } catch {} }return Response.json({ok:true}); }
    if(path==='/discord/community-feed') {
      const timestamp=request.headers.get('X-SkyMyce-Timestamp'),nonce=request.headers.get('X-SkyMyce-Nonce'),signature=request.headers.get('X-SkyMyce-Signature');
      const text=await request.text(), hash=hex(await crypto.subtle.digest('SHA-256',encode.encode(text)));
      const key=await crypto.subtle.importKey('raw',encode.encode('${bridgeSecret}'),{name:'HMAC',hash:'SHA-256'},false,['sign']);
      const expected=hex(await crypto.subtle.sign('HMAC',key,encode.encode('sky-myce-community-feed\\nPOST\\n/discord/community-feed\\n'+timestamp+'\\n'+nonce+'\\n'+hash)));
      const raw=JSON.parse(text);
      if(request.method!=='POST'||signature!==expected||!/^\\d{13}$/.test(timestamp??'')||Math.abs(Date.now()-Number(timestamp))>60000||!/^[a-f0-9]{32}$/.test(nonce??'')||raw.channelId!==rng||Object.keys(raw).some(key=>!['action','channelId','id','claim','operation','messageId','receipts'].includes(key))) return new Response(null,{status:401});
      try { this.ctx.storage.sql.exec('INSERT INTO nonces VALUES (?)',nonce); } catch { return new Response(null,{status:409}); }
      this.count('signedFeedRequests');
      if(raw.action==='claim') {
        this.count('claims');
        const invalid=this.take('invalidClaim');
        if(invalid==='oversize')return new Response(JSON.stringify({ok:true,padding:'x'.repeat(17000),deliveries:[]}));
        if(invalid==='utf8')return new Response(new Uint8Array([123,34,120,34,58,34,255,34,125]));
        if(invalid==='channel')return Response.json({ok:true,deliveries:[{id:'a'.repeat(32),claim:'a'.repeat(43),operation:'delete',channelId:chat}]});
        if(invalid==='confirmation')return Response.json({ok:true,deliveries:[],acknowledged:['9'.repeat(32)]});
        const receipts=raw.receipts??[], acknowledged=[];
        if(!Array.isArray(receipts)||receipts.length>32||receipts.some(receipt=>!/^[a-f0-9]{32}$/.test(receipt?.id??'')||!/^\\d{17,20}$/.test(receipt?.messageId??'')))return new Response(null,{status:400});
        for(const receipt of receipts)if(this.ctx.storage.sql.exec('SELECT id FROM published WHERE id=? AND message_id=? AND channel=?',receipt.id,receipt.messageId,raw.channelId).toArray().length)acknowledged.push(receipt.id);
        this.count('receiptConfirmations:'+acknowledged.length);
        const deliveries=[];
        for(const row of this.ctx.storage.sql.exec('SELECT * FROM outbox ORDER BY id LIMIT 3')) {
          const claim=hex(crypto.getRandomValues(new Uint8Array(16)).buffer)+'a'.repeat(11);
          this.ctx.storage.sql.exec('UPDATE outbox SET claim=? WHERE id=?',claim,row.id);
          deliveries.push({id:row.id,claim,operation:row.operation,channelId:rng,...(row.operation==='publish'?{event:JSON.parse(row.event)}:{}),...(row.message_id?{messageId:row.message_id}:{})});
        }
        return Response.json({ok:true,deliveries,acknowledged});
      }
      if(raw.action!=='ack')return new Response(null,{status:400});
      this.count('ackAttempts');
      const row=this.ctx.storage.sql.exec('SELECT * FROM outbox WHERE id=? AND claim=?',raw.id,raw.claim).toArray()[0];
      if(!row||row.operation!==raw.operation)return new Response(null,{status:409});
      if(this.take('failAck'))return new Response(null,{status:503});
      this.count(raw.operation==='delete'?'deleteAcks':'publishAcks');
      if(raw.operation==='publish')this.ctx.storage.sql.exec('INSERT OR REPLACE INTO published VALUES (?,?,?)',raw.id,raw.messageId,raw.channelId);
      else this.ctx.storage.sql.exec('DELETE FROM published WHERE id=? AND channel=?',raw.id,raw.channelId);
      if(raw.operation==='publish'&&this.take('revokeAtAck')) {
        this.ctx.storage.sql.exec("UPDATE outbox SET operation='delete',message_id=?,claim=NULL WHERE id=?",raw.messageId,raw.id);
        return Response.json({ok:true,deleteRequired:true});
      }
      this.ctx.storage.sql.exec('DELETE FROM outbox WHERE id=?',raw.id);
      if(raw.operation==='publish'&&this.take('losePublishAckResponse'))return new Response(null,{status:503});
      if(raw.operation==='delete'&&this.take('loseDeleteAckResponse'))return new Response(null,{status:503});
      return Response.json({ok:true,deleteRequired:false});
    }
    if(path==='/api/v10/gateway/bot')return Response.json({url:'wss://gateway.discord.gg/',session_start_limit:{remaining:100,reset_after:60000}});
    if(path==='/api/v10/users/@me')return request.headers.get('Authorization')==='Bot ${botToken}' ? Response.json({id:this.take('wrongBot')?human:bot,bot:true}) : new Response(null,{status:401});
    if(path.startsWith('/api/v10/channels/')) {
      if(request.headers.get('Authorization')!=='Bot ${botToken}')return new Response(null,{status:401});
      const [,,,,channel,part,id]=path.split('/');
      if(!part) { this.count('channelChecks');return Response.json({id:channel,guild_id:this.take('wrongGuild')?'100000000000000099':guild,type:0}); }
      if(part!=='messages')throw new Error('Unexpected REST route');
      if(!id&&request.method==='POST') {
        this.count('postAttempts');
        const limit=this.take('rateLimit');if(limit)return new Response(null,{status:429,headers:{'Retry-After':String(limit)}});
        const body=await request.json(); let row=this.ctx.storage.sql.exec('SELECT id FROM posts WHERE nonce=?',body.nonce).toArray()[0];
        if(!row) { this.count('posts');const id=String((BigInt(Date.now())-1420070400000n)<<22n)+'';this.ctx.storage.sql.exec('INSERT INTO posts VALUES (?,?,?,?,0)',body.nonce,id,channel,JSON.stringify(body));row={id}; }
        if(this.take('losePostResponse'))return new Response(null,{status:503});
        return Response.json({id:row.id,channel_id:channel,author:{id:bot}});
      }
      if(id&&request.method==='GET') {
        this.count('get:'+channel+':'+id);
        const delay=this.take('slowParent');if(delay)await new Promise(resolve=>setTimeout(resolve,delay));
        const parent=this.ctx.storage.sql.exec('SELECT body FROM parents WHERE id=?',id).toArray()[0];
        if(parent)return Response.json(JSON.parse(parent.body));
        const row=this.ctx.storage.sql.exec('SELECT * FROM posts WHERE id=? AND channel=?',id,channel).toArray()[0];
        if(!row||row.deleted)return new Response(null,{status:404});
        return Response.json({id,channel_id:channel,author:{id:this.take('wrongOwner')?'${human}':bot},embeds:JSON.parse(row.body).embeds});
      }
      if(id&&request.method==='DELETE') {
        this.count('deleteAttempts');if(this.take('failDelete'))return new Response(null,{status:403});
        const row=this.ctx.storage.sql.exec('SELECT * FROM posts WHERE id=? AND channel=?',id,channel).toArray()[0];
        if(!row||row.deleted)return new Response(null,{status:404});
        this.ctx.storage.sql.exec('UPDATE posts SET deleted=1 WHERE id=? AND channel=?',id,channel);this.count('deletes');return new Response(null,{status:204});
      }
      throw new Error('Unexpected REST method');
    }
    if(request.headers.get('Upgrade')==='websocket') {
      const kind=path==='/discord/bridge'?'bridge':'gateway';
      if(kind==='bridge') {
        const timestamp=request.headers.get('X-SkyMyce-Timestamp'),nonce=request.headers.get('X-SkyMyce-Nonce');
        const key=await crypto.subtle.importKey('raw',encode.encode('${bridgeSecret}'),{name:'HMAC',hash:'SHA-256'},false,['sign']);
        const expected=hex(await crypto.subtle.sign('HMAC',key,encode.encode('sky-myce-bridge\\nglobal-chat\\n'+timestamp+'\\n'+nonce)));
        if(request.headers.get('X-SkyMyce-Signature')!==expected||url.searchParams.get('room')!=='global-chat')return new Response(null,{status:401});
      }
      this.count(kind+'Connections');const pair=new WebSocketPair();this.ctx.acceptWebSocket(pair[1],[kind]);pair[1].serializeAttachment({kind});
      pair[1].send(JSON.stringify(kind==='gateway'?{op:10,d:{heartbeat_interval:1000}}:{type:'bridge_ready',protocol:3,room:'global-chat',globalChat:true}));
      return new Response(null,{status:101,webSocket:pair[0]});
    }
    throw new Error('Unexpected fixture request '+path);
  }
  async webSocketMessage(ws,raw) {
    const kind=ws.deserializeAttachment().kind;
    if(raw==='ping'){ws.send('pong');return;}
    const packet=JSON.parse(raw);this.ctx.storage.sql.exec('INSERT INTO frames VALUES (?,?)',kind,JSON.stringify(kind==='gateway'?{op:packet.op}:packet));
    if(kind==='gateway') {
      if(packet.op===2)ws.send(JSON.stringify({op:0,t:'READY',s:1,d:{application:{id:bot},user:{id:bot,bot:true},session_id:'community-test-session',resume_gateway_url:'wss://gateway.discord.gg/'}}));
      else if(packet.op===6)ws.send(JSON.stringify({op:0,t:'RESUMED',s:packet.d.seq,d:{}}));
      else if(packet.op===1){this.count('heartbeats');ws.send(JSON.stringify({op:11}));}
    } else if(packet.type==='discord_message')ws.send(JSON.stringify({type:'irc_result',id:packet.id,scope:'global',room:'global-chat',...(packet.part?{part:packet.part}:{})}));
  }
}
export default { async fetch(request,env) { const path=new URL(request.url).pathname;
  if(path==='/test/demand') { await env.GATEWAY_CONTROL.wake();return Response.json({ok:true}); }
  if(path.startsWith('/test/')||path==='/discord/bridge'||path==='/discord/community-feed')return env.TEST_DISCORD.getByName('fixture').fetch(request);
  return await globalChatRoute(request,env)??new Response(null,{status:404});
} };
`;
const built=await build({stdin:{contents:fixture,resolveDir:process.cwd(),sourcefile:'community-gateway-fixture.ts'},bundle:true,format:'esm',platform:'browser',external:['cloudflare:workers'],write:false,
  plugins:[{name:'synthetic-discord-transport',setup(plugin) { plugin.onLoad({filter:/global-chat-gateway\.ts$/},async args=>{
    let contents=await readFile(args.path,'utf8');const anchor='private async externalFetch(request: Request): Promise<Response> { return fetch(request); }';
    assert.equal(contents.split(anchor).length,2);contents=contents.replace(anchor,'private async externalFetch(request: Request): Promise<Response> { return (this.env as any).TEST_DISCORD.getByName("fixture").fetch(request); }');
    return {contents,loader:'ts'};
  });}}]});
const bindings={GLOBAL_CHAT_ENABLED:'true',GLOBAL_CHAT_CHANNEL_ID:chat,DISCORD_GUILD_ID:guild,USER_BOT_APPLICATION_ID:bot,USER_BOT_TOKEN:botToken,DISCORD_BRIDGE_SECRET:bridgeSecret,GLOBAL_CHAT_CONTROL_SECRET:controlSecret};
const makeRuntime=channel=>{
  const name='community-gateway-check';
  const options=convertV4MiniflareOptions({name,modules:true,script:built.outputFiles[0].text,compatibilityDate:'2026-09-21',bindings:{...bindings,...(channel?{DISCORD_RNG_CHANNEL_ID:channel}:{})},
    durableObjects:{GLOBAL_CHAT_GATEWAY:{className:'GlobalChatGateway',useSQLite:true},TEST_DISCORD:{className:'FeedFixture',useSQLite:true}},serviceBindings:{SKY_MYCE_BACKEND:name,GATEWAY_CONTROL:{name,entrypoint:'GatewayControl'}},
    outboundService:()=>{throw new Error('Live outbound traffic forbidden');}});
  options.unsafeInspectDurableObjects=true;return new Miniflare(options);
};
let mf=makeRuntime(rng);
const call=(path,body,authorization)=>mf.dispatchFetch('https://fixture.example'+path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json',...(authorization?{Authorization:authorization}:{})},body:JSON.stringify(body)});
const snapshot=async()=>await (await call('/test/snapshot')).json();
const health=async()=>await (await call('/global-chat/health')).json();
const control=async action=>{ const response=await call('/global-chat/control',{action},'Bearer '+controlSecret);assert.equal(response.status,200);if(action==='start')await call('/test/demand');return await response.json(); };
const demandTimer=setInterval(()=>{call('/test/demand').then(response=>response.body?.cancel()).catch(()=>{});},20_000);
const until=async(condition,label,timeout=8000)=>{const deadline=Date.now()+timeout;do { const result=await condition();if(result)return result;await new Promise(resolve=>setTimeout(resolve,40));}while(Date.now()<deadline);throw new Error('Timed out: '+label+'; health='+JSON.stringify(await health()));};
const storage=()=>mf.unsafeGetDurableObjectStorage('community-gateway-check','GlobalChatGateway',{name:'global-chat:'+chat});
const state=async()=>JSON.parse((await (await storage()).exec('SELECT value FROM gateway_state'))[0].value);
const poll=async()=>{await until(async()=>!((await state()).rngDue<=Date.now()),'previous feed drain finished');const before=(await snapshot()).counts.claims??0;await control('start');await until(async()=>((await snapshot()).counts.claims??0)>before,'signed account feed claim');await until(async()=>(await state()).rngDue>Date.now(),'feed drain persisted');};
const flags=input=>call('/test/flags',input);
let serial=0n;
const snowflake=()=>String(((BigInt(Date.now())-1420070400000n)<<22n)+serial++);
const event=(id,changes={})=>({id,player:'Anonymous',item:'NECRON_HANDLE',activity:'dungeon',occurredAt:Date.now(),unverified:true,rarity:'LEGENDARY',floor:'M7',...changes});
const publish=(id,changes={})=>call('/test/outbox',{id,event:event(id,changes)});
const cancel=(id,messageId)=>call('/test/outbox',{id,operation:'delete',...(messageId?{messageId}:{})});
const evict=async()=>{
  const sql=await storage(), saved=(await sql.exec('SELECT value FROM gateway_state'))[0].value;
  await call('/test/inject',{kind:'gateway',close:4000});await call('/test/inject',{kind:'bridge',close:1000});
  await until(async()=>{const current=await health();return !current.gatewayReady&&!current.bridgeReady;},'mock sockets closed');
  await control('stop');await sql.exec('UPDATE gateway_state SET value=? WHERE id=1',saved);
  await mf.unsafeEvictDurableObject('community-gateway-check','GlobalChatGateway',{name:'global-chat:'+chat,webSockets:'close'});
  await control('start');await until(async()=>{const current=await health();return current.gatewayReady&&current.bridgeReady;},'Gateway resumed after actual actor eviction');
  await until(async()=>(await state()).rngDue>Date.now(),'restart feed drain finished');
};
const replies=async()=>((await snapshot()).frames.filter(row=>row.kind==='bridge'&&row.body.type==='discord_message').map(row=>row.body));
let sequence=10;
const sendReply=async(parent,changes={})=>{
  const id=snowflake(), data={id,guild_id:guild,channel_id:chat,author:{id:human,username:'ReplySender'},content:'Reply body',message_reference:{message_id:parent,channel_id:chat},...changes};
  await call('/test/inject',{kind:'gateway',packet:{op:0,t:'MESSAGE_CREATE',s:sequence++,d:data}});
  return await until(async()=> (await replies()).find(row=>row.id===id),'reply sent through real bridge',6500);
};
try {
  await call('/global-chat/control',{action:'start'},'Bearer '+controlSecret);
  await until(async()=>((await snapshot()).counts.claims??0)>0,'initial signed RNG claim');await until(async()=>(await state()).rngDue>Date.now(),'initial feed drain finished');
  assert.equal((await health()).gatewayReady,false);assert.equal((await health()).bridgeReady,false);
  const id='a'.repeat(32);await publish(id,{displayName:'@everyone **Handle** <:hidden:123456789012345678>'});
  await call('/global-chat/control',{action:'start'},'Bearer '+controlSecret);
  await until(async()=>((await snapshot()).posts.length===1),'RNG publication while the chat gateway sleeps');
  assert.equal((await health()).gatewayReady,false);assert.equal((await health()).bridgeReady,false);
  await control('start');await until(async()=>{const current=await health();return current.gatewayReady&&current.bridgeReady;},'single existing Gateway ready');
  let result=await snapshot(), first=result.posts[0];assert.equal(result.posts.length,1);assert.equal(first.channel,rng);
  assert.equal(first.body.nonce,createHash('sha256').update('rng:'+id).digest('hex').slice(0,24));assert.equal(first.body.enforce_nonce,true);
  assert.deepEqual(first.body.allowed_mentions,{parse:[],replied_user:false});assert.match(first.body.embeds[0].description,/Anonymous/);assert.match(first.body.embeds[0].fields.find(field=>field.name==='Source').value,/unverified/i);
  assert.ok(!JSON.stringify(first.body).includes(human));assert.ok(!JSON.stringify(first.body).includes(id));assert.ok(!JSON.stringify(first.body).includes('@everyone'));
  assert.equal(result.counts.gatewayConnections,1);assert.equal(result.sockets.gateway,1);

  const lost='b'.repeat(32);await flags({failAck:true});await publish(lost);await poll();
  const savedReceipt=(await (await storage()).exec('SELECT * FROM gateway_rng_receipts WHERE id=?',lost))[0];assert.equal(savedReceipt.acknowledged,0);
  await evict();result=await snapshot();assert.equal(result.posts.length,2,'lost ACK followed by actual eviction/reclaim does not post twice');
  assert.equal((await (await storage()).exec('SELECT * FROM gateway_rng_receipts WHERE id=?',lost))[0].acknowledged,1);assert.equal(result.counts.gatewayConnections,2);

  const committed='4'.repeat(32);await flags({losePublishAckResponse:true});await publish(committed);await poll();
  assert.ok(!(await snapshot()).outbox.some(row=>row.id===committed),'account committed publish ACK before its response was lost');
  assert.equal((await (await storage()).exec('SELECT * FROM gateway_rng_receipts WHERE id=?',committed))[0].acknowledged,0);
  const confirmedPostCount=(await snapshot()).counts.postAttempts, confirmedAckCount=(await snapshot()).counts.ackAttempts;
  await flags({invalidClaim:'confirmation'});await poll();assert.equal((await (await storage()).exec('SELECT * FROM gateway_rng_receipts WHERE id=?',committed))[0].acknowledged,0,'unsolicited receipt confirmation cannot clear pending status');
  await poll();const recovered=(await (await storage()).exec('SELECT * FROM gateway_rng_receipts WHERE id=?',committed))[0];
  assert.equal(recovered.acknowledged,1,'empty delivery list plus exact receipt confirmation recovers committed publish ACK');assert.ok(recovered.expires>Date.now());
  assert.equal((await snapshot()).counts.postAttempts,confirmedPostCount,'receipt reconciliation never republishes');assert.equal((await snapshot()).counts.ackAttempts,confirmedAckCount,'receipt reconciliation does not re-ACK a removed publication');
  await (await storage()).exec('UPDATE gateway_rng_receipts SET expires=? WHERE id=?',Date.now()-1,committed);await health();
  assert.equal((await (await storage()).exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts WHERE id=?',committed))[0].count,0,'confirmed receipt returns to normal bounded expiry');

  const offline='c'.repeat(32);await flags({failAck:true});await publish(offline);await poll();
  await (await storage()).exec('UPDATE gateway_rng_receipts SET expires=? WHERE id=?',Date.now()-86_400_000,offline);
  await health();assert.equal((await (await storage()).exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts WHERE id=?',offline))[0].count,1,'unacknowledged receipt survives old expiry');
  const offlinePost=(await snapshot()).posts.find(row=>row.body.nonce===createHash('sha256').update('rng:'+offline).digest('hex').slice(0,24));
  await cancel(offline);await flags({failDelete:true});await poll();assert.equal((await snapshot()).posts.find(row=>row.id===offlinePost.id).deleted,0);assert.ok((await snapshot()).outbox.some(row=>row.id===offline));
  await flags({loseDeleteAckResponse:true});await poll();assert.equal((await snapshot()).posts.find(row=>row.id===offlinePost.id).deleted,1,'NULL messageId cancellation uses retained channel receipt');
  assert.ok(!(await snapshot()).outbox.some(row=>row.id===offline),'account committed delete ACK before its response was lost');
  assert.equal((await (await storage()).exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts WHERE id=?',offline))[0].count,0,'lost committed delete ACK cannot retain an immortal receipt');
  await cancel('d'.repeat(32));await poll();assert.ok(!(await snapshot()).outbox.some(row=>row.id==='d'.repeat(32)),'empty cancellation ACKs without a Discord post');

  await cancel(id,first.id);await flags({wrongOwner:true});await poll();assert.equal((await snapshot()).posts.find(row=>row.id===first.id).deleted,0,'human-owned message cannot be deleted');
  await poll();assert.equal((await snapshot()).posts.find(row=>row.id===first.id).deleted,1);
  await cancel(id,first.id);await poll();assert.ok(!(await snapshot()).outbox.some(row=>row.id===id),'404 deletion is idempotent');
  const raced='e'.repeat(32);await flags({revokeAtAck:true});await publish(raced);await poll();assert.equal((await snapshot()).posts.find(row=>row.body.nonce===createHash('sha256').update('rng:'+raced).digest('hex').slice(0,24)).deleted,1,'consent revocation at ACK deletes immediately');await poll();

  const nonceLost='f'.repeat(32);await flags({losePostResponse:true});await publish(nonceLost);await poll();const beforeLost=(await snapshot()).posts.length;await poll();assert.equal((await snapshot()).posts.length,beforeLost,'lost POST response reuses enforced nonce');
  const rate='1'.repeat(32);await flags({rateLimit:1337});await publish(rate);const beforeRate=(await snapshot()).counts.postAttempts;const startRate=Date.now();await poll();
  assert.ok((await state()).postDue>=startRate+1_337_000,'full Discord Retry-After persists');await control('start');assert.equal((await snapshot()).counts.postAttempts,beforeRate+1,'repeated start respects shared Discord cooldown');
  let current=await state();delete current.postDue;current.rngDue=Date.now()+10000;await (await storage()).exec('UPDATE gateway_state SET value=? WHERE id=1',JSON.stringify(current));await evict();await poll();

  for(const invalid of ['oversize','utf8','channel']) {const count=(await snapshot()).counts.postAttempts;await flags({invalidClaim:invalid});await poll();assert.equal((await snapshot()).counts.postAttempts,count,'invalid bounded account response never posts');}
  await publish('2'.repeat(32),{unverified:false});const invalidCount=(await snapshot()).counts.postAttempts;await poll();assert.equal((await snapshot()).counts.postAttempts,invalidCount,'invalid public event never posts');await call('/test/clear-outbox',{});
  await (await storage()).exec('UPDATE gateway_rng_receipts SET expires=? WHERE acknowledged=1',Date.now()-1);await health();assert.equal((await (await storage()).exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts'))[0].count,0,'acknowledged receipts expire normally');
  let sql=await storage();for(let index=0;index<33;index++)await sql.exec('INSERT INTO gateway_rng_receipts VALUES (?,?,?,?,?)',index.toString(16).padStart(32,'0'),rng,snowflake(),0,Date.now()-1);
  const committedDiscord=(await snapshot()).posts.find(row=>row.body.nonce===createHash('sha256').update('rng:'+committed).digest('hex').slice(0,24)).id;
  await sql.exec('INSERT INTO gateway_rng_receipts VALUES (?,?,?,?,?)',committed,rng,committedDiscord,0,Date.now()-1);
  current=await state();current.rngReceiptCursor='';await sql.exec('UPDATE gateway_state SET value=? WHERE id=1',JSON.stringify(current));await evict();
  sql=await storage();assert.equal((await sql.exec('SELECT acknowledged FROM gateway_rng_receipts WHERE id=?',committed))[0].acknowledged,0,'first reconciliation batch is occupied by32 unmatched receipts');
  await poll();assert.equal((await sql.exec('SELECT acknowledged FROM gateway_rng_receipts WHERE id=?',committed))[0].acknowledged,1,'persisted rotating cursor reaches later receipt confirmations');
  assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts WHERE acknowledged=0'))[0].count,33,'rotation does not drop unmatched pending receipts');await sql.exec('DELETE FROM gateway_rng_receipts');
  for(let index=0;index<1024;index++)await sql.exec('INSERT INTO gateway_rng_receipts VALUES (?,?,?,?,?)',index.toString(16).padStart(32,'0'),rng,snowflake(),0,Date.now()-1);
  await publish('3'.repeat(32));const capacityPosts=(await snapshot()).counts.postAttempts;await poll();assert.equal((await snapshot()).counts.postAttempts,capacityPosts,'receipt capacity fails closed before publication');assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM gateway_rng_receipts'))[0].count,1024,'pending receipts are never evicted');await call('/test/clear-outbox',{});await sql.exec('DELETE FROM gateway_rng_receipts');

  const own=snowflake();await call('/test/parents',{id:own,channel_id:chat,guild_id:guild,author:{id:bot,bot:true},embeds:[{author:{name:'MinecraftPlayer'},description:'Escaped \\*text\\*',footer:{text:'ignored private footer'},fields:[{name:'Reply context',value:'ignored recursive reply'}]}]});
  assert.deepEqual((await sendReply(own)).reply,{message_id:own,from:'MinecraftPlayer',text:'Escaped *text*'},'uncached own card recovers original Minecraft sender/body');
  const ordinary=snowflake();await call('/test/parents',{id:ordinary,channel_id:chat,guild_id:guild,author:{id:human,username:'OriginalSender'},content:'Original content'});assert.equal((await sendReply(ordinary)).reply.text,'Original content');
  const missing=snowflake();assert.equal((await sendReply(missing)).reply.from,'Unavailable message');
  const foreign=snowflake();await call('/test/parents',{id:foreign,channel_id:rng,guild_id:guild,author:{id:human,username:'Secret'},content:'private'});assert.equal((await sendReply(foreign)).reply.from,'Unavailable message','mismatched REST audience cannot quote content');
  const declared=snowflake();const lookup=(await snapshot()).counts['get:'+chat+':'+declared]??0;
  const foreignPacket=await sendReply(declared,{message_reference:{message_id:declared,channel_id:rng}});assert.ok(!foreignPacket.reply);assert.equal((await snapshot()).counts['get:'+chat+':'+declared]??0,lookup,'declared other-channel reply never fetched');
  const slow=snowflake();await flags({slowParent:4000});const heartbeatBefore=(await snapshot()).counts.heartbeats, connectionsBefore=(await snapshot()).counts.gatewayConnections;
  assert.equal((await sendReply(slow)).reply.from,'Unavailable message');assert.ok((await snapshot()).counts.heartbeats>heartbeatBefore,'slow reply lookup leaves heartbeat queue active');assert.equal((await snapshot()).counts.gatewayConnections,connectionsBefore,'slow lookup never forces a second Gateway');
  await control('stop');
  clearInterval(demandTimer);
  await mf.dispose();mf=makeRuntime(rng);
  await flags({wrongBot:true});await publish('9'.repeat(32));
  await call('/global-chat/control',{action:'start'},'Bearer '+controlSecret);
  await until(async()=>(await health()).rngFeedLastError==='rng_feed_unavailable','wrong REST bot identity rejected');
  assert.equal((await snapshot()).posts.length,0,'idle RNG feed never publishes with another bot identity');
  assert.equal((await health()).gatewayReady,false);
  await call('/global-chat/control',{action:'start'},'Bearer '+controlSecret);
  await until(async()=>(await snapshot()).posts.length===1,'verified REST bot publishes while chat stays idle');
  assert.equal((await health()).gatewayReady,false);assert.equal((await health()).bridgeReady,false);
  await control('stop');
  for(const channel of [undefined,chat]) {await mf.dispose();mf=makeRuntime(channel);await control('start');await until(async()=>(await health()).gatewayReady,'disabled feed Gateway ready');assert.equal((await health()).rngFeedConfigured,false);await new Promise(resolve=>setTimeout(resolve,650));assert.equal((await snapshot()).counts.claims??0,0,'unset/conflicting feed stays disabled');await control('stop');}
  console.log('Community Gateway workerd checks passed: exact HMAC, isolated channel, nonce/receipts, eviction, consent cancellation, capacity, cooldown, bounded replies and heartbeats.');
} finally { clearInterval(demandTimer); await mf.dispose(); }
