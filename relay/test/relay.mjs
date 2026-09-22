import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { generateKeyPairSync, sign } from "node:crypto";
import { Miniflare, convertV4MiniflareOptions } from "miniflare";

// Replace public trust roots only in this in-memory test bundle. Production has no auth bypass.
const rsa = () => generateKeyPairSync("rsa", { modulusLength: 2048 });
const authority = rsa(), profileAuthority = rsa(), playerKey = rsa();
const publicDer = pair => pair.publicKey.export({ format: "der", type: "spki" });
const publicBase64 = pair => publicDer(pair).toString("base64");
const trusted = JSON.parse(await readFile("src/minecraft-keys.json", "utf8"));
let script = await readFile(".test-build/index.js", "utf8");
for (const [original, replacement] of [[trusted.playerCertificateKeys[0].publicKey, publicBase64(authority)],
  [trusted.profilePropertyKeys[1].publicKey, publicBase64(profileAuthority)]]) {
  assert.ok(script.includes(original));
  script = script.replaceAll(original, replacement);
}
const identities = new Map([["Alice", "a".repeat(32)], ["Bob", "b".repeat(32)], ["Carol", "c".repeat(32)]]);
function proof(name, challenge, changes = {}) {
  const id = identities.get(name), expires = changes.expires ?? Date.now() + 3600000;
  const expiry = Buffer.alloc(8); expiry.writeBigInt64BE(BigInt(expires));
  const certificate = Buffer.concat([Buffer.from(id, "hex"), expiry, publicDer(playerKey)]);
  const profile = Buffer.from(JSON.stringify({ profileId: id, profileName: name,
    timestamp: changes.timestamp ?? Date.now(), textures: {} })).toString("base64");
  return { type: "authenticate", name: changes.name ?? name, uuid: id, expires, publicKey: publicBase64(playerKey),
    keySignature: sign("RSA-SHA1", certificate, authority.privateKey).toString("base64"),
    proof: sign("RSA-SHA256", Buffer.from(`SkyMyce relay v2\n${challenge}\n${id}\n${changes.name ?? name}`), playerKey.privateKey).toString("base64"),
    profile, profileSignature: sign("RSA-SHA1", Buffer.from(profile), profileAuthority.privateKey).toString("base64") };
}
const options = convertV4MiniflareOptions({
  name: "relay-check",
  modules: true, script, compatibilityDate: "2026-09-21",
  bindings: { ROOMS: "friends,testing" },
  durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
  outboundService: () => { throw new Error("Account verification must not make outbound requests"); },
});
options.workers[0].config.env.CONNECT_LIMIT = { type: "rate-limit", namespace: "7112026", simple: { limit: 20, period: 60 } };
const mf = new Miniflare(options);
const sockets = [];
function inbox(ws) {
  const queued = [], waiting = [];
  ws.addEventListener("message", event => {
    const data = event.data === "pong" ? "pong" : JSON.parse(event.data);
    if (waiting.length) waiting.shift()(data); else queued.push(data);
  });
  return () => queued.length ? Promise.resolve(queued.shift()) : new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Timed out waiting for WebSocket message")), 3000);
    waiting.push(data => { clearTimeout(timer); resolve(data); });
  });
}
async function connect(name, room = "friends") {
  const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, {
    headers: { Upgrade: "websocket", "CF-Connecting-IP": `127.0.0.${sockets.length + 1}` },
  });
  assert.equal(response.status, 101);
  const ws = response.webSocket;
  sockets.push(ws);
  const next = inbox(ws);
  ws.accept();
  const challenge = await next();
  assert.equal(challenge.type, "challenge");
  assert.equal(challenge.protocol, 2);
  return { ws, next, challenge: challenge.serverId,
    authenticate(data = proof(name, challenge.serverId)) { ws.send(JSON.stringify(data)); } };
}
function closeEvent(ws) { return new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error("Expected connection close")), 3000);
  ws.addEventListener("close", event => { clearTimeout(timer); resolve(event); }, { once: true });
}); }
try {
  assert.equal((await mf.dispatchFetch("http://localhost/health")).status, 200);
  assert.equal((await mf.dispatchFetch("http://localhost/websocket")).status, 426);
  assert.equal((await mf.dispatchFetch("http://localhost/websocket?room=unlisted", { headers: { Upgrade: "websocket" } })).status, 403);
  const alice = await connect("Alice"); alice.authenticate(); assert.equal((await alice.next()).type, "ready");
  const bob = await connect("Bob"); bob.authenticate(); assert.equal((await bob.next()).type, "ready");
  const carol = await connect("Carol", "testing"); carol.authenticate(); await carol.next();
  let cacheId = 100;
  async function cache(client, data) {
    client.ws.send(JSON.stringify({ id: (cacheId++).toString(16).padStart(32, "0"), ...data }));
    const response = await client.next();
    assert.equal(response.type, "stats_result");
    return response;
  }
  const stats = { state: "AVAILABLE", catacombs: 52, classes: { ARCHER: 52, MAGE: 2 },
    selectedClass: "MAGE", completionTimes: { F7: 300000 }, sPlusTimes: { F7: 310000 }, completedFloors: ["F7"] };
  const lookup = { type: "stats_get", name: "Bob", uuid: identities.get("Bob") };
  const first = await cache(alice, lookup), racing = await cache(bob, lookup);
  assert.equal(first.record, null); assert.match(first.upload, /^[a-f0-9]{32}$/);
  const entry = { type: "stats_put", name: "Bob", uuid: identities.get("Bob"), stats, fetchedAt: Date.now(), upload: first.upload };
  assert.equal((await cache(alice, { ...entry, upload: "0".repeat(32) })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, fetchedAt: Date.now() - 660000 })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, stats: { ...stats, sPlusTimes: { F7: -1 } } })).error, "invalid_upload");
  assert.equal((await cache(alice, { ...entry, stats: { ...stats, state: { toString: "AVAILABLE" } } })).error, "invalid_upload");
  assert.equal((await cache(alice, entry)).stored, true);
  assert.equal((await cache(bob, { ...entry, upload: racing.upload, stats: { ...stats, catacombs: 99 } })).stored, false);
  const cached = await cache(bob, lookup);
  assert.deepEqual(cached.record.stats, stats); assert.equal(cached.record.fetchedAt, entry.fetchedAt);
  assert.equal(cached.upload, undefined); // No upload needed and no duplicate row or renewed age.
  assert.equal((await cache(carol, lookup)).record, null); // Room isolation includes stats.
  assert.equal((await cache(bob, { ...lookup, uuid: identities.get("Alice") })).record, null);
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  assert.deepEqual((await cache(bob, lookup)).record.stats, stats); // SQLite survives hibernation.
  const id = "1".repeat(32);
  alice.ws.send(JSON.stringify({ type: "message", id, to: "Bob", from: "Forged", text: "LFG F7?" }));
  const received = await bob.next();
  assert.deepEqual(received, { type: "message", id, from: "Alice", uuid: identities.get("Alice"), text: "LFG F7?" });
  bob.ws.send(JSON.stringify({ type: "ack", to: "Alice", id }));
  assert.deepEqual(await alice.next(), { type: "ack", id, from: "Bob", uuid: identities.get("Bob") });
  alice.ws.send(JSON.stringify({ type: "message", id: "2".repeat(32), to: "Carol", text: "must not cross rooms" }));
  assert.equal((await alice.next()).code, "offline");
  carol.ws.send("ping"); assert.equal(await carol.next(), "pong"); // No leaked message preceded the pong.
  for (const mutate of [
    (data, challenge) => ({ ...data, proof: Buffer.alloc(256).toString("base64") }),
    data => ({ ...data, uuid: identities.get("Bob") }),
    () => proof("Alice", alice.challenge), // Valid proof from another socket cannot be replayed.
    (data, challenge) => proof("Alice", challenge, { name: "Bob" }), // A verified UUID cannot impersonate a name.
    (data, challenge) => proof("Alice", challenge, { expires: Date.now() - 1 }),
    (data, challenge) => proof("Alice", challenge, { timestamp: Date.now() - 2 * 86400000 }),
    data => ({ ...data, keySignature: data.profileSignature }),
    data => ({ ...data, profileSignature: data.keySignature }),
  ]) {
    const fake = await connect("Alice", "testing");
    const rejected = closeEvent(fake.ws);
    fake.authenticate(mutate(proof("Alice", fake.challenge), fake.challenge));
    assert.equal((await rejected).code, 4003);
  }
  const unauth = await connect("Bob", "testing");
  const blocked = closeEvent(unauth.ws);
  unauth.ws.send(JSON.stringify({ type: "message", id, to: "Carol", text: "not authenticated" }));
  assert.equal((await blocked).code, 1008);
  const closed = closeEvent(bob.ws);
  bob.ws.send(JSON.stringify({ type: "message", id: "3".repeat(32), to: "Alice", text: "x".repeat(2050) }));
  assert.equal((await closed).code, 1009);
  const spam = closeEvent(alice.ws);
  for (let i = 0; i < 15; i++) alice.ws.send(JSON.stringify({ type: "message", id: i.toString(16).padStart(32, "0"), to: "Offline", text: "rate test" }));
  assert.equal((await spam).code, 1008);
  const cleanClose = closeEvent(carol.ws);
  carol.ws.close(1000, "Diagnostic complete");
  assert.equal((await cleanClose).code, 1000);
  console.log("Relay checks passed: signed account proof, shared stats persistence/deduplication/expiry validation, hibernation, receipts, isolation, and limits.");
} finally {
  for (const ws of sockets) { try { ws.close(); } catch {} }
  await mf.dispose();
}
