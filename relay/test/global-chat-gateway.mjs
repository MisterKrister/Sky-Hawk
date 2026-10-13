import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';

const formatterBuild=await build({entryPoints:['src/gateway-chat.ts'],bundle:true,format:'esm',write:false});
const {bounded,bridgeOperationApplied,minecraftReactionFooter,discordPacket,embedPacket,minecraftContent,discordMediaKey,discordMessageLinks,discordMedia,minecraftCard,discordOperation,validBridgeOperation}=await import('data:text/javascript;base64,'+Buffer.from(formatterBuild.outputFiles[0].text).toString('base64'));

assert.equal(bounded('a👩‍👩‍👧‍👦b', 8), 'a', 'whole emoji clusters survive truncation');
assert.equal(bounded('a<a:dance:100000000000000003>b', 10), 'a', 'custom emoji tokens stay whole');
assert.equal(bridgeOperationApplied({bodyRevision:2,reactionRevisions:{'🔥':5}}, {operation:'edit',revision:3}), false);
assert.equal(bridgeOperationApplied({deletedRevision:1}, {operation:'reaction',revision:9,emoji:'🔥'}), true);
assert.equal(minecraftReactionFooter({reactions:[{emoji:'🔥',count:2},{emoji:'invalid',count:3},{emoji:'👀',count:999}]}, 'global'), 'Minecraft · Sky-Myce Global · 🔥 2');
// Production code runs in workerd with real SQLite, outbound WebSockets, alarms and eviction.
// Only Discord's transport is replaced in this in-memory fixture bundle. No live messages are sent.
const guild = '1552053091493486594', channel = '1553536927498506270', bot = '1553617043914367068';
const secret = 'test-only-global-chat-control-secret-32bytes';
const rich=discordPacket({id:'100000000000000001',guild_id:guild,channel_id:channel,author:{id:'100000000000000002',username:'Other bot',bot:true},
 content:'Hello 😀 <a:dance:100000000000000003> <#100000000000000004>',embeds:[{title:'Title',description:'Description',fields:[{name:'Field',value:'Value'}],image:{url:'https://example.com/image'},url:'javascript:unsafe'}],
 attachments:[{filename:'picture.png',url:'https://example.com/picture.png'}],message_reference:{message_id:'100000000000000005',channel_id:channel},referenced_message:{channel_id:channel,author:{username:'Original'},content:'Reply context'}},guild,channel,bot);
assert.equal(rich.text,'Hello 😀 <a:dance:100000000000000003> #channel\nTitle\nDescription\nField\nValue\nhttps://example.com/image\npicture.png: https://example.com/picture.png');
assert.deepEqual(rich.reply,{message_id:'100000000000000005',from:'Original',text:'Reply context'});
assert.equal(await minecraftContent({type:'irc_message',scope:'party',room:'global-chat',source:'minecraft',id:'a'.repeat(32),message_id:'a'.repeat(32),from:'Alice',text:'private'}),null);
const replyMessage={id:'100000000000000010',guild_id:guild,channel_id:channel,author:{id:'100000000000000011',username:'Reply author'},
 content:'https://example.com',embeds:[],message_reference:{message_id:'100000000000000005',channel_id:channel},referenced_message:rich.referenced_message};
const replyPreview=embedPacket(replyMessage,{...replyMessage,embeds:[{description:'New reply preview'}]},guild,channel,bot);
assert.equal(replyPreview.part,'embeds');assert.equal(replyPreview.text,'New reply preview');
assert.deepEqual(replyPreview.mentions,[]);assert.ok(!Object.hasOwn(replyPreview,'reply'),'embed continuation cannot carry duplicate reply context');

const unsignedGif=`https://cdn.discordapp.com/attachments/${channel}/100000000000000021/dance.gif`;
const signedGif=`${unsignedGif}?ex=7fffffff&is=6550aabb&hm=${'a'.repeat(64)}`;
const mediaGif=signedGif.replace('cdn.discordapp.com','media.discordapp.net');
const gifMessage={...replyMessage,content:unsignedGif,attachments:[{filename:'dance.gif',url:signedGif}],
 embeds:[{url:unsignedGif,image:{url:signedGif},thumbnail:{url:mediaGif}}]};
assert.equal(discordPacket(gifMessage,guild,channel,bot).text,signedGif,'content, embed and attachment share one actual signed GIF URL');
assert.deepEqual(discordMessageLinks(gifMessage),[unsignedGif],'preview identities retain no signing parameters');
assert.equal(discordMediaKey(signedGif),discordMediaKey(mediaGif));
assert.equal(discordPacket({...gifMessage,content:`Look at [this GIF](${unsignedGif})`},guild,channel,bot).text,`Look at [this GIF](${signedGif})`);
const differentQueries={...replyMessage,content:'',embeds:[{url:'https://example.com/image.gif?ex=1',image:{url:'https://example.com/image.gif?ex=2'},thumbnail:{url:'https://example.com/image.gif?ex=1'}}]};
assert.equal(discordPacket(differentQueries,guild,channel,bot).text,'https://example.com/image.gif?ex=1\nhttps://example.com/image.gif?ex=2','ordinary query variants remain different links');
assert.notEqual(discordMediaKey(unsignedGif+'?width=200'),discordMediaKey(unsignedGif+'?width=400'),'non-signing CDN query values retain their semantics');
assert.notEqual(discordMediaKey('https://cdn.discordapp.com/avatars/1/image.gif?ex=1'),discordMediaKey('https://cdn.discordapp.com/avatars/1/image.gif?ex=2'),'non-attachment CDN paths retain all parameters');
const beforeGif={...gifMessage,embeds:[]};
assert.equal(embedPacket(beforeGif,gifMessage,guild,channel,bot),null,'URL-only delayed GIF preview cannot repeat the existing content/attachment');
const usefulGif=embedPacket(beforeGif,{...gifMessage,embeds:[{title:'GIF information',description:'A genuine additional description',url:unsignedGif,image:{url:signedGif}}]},guild,channel,bot);
assert.equal(usefulGif.text,'GIF information\nA genuine additional description');
assert.equal(usefulGif.part,'embeds');assert.deepEqual(usefulGif.mentions,[]);assert.ok(!Object.hasOwn(usefulGif,'reply'));
const distinctGif=embedPacket(beforeGif,{...gifMessage,embeds:[{url:unsignedGif,image:{url:unsignedGif.replace('dance.gif','second.gif')}}]},guild,channel,bot);
assert.equal(distinctGif.text,unsignedGif.replace('dance.gif','second.gif'),'a real additional media URL is preserved');
// The original content is the view page; Discord's separate gifv fields supply actual media URLs.
const tenorPage='https://tenor.com/view/sample-animation-gif-123456789';
const tenorGif='https://media1.tenor.com/m/sampleAAAAC/sample.gif';
const tenorVideo='https://media.tenor.com/sampleAAAPo/sample.mp4';
const tenorEmbed={type:'gifv',url:tenorPage,video:{url:tenorVideo,width:400,height:300},thumbnail:{url:tenorGif,width:400,height:300}};
const tenorBefore={...replyMessage,content:tenorPage,embeds:[]};
const tenorMessage={...tenorBefore,embeds:[tenorEmbed]};
const tenorExpected=[{id:'media-0',type:'gif',url:tenorGif,width:400,height:300}];
const tenorPacket=discordPacket(tenorMessage,guild,channel,bot);
assert.equal(tenorPacket.text,`${tenorPage}\n${tenorGif}`);
assert.deepEqual(tenorPacket.media,tenorExpected,'gifv selects the actual supplied GIF, not its MP4 variant');
const tenorContinuation=embedPacket(tenorBefore,tenorMessage,guild,channel,bot);
assert.equal(tenorContinuation.text,tenorGif);assert.deepEqual(tenorContinuation.media,tenorExpected);
assert.deepEqual(discordMedia({...tenorMessage,content:tenorGif}),tenorExpected,'a direct GIF and its embed stay one item');
assert.deepEqual(discordMedia({embeds:[{...tenorEmbed,type:'video'}]}),
 [{id:'media-0',type:'video',url:tenorVideo,thumbnailUrl:tenorGif,width:400,height:300}],'ordinary video keeps browser playback and its GIF poster');
for(const invalid of ['http://media1.tenor.com/sample.gif','https://localhost/sample.gif','https://user:password@media1.tenor.com/sample.gif'])
 assert.deepEqual(discordMedia({embeds:[{...tenorEmbed,thumbnail:{url:invalid}}]}),
  [{id:'media-0',type:'video',url:tenorVideo,width:400,height:300}],'unsafe thumbnail cannot replace a video');
const tenorPoster='https://media1.tenor.com/sample.png';
assert.deepEqual(discordMedia({embeds:[{...tenorEmbed,thumbnail:{url:tenorPoster}}]}),
 [{id:'media-0',type:'video',url:tenorVideo,thumbnailUrl:tenorPoster,width:400,height:300}],'gifv without actual GIF retains video and poster');
