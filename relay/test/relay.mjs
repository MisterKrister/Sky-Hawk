import assert from "node:assert/strict";
import { Miniflare, Response, convertV4MiniflareOptions } from "miniflare";

// Mock ONLY Mojang's outbound response; production has no authentication bypass.
const identities = new Map([["Alice", "a".repeat(32)], ["Bob", "b".repeat(32)], ["Carol", "c".repeat(32)]]);
const proofs = new Map();
const options = convertV4MiniflareOptions({
  name: "relay-check",
  modules: true, scriptPath: ".test-build/index.js", compatibilityDate: "2026-09-21",
  bindings: { ROOMS: "friends,testing" },
  durableObjects: { RELAY_ROOMS: { className: "RelayRoom", useSQLite: true } },
  outboundService: async request => {
    const url = new URL(request.url);
    assert.equal(url.origin + url.pathname, "https://sessionserver.mojang.com/session/minecraft/hasJoined");
    const name = url.searchParams.get("username");
    const valid = proofs.get(url.searchParams.get("serverId")) === name;
    return valid ? Response.json({ name, id: identities.get(name) }) : new Response(null, { status: 204 });
  },
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
async function connect(name, room = "friends", prove = true) {
  const response = await mf.dispatchFetch(`http://localhost/websocket?room=${room}`, { headers: { Upgrade: "websocket" } });
  assert.equal(response.status, 101);
  const ws = response.webSocket;
  sockets.push(ws);
  const next = inbox(ws);
  ws.accept();
  const challenge = await next();
  assert.equal(challenge.type, "challenge");
  if (prove) proofs.set(challenge.serverId, name);
  return { ws, next, authenticate() { ws.send(JSON.stringify({ type: "authenticate", name, uuid: identities.get(name) })); } };
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
  await mf.unsafeEvictDurableObject("relay-check", "RelayRoom", { name: "friends", webSockets: "hibernate" });
  const id = "1".repeat(32);
  alice.ws.send(JSON.stringify({ type: "message", id, to: "Bob", from: "Forged", text: "LFG F7?" }));
  const received = await bob.next();
  assert.deepEqual(received, { type: "message", id, from: "Alice", uuid: identities.get("Alice"), text: "LFG F7?" });
  bob.ws.send(JSON.stringify({ type: "ack", to: "Alice", id }));
  assert.deepEqual(await alice.next(), { type: "ack", id, from: "Bob", uuid: identities.get("Bob") });
  alice.ws.send(JSON.stringify({ type: "message", id: "2".repeat(32), to: "Carol", text: "must not cross rooms" }));
  assert.equal((await alice.next()).code, "offline");
  carol.ws.send("ping"); assert.equal(await carol.next(), "pong"); // No leaked message preceded the pong.
  const fake = await connect("Alice", "testing", false);
  const rejected = closeEvent(fake.ws); fake.authenticate(); assert.equal((await rejected).code, 4003);
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
  console.log("Relay checks passed: account verification, recipient acknowledgement after hibernation, room isolation, limits, and idle ping.");
} finally {
  for (const ws of sockets) { try { ws.close(); } catch {} }
  await mf.dispose();
}
