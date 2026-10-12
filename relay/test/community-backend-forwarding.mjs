import test from 'node:test';
import assert from 'node:assert/strict';
import { build } from 'esbuild';
import { Miniflare, convertV4MiniflareOptions } from 'miniflare';
import { createHash } from 'node:crypto';

const application = '11111111111111111', guild = '22222222222222222', administrator = '33333333333333333';
const keys = await crypto.subtle.generateKey('Ed25519', true, ['sign', 'verify']);
const publicKey = Buffer.from(await crypto.subtle.exportKey('raw', keys.publicKey)).toString('hex');
const digest = body => createHash('sha256').update(body).digest('hex');
const root = name => ({ type: 2, data: { type: 1, name, options: [] } });
const control = custom_id => ({ type: 3, data: { component_type: 2, custom_id } });
const base = { application_id: application, guild_id: guild, id: '44444444444444444',
  member: { user: { id: administrator }, roles: [] } };
const router = (await build({ stdin: { contents: `
  import { communityBackendInteraction } from './src/community-backend.ts';
  export default { async fetch(request, env) {
    const result = await communityBackendInteraction(request, env); if (result) return result;
    const originalBodyUnread = !request.bodyUsed;
    const body = await request.arrayBuffer();
    const bodySha = [...new Uint8Array(await crypto.subtle.digest('SHA-256', body))].map(byte => byte.toString(16).padStart(2, '0')).join('');
    return Response.json({ legacy: true, originalBodyUnread, bodySha });
  } };
`, resolveDir: process.cwd(), sourcefile: 'community-backend-runtime-fixture.ts' }, bundle: true, format: 'esm', write: false })).outputFiles[0].text;
// Independent transport fixture; primary relay tests cover the real verifier and AccountStore authority.
const receiverScript = `
export default { async fetch(request, env) {
  const signature = request.headers.get('X-Signature-Ed25519') ?? '', timestamp = request.headers.get('X-Signature-Timestamp') ?? '';
  if (!/^[a-f0-9]{128}$/i.test(signature) || !/^[0-9]{10}$/.test(timestamp)) return new Response('Invalid signature', {status:401});
  const bytes = new Uint8Array(await request.arrayBuffer()), prefix = new TextEncoder().encode(timestamp);
  const signedBytes = new Uint8Array(prefix.byteLength + bytes.byteLength); signedBytes.set(prefix); signedBytes.set(bytes, prefix.byteLength);
  const fromHex = value => Uint8Array.from(value.match(/../g), pair => parseInt(pair, 16));
  const key = await crypto.subtle.importKey('raw', fromHex(env.DISCORD_PUBLIC_KEY), 'Ed25519', false, ['verify']);
  if (!await crypto.subtle.verify('Ed25519', key, fromHex(signature), signedBytes)) return new Response('Invalid signature', {status:401});
  const data = JSON.parse(new TextDecoder().decode(bytes));
  if (data.application_id !== env.DISCORD_APPLICATION_ID) return new Response('Wrong application', {status:401});
  const actor = data.member?.user ?? data.user;
  if (data.guild_id !== env.TEST_GUILD && !(data.type === 3 && data.guild_id === undefined)) return new Response('Wrong guild', {status:403});
  if (actor?.id !== env.TEST_ADMIN) return Response.json({type:4,data:{content:'Fixture actor required.',flags:64,allowed_mentions:{parse:[]}}});
  return Response.json({type:5,data:{flags:64},proof:{path:new URL(request.url).pathname,
    signature:request.headers.get('X-Signature-Ed25519'),timestamp:request.headers.get('X-Signature-Timestamp'),
    contentType:request.headers.get('Content-Type'),bodySha:[...new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))].map(byte=>byte.toString(16).padStart(2,'0')).join('')}});
} };
`;

async function runtime(t, overrides = {}, script = receiverScript) {
  const options = convertV4MiniflareOptions({ workers: [
    { name: 'community-forwarder', modules: true, script: router, compatibilityDate: '2026-09-21',
      bindings: { BACKEND_BOT_APPLICATION_ID: application, SKY_MYCE_BACKEND_URL: 'https://account.example', ...overrides },
      serviceBindings: { SKY_MYCE_BACKEND: 'community-account-target' }, outboundService: () => { throw Error('External traffic prohibited'); } },
    { name: 'community-account-target', modules: true, script, compatibilityDate: '2026-09-21',
      bindings: { DISCORD_APPLICATION_ID: application, DISCORD_PUBLIC_KEY: publicKey, TEST_GUILD: guild, TEST_ADMIN: administrator } },
  ] });
  const worker = new Miniflare(options);
  t.after(() => worker.dispose());
  return worker;
}
async function signed(worker, payload, changes = {}) {
  const body = changes.body ?? JSON.stringify({ ...base, ...payload }, null, 1);
  const stamp = String(Math.floor(Date.now() / 1000));
  const signature = Buffer.from(await crypto.subtle.sign('Ed25519', keys.privateKey, new TextEncoder().encode(stamp + body))).toString('hex');
  const transmitted = body + (changes.tampered ? ' ' : '');
  const response = await worker.dispatchFetch(changes.url ?? 'https://legacy.example/discord/interactions', {
    method: 'POST', body: transmitted, headers: { 'Content-Type': 'application/json; charset=utf-8',
      'X-Signature-Ed25519': signature, 'X-Signature-Timestamp': stamp, ...(changes.headers ?? {}) },
  });
  return { response, body: transmitted, signature, stamp };
}