const videoMedia='https://example.com/movie.mp4', videoPoster='https://example.com/poster.png';
assert.deepEqual(discordMedia({content:videoMedia,embeds:[{video:{url:videoMedia,width:1280,height:720},thumbnail:{url:videoPoster}}]}),
 [{id:'media-0',type:'video',url:videoMedia,thumbnailUrl:videoPoster,width:1280,height:720}]);
const namedVideo=discordMedia({attachments:[{id:'100000000000000002',url:videoMedia,filename:'a'.repeat(200),content_type:'video/mp4'}],embeds:[{video:{url:videoMedia},thumbnail:{url:videoPoster}}]});
assert.equal(namedVideo.length,1);assert.equal(namedVideo[0].name.length,80);assert.equal(namedVideo[0].thumbnailUrl,videoPoster);
assert.deepEqual(discordMedia({embeds:[{thumbnail:{url:videoPoster}}]}),[{id:'media-0',type:'image',url:videoPoster}]);
const plainMc={type:'irc_message',scope:'global',room:'global-chat',source:'minecraft',id:'a'.repeat(32),message_id:'a'.repeat(32),from:'Steve',text:'hello'};
assert.equal((await minecraftContent(plainMc)).content,undefined);assert.equal((await minecraftContent(plainMc)).embeds[0].author.name,'Steve');assert.equal((await minecraftContent(plainMc)).embeds[0].description,'hello');
assert.equal((await minecraftContent({...plainMc,text:'*'.repeat(1024)})).embeds[0].author.name,'Steve');
const namedMention='100000000000000099';
const mentionCard=await minecraftContent({...plainMc,text:`@everyone @here @Linked <@${bot}> <@&${bot}>`,mentions:[namedMention,namedMention]});
assert.equal(mentionCard.content,`<@${namedMention}>`);
assert.deepEqual(mentionCard.allowed_mentions,{parse:[],users:[namedMention],replied_user:false});
assert.equal((await minecraftContent({...plainMc,text:`@everyone <@${bot}>`})).content,undefined);
assert.deepEqual((await minecraftContent({...plainMc,text:'@everyone @here'})).allowed_mentions,{parse:[],replied_user:false});
assert.equal(minecraftCard({...plainMc,scope:'room',room:'testing',mentions:[namedMention]},'room','testing').content,undefined);
for(const mentions of ['everyone',['everyone'],[null],Array(9).fill(namedMention)])assert.equal(await minecraftContent({...plainMc,mentions}),null);


const fixture = `
import { DurableObject } from 'cloudflare:workers';
import production from './src/index';
export { RelayRoom, GlobalChatGateway, GatewayControl } from './src/index';
export class GatewayFixture extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    ctx.storage.sql.exec('CREATE TABLE IF NOT EXISTS frames (kind TEXT NOT NULL, value TEXT NOT NULL); CREATE TABLE IF NOT EXISTS posts (nonce TEXT PRIMARY KEY, id TEXT NOT NULL, body TEXT NOT NULL); CREATE TABLE IF NOT EXISTS counters (name TEXT PRIMARY KEY, value INTEGER NOT NULL); CREATE TABLE IF NOT EXISTS resume_events (position INTEGER PRIMARY KEY, packet TEXT NOT NULL);');
  }
  count(name) { this.ctx.storage.sql.exec('INSERT INTO counters VALUES (?,1) ON CONFLICT(name) DO UPDATE SET value=value+1',name); }
  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === '/test/snapshot') return Response.json({ frames: this.ctx.storage.sql.exec('SELECT kind,value FROM frames').toArray().map(row=>({...row,value:JSON.parse(row.value)})), posts: this.ctx.storage.sql.exec('SELECT id,body FROM posts').toArray().map(row=>({...row,body:JSON.parse(row.body)})), counters: this.ctx.storage.sql.exec('SELECT name,value FROM counters').toArray(), sockets: { gateway: this.ctx.getWebSockets('gateway').length, bridge: this.ctx.getWebSockets('bridge').length } });
    if (url.pathname === '/test/inject') {
      const input = await request.json();
      for (const ws of this.ctx.getWebSockets(input.kind)) {
        if(ws.readyState!==1)continue;
        try { if (input.close) ws.close(input.close,'Fixture close');
        else ws.send(typeof input.packet==='string' ? input.packet : JSON.stringify(input.packet)); } catch { /* Timed-out fixture upgrades may already be closing. */ }
      }
      return Response.json({ok:true});
    }
    if (url.pathname === '/test/reset') { this.ctx.storage.sql.exec('DELETE FROM frames; DELETE FROM posts; DELETE FROM counters;'); return Response.json({ok:true}); }
    if (url.pathname === '/test/replay-next') {
      const packets = await request.json();
      this.ctx.storage.sql.exec('DELETE FROM resume_events');
      packets.forEach((packet,position)=>this.ctx.storage.sql.exec('INSERT INTO resume_events VALUES (?,?)',position,JSON.stringify(packet)));
      return Response.json({ok:true});
    }
    if (url.pathname === '/test/limit-next') {
      const input=await request.json();
      this.ctx.storage.sql.exec('INSERT OR REPLACE INTO counters VALUES (?,1)',input.kind==='post'?'limitNextPost':'limitNextBridge');
      if(input.kind==='post') this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('postRetryAfter',?)",input.retryAfter??'1');
      return Response.json({ok:true});
    }
    if (url.pathname === '/test/delay-next-post') { this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('delayNextPost',1)");return Response.json({ok:true}); }
    if (url.pathname === '/test/exhaust-next-post') { this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('exhaustNextPost',1)");return Response.json({ok:true}); }
    if (url.pathname === '/test/delay-next-bridge') { this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('delayNextBridge',1)");return Response.json({ok:true}); }
    if (url.pathname === '/test/stall-next-bridge') { this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('stallNextBridge',1)");return Response.json({ok:true}); }
    if (url.pathname === '/api/v10/gateway/bot') return Response.json({url:'wss://gateway.discord.gg/',session_start_limit:{remaining:100,reset_after:60000}});
    if (url.pathname === '/api/v10/channels/${channel}/messages') {
      if (request.headers.get('Authorization') !== 'Bot test-only-user-bot-token-32bytes-long') return new Response(null,{status:401});
      this.count('postCalls');
      if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='delayNextPost'").toArray()[0]?.value) {
        this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='delayNextPost'");await new Promise(resolve=>setTimeout(resolve,2500));
      }
      if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='limitNextPost'").toArray()[0]?.value) {
        this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='limitNextPost'");
        const retry=this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='postRetryAfter'").toArray()[0]?.value??1;
        return Response.json({retry_after:retry},{status:429,headers:{'Retry-After':String(retry)}});
      }
      const body = await request.json();
      let row = this.ctx.storage.sql.exec('SELECT id FROM posts WHERE nonce=?',body.nonce).toArray()[0];
      if (!row) {
        const id = String((BigInt(Date.now())-1420070400000n)<<22n);
        this.ctx.storage.sql.exec('INSERT INTO posts VALUES (?,?,?)',body.nonce,id,JSON.stringify(body)); row={id};
      }
      const exhausted=this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='exhaustNextPost'").toArray()[0]?.value;
      if(exhausted)this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='exhaustNextPost'");
      return Response.json({id:row.id,channel_id:'${channel}',author:{id:'${bot}'}},{headers:exhausted?{'X-RateLimit-Remaining':'0','X-RateLimit-Reset-After':'1337'}:{}});
    }

    if (url.pathname.startsWith('/api/v10/channels/${channel}/messages/')) {
      if(request.headers.get('Authorization')!=='Bot test-only-user-bot-token-32bytes-long')return new Response(null,{status:401});
      const id=url.pathname.split('/')[6], own=this.ctx.storage.sql.exec('SELECT body FROM posts WHERE id=?',id).toArray()[0];
      const human=this.ctx.storage.sql.exec('SELECT packet FROM resume_events WHERE position=-1').toArray()[0];
      const knownHuman=human&&JSON.parse(human.packet).id===id?JSON.parse(human.packet):undefined;
      const deleted=this.ctx.storage.sql.exec('SELECT value FROM counters WHERE name=?','deleted:'+id).toArray()[0]?.value;
      if(deleted||!own&&!knownHuman)return new Response(null,{status:404});
      if(request.method==='GET') {
        if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='delayNextReply'").toArray()[0]?.value) {
          this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='delayNextReply'");this.count('replyLookups');
          await new Promise(resolve=>setTimeout(resolve,1500));this.count('replyLookupsFinished');
        }
        return Response.json({... (own?JSON.parse(own.body):knownHuman),id,channel_id:'${channel}',author:{id:own?'${bot}':knownHuman.author.id}});
      }
      const patch=request.method==='PATCH'?await request.json():undefined;
      this.ctx.storage.sql.exec('INSERT INTO frames VALUES (?,?)','rest',JSON.stringify({method:request.method,path:url.pathname,...(patch?{body:patch}:{})}));
      if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='limitNextOperation'").toArray()[0]?.value){
        this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='limitNextOperation'");return new Response(null,{status:429,headers:{'Retry-After':'1'}});
      }
      if(url.pathname.includes('/reactions/'))return new Response(null,{status:204});
      if(!own)return new Response(null,{status:403});
      if(patch)this.ctx.storage.sql.exec('UPDATE posts SET body=? WHERE id=?',JSON.stringify({...JSON.parse(own.body),...patch}),id);
      if(request.method==='DELETE')this.ctx.storage.sql.exec('INSERT OR REPLACE INTO counters VALUES (?,1)','deleted:'+id);
      return request.method==='DELETE'?new Response(null,{status:204}):Response.json({id,channel_id:'${channel}',author:{id:'${bot}'}});
    }
    if(url.pathname==='/test/human-source'){const input=await request.json();this.ctx.storage.sql.exec('INSERT OR REPLACE INTO resume_events VALUES (-1,?)',JSON.stringify(input));return Response.json({ok:true});}
    if(url.pathname==='/test/hold-capabilities'){const input=await request.json();this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('holdCapabilities',?)",input.hold===false?0:1);return Response.json({ok:true});}
    if(url.pathname==='/test/delay-next-reply'){this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('delayNextReply',1)");return Response.json({ok:true});}
    if(url.pathname==='/test/limit-next-operation'){this.ctx.storage.sql.exec("INSERT OR REPLACE INTO counters VALUES ('limitNextOperation',1)");return Response.json({ok:true});}
    if (request.headers.get('Upgrade') === 'websocket') {
      const kind=url.pathname==='/discord/bridge'?'bridge':'gateway';
      if (kind==='bridge') {
        this.count('bridgeAttempts');
        if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='delayNextBridge'").toArray()[0]?.value) {
          this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='delayNextBridge'");this.count('delayedBridgeHandshakes');
          await new Promise(resolve=>setTimeout(resolve,2500));
        }
        if(this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='stallNextBridge'").toArray()[0]?.value) {
          this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='stallNextBridge'");this.count('stalledBridgeHandshakes');
          await new Promise(resolve=>setTimeout(resolve,11500));
        }
        const stamp=request.headers.get('X-SkyMyce-Timestamp'),nonce=request.headers.get('X-SkyMyce-Nonce'),signature=request.headers.get('X-SkyMyce-Signature');
        const key=await crypto.subtle.importKey('raw',new TextEncoder().encode('test-only-discord-bridge-secret-32bytes'),{name:'HMAC',hash:'SHA-256'},false,['sign']);
        const digest=await crypto.subtle.sign('HMAC',key,new TextEncoder().encode('sky-myce-bridge\\nglobal-chat\\n'+stamp+'\\n'+nonce));
        const expected=[...new Uint8Array(digest)].map(b=>b.toString(16).padStart(2,'0')).join('');
        if(signature!==expected||!/^[a-f0-9]{32}$/.test(nonce)||url.searchParams.get('room')!=='global-chat') return new Response(null,{status:401});
        this.count('signedBridgeConnections');
      }
      this.count(kind+'Connections');
      const pair=new WebSocketPair(); this.ctx.acceptWebSocket(pair[1],[kind]); pair[1].serializeAttachment({kind});
      pair[1].send(JSON.stringify(kind==='gateway'?{op:10,d:{heartbeat_interval:1000}}:{type:'bridge_ready',protocol:3,room:'global-chat',globalChat:true}));
      return new Response(null,{status:101,webSocket:pair[0]});
    }
    throw new Error('Unexpected fixture request '+url.pathname);
  }
  async webSocketMessage(ws, raw) {
    const kind=ws.deserializeAttachment().kind;
    if(raw==='ping') {ws.send('pong');return;}
    const packet=JSON.parse(raw);
    this.ctx.storage.sql.exec('INSERT INTO frames VALUES (?,?)',kind,JSON.stringify(kind==='gateway'?{op:packet.op,seq:packet.d?.seq}:packet));
    if(kind==='gateway') {
      if(packet.op===2) {
        if(packet.d.token!=='test-only-user-bot-token-32bytes-long'||packet.d.intents!==(512|1024|32768)) throw new Error('Wrong identify');
        const id=String((BigInt(Date.now())-1420070400000n)<<22n);
        ws.send(JSON.stringify({op:0,t:'MESSAGE_CREATE',s:9,d:{id,guild_id:'${guild}',channel_id:'${channel}',author:{id:'100000000000000001',username:'User'},content:'Ignore before READY'}}));
        ws.send(JSON.stringify({op:0,t:'READY',s:10,d:{application:{id:'${bot}'},user:{id:'${bot}',bot:true},session_id:'test-session',resume_gateway_url:'wss://gateway.discord.gg/'}}));
      } else if(packet.op===6) {
        let sequence=Math.max(10,packet.d.seq);
        for(const row of this.ctx.storage.sql.exec('SELECT packet FROM resume_events ORDER BY position')) {
          const replay=JSON.parse(row.packet);sequence=Math.max(sequence,replay.s);ws.send(row.packet);
        }
        this.ctx.storage.sql.exec('DELETE FROM resume_events');
        ws.send(JSON.stringify({op:0,t:'RESUMED',s:sequence,d:{}}));
      }
      else if(packet.op===1) ws.send(JSON.stringify({op:11}));
    } else if(packet.type==='bridge_capabilities') { if(!this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='holdCapabilities'").toArray()[0]?.value)ws.send(JSON.stringify({type:'irc_result',scope:'global',room:'global-chat',id:packet.id}));
    } else if(packet.type==='discord_operation') { ws.send(JSON.stringify({type:'irc_result',scope:'global',room:'global-chat',id:packet.id}));
    } else if(packet.type==='discord_message') {
      const limited=this.ctx.storage.sql.exec("SELECT value FROM counters WHERE name='limitNextBridge'").toArray()[0]?.value;
      if(limited)this.ctx.storage.sql.exec("UPDATE counters SET value=0 WHERE name='limitNextBridge'");
      ws.send(JSON.stringify({type:'irc_result',scope:'global',id:packet.id,...(packet.part?{part:packet.part}:{}),...(limited?{error:'rate_limited'}:{})}));
    }
  }
}
export default {async fetch(request,env) {
 const path=new URL(request.url).pathname;
 if(path==='/test/demand') { await env.GATEWAY_CONTROL.wake(); return Response.json({ok:true}); }
 if(path==='/test/expire-demand') { await env.GLOBAL_CHAT_GATEWAY.getByName('global-chat:${channel}').testOnlyExpireDemand(); return Response.json({ok:true}); }
 if(path.startsWith('/test/')||path==='/discord/bridge') return env.TEST_DISCORD.getByName('fixture').fetch(request);
 return production.fetch(request,env);
}};
`;
const built = await build({ stdin: { contents: fixture, resolveDir: process.cwd(), sourcefile: 'gateway-runtime-fixture.ts' }, bundle: true,
  format: 'esm', platform: 'browser', external: ['cloudflare:workers'], write: false,
  plugins: [{ name: 'fixture-discord-transport', setup(plugin) {
    plugin.onLoad({filter:/global-chat-gateway\.ts$/}, async args => {
      let contents = await readFile(args.path, 'utf8');
      const anchor = 'private async externalFetch(request: Request): Promise<Response> { return fetch(request); }';
      assert.equal(contents.split(anchor).length, 2);
      contents = contents.replace(anchor, 'private async externalFetch(request: Request): Promise<Response> { return (this.env as any).TEST_DISCORD.getByName("fixture").fetch(request); }');
      const demandAnchor = 'async renewDemand(): Promise<void> {';
      assert.equal(contents.split(demandAnchor).length, 2);
      contents = contents.replace(demandAnchor, 'async testOnlyExpireDemand(): Promise<void> { await this.enqueue(async () => { this.state.demandUntil = 0; this.persist(); await this.tick(); }); }\n' + demandAnchor);
      return { contents, loader: 'ts' };
    });
  }}] });
const options = convertV4MiniflareOptions({ name:'gateway-runtime-check', modules:true, script:built.outputFiles[0].text, compatibilityDate:'2026-09-21',
  bindings:{ GLOBAL_CHAT_ENABLED:'true', GLOBAL_CHAT_CHANNEL_ID:channel, DISCORD_GUILD_ID:guild, USER_BOT_APPLICATION_ID:bot,
    USER_BOT_TOKEN:'test-only-user-bot-token-32bytes-long', DISCORD_BRIDGE_SECRET:'test-only-discord-bridge-secret-32bytes', GLOBAL_CHAT_CONTROL_SECRET:secret, ROOMS:'friends,testing', IRC_ROOMS:'testing', DISCORD_IRC_CHANNEL_ID:channel, DISCORD_IRC_TEST_CHANNEL_ID:'1553537591960412241' },
  durableObjects:{ GLOBAL_CHAT_GATEWAY:{className:'GlobalChatGateway',useSQLite:true}, TEST_DISCORD:{className:'GatewayFixture',useSQLite:true}, RELAY_ROOMS:{className:'RelayRoom',useSQLite:true} },
  serviceBindings:{SKY_MYCE_BACKEND:'gateway-runtime-check', GATEWAY_CONTROL:{name:'gateway-runtime-check',entrypoint:'GatewayControl'}}, outboundService:()=>{throw new Error('No external traffic is permitted in Gateway fixtures');} });