test('actual Workers service binding preserves exact signed bytes, path and headers for current Backend roots', async t => {
  const worker = await runtime(t);
  for (const name of ['admin', 'account', 'blacklist', 'session', 'skyhawk']) {
    const result = await signed(worker, { ...root(name), padding: 'Unicode \u2603 and whitespace preserved' });
    assert.equal(result.response.status, 200);
    const actual = await result.response.json();
    assert.equal(actual.type, 5); assert.equal(actual.data.flags, 64);
    assert.deepEqual(actual.proof, { path: '/discord/backend/interactions', signature: result.signature,
      timestamp: result.stamp, contentType: 'application/json; charset=utf-8', bodySha: digest(result.body) });
  }
  const autocomplete = await signed(worker, { ...root('skyhawk'), type: 4 });
  assert.equal((await autocomplete.response.json()).proof.bodySha, digest(autocomplete.body));
});

test('known controls support guild and DM while the fixture target verifies signatures, guild and actor', async t => {
  const worker = await runtime(t);
  for (const id of ['sky:confirm:' + 'a'.repeat(43), 'sky:cancel:' + 'a'.repeat(43),
    'sky:page:devices:' + administrator + ':1', 'sky:page:blacklist:all:100',
    'sky:admin:info:' + administrator, 'sky:admin:audit:' + administrator]) {
    for (const dm of [false, true]) {
      const result = await signed(worker, { ...control(id), ...(dm ? { guild_id: undefined, member: undefined, user: { id: administrator } } : {}) });
      assert.equal((await result.response.json()).proof.bodySha, digest(result.body));
    }
  }
  const denied = await signed(worker, { ...root('blacklist'), member: { user: { id: '55555555555555555' }, roles: ['66666666666666666'], permissions: '8' } });
  assert.match((await denied.response.json()).data.content, /Fixture actor required/);
  assert.equal((await signed(worker, { ...root('blacklist'), guild_id: '77777777777777777' })).response.status, 403);
  assert.equal((await signed(worker, root('blacklist'), { tampered: true })).response.status, 401);
});

test('unknown, prompt, wrong-app and old User controls fall through with the original body unread and intact', async t => {
  const worker = await runtime(t);
  const payloads = [root('prompt'), root('prompt-status'), root('hwid'), root('authorise'), root('cosmetics'),
    { type: 2, data: { type: 3, name: 'Run prompt' } }, { type: 5, data: { custom_id: 'prompt:job:reply' } },
    control('prompt:job:approve'), control('sky:user:status'), control('sky:page:devices:all:0'),
    control('sky:page:devices:all:101'), control('sky:page:unknown:all:1'), control('sky:confirm:bad'),
    { ...root('account'), application_id: '88888888888888888' }];
  for (const payload of payloads) {
    const result = await signed(worker, payload);
    assert.deepEqual(await result.response.json(), { legacy: true, originalBodyUnread: true, bodySha: digest(result.body) });
  }
});

test('body framing is bounded without changing the legacy reader, and an exact-limit signed envelope still forwards', async t => {
  const worker = await runtime(t);
  const empty = JSON.stringify({ ...base, ...root('account'), padding: '' });
  const body = JSON.stringify({ ...base, ...root('account'), padding: 'x'.repeat(96 * 1024 - Buffer.byteLength(empty)) });
  assert.equal(Buffer.byteLength(body), 96 * 1024);
  const allowed = await signed(worker, root('account'), { body });
  assert.equal((await allowed.response.json()).proof.bodySha, digest(body));
  for (const invalid of [body + ' ', '{invalid json', '[]']) {
    const result = await signed(worker, root('account'), { body: invalid });
    assert.deepEqual(await result.response.json(), { legacy: true, originalBodyUnread: true, bodySha: digest(invalid) });
  }
});

test('invalid origins, backend errors and redirects fail privately without public fetch or legacy command execution', async t => {
  for (const url of ['http://account.example', 'https://account.example/path', 'https://user@account.example',
    'https://account.example?query=1', 'https://account.example#fragment', 'https://legacy.example']) {
    const worker = await runtime(t, { SKY_MYCE_BACKEND_URL: url });
    const actual = await (await signed(worker, root('blacklist'))).response.json();
    assert.equal(actual.type, 4); assert.equal(actual.data.flags, 64); assert.match(actual.data.content, /unavailable/);
  }
  for (const status of [302, 503]) {
    const worker = await runtime(t, {}, `export default { fetch() { return new Response(null,{status:${status},headers:{Location:'https://external.invalid'}}); } };`);
    const actual = await (await signed(worker, root('blacklist'))).response.json();
    assert.equal(actual.type, 4); assert.equal(actual.data.flags, 64); assert.match(actual.data.content, /unavailable/);
    const autocomplete = await (await signed(worker, { ...root('skyhawk'), type: 4 })).response.json();
    assert.deepEqual(autocomplete, { type: 8, data: { choices: [] } });
  }
});