options.unsafeInspectDurableObjects=true;
let mf = new Miniflare(options);
const call=(path,body,authorization)=>mf.dispatchFetch('https://test.example'+path,body===undefined?{}:{method:'POST',headers:{'Content-Type':'application/json',...(authorization?{Authorization:authorization}:{})},body:JSON.stringify(body)});
const snapshot=async()=>await (await call('/test/snapshot')).json();
const health=async()=>await (await call('/global-chat/health')).json();
const control=async(action,authorization='Bearer '+secret)=>{
 const response=await call('/global-chat/control',{action},authorization);
 if(action==='start'&&response.ok)await call('/test/demand');
 return response;
};
const demandTimer=setInterval(()=>{call('/test/demand').then(response=>response.body?.cancel()).catch(()=>{});},20_000);
const inject=(kind,packet)=>call('/test/inject',{kind,packet});
const until=async(condition,label,timeout=7000)=>{
  const deadline=Date.now()+timeout;
  do { const result=await condition(); if(result)return result; await new Promise(resolve=>setTimeout(resolve,50)); }while(Date.now()<deadline);
  throw new Error('Timed out: '+label+'; health='+JSON.stringify(await health()));
};
const evictGateway=async()=>{
  // Outbound sockets correctly keep the actor active. Drop the mock transports before eviction.
  const storage=await mf.unsafeGetDurableObjectStorage('gateway-runtime-check','GlobalChatGateway',{name:'global-chat:'+channel});
  const saved=(await storage.exec('SELECT value FROM gateway_state'))[0].value;
  const previews=await storage.exec('SELECT id,value,expires FROM gateway_previews');
  await call('/test/inject',{kind:'gateway',close:4000});
  await call('/test/inject',{kind:'bridge',close:1000});
  await until(async()=>{const state=await health();return !state.gatewayReady&&!state.bridgeReady;},'remote sockets release active references before eviction');
  // Prevent alarms reopening transport while the dev eviction waits for references to drain.
  const stopped=await control('stop');await stopped.body?.cancel();
  await storage.exec('UPDATE gateway_state SET value=? WHERE id=1',saved);
  // Stop clears recovery previews by design; restore them alongside state to simulate ordinary eviction.
  for(const row of previews) await storage.exec('INSERT OR REPLACE INTO gateway_previews VALUES (?,?,?)',row.id,row.value,row.expires);
  await mf.unsafeEvictDurableObject('gateway-runtime-check','GlobalChatGateway',{name:'global-chat:'+channel,webSockets:'close'});
};
let nextSnowflake=0n;
const newSnowflake=()=>String(((BigInt(Date.now())-1420070400000n)<<22n)+nextSnowflake++);
const message=(id=newSnowflake(),changes={})=>({id,guild_id:guild,channel_id:channel,author:{id:'100000000000000001',username:'User'},member:{nick:'Display name'},content:'Hello <@100000000000000002>',mentions:[{id:'100000000000000002',username:'Mention'}],embeds:[],...changes});
const mc=(id='a'.repeat(32),changes={})=>{const now=Date.now();return {type:'irc_message',scope:'global',room:'global-chat',source:'minecraft',id,message_id:id,from:'Alice',text:'hello @everyone **test**',timestamp:now,expiresAt:now+120000,...changes};};
const frames=(state,kind,type)=>state.frames.filter(row=>row.kind===kind&&(type===undefined||row.value.type===type)).map(row=>row.value);
try {
  assert.equal((await control('start','Bearer wrong')).status,401);
  assert.equal((await control('start','')).status,401);
  assert.equal((await control('unknown')).status,400);
  assert.equal((await health()).running,false);
  assert.equal((await call('/global-chat/control',{action:'start'},'Bearer '+secret)).status,200);
  assert.equal((await health()).gatewayReady,false,'enabling an idle gateway opens no Discord socket');
  assert.equal((await health()).bridgeReady,false,'enabling an idle gateway opens no backend socket');
  assert.equal((await call('/global-chat/control',{action:'wake'},'Bearer wrong')).status,401,'public callers cannot renew demand');
  assert.equal((await call('/global-chat/control',{action:'wake'},'Bearer '+secret)).status,400,'demand is binding-only');
  assert.equal((await control('start')).status,200);
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'both real outbound WebSockets ready');
  const ready=await snapshot();
  assert.equal(frames(ready,'bridge','discord_message').length,0,'initial Identify cannot admit messages before validated READY');
  assert.equal(ready.counters.find(row=>row.name==='signedBridgeConnections').value,1);
  const stableUntil=Date.now()+11000;
  do {
    const state=await health();
    assert.ok(state.gatewayReady&&state.bridgeReady,'adopted sockets must survive past their ten-second handshake deadlines');
    await new Promise(resolve=>setTimeout(resolve,100));
  } while(Date.now()<stableUntil);
  const stable=await snapshot();
  assert.equal(stable.counters.find(row=>row.name==='gatewayConnections').value,1,'Gateway handshake timer cannot close and reconnect an adopted socket');
  assert.equal(stable.counters.find(row=>row.name==='bridgeConnections').value,1,'bridge handshake timer cannot close and reconnect an adopted socket');
  await control('start');
  assert.equal((await snapshot()).counters.find(row=>row.name==='gatewayConnections').value,1,'repeated start never creates a second socket');
  assert.equal((await snapshot()).sockets.gateway,1);

  const incoming=message(undefined,{message_reference:{message_id:'100000000000000005',channel_id:channel},referenced_message:{channel_id:channel,author:{username:'Original'},content:'Reply context'}});
  await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:11,d:incoming});
  await until(async()=>frames(await snapshot(),'bridge','discord_message').length===1,'Discord message sent to authenticated backend');
  const delivered=frames(await snapshot(),'bridge','discord_message')[0];
  assert.equal(delivered.scope,'global');assert.equal(delivered.room,'global-chat');assert.equal(delivered.from,'Display name');
  assert.equal(delivered.text,'Hello @Mention');assert.deepEqual(delivered.mentions,['100000000000000002']);
  await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:12,d:incoming});
  for(const change of [{guild_id:'100000000000000004'},{channel_id:'100000000000000005'},{webhook_id:'100000000000000006'},{author:{id:bot,bot:true}},{id:'invalid'}]) {
    await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:13,d:message(undefined,change)});
  }
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:14,d:{id:incoming.id,guild_id:guild,channel_id:channel,embeds:[{description:'Delayed link preview'}],message_reference:incoming.message_reference,referenced_message:incoming.referenced_message}});
  await until(async()=>frames(await snapshot(),'bridge','discord_message').length===2,'one delayed embed continuation');
  assert.equal(frames(await snapshot(),'bridge','discord_message')[1].part,'embeds');
  assert.ok(!Object.hasOwn(frames(await snapshot(),'bridge','discord_message')[1],'reply'),'real Gateway reply-preview frame obeys backend continuation rules');
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:15,d:{id:incoming.id,guild_id:guild,channel_id:channel,embeds:[{description:'Delayed link preview'}]}});

  const outgoing=mc();
  await inject('bridge',outgoing);
  await until(async()=>(await snapshot()).posts.length===1,'Minecraft message posted to Discord');
  await inject('bridge',outgoing);
  await until(async()=>frames(await snapshot(),'bridge','discord_ack').length===2,'duplicate packet receives original delivery receipt');
  const posted=(await snapshot()).posts[0];
  assert.deepEqual(posted.body.allowed_mentions,{parse:[],replied_user:false});assert.equal(posted.body.enforce_nonce,true);
  assert.match(posted.body.embeds[0].description,/\\\*\\\*test\\\*\\\*/);assert.equal(posted.body.nonce.length,24);
  assert.equal(posted.body.content,undefined);assert.equal(posted.body.embeds[0].author.name,'Alice');assert.ok(!JSON.stringify(posted.body).includes('[SkyMyce]'));
  assert.equal((await snapshot()).counters.find(row=>row.name==='postCalls').value,1);
  for(const change of [{scope:'party'},{scope:undefined},{room:'friends'},{from:'Discord Spoof'},{message_id:'d'.repeat(32)},{expiresAt:Date.now()-1},{expiresAt:Date.now()+240000}]) await inject('bridge',mc('b'.repeat(32),change));
  await inject('bridge',mc('c'.repeat(32),{text:'**'.repeat(512)}));
  await until(async()=>(await snapshot()).posts.length===2,'maximum escaped Minecraft message uses an embed');
  assert.equal((await snapshot()).posts[1].body.embeds[0].description.length,2048);
  assert.equal((await snapshot()).posts[1].body.embeds[0].author.name,'Alice');
  assert.equal(frames(await snapshot(),'bridge','discord_message').length,2,'duplicate and wrong-context Discord events never deliver');

  await inject('gateway',{op:1,d:null});
  await until(async()=>frames(await snapshot(),'gateway').some(packet=>packet.op===1),'Discord heartbeat request answered');
  let sql=await mf.unsafeGetDurableObjectStorage('gateway-runtime-check','GlobalChatGateway',{name:'global-chat:'+channel});
  assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM gateway_receipts'))[0].count,2);
  assert.ok((await sql.exec('SELECT value FROM gateway_state'))[0].value.includes('"sequence":15'));
  const uploadedGif=message(undefined,{content:'GIF upload',mentions:[],attachments:[{filename:'dance.gif',url:signedGif}]});
  await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:16,d:uploadedGif});
  await until(async()=>frames(await snapshot(),'bridge','discord_message').some(packet=>packet.id===uploadedGif.id),'attachment-only GIF is forwarded once');
  const gifBody=frames(await snapshot(),'bridge','discord_message').find(packet=>packet.id===uploadedGif.id);
  assert.equal(gifBody.text,`GIF upload\ndance.gif: ${signedGif}`);
  const gifPreview=JSON.parse((await sql.exec('SELECT value FROM gateway_previews WHERE id=?',uploadedGif.id))[0].value);
  assert.deepEqual(gifPreview.links,[unsignedGif],'bounded durable preview knows the attachment identity');
  assert.ok(!gifPreview.links.some(value=>value.includes('hm=')),'canonical preview identities retain no signing tokens');
  assert.ok(!String((await sql.exec('SELECT value FROM gateway_sources WHERE id=?',uploadedGif.id))[0].value).includes('hm='),'fifteen-minute routing metadata retains no signing tokens');

  const missed=message(undefined,{content:'Missed human message replayed before RESUMED',mentions:[]});
  const rejectedReplays=[message(undefined,{guild_id:'100000000000000004'}),message(undefined,{channel_id:'100000000000000005'}),message(undefined,{author:{id:bot,bot:true}}),
    message(String((BigInt(Date.now()-120001)-1420070400000n)<<22n))];
  const replayPackets=[missed,...rejectedReplays].map((data,index)=>({op:0,t:'MESSAGE_CREATE',s:17+index,d:data}));
  await call('/test/replay-next',replayPackets);
  await evictGateway();
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'persisted desired state restores sockets after actual eviction');
  await until(async()=>frames(await snapshot(),'gateway').some(packet=>packet.op===6&&packet.seq===16),'persistent Gateway sequence used for Resume');
  await until(async()=>frames(await snapshot(),'bridge','discord_message').some(packet=>packet.id===missed.id),'MESSAGE_CREATE replayed before RESUMED durably reaches the signed backend');
  assert.equal(frames(await snapshot(),'bridge','discord_message').filter(packet=>packet.id===missed.id).length,1);
  assert.equal((await sql.exec('SELECT id FROM gateway_seen WHERE id=?',missed.id+':body')).length,1,'replay is durably deduplicated with its saved sequence');
  await until(async()=>(await sql.exec('SELECT id FROM gateway_pending WHERE id=?',missed.id+':body')).length===0,'backend acknowledgment clears the durably queued replay');
  const replayDelivery=frames(await snapshot(),'bridge','discord_message').find(packet=>packet.id===missed.id);
  assert.equal(replayDelivery.text,missed.content);assert.equal(replayDelivery.scope,'global');
  for(const rejected of rejectedReplays) {
    assert.ok(!frames(await snapshot(),'bridge','discord_message').some(packet=>packet.id===rejected.id),'resume retains guild/channel/own-bot/TTL filtering');
    assert.equal((await sql.exec('SELECT id FROM gateway_seen WHERE id=?',rejected.id+':body')).length,0);
  }

  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:22,d:{id:uploadedGif.id,guild_id:guild,channel_id:channel,embeds:[{url:unsignedGif,image:{url:mediaGif},thumbnail:{url:signedGif}}]}});
  await until(async()=>JSON.parse((await sql.exec('SELECT value FROM gateway_state'))[0].value).sequence===22,'URL-only delayed update was processed after actual eviction');
  assert.equal(frames(await snapshot(),'bridge','discord_message').filter(packet=>packet.id===uploadedGif.id).length,1,'persisted attachment identity suppresses the duplicate delayed GIF');
  assert.equal((await sql.exec('SELECT id FROM gateway_seen WHERE id=?',uploadedGif.id+':embeds')).length,0,'a duplicate-only preview cannot consume the one genuine continuation');
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:23,d:{id:uploadedGif.id,guild_id:guild,channel_id:channel,embeds:[{title:'GIF information',description:'A genuine additional description',url:unsignedGif,image:{url:mediaGif}}]}});
  await until(async()=>frames(await snapshot(),'bridge','discord_message').filter(packet=>packet.id===uploadedGif.id).length===2,'a later genuine title and description still deliver');
  const continuedGif=frames(await snapshot(),'bridge','discord_message').find(packet=>packet.id===uploadedGif.id&&packet.part==='embeds');
  assert.equal(continuedGif.text,'GIF information\nA genuine additional description');
  assert.deepEqual(continuedGif.mentions,[]);assert.ok(!Object.hasOwn(continuedGif,'reply'));
  await inject('bridge',outgoing);
  await until(async()=>frames(await snapshot(),'bridge','discord_ack').length>=4,'receipt survives eviction');
  assert.equal((await snapshot()).counters.find(row=>row.name==='postCalls').value,2,'eviction never reposts a receipted message');
  await call('/test/limit-next',{kind:'bridge'});
  const limitedMessage=message();
  await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:24,d:limitedMessage});
  await until(async()=>{const rows=await sql.exec('SELECT id,due FROM gateway_pending');return rows.some(row=>row.id.startsWith(limitedMessage.id)&&row.due>Date.now());},'backend shared-quota rejection retains a bounded pending delivery');
  await sql.exec('UPDATE gateway_pending SET due=? WHERE id=?',Date.now()-1,limitedMessage.id+':body');
  await inject('gateway',{op:1,d:null});
  await until(async()=>(await sql.exec('SELECT COUNT(*) AS count FROM gateway_pending'))[0].count===0,'rate-limited delivery retries and clears only after backend acceptance');
  assert.equal(frames(await snapshot(),'bridge','discord_message').filter(packet=>packet.id===limitedMessage.id).length,2);
  await call('/test/limit-next',{kind:'post'});
  const limitedMc=mc('e'.repeat(32));
  await inject('bridge',limitedMc);
  await until(async()=>(await health()).lastError==='discord_rate_limited','Discord rate limit recorded');
  assert.equal((await sql.exec('SELECT id FROM gateway_receipts WHERE id=?',limitedMc.id)).length,0,'429 is never acknowledged or recorded as a delivery');
  await new Promise(resolve=>setTimeout(resolve,1050));
  await inject('bridge',limitedMc);
  await until(async()=>(await sql.exec('SELECT id FROM gateway_receipts WHERE id=?',limitedMc.id)).length===1,'Discord delivery retries after server Retry-After');
  const gatewayState=async()=>JSON.parse((await sql.exec('SELECT value FROM gateway_state'))[0].value);
  const restartGateway=async()=>{
    await evictGateway();
    await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'Gateway restores after cooldown eviction');
  };
  const releaseFixtureCooldown=async()=>{
    // Stop first so an old socket event cannot overwrite the fixture's stored clock advance.
    await control('stop');
    await sql.exec("UPDATE gateway_state SET value=json_set(value,'$.postDue',0,'$.running',true)");
    await restartGateway();
  };
  const longLimited=mc('9'.repeat(32)), longStarted=Date.now();
  await call('/test/limit-next',{kind:'post',retryAfter:'1337'});
  await inject('bridge',longLimited);
  await until(async()=>(await gatewayState()).postDue>=longStarted+1337000,'full long Discord Retry-After is durably retained');
  const cooldown=await gatewayState(), longCalls=(await snapshot()).counters.find(row=>row.name==='postCalls').value;
  assert.ok(cooldown.postDue<=Date.now()+1337000,'cooldown uses a finite supported millisecond deadline');
  for(const packet of [longLimited,mc('8'.repeat(32))]) await inject('bridge',packet);
  let ackCount=frames(await snapshot(),'bridge','discord_ack').length;
  await inject('bridge',outgoing);
  await until(async()=>frames(await snapshot(),'bridge','discord_ack').length>ackCount,'receipts still acknowledge while fresh posts are cooling down');
  assert.equal((await snapshot()).counters.find(row=>row.name==='postCalls').value,longCalls,'repeated/fresh arrivals cannot shorten the server cooldown');
  await restartGateway();
  assert.equal((await gatewayState()).postDue,cooldown.postDue,'Discord cooldown survives actual DO eviction');
  await inject('bridge',mc('7'.repeat(32)));
  ackCount=frames(await snapshot(),'bridge','discord_ack').length;
  await inject('bridge',outgoing);
  await until(async()=>frames(await snapshot(),'bridge','discord_ack').length>ackCount,'receipt after restart drains preceding bridge messages');
  assert.equal((await snapshot()).counters.find(row=>row.name==='postCalls').value,longCalls,'eviction cannot bypass Retry-After');
  await releaseFixtureCooldown();
  for(const [index,retryAfter] of ['Infinity','9007199254740991'].entries()) {
    const started=Date.now();
    await call('/test/limit-next',{kind:'post',retryAfter});
    await inject('bridge',mc(String(index+5).repeat(32)));
    await until(async()=>(await gatewayState()).postDue>=started+5000,'invalid/overflowing Retry-After uses a finite fallback');
    const due=(await gatewayState()).postDue;
    assert.ok(Number.isSafeInteger(due)&&due<=Date.now()+5000,'unusable server values cannot create infinite/overflowing deadlines: '+JSON.stringify({retryAfter,due,now:Date.now()}));
    await releaseFixtureCooldown();
  }
  const exhaustedMc=mc('4'.repeat(32)), exhaustedStarted=Date.now();
  await call('/test/exhaust-next-post',{});
  await inject('bridge',exhaustedMc);
  await until(async()=>(await sql.exec('SELECT id FROM gateway_receipts WHERE id=?',exhaustedMc.id)).length===1,'successful exhausted Discord bucket records delivery');
  assert.ok((await gatewayState()).postDue>=exhaustedStarted+1337000,'successful response retains the full Discord bucket reset deadline');
  const exhaustedCalls=(await snapshot()).counters.find(row=>row.name==='postCalls').value;
  await inject('bridge',mc('3'.repeat(32)));
  ackCount=frames(await snapshot(),'bridge','discord_ack').length;
  await inject('bridge',exhaustedMc);
  await until(async()=>frames(await snapshot(),'bridge','discord_ack').length>ackCount,'successful receipt remains available while the bucket is exhausted');
  assert.equal((await snapshot()).counters.find(row=>row.name==='postCalls').value,exhaustedCalls,'exhausted successful response prevents fresh posts');
  await releaseFixtureCooldown();
  const beforeDelayed=await snapshot(), heartbeatCount=frames(beforeDelayed,'gateway').filter(packet=>packet.op===1).length;
  const postCount=beforeDelayed.counters.find(row=>row.name==='postCalls').value;
  await call('/test/delay-next-post',{});
  const slowMc=mc('f'.repeat(32));
  await inject('bridge',slowMc);
  await until(async()=>(await snapshot()).counters.find(row=>row.name==='postCalls').value>postCount,'slow Discord REST call started');
  await inject('gateway',{op:1,d:null});
  await until(async()=>frames(await snapshot(),'gateway').filter(packet=>packet.op===1).length>heartbeatCount,'slow Discord REST cannot block Gateway heartbeat requests',1000);
  assert.equal((await sql.exec('SELECT id FROM gateway_receipts WHERE id=?',slowMc.id)).length,0,'heartbeat was answered while Discord REST was still waiting');
  await until(async()=>(await sql.exec('SELECT id FROM gateway_receipts WHERE id=?',slowMc.id)).length===1,'slow REST eventually records its receipt');
  await sql.exec("WITH RECURSIVE n(i) AS (SELECT 1 UNION ALL SELECT i+1 FROM n WHERE i<1100) INSERT OR IGNORE INTO gateway_receipts SELECT printf('%032x',i),'100000000000000001',? FROM n",Date.now()+120000);
  await health();
  assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM gateway_receipts'))[0].count,1024,'persistent receipt storage remains bounded');
  const summary=JSON.stringify(await health());
  assert.ok(!summary.includes(secret)&&!summary.includes('test-session')&&!summary.includes('hello @everyone'),'health exposes no credentials, resume token or chat text');
  // A bridge handshake cannot stall the Gateway's serialized heartbeats or control actions.
  await call('/test/inject',{kind:'bridge',close:1000});
  await until(async()=>!(await health()).bridgeReady,'bridge disconnected before stalled reconnect');
  const beforeBridgeDelay=await snapshot(), bridgeAttempts=beforeBridgeDelay.counters.find(row=>row.name==='bridgeAttempts').value;
  await call('/test/delay-next-bridge',{});
  const stalledReconnect=control('start');
  await until(async()=>(await snapshot()).counters.some(row=>row.name==='delayedBridgeHandshakes'&&row.value===1),'bridge handshake is waiting');
  const heartbeatDuringConnect=frames(await snapshot(),'gateway').filter(packet=>packet.op===1).length;
  await inject('gateway',{op:1,d:null});
  await until(async()=>frames(await snapshot(),'gateway').filter(packet=>packet.op===1).length>heartbeatDuringConnect,'stalled bridge handshake cannot block Gateway heartbeat requests',1000);
  assert.equal((await health()).bridgeReady,false,'heartbeat is answered before delayed bridge upgrade completes');
  const stalledResponse=await stalledReconnect;assert.equal(stalledResponse.status,200);await stalledResponse.body?.cancel();
  assert.equal((await control('start')).status,200);
  assert.equal((await snapshot()).counters.find(row=>row.name==='bridgeAttempts').value,bridgeAttempts+1,'repeated start keeps exactly one pending bridge handshake');
  assert.equal((await control('stop')).status,200);
  await new Promise(resolve=>setTimeout(resolve,2700));
  const stoppedAfterHandshake=await health();
  assert.equal(stoppedAfterHandshake.running,false);
  assert.equal(stoppedAfterHandshake.gatewayReady,false);assert.equal(stoppedAfterHandshake.bridgeReady,false,'late handshake cannot reactivate a stopped service');
  assert.equal((await snapshot()).counters.find(row=>row.name==='bridgeAttempts').value,bridgeAttempts+1,'cancelled handshake cannot schedule another connection');
  assert.equal((await control('start')).status,200);
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'new activation restores both sockets after cancelling a pending handshake');
  // Keep the handshake bound while removing the timeout from a successfully upgraded socket.
  await call('/test/inject',{kind:'bridge',close:1000});
  await until(async()=>!(await health()).bridgeReady,'bridge disconnected before stalled handshake');
  await call('/test/stall-next-bridge',{});
  const beforeStall=(await snapshot()).counters.find(row=>row.name==='bridgeAttempts').value;
  assert.equal((await control('start')).status,200);
  await until(async()=>(await snapshot()).counters.some(row=>row.name==='stalledBridgeHandshakes'&&row.value===1),'stalled bridge handshake started');
  await until(async()=>{const state=await health();return !state.bridgeReady&&['bridge_connect_timeout','bridge_connect_failed'].includes(state.lastError);},'stalled handshake still rejects at its bounded deadline',12000);
  await new Promise(resolve=>setTimeout(resolve,1800));
  const afterStall=await health();
  assert.equal(afterStall.gatewayReady,true,'healthy Gateway remains connected throughout the stalled bridge handshake');
  assert.equal(afterStall.bridgeReady,false,'late upgrade from an expired attempt cannot become active');
  assert.equal((await snapshot()).counters.find(row=>row.name==='bridgeAttempts').value,beforeStall+1,'expired attempt waits for backoff instead of starting duplicate handshakes');
  assert.equal((await control('start')).status,200);
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'new bridge attempt restores service after handshake timeout');

  // Timed-out mock Upgrades may retain workerd request references. Isolate rich eviction from those transport fault fixtures.
  await mf.dispose(); mf = new Miniflare(options);
  await control('start');
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'fresh rich lifecycle coordinator ready');
  sql=await mf.unsafeGetDurableObjectStorage('gateway-runtime-check','GlobalChatGateway',{name:'global-chat:'+channel});
  assert.equal((await call('/irc/friends/health')).status,503,'room/global channel conflict is fail closed');
  const roomHealth=await (await call('/irc/testing/health')).json();
  assert.equal(roomHealth.room,'testing');assert.equal(roomHealth.scope,'room');assert.equal(roomHealth.running,false);
  assert.equal((await call('/irc/testing/control',{action:'start'})).status,401,'room coordinator never starts without control authentication');
  const roomStatus=await call('/irc/testing/control',{action:'status'},'Bearer '+secret);
  assert.equal(roomStatus.status,200);assert.equal((await roomStatus.json()).running,false,'channel binding does not auto-activate a room');
  await call('/test/hold-capabilities',{});
  await inject('bridge',{type:'bridge_ready',protocol:3,room:'global-chat',globalChat:true,richChatVersion:1});
  const capability=await until(async()=>frames(await snapshot(),'bridge','bridge_capabilities').at(-1),'capability offer reached backend');
  assert.equal((await health()).richChatVersion,0,'offered rich capability is not ACKed yet');
  const richMc={...mc('c'.repeat(32)),mentions:[namedMention],model:1,sender:{name:'Alice',uuid:'a'.repeat(32),cosmeticName:'Champion'},media:[{id:'gif',type:'gif',url:signedGif}],revision:0};
  await inject('bridge',richMc); await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal((await snapshot()).posts.length,0,'first rich card waits for capability ACK');
  await inject('bridge',{type:'irc_result',scope:'global',room:'global-chat',id:capability.id});
  await call('/test/hold-capabilities',{hold:false});
  await until(async()=>(await health()).richChatVersion===1,'rich capabilities require server ACK before mutations');
  await until(async()=>(await sql.exec('SELECT discord_id FROM gateway_receipts WHERE id=?',richMc.id)).length===1,'rich card obtains authoritative destination receipt');
  const destination=(await sql.exec('SELECT discord_id FROM gateway_receipts WHERE id=?',richMc.id))[0].discord_id;
  const mentionedPost=(await snapshot()).posts.find(row=>row.id===destination);
  assert.equal(mentionedPost.body.content,`<@${namedMention}>`);
  assert.deepEqual(mentionedPost.body.allowed_mentions,{parse:[],users:[namedMention],replied_user:false});
  const outgoingOp=(id,operation,revision,extra={})=>{const now=Date.now();return {type:'irc_operation',id,scope:'global',room:'global-chat',messageId:richMc.id,discordMessageId:destination,operation,revision,timestamp:now,expiresAt:now+120000,...extra};};
  const editOp=outgoingOp('d'.repeat(32),'edit',1,{text:'Edited in Minecraft',sender:richMc.sender,media:[]});
  await inject('bridge',editOp);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===editOp.id),'edit waits for actual Discord REST success before ACK');
  const edits=frames(await snapshot(),'rest').filter(row=>row.method==='PATCH');
  assert.equal(edits.at(-1).body.embeds[0].description,'Edited in Minecraft');assert.equal(edits.at(-1).body.embeds[0].author.name,'Champion (Alice)');
  assert.deepEqual(edits.at(-1).body.allowed_mentions,{parse:[],replied_user:false});
  assert.equal(edits.at(-1).body.content,'','Edits clear notification tags without notifying again');
  await inject('bridge',editOp);await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(frames(await snapshot(),'rest').filter(row=>row.method==='PATCH').length,edits.length,'operation receipt prevents a retry patch');

  const editSecond=outgoingOp('01'.repeat(16),'edit',2,{text:'Second in-game edit',sender:richMc.sender,media:[]});
  await inject('bridge',editSecond);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===editSecond.id),'second edit records expected description before REST');
  const patchesBeforeSuperseded=frames(await snapshot(),'rest').filter(row=>row.method==='PATCH').length;
  const superseded=outgoingOp('02'.repeat(16),'edit',1,{text:'Older delayed edit',sender:richMc.sender,media:[]});
  await inject('bridge',superseded);
  const supersededResult=await until(async()=>frames(await snapshot(),'bridge','discord_operation_result').find(row=>row.id===superseded.id&&row.error==='superseded'),'older edit reports an authoritative terminal revision result');
  assert.equal(supersededResult.channelId,channel);assert.equal(supersededResult.scope,'global');assert.equal(supersededResult.room,'global-chat');
  assert.equal(frames(await snapshot(),'rest').filter(row=>row.method==='PATCH').length,patchesBeforeSuperseded,'superseded edit cannot mutate Discord');
  const delayedOwnEcho={op:0,t:'MESSAGE_UPDATE',s:46,d:{id:destination,guild_id:guild,channel_id:channel,embeds:[{description:'Edited in Minecraft',author:{name:'Ignored identity header'},footer:{text:'Ignored footer'}}]}};
  await inject('gateway',delayedOwnEcho);
  await until(async()=>String((await sql.exec('SELECT value FROM gateway_state'))[0].value).includes('"sequence":46'),'old self echo was handled');
  assert.equal(frames(await snapshot(),'bridge','discord_operation').filter(row=>row.message_id===destination&&row.operation==='edit').length,0,'older expected edit echo cannot undo a newer edit');
  const genuineBody='Genuine *external* card edit', genuineDescription='Genuine \\*external\\* card edit';
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:47,d:{...delayedOwnEcho.d,embeds:[{description:genuineDescription,author:{name:'Not message text'},footer:{text:'Not message text'}}]}});
  await until(async()=>frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===destination&&row.operation==='edit'),'genuine mapped bot card edit is propagated');
  const genuine=frames(await snapshot(),'bridge','discord_operation').find(row=>row.message_id===destination&&row.operation==='edit');
  const recoveredCard=JSON.parse((await sql.exec('SELECT value FROM gateway_previews WHERE id=?',destination))[0].value);
  assert.equal(recoveredCard.embeds[0].description,genuineDescription,'Mapped recovery preserves markdown escaping without code fragments');
  assert.equal(genuine.text,genuineBody);assert.equal(genuine.discordId,bot,'backend retains original Minecraft ownership for mapped bot cards');

  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:48,d:{id:destination,guild_id:guild,channel_id:channel,attachments:[{id:'100000000000000009',filename:'new.gif',url:signedGif}]}});
  await until(async()=>frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===destination&&row.media?.length===1),'genuine mapped partial media update keeps its message body');
  const partial=frames(await snapshot(),'bridge','discord_operation').find(row=>row.message_id===destination&&row.media?.length===1);
  assert.equal(partial.text,genuineBody);

  const source={id:String(((BigInt(Date.now())-1420070400000n)<<22n)+77n),guild_id:guild,channel_id:channel,author:{id:'100000000000000007',username:'Human'},content:'Original human text',attachments:[],embeds:[]};
  await call('/test/human-source',source);
  await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:50,d:source});
  await until(async()=>(await sql.exec('SELECT id FROM gateway_sources WHERE id=?',source.id)).length===1,'human source routing metadata validated');
  const nativeEdit={op:0,t:'MESSAGE_UPDATE',s:51,d:{id:source.id,guild_id:guild,channel_id:channel,content:'Native Discord edit',edited_timestamp:new Date().toISOString(),attachments:[],embeds:[]}};
  await inject('gateway',nativeEdit);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===source.id&&row.operation==='edit'),'native edit reaches authenticated bridge');
  const nativeOps=frames(await snapshot(),'bridge','discord_operation').filter(row=>row.message_id===source.id);
  assert.equal(nativeOps[0].text,'Native Discord edit');assert.equal(nativeOps[0].discordId,source.author.id);assert.match(nativeOps[0].id,/^[a-f0-9]{32}$/);
  await inject('gateway',nativeEdit);await new Promise(resolve=>setTimeout(resolve,100));
  assert.equal(frames(await snapshot(),'bridge','discord_operation').filter(row=>row.message_id===source.id&&row.operation==='edit').length,1,'native replay identity is stable');
  const forbidden=outgoingOp('e'.repeat(32),'delete',2,{discordMessageId:source.id});
  await inject('bridge',forbidden);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_result').some(row=>row.id===forbidden.id&&row.error==='destination_unavailable'),'client cannot mutate a human source message');
  const react=outgoingOp('e'.repeat(32),'reaction',2,{discordMessageId:source.id,emoji:'🔥',active:true});
  await call('/test/limit-next-operation',{});await inject('bridge',react);
  await until(async()=>(await gatewayState()).postDue>Date.now(),'native reaction preserves actual REST rate limit');
  assert.ok(!frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===react.id),'rate-limited mutation does not ACK prematurely');
  await new Promise(resolve=>setTimeout(resolve,1100));await inject('bridge',react);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===react.id),'own aggregate reaction retries idempotently');
  await inject('gateway',{op:0,t:'MESSAGE_REACTION_ADD',s:52,d:{guild_id:guild,channel_id:channel,message_id:source.id,user_id:bot,emoji:{name:'🔥'}}});
  await inject('gateway',{op:0,t:'MESSAGE_REACTION_ADD',s:53,d:{guild_id:guild,channel_id:channel,message_id:source.id,user_id:source.author.id,emoji:{name:'🔥'}}});
  await until(async()=>frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===source.id&&row.operation==='reaction'),'human reaction forwarded once');
  assert.equal(frames(await snapshot(),'bridge','discord_operation').filter(row=>row.operation==='reaction'&&row.message_id===source.id).length,1,'own bot mirrored reaction cannot echo');
  await inject('gateway',{op:0,t:'MESSAGE_DELETE',s:54,d:{id:source.id,guild_id:guild,channel_id:channel}});
  await until(async()=>frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===source.id&&row.operation==='delete'),'native deletion uses cached verified author');
  // A failed body edit and each emoji have independent progress, including after newer success.
  const failedEdit=outgoingOp('03'.repeat(16),'edit',10,{text:'Recovered earlier edit',sender:richMc.sender,media:[],reactions:[{emoji:'🔥',count:1}]});
  await call('/test/limit-next-operation',{});await inject('bridge',failedEdit);
  await until(async()=>(await gatewayState()).postDue>Date.now(),'failed edit retains retry');
  await new Promise(resolve=>setTimeout(resolve,1100));
  const newerReaction=outgoingOp('04'.repeat(16),'reaction',11,{emoji:'👍',active:true,reactions:[{emoji:'👍',count:2}]});
  await inject('bridge',newerReaction);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===newerReaction.id),'newer reaction succeeds independently');
  await inject('bridge',failedEdit);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===failedEdit.id),'earlier failed edit still applies');
  const recovered=(await snapshot()).posts.find(row=>row.id===destination).body.embeds[0];
  assert.equal(recovered.description,'Recovered earlier edit');assert.equal(recovered.footer.text,'Minecraft · Sky-Myce Global · 👍 2','older edit preserves newer counts');
  const olderEmoji=outgoingOp('05'.repeat(16),'reaction',12,{emoji:'🔥',active:true,reactions:[{emoji:'👍',count:2},{emoji:'🔥',count:3}]});
  await call('/test/limit-next-operation',{});await inject('bridge',olderEmoji);
  await until(async()=>(await gatewayState()).postDue>Date.now(),'failed emoji retains retry');await new Promise(resolve=>setTimeout(resolve,1100));
  const otherEmoji=outgoingOp('06'.repeat(16),'reaction',13,{emoji:'👀',active:true,reactions:[{emoji:'👍',count:2},{emoji:'🔥',count:3},{emoji:'👀',count:1}]});
  await inject('bridge',otherEmoji);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===otherEmoji.id),'different emoji succeeds independently');
  await inject('bridge',olderEmoji);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===olderEmoji.id),'older different emoji still applies');
  assert.equal((await snapshot()).posts.find(row=>row.id===destination).body.embeds[0].footer.text,'Minecraft · Sky-Myce Global · 👍 2  🔥 3  👀 1');

  const replyCreate=message(newSnowflake(),{content:'Before reply edit',message_reference:{message_id:source.id,channel_id:channel}});
  await call('/test/delay-next-reply',{});await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:55,d:replyCreate});
  await until(async()=>(await snapshot()).counters.some(row=>row.name==='replyLookups'&&row.value===1),'reply context is loading');
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:56,d:{id:replyCreate.id,guild_id:guild,channel_id:channel,content:'Latest reply edit',mentions:[],edited_timestamp:new Date().toISOString()}});
  await until(async()=>frames(await snapshot(),'bridge','discord_message').some(row=>row.message_id===replyCreate.id),'edited reply create delivered');
  const replyFrames=frames(await snapshot(),'bridge','discord_message').filter(row=>row.message_id===replyCreate.id);
  assert.equal(replyFrames.length,1);assert.equal(replyFrames[0].text,'Latest reply edit');assert.deepEqual(replyFrames[0].mentions,[],'removed mentions cannot notify from stale create data');
  assert.ok(!frames(await snapshot(),'bridge','discord_operation').some(row=>row.message_id===replyCreate.id),'edit cannot precede the delayed create');
  const deletedReply=message(newSnowflake(),{content:'Reply to delete',message_reference:{message_id:source.id,channel_id:channel}});
  await call('/test/delay-next-reply',{});await inject('gateway',{op:0,t:'MESSAGE_CREATE',s:57,d:deletedReply});
  await until(async()=>(await snapshot()).counters.some(row=>row.name==='replyLookups'&&row.value===2),'second reply lookup is loading');
  await inject('gateway',{op:0,t:'MESSAGE_DELETE',s:58,d:{id:deletedReply.id,guild_id:guild,channel_id:channel}});
  await inject('gateway',{op:0,t:'MESSAGE_UPDATE',s:59,d:{id:deletedReply.id,guild_id:guild,channel_id:channel,content:'Late edit cannot revive',edited_timestamp:new Date().toISOString()}});
  await until(async()=>(await snapshot()).counters.some(row=>row.name==='replyLookupsFinished'&&row.value===2),'deleted reply lookup completes');
  assert.ok(!frames(await snapshot(),'bridge').some(row=>row.message_id===deletedReply.id),'deleted pending create cannot be revived by lookup or update');
  const deleteOp=outgoingOp('f'.repeat(32),'delete',3);
  await inject('bridge',deleteOp);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').some(row=>row.id===deleteOp.id),'owned card deletion reaches REST');
  await evictGateway();
  await until(async()=>(await health()).gatewayReady&&(await health()).bridgeReady,'rich receipts survive actual eviction');
  await inject('bridge',{type:'bridge_ready',protocol:3,room:'global-chat',globalChat:true,richChatVersion:1});
  await until(async()=>(await health()).richChatVersion===1,'capability restored after eviction');
  await inject('bridge',outgoingOp('07'.repeat(16),'edit',14,{text:'Cannot revive a deletion',sender:richMc.sender,media:[]}));
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_result').some(row=>row.id==='07'.repeat(16)&&row.error==='superseded'),'deletion stays terminal across eviction');
  const deletes=frames(await snapshot(),'rest').filter(row=>row.method==='DELETE'&&!row.path.includes('/reactions/')).length;
  await inject('bridge',deleteOp);
  await until(async()=>frames(await snapshot(),'bridge','discord_operation_ack').filter(row=>row.id===deleteOp.id).length===2,'persisted exact operation receipt ACKs reconnect retry');
  assert.equal(frames(await snapshot(),'rest').filter(row=>row.method==='DELETE'&&!row.path.includes('/reactions/')).length,deletes,'reconnect does not delete twice');

  await call('/test/inject',{kind:'gateway',close:4014});
  await until(async()=>(await health()).blocked==='gateway_close_4014','disallowed intents stop reconnecting with an actionable health code');
  const beforeStop=(await snapshot()).counters.find(row=>row.name==='gatewayConnections').value;
  await new Promise(resolve=>setTimeout(resolve,200));
  assert.equal((await snapshot()).counters.find(row=>row.name==='gatewayConnections').value,beforeStop);
  assert.equal((await control('stop')).status,200);
  assert.equal((await health()).running,false);
  assert.equal((await sql.exec('SELECT COUNT(*) AS count FROM gateway_pending'))[0].count,0);
  console.log('Persistent Discord Gateway runtime checks passed (workerd WebSockets, signed service bridge, SQLite receipts, alarms and actual eviction/Resume).');
} finally { clearInterval(demandTimer); await mf.dispose(); }

mf=new Miniflare(options);
try {
  await call('/global-chat/control',{action:'start'},'Bearer '+secret);
  assert.equal((await health()).gatewayReady,false,'enabled gateway stays idle without players');
  await call('/test/demand');
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'private demand wakes the gateway');
  await call('/test/expire-demand');
  const idle=await health();assert.equal(idle.chatDemand,false);assert.equal(idle.gatewayReady,false);assert.equal(idle.bridgeReady,false);assert.equal(idle.running,true,'idle expiry preserves operator enablement');
  await mf.unsafeEvictDurableObject('gateway-runtime-check','GlobalChatGateway',{name:'global-chat:'+channel,webSockets:'close'});
  assert.equal((await health()).gatewayReady,false,'expired demand stays idle after actual eviction');
  await call('/test/demand');
  await until(async()=>{const state=await health();return state.gatewayReady&&state.bridgeReady;},'next player wakes the evicted gateway');
  await control('stop');await call('/test/demand');
  assert.equal((await health()).running,false,'presence cannot override an operator stop');
  console.log('On-demand Gateway checks passed: private wake, idle socket closure, eviction/rejoin and operator stop.');
} finally {await mf.dispose();}

const disabledOptions=convertV4MiniflareOptions({name:'gateway-disabled-check',modules:true,script:built.outputFiles[0].text,compatibilityDate:'2026-09-21',
  bindings:{GLOBAL_CHAT_ENABLED:'false',GLOBAL_CHAT_CHANNEL_ID:channel,GLOBAL_CHAT_CONTROL_SECRET:secret},
  durableObjects:{GLOBAL_CHAT_GATEWAY:{className:'GlobalChatGateway',useSQLite:true}},
  outboundService:()=>{throw new Error('Disabled Gateway must never make an external request');}});
const disabled=new Miniflare(disabledOptions);
try {
  const start=await disabled.dispatchFetch('https://test.example/global-chat/control',{method:'POST',headers:{Authorization:'Bearer '+secret},body:JSON.stringify({action:'start'})});
  assert.equal(start.status,503);assert.equal((await start.json()).running,false);
  assert.equal((await (await disabled.dispatchFetch('https://test.example/global-chat/health')).json()).enabled,false);
  console.log('Disabled Gateway refuses activation and makes no external requests.');
} finally {await disabled.dispose();}
