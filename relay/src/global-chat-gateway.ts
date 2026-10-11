import { DurableObject } from "cloudflare:workers";
import { GLOBAL_ROOM, discordPacket, discordMessageLinks, embedPacket, minecraftContent, minecraftCard, discordOperation, discordMedia, validBridgeOperation, minecraftId, snowflake } from "./gateway-chat";

export type GlobalChatConfig = {
  GLOBAL_CHAT_GATEWAY?: DurableObjectNamespace<GlobalChatGateway>;
  GLOBAL_CHAT_ENABLED?: string;
  GLOBAL_CHAT_CHANNEL_ID?: string;
  DISCORD_IRC_CHANNEL_ID?: string;
  DISCORD_IRC_TEST_CHANNEL_ID?: string;
  IRC_ROOMS?: string;
  DISCORD_GUILD_ID?: string;
  USER_BOT_APPLICATION_ID?: string;
  USER_BOT_TOKEN?: string;
  DISCORD_BRIDGE_SECRET?: string;
  GLOBAL_CHAT_CONTROL_SECRET?: string;
  SKY_MYCE_BACKEND?: Fetcher;
};
type Json = Record<string, any>;
type BridgeAttempt = { generation: number; controller: AbortController; deadline: number };
type State = { running: boolean; room?: string; channel?: string; guild?: string; application?: string; session?: string; resume?: string; sequence?: number; ownBot?: string;
  gatewayDue: number; bridgeDue: number; gatewayFailures: number; bridgeFailures: number; blocked?: string; lastError?: string; lastAck?: number; postDue?: number };
const LIFETIME = 900_000;
const TTL = 120_000, MAX_PENDING = 256, MAX_RECEIPTS = 1024;
/** Keep Discord's full cooldown, rounding up; invalid/overflowing values use a safe retry delay. */
function retryAfterDeadline(value: string | null, minimum = 1000, fallback = 5000): number {
  const now = Date.now(), milliseconds = Number(value) * 1000;
  const valid = value !== null && value.trim() !== "" && Number.isFinite(milliseconds) && milliseconds > 0 &&
    Math.ceil(milliseconds) <= Number.MAX_SAFE_INTEGER - now;
  return now + (valid ? Math.max(minimum, Math.ceil(milliseconds)) : fallback);
}
const encoder = new TextEncoder();
const hex = (bytes: ArrayBuffer): string => [...new Uint8Array(bytes)].map(byte => byte.toString(16).padStart(2, "0")).join("");
function gatewayUrl(value: unknown): URL | null {
  if (typeof value !== "string" || value.length > 256) return null;
  try {
    const url = new URL(value);
    if (url.protocol !== "wss:" || !/^gateway(?:-[a-z0-9-]+)?\.discord\.gg$/.test(url.hostname) || url.port || url.username || url.password) return null;
    url.search = "?v=10&encoding=json";
    return url;
  } catch { return null; }
}
function selectedChannel(env: GlobalChatConfig, room: string): string | undefined {
  const channels: Record<string, string | undefined> = { [GLOBAL_ROOM]: env.GLOBAL_CHAT_CHANNEL_ID, friends: env.DISCORD_IRC_CHANNEL_ID, testing: env.DISCORD_IRC_TEST_CHANNEL_ID };
  if (room !== GLOBAL_ROOM && !String(env.IRC_ROOMS ?? "").split(",").map(value => value.trim()).includes(room)) return undefined;
  const channel = channels[room];
  if (!snowflake.test(channel ?? "")) return undefined;
  // Existing global operation remains unchanged; room bridges never share any configured audience.
  if (room !== GLOBAL_ROOM && Object.entries(channels).some(([other, value]) => other !== room && value === channel)) return undefined;
  return channel;
}
function configured(env: GlobalChatConfig, room = GLOBAL_ROOM): boolean {
  return !!env.SKY_MYCE_BACKEND && [selectedChannel(env, room), env.DISCORD_GUILD_ID, env.USER_BOT_APPLICATION_ID].every(id => typeof id === "string" && snowflake.test(id)) &&
    [env.USER_BOT_TOKEN, env.DISCORD_BRIDGE_SECRET, env.GLOBAL_CHAT_CONTROL_SECRET].every(secret => typeof secret === "string" && secret.length >= 32 && secret.length <= 512);
}
function routedRoom(path: string): string | undefined {
  if (path === "/global-chat/health" || path === "/global-chat/control") return GLOBAL_ROOM;
  return /^\/irc\/(friends|testing)\/(health|control)$/u.exec(path)?.[1];
}
async function authorized(request: Request, env: GlobalChatConfig): Promise<boolean> {
  const secret = env.GLOBAL_CHAT_CONTROL_SECRET, bearer = request.headers.get("Authorization");
  if (!secret || secret.length < 32 || !bearer?.startsWith("Bearer ") || bearer.length > 520) return false;
  const [expected, actual] = await Promise.all([secret, bearer.slice(7)].map(value => crypto.subtle.digest("SHA-256", encoder.encode(value))));
  const a = new Uint8Array(expected), b = new Uint8Array(actual);
  let different = 0;
  for (let index = 0; index < a.length; index++) different |= a[index] ^ b[index];
  return different === 0;
}
export async function globalChatRoute(request: Request, env: GlobalChatConfig): Promise<Response | null> {
  const room = routedRoom(new URL(request.url).pathname); if (!room) return null;
  const channel = selectedChannel(env, room);
  if (!env.GLOBAL_CHAT_GATEWAY || !channel) return Response.json({ enabled: false, configured: false,
    error: room === GLOBAL_ROOM ? "gateway_unconfigured" : "room_channel_unconfigured_or_conflicting" }, { status: 503 });
  // Coordinators and channels come exclusively from the operator's strict configuration.
  const name = room === GLOBAL_ROOM ? `global-chat:${channel}` : `irc:${room}:${channel}`;
  return env.GLOBAL_CHAT_GATEWAY.getByName(name).fetch(request);
}

/** Outbound sockets deliberately keep this one DO active; server WebSocket hibernation does not apply. */
export class GlobalChatGateway extends DurableObject<GlobalChatConfig> {
  private state: State;
  private get room(): string { return this.state.room ?? GLOBAL_ROOM; }
  private get scope(): string { return this.room === GLOBAL_ROOM ? "global" : "room"; }
  private get channelId(): string | undefined { return selectedChannel(this.env, this.room); }
  private gateway?: WebSocket;
  private bridge?: WebSocket;
  private bridgeConnecting?: BridgeAttempt;
  private bridgeGeneration = 0;
  private gatewayReady = false;
  private gatewayResuming = false;
  private bridgeReady = false;
  private richReady = false;
  private richOffered = false;
  private capabilityId?: string;
  private readonly offeredMinecraft = new Map<string, string>();
  private offeredMinecraftBytes = 0;
  private heartbeatInterval = 0;
  private heartbeatDue = Infinity;
  private heartbeatPending = false;
  private gatewayDeadline = Infinity;
  private bridgeDeadline = Infinity;
  private bridgePingDue = Infinity;
  private bridgePongPending = false;
  private readonly deliveries = new Set<string>();
  private chain: Promise<unknown> = Promise.resolve();
  private queued = 0;

  constructor(ctx: DurableObjectState, env: GlobalChatConfig) {
    super(ctx, env);
    const room = /^irc:(friends|testing):\d{17,20}$/u.exec(ctx.id.name ?? "")?.[1] ?? GLOBAL_ROOM;
    const channel = selectedChannel(env, room);
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS gateway_state (id INTEGER PRIMARY KEY CHECK(id = 1), value TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_pending (id TEXT PRIMARY KEY, packet TEXT NOT NULL, expires INTEGER NOT NULL, due INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_seen (id TEXT PRIMARY KEY, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_receipts (id TEXT PRIMARY KEY, discord_id TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_previews (id TEXT PRIMARY KEY, value TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_sources (id TEXT PRIMARY KEY, value TEXT NOT NULL, expires INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS gateway_operations (id TEXT PRIMARY KEY, message_id TEXT NOT NULL, revision INTEGER NOT NULL, discord_id TEXT NOT NULL, expires INTEGER NOT NULL);`);
    const saved = ctx.storage.sql.exec<{ value: string }>("SELECT value FROM gateway_state WHERE id = 1").toArray()[0];
    this.state = saved ? JSON.parse(saved.value) : { running: false, room, channel, guild: env.DISCORD_GUILD_ID,
      application: env.USER_BOT_APPLICATION_ID, gatewayDue: 0, bridgeDue: 0, gatewayFailures: 0, bridgeFailures: 0 };
    if (saved && (this.state.channel !== channel || (this.state.room ?? GLOBAL_ROOM) !== room || this.state.guild !== env.DISCORD_GUILD_ID || this.state.application !== env.USER_BOT_APPLICATION_ID)) {
      this.state.running = false; this.state.blocked = "configuration_changed"; this.clearSession(); delete this.state.ownBot;
      ctx.storage.sql.exec("DELETE FROM gateway_pending; DELETE FROM gateway_previews; DELETE FROM gateway_sources; DELETE FROM gateway_operations;"); this.persist();
    }
    if (this.state.running && this.env.GLOBAL_CHAT_ENABLED === "true" && configured(this.env, this.room)) ctx.blockConcurrencyWhile(() => this.schedule());
  }

  private persist(): void { this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_state VALUES (1, ?)", JSON.stringify(this.state)); }
  private prune(): void {
    const now = Date.now();
    for (const table of ["gateway_pending", "gateway_seen", "gateway_receipts", "gateway_previews", "gateway_sources", "gateway_operations"]) this.ctx.storage.sql.exec(`DELETE FROM ${table} WHERE expires <= ?`, now);
    this.ctx.storage.sql.exec("DELETE FROM gateway_operations WHERE id IN (SELECT id FROM gateway_operations ORDER BY expires DESC, id DESC LIMIT -1 OFFSET 4096)");
    for (const table of ["gateway_seen", "gateway_receipts", "gateway_previews", "gateway_sources"]) this.ctx.storage.sql.exec(`DELETE FROM ${table} WHERE id IN (SELECT id FROM ${table} ORDER BY expires DESC, id DESC LIMIT -1 OFFSET ?)`, MAX_RECEIPTS);
  }
  private enqueue<T>(work: () => Promise<T>): Promise<T> {
    const next = this.chain.then(work);
    // The sequencing tail must not retain a control Response and its actor request after completion.
    this.chain = next.then(() => undefined).catch(() => { this.state.lastError = "transport_failure"; this.persist(); });
    return next;
  }
  private event(work: () => Promise<void>): void {
    if (++this.queued > 128) {
      this.queued--;
      this.disconnectGateway("event_backlog");
      this.ctx.waitUntil(this.schedule());
      return;
    }
    this.ctx.waitUntil(this.enqueue(work).finally(() => { this.queued--; }).catch(() => {}));
  }
  async fetch(request: Request): Promise<Response> {
    if (routedRoom(new URL(request.url).pathname) === this.room && new URL(request.url).pathname.endsWith("/health") && request.method === "GET") return Response.json(this.health());
    if (routedRoom(new URL(request.url).pathname) !== this.room || !new URL(request.url).pathname.endsWith("/control") || request.method !== "POST") return new Response("Not found", { status: 404 });
    if (!(await authorized(request, this.env))) return Response.json({ error: "not_authorized" }, { status: 401 });
    if (Number(request.headers.get("Content-Length")) > 256) return new Response("Too large", { status: 413 });
    let body: Json;
    try {
      const reader = request.body?.getReader();
      let text = "", length = 0;
      if (reader) while (true) {
        const part = await reader.read();
        if (part.done) break;
        length += part.value.byteLength;
        if (length > 256) { await reader.cancel(); return new Response("Too large", { status: 413 }); }
        text += new TextDecoder().decode(part.value);
      }
      body = JSON.parse(text);
    } catch { return Response.json({ error: "invalid_request" }, { status: 400 }); }
    if (body?.action !== "start" && body?.action !== "stop" && body?.action !== "status") return Response.json({ error: "invalid_action" }, { status: 400 });
    return this.enqueue(async () => {
      if (body.action === "start") {
        if (this.env.GLOBAL_CHAT_ENABLED !== "true" || !configured(this.env, this.room)) return Response.json({ ...this.health(), error: "gateway_disabled_or_unconfigured" }, { status: 503 });
        this.state.running = true; delete this.state.blocked;
        this.state.channel = this.channelId; this.state.guild = this.env.DISCORD_GUILD_ID; this.state.application = this.env.USER_BOT_APPLICATION_ID;
        this.state.gatewayDue = this.state.bridgeDue = Date.now();
        this.persist(); await this.tick();
      } else if (body.action === "stop") {
        this.state.running = false; this.persist();
        this.closeGateway(); this.closeBridge();
        this.ctx.storage.sql.exec("DELETE FROM gateway_pending; DELETE FROM gateway_previews;");
        await this.ctx.storage.deleteAlarm();
      }
      return Response.json(this.health());
    });
  }
  private health(): Json {
    this.prune();
    return { room: this.room, scope: this.scope, enabled: this.env.GLOBAL_CHAT_ENABLED === "true", configured: configured(this.env, this.room), running: this.state.running,
      gatewayReady: this.gatewayReady, bridgeReady: this.bridgeReady, richChatVersion: this.richReady ? 1 : 0, blocked: this.state.blocked ?? null,
      lastError: this.state.lastError ?? null, lastDeliveryAt: this.state.lastAck ?? null,
      pendingMessages: this.ctx.storage.sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM gateway_pending").one().count };
  }
  async alarm(): Promise<void> { await this.enqueue(() => this.tick()); }
  private async schedule(): Promise<void> {
    if (!this.state.running || this.env.GLOBAL_CHAT_ENABLED !== "true" || !configured(this.env, this.room) || this.state.blocked) { await this.ctx.storage.deleteAlarm(); return; }
    const pending = this.ctx.storage.sql.exec<{ due: number }>("SELECT MIN(due) AS due FROM gateway_pending WHERE ? = 1 OR id NOT LIKE 'op:%'", this.richReady ? 1 : 0).one().due;
    const next = Math.min(this.gateway ? this.heartbeatDue : this.state.gatewayDue,
      this.bridge ? this.bridgePingDue : this.bridgeConnecting?.deadline ?? this.state.bridgeDue, this.gatewayDeadline, this.bridgeDeadline,
      this.bridgeReady && (!this.richOffered || this.richReady) && pending != null ? pending : Infinity, Date.now() + TTL);
    await this.ctx.storage.setAlarm(Math.max(Date.now() + 100, next));
  }
  private async tick(): Promise<void> {
    this.prune();
    if (!this.state.running || this.env.GLOBAL_CHAT_ENABLED !== "true" || !configured(this.env, this.room) || this.state.blocked) { this.closeGateway(); this.closeBridge(); await this.ctx.storage.deleteAlarm(); return; }
    const now = Date.now();
    if (this.gateway && now >= this.gatewayDeadline) this.disconnectGateway("gateway_ready_timeout");
    if (this.bridge && now >= this.bridgeDeadline) this.disconnectBridge("bridge_ready_timeout");
    if (this.bridgeConnecting && now >= this.bridgeConnecting.deadline) this.disconnectBridge("bridge_connect_timeout");
    if (this.gateway && now >= this.heartbeatDue) {
      if (this.heartbeatPending) this.disconnectGateway("gateway_heartbeat_timeout");
      else this.heartbeat();
    }
    if (this.bridge && now >= this.bridgePingDue) {
      if (this.bridgePongPending) this.disconnectBridge("bridge_heartbeat_timeout");
      else { this.bridge.send("ping"); this.bridgePongPending = true; this.bridgePingDue = now + 30_000; }
    }
    if (!this.gateway && now >= this.state.gatewayDue) await this.connectGateway();
    if (!this.bridge && !this.bridgeConnecting && now >= this.state.bridgeDue && !this.state.blocked) {
      const attempt = { generation: ++this.bridgeGeneration, controller: new AbortController(), deadline: Date.now() + 10_000 };
      this.bridgeConnecting = attempt;
      // Handshakes must not hold the serialized heartbeat/control queue. Only adoption joins it.
      this.ctx.waitUntil(this.connectBridge(attempt));
    }
    this.flushDiscord();
    await this.schedule();
  }
  private closeGateway(): void {
    const ws = this.gateway; this.gateway = undefined; this.gatewayReady = false;
    this.gatewayResuming = false;
    this.heartbeatDue = this.gatewayDeadline = Infinity; this.heartbeatInterval = 0; this.heartbeatPending = false;
    try { ws?.close(4000, "Reconnect"); } catch { /* Already closed. */ }
  }
  private closeBridge(): void {
    this.bridgeGeneration++; this.bridgeConnecting?.controller.abort(); this.bridgeConnecting = undefined;
    const ws = this.bridge; this.bridge = undefined; this.bridgeReady = false; this.richReady = false; this.richOffered = false; this.capabilityId = undefined;
    this.offeredMinecraft.clear(); this.offeredMinecraftBytes = 0;
    this.bridgePingDue = this.bridgeDeadline = Infinity; this.bridgePongPending = false;
    try { ws?.close(1000, "Stopped"); } catch { /* Already closed. */ }
  }
  private disconnectGateway(error: string): void {
    this.closeGateway(); this.state.lastError = error;
    this.state.gatewayDue = Date.now() + this.backoff(++this.state.gatewayFailures); this.persist();
  }
  private disconnectBridge(error: string): void {
    this.closeBridge(); this.state.lastError = error;
    this.state.bridgeDue = Date.now() + this.backoff(++this.state.bridgeFailures); this.persist();
  }
  private backoff(failures: number): number { return Math.min(60_000, Math.max(5000, 1000 * 2 ** Math.min(failures, 6))) + Math.floor(Math.random() * 1000); }
  private async externalFetch(request: Request): Promise<Response> { return fetch(request); }
  private async connectGateway(): Promise<void> {
    try {
      if (this.state.ownBot && this.state.ownBot !== this.env.USER_BOT_APPLICATION_ID) this.clearSession();
      let url = this.state.session && this.state.sequence != null ? gatewayUrl(this.state.resume) : null;
      if (!url) {
        const response = await this.externalFetch(new Request("https://discord.com/api/v10/gateway/bot", { headers: { Authorization: `Bot ${this.env.USER_BOT_TOKEN}` }, redirect: "manual", signal: AbortSignal.timeout(10_000) }));
        if (response.status === 401 || response.status === 403) { this.state.blocked = "discord_credentials_rejected"; this.persist(); return; }
        if (!response.ok) {
          this.disconnectGateway(`gateway_discovery_http_${response.status}`);
          if (response.status === 429) {
            this.state.gatewayDue = retryAfterDeadline(response.headers.get("Retry-After"), 5000, 60_000); this.persist();
          }
          return;
        }
        const info = await response.json<Json>(); url = gatewayUrl(info.url);
        if (!url || !Number.isSafeInteger(info.session_start_limit?.remaining) || !Number.isSafeInteger(info.session_start_limit?.reset_after)) throw new Error("Invalid gateway discovery");
        if (info.session_start_limit.remaining < 1) {
          this.state.gatewayDue = Date.now() + Math.max(5000, Math.min(86_400_000, info.session_start_limit.reset_after));
          this.state.lastError = "gateway_session_limit"; this.persist(); return;
        }
      }
      url.protocol = "https:";
      const controller = new AbortController();
      const timeout = setTimeout(() => controller.abort(new DOMException("Gateway handshake timed out", "TimeoutError")), 10_000);
      let response: Response;
      try {
        response = await this.externalFetch(new Request(url, { headers: { Upgrade: "websocket" }, redirect: "manual", signal: controller.signal }));
      } finally { clearTimeout(timeout); }
      // workerd attaches this signal to the upgraded socket; only the handshake has a timeout.
      const ws = response.webSocket;
      if (response.status !== 101 || !ws) throw new Error("Gateway upgrade failed");
      this.gateway = ws; this.gatewayDeadline = Date.now() + 20_000;
      ws.addEventListener("message", event => this.event(async () => {
        if (ws !== this.gateway) return;
        await this.gatewayMessage(event.data); await this.schedule();
      }));
      ws.addEventListener("close", event => this.event(async () => {
        if (ws !== this.gateway) return;
        if ([4004, 4010, 4011, 4012, 4013, 4014].includes(event.code)) { this.state.blocked = `gateway_close_${event.code}`; this.closeGateway(); this.closeBridge(); this.persist(); }
        else {
          if ([4007, 4009].includes(event.code)) this.clearSession();
          this.disconnectGateway("gateway_disconnected");
        }
        await this.schedule();
      }));
      ws.addEventListener("error", () => this.event(async () => { if (ws === this.gateway) { this.disconnectGateway("gateway_socket_error"); await this.schedule(); } }));
      ws.accept();
    } catch { this.disconnectGateway("gateway_connect_failed"); }
  }
  private clearSession(): void { this.gatewayResuming = false; delete this.state.session; delete this.state.resume; delete this.state.sequence; }
  private sendGateway(op: number, data: unknown): void { this.gateway?.send(JSON.stringify({ op, d: data })); }
  private heartbeat(): void { this.sendGateway(1, this.state.sequence ?? null); this.heartbeatPending = true; this.heartbeatDue = Date.now() + this.heartbeatInterval; }
  private async gatewayMessage(raw: unknown): Promise<void> {
    const previous = { ...this.state };
    try {
      // Never persist a resumed sequence past a message that was not durably queued.
      const eventId = typeof raw === "string" && raw.length <= 1_048_576 ? hex(await crypto.subtle.digest("SHA-256", encoder.encode(`${this.state.session ?? "identify"}\n${raw}`))).slice(0, 32) : "";
      let descriptionHash: string | undefined;
      if (typeof raw === "string" && raw.length <= 1_048_576) {
        const frame = JSON.parse(raw), description = frame?.t === "MESSAGE_UPDATE" ? frame.d?.embeds?.[0]?.description : undefined;
        if (typeof description === "string" && description.length <= 4096) descriptionHash = hex(await crypto.subtle.digest("SHA-256", encoder.encode(description)));
      }
      this.ctx.storage.transactionSync(() => this.applyGatewayMessage(raw, eventId, descriptionHash));
    } catch {
      this.state = previous; this.disconnectGateway("gateway_dispatch_failed");
    }
  }
  private applyGatewayMessage(raw: unknown, eventId: string, descriptionHash?: string): void {
    if (typeof raw !== "string" || raw.length > 1_048_576) { this.disconnectGateway("gateway_invalid_frame"); return; }
    let packet: Json;
    try { packet = JSON.parse(raw); } catch { this.disconnectGateway("gateway_invalid_frame"); return; }
    if (!packet || typeof packet !== "object") return;
    if (Number.isSafeInteger(packet.s) && packet.s >= 0) { this.state.sequence = packet.s; this.persist(); }
    if (packet.op === 10) {
      if (this.heartbeatInterval && this.heartbeatDue !== Infinity) { this.disconnectGateway("gateway_duplicate_hello"); return; }
      const interval = packet.d?.heartbeat_interval;
      if (!Number.isSafeInteger(interval) || interval < 1000 || interval > 120_000) { this.disconnectGateway("gateway_invalid_hello"); return; }
      this.heartbeatInterval = interval; this.heartbeatDue = Date.now() + Math.floor(Math.random() * interval);
      if (this.state.session && this.state.sequence != null && this.state.ownBot === this.env.USER_BOT_APPLICATION_ID && gatewayUrl(this.state.resume)) {
        this.sendGateway(6, { token: this.env.USER_BOT_TOKEN, session_id: this.state.session, seq: this.state.sequence });
        this.gatewayResuming = true;
      } else this.sendGateway(2, { token: this.env.USER_BOT_TOKEN, intents: 512 | 1024 | 32768, properties: { os: "linux", browser: "SkyMyce Cloudflare", device: "SkyMyce Cloudflare" } });
    } else if (packet.op === 11) this.heartbeatPending = false;
    else if (packet.op === 1) this.heartbeat();
    else if (packet.op === 7) this.disconnectGateway("gateway_reconnect_requested");
    else if (packet.op === 9) {
      if (packet.d !== true) this.clearSession();
      this.disconnectGateway("gateway_invalid_session");
      this.state.gatewayDue = Math.max(this.state.gatewayDue, Date.now() + 1000 + Math.floor(Math.random() * 4000)); this.persist();
    } else if (packet.op === 0 && packet.t === "READY") {
      this.gatewayResuming = false;
      const data = packet.d;
      if (data?.application?.id !== this.env.USER_BOT_APPLICATION_ID || data?.user?.id !== this.env.USER_BOT_APPLICATION_ID || data?.user?.bot !== true ||
          typeof data.session_id !== "string" || data.session_id.length > 256 || !gatewayUrl(data.resume_gateway_url)) {
        this.state.blocked = "discord_application_mismatch"; this.closeGateway(); this.closeBridge(); this.persist(); return;
      }
      this.state.session = data.session_id; this.state.resume = data.resume_gateway_url; this.state.ownBot = data.user.id;
      this.gatewayReady = true; this.gatewayDeadline = Infinity; this.state.gatewayFailures = 0; this.persist();
    } else if (packet.op === 0 && packet.t === "RESUMED") {
      this.gatewayResuming = false;
      this.gatewayReady = true; this.gatewayDeadline = Infinity; this.state.gatewayFailures = 0; this.persist();
    } else if (packet.op === 0 && (this.gatewayReady || this.gatewayResuming) && ["MESSAGE_CREATE", "MESSAGE_UPDATE", "MESSAGE_DELETE", "MESSAGE_DELETE_BULK", "MESSAGE_REACTION_ADD", "MESSAGE_REACTION_REMOVE"].includes(packet.t)) {
      // Discord replays missed dispatches before RESUMED, using the previously validated bot session.
      if (packet.t === "MESSAGE_CREATE") this.discordMessage(packet.d, false);
      else if (packet.t === "MESSAGE_UPDATE") { if (this.richReady) this.nativeOperation(packet.t, packet.d, eventId, descriptionHash); else this.discordMessage(packet.d, true); }
      else if (packet.t === "MESSAGE_DELETE_BULK" && Array.isArray(packet.d?.ids)) {
        for (const id of packet.d.ids.slice(0, 100).filter((value: unknown) => typeof value === "string" && snowflake.test(value))) this.nativeOperation("MESSAGE_DELETE", { ...packet.d, id }, (BigInt(`0x${eventId}`) ^ BigInt(id)).toString(16).padStart(32, "0"));
      } else this.nativeOperation(packet.t, packet.d, eventId);
    }
  }
  private currentBridgeAttempt(attempt: BridgeAttempt): boolean {
    return this.bridgeConnecting === attempt && this.bridgeGeneration === attempt.generation && this.state.running &&
      this.env.GLOBAL_CHAT_ENABLED === "true" && configured(this.env, this.room) && !this.state.blocked;
  }
  private async connectBridge(attempt: BridgeAttempt): Promise<void> {
    const timeout = setTimeout(() => attempt.controller.abort(new DOMException("Bridge handshake timed out", "TimeoutError")), 10_000);
    try {
      const timestamp = String(Date.now()), nonce = hex(crypto.getRandomValues(new Uint8Array(16)).buffer);
      const key = await crypto.subtle.importKey("raw", encoder.encode(this.env.DISCORD_BRIDGE_SECRET!), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
      const signature = hex(await crypto.subtle.sign("HMAC", key, encoder.encode(`sky-myce-bridge\n${this.room}\n${timestamp}\n${nonce}`)));
      if (!this.currentBridgeAttempt(attempt)) return;
      const response = await this.env.SKY_MYCE_BACKEND!.fetch(new Request(`https://sky-myce-backend/discord/bridge?room=${this.room}`, {
        headers: { Upgrade: "websocket", "X-SkyMyce-Timestamp": timestamp, "X-SkyMyce-Nonce": nonce, "X-SkyMyce-Signature": signature }, redirect: "manual", signal: attempt.controller.signal }));
      clearTimeout(timeout);
      await this.enqueue(async () => {
        const ws = response.webSocket;
        if (!this.currentBridgeAttempt(attempt)) {
          try { ws?.accept(); ws?.close(1000, "Superseded"); } catch { /* Cancelled upgrade or already closed. */ }
          return;
        }
        this.bridgeConnecting = undefined;
        if (response.status !== 101 || !ws) this.disconnectBridge(`bridge_http_${response.status}`);
        else {
          this.bridge = ws; this.bridgeDeadline = Date.now() + 15_000;
          ws.addEventListener("message", event => this.event(async () => { if (ws !== this.bridge) return; await this.bridgeMessage(event.data); await this.schedule(); }));
          ws.addEventListener("close", () => this.event(async () => { if (ws === this.bridge) { this.disconnectBridge("bridge_disconnected"); await this.schedule(); } }));
          ws.addEventListener("error", () => this.event(async () => { if (ws === this.bridge) { this.disconnectBridge("bridge_socket_error"); await this.schedule(); } }));
          try { ws.accept(); } catch { this.disconnectBridge("bridge_connect_failed"); }
        }
        await this.schedule();
      });
    } catch {
      await this.enqueue(async () => {
        if (!this.currentBridgeAttempt(attempt)) return;
        this.disconnectBridge("bridge_connect_failed"); await this.schedule();
      });
    } finally { clearTimeout(timeout); }
  }
  private discordMessage(data: Json, update: boolean): void {
    if (!data || data.guild_id !== this.env.DISCORD_GUILD_ID || data.channel_id !== this.channelId || !snowflake.test(data.id ?? "")) return;
    // A resume may replay old messages. Never revive a delivery after the backend's bounded dedup window.
    const created = Number((BigInt(data.id) >> 22n) + 1420070400000n);
    if (created < Date.now() - TTL || created > Date.now() + 30_000) return;
    this.prune();
    if (data.author?.id === this.state.ownBot) return;
    const previous = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM gateway_previews WHERE id = ?", data.id).toArray()[0];
    let message = data;
    if (update && previous) message = { ...JSON.parse(previous.value), ...data };
    const packet = update ? previous && embedPacket(JSON.parse(previous.value), message, this.env.DISCORD_GUILD_ID!, this.channelId!, this.state.ownBot!) :
      discordPacket(message, this.env.DISCORD_GUILD_ID!, this.channelId!, this.state.ownBot!);
    if (!packet) return;
    packet.scope = this.scope; packet.room = this.room;
    const id = `${data.id}:${packet.part ?? "body"}`;
    if (this.ctx.storage.sql.exec("SELECT id FROM gateway_seen WHERE id = ?", id).toArray().length) return;
    const count = this.ctx.storage.sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM gateway_pending").one().count;
    if (count >= MAX_PENDING) { this.state.lastError = "discord_queue_full"; this.persist(); return; }
    const expires = Math.min(created + TTL, Date.now() + TTL);
    this.ctx.storage.sql.exec("INSERT INTO gateway_seen VALUES (?, ?)", id, expires);
    this.ctx.storage.sql.exec("INSERT INTO gateway_pending VALUES (?, ?, ?, ?)", id, JSON.stringify(packet), expires, Date.now());
    // Retain only the bounded data required for one delayed preview, never a durable chat history.
    if (!update) {
      const user = (raw: Json): Json => ({ id: raw?.id, username: typeof raw?.username === "string" ? raw.username.slice(0, 64) : undefined,
        avatar: typeof raw?.avatar === "string" && /^[a-f0-9]{32}$/.test(raw.avatar) ? raw.avatar : undefined, global_name: typeof raw?.global_name === "string" ? raw.global_name.slice(0, 64) : undefined, bot: raw?.bot === true });
      const member = { nick: typeof data.member?.nick === "string" ? data.member.nick.slice(0, 64) : undefined };
      const preview = { id: data.id, guild_id: data.guild_id, channel_id: data.channel_id, author: user(data.author), member,
        webhook_id: data.webhook_id, content: typeof data.content === "string" ? data.content.slice(0, 4096) : "", embeds: Array.isArray(data.embeds) && data.embeds.length ? [{}] : [],
        mentions: Array.isArray(data.mentions) ? data.mentions.slice(0, 100).map(user) : [],
        links: discordMessageLinks({ content: packet.text }), text: packet.text, media: packet.media,
        attachments: Array.isArray(data.attachments) ? data.attachments.slice(0, 4).map((raw: Json) => ({ id: raw.id, filename: typeof raw.filename === "string" ? raw.filename.slice(0, 128) : undefined,
          url: typeof raw.url === "string" ? raw.url.slice(0, 2048) : undefined, content_type: typeof raw.content_type === "string" ? raw.content_type.slice(0, 80) : undefined,
          size: Number.isSafeInteger(raw.size) && raw.size >= 0 && raw.size <= 25_000_000 ? raw.size : undefined, width: Number.isSafeInteger(raw.width) && raw.width >= 0 && raw.width <= 16384 ? raw.width : undefined, height: Number.isSafeInteger(raw.height) && raw.height >= 0 && raw.height <= 16384 ? raw.height : undefined })) : [] };
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_previews VALUES (?, ?, ?)", data.id, JSON.stringify(preview), expires);
      const source = { id: data.id, guild_id: data.guild_id, channel_id: data.channel_id, author: user(data.author), member, webhook_id: data.webhook_id, mapped: false };
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_sources VALUES (?, ?, ?)", data.id, JSON.stringify(source), created + LIFETIME);
    }
    this.flushDiscord();
  }
  private flushDiscord(): void {
    if (!this.bridgeReady || !this.bridge || this.richOffered && !this.richReady) return;
    this.prune();
    for (const row of this.ctx.storage.sql.exec<{ id: string; packet: string }>("SELECT id, packet FROM gateway_pending WHERE due <= ? ORDER BY expires, id LIMIT 16", Date.now())) {
      if (row.id.startsWith("op:") && !this.richReady) continue;
      this.bridge.send(row.packet);
      this.ctx.storage.sql.exec("UPDATE gateway_pending SET due = ? WHERE id = ?", Date.now() + 5000, row.id);
    }
  }
  private async bridgeMessage(raw: unknown): Promise<void> {
    if (raw === "pong") { this.bridgePongPending = false; return; }
    if (typeof raw !== "string" || raw.length > 32_768) { this.disconnectBridge("bridge_invalid_frame"); return; }
    let packet: Json;
    try { packet = JSON.parse(raw); } catch { this.disconnectBridge("bridge_invalid_frame"); return; }
    if (!packet || typeof packet !== "object") return;
    if (packet.type === "bridge_ready") {
      if (packet.room !== this.room || packet.protocol !== 3 || this.scope === "global" && packet.globalChat !== true) { this.disconnectBridge("bridge_capability_mismatch"); return; }
      this.bridgeReady = true; this.bridgeDeadline = Infinity; this.bridgePingDue = Date.now() + 30_000;
      this.state.bridgeFailures = 0; this.persist();
      this.richOffered = packet.richChatVersion === 1;
      if (this.richOffered) {
        this.bridgeDeadline = Date.now() + 15_000;
        this.capabilityId = hex(crypto.getRandomValues(new Uint8Array(16)).buffer);
        this.bridge?.send(JSON.stringify({ type: "bridge_capabilities", id: this.capabilityId, richChatVersion: 1 }));
      }
      this.flushDiscord(); return;
    }
    if (packet.type === "irc_result" && packet.id === this.capabilityId && packet.scope === this.scope && packet.room === this.room && !packet.error) {
      this.richReady = true; this.bridgeDeadline = Infinity; this.flushDiscord();
      const waiting = [...this.offeredMinecraft.values()]; this.offeredMinecraft.clear(); this.offeredMinecraftBytes = 0;
      for (const message of waiting) this.event(() => this.bridgeMessage(message));
      return;
    }
    if (packet.type === "irc_result" && packet.id === this.capabilityId && packet.error) { this.disconnectBridge("bridge_rich_capability_rejected"); return; }
    if (!this.bridgeReady || packet.scope !== this.scope || packet.room !== this.room && packet.type !== "irc_result") return;
    if (packet.type === "irc_result" && typeof packet.id === "string" && (snowflake.test(packet.id) || minecraftId.test(packet.id))) {
      const id = minecraftId.test(packet.id) ? `op:${packet.id}` : `${packet.id}:${packet.part ?? "body"}`;
      if (packet.error === "rate_limited" || packet.error === "temporarily_unavailable") this.ctx.storage.sql.exec("UPDATE gateway_pending SET due = ? WHERE id = ?", Date.now() + 5000, id);
      else {
        this.ctx.storage.sql.exec("DELETE FROM gateway_pending WHERE id = ?", id);
        if (packet.error && packet.error !== "duplicate") { this.state.lastError = "discord_delivery_rejected"; this.persist(); }
      }
      return;
    }
    if (packet.type === "irc_operation") {
      if (!this.richReady || !validBridgeOperation(packet, this.scope, this.room) || this.deliveries.size >= 8 || this.deliveries.has(`op:${packet.messageId}`)) return;
      this.deliveries.add(`op:${packet.messageId}`);
      this.ctx.waitUntil(this.postOperation(packet).finally(() => this.deliveries.delete(`op:${packet.messageId}`)));
      return;
    }
    if (packet.type !== "irc_message") return;
    const body = await minecraftContent(packet, this.scope, this.room);
    const now = Date.now();
    if (!body || !Number.isSafeInteger(packet.expiresAt) || packet.expiresAt <= now || packet.expiresAt <= packet.timestamp || packet.expiresAt > packet.timestamp + TTL ||
        !Number.isSafeInteger(packet.timestamp) || packet.timestamp > now + 30_000 || packet.timestamp < now - TTL) return;
    if (this.richOffered && !this.richReady) {
      const bytes = encoder.encode(raw).byteLength, previous = this.offeredMinecraft.get(packet.id);
      if (previous || this.offeredMinecraft.size >= 64 || this.offeredMinecraftBytes + bytes > 1_048_576) return;
      this.offeredMinecraft.set(packet.id, raw); this.offeredMinecraftBytes += bytes; return;
    }
    this.prune();
    const receipt = this.ctx.storage.sql.exec<{ discord_id: string }>("SELECT discord_id FROM gateway_receipts WHERE id = ?", packet.id).toArray()[0];
    if (receipt) { this.ack(packet.id, receipt.discord_id); return; }
    if (now < (this.state.postDue ?? 0) || !this.gatewayReady || !this.state.ownBot || this.deliveries.has(packet.id) || this.deliveries.size >= 8) return;
    this.deliveries.add(packet.id);
    // Slow Discord REST calls must never block Gateway heartbeats or bridge acknowledgments.
    this.ctx.waitUntil(this.postMinecraft(packet, body).finally(() => { this.deliveries.delete(packet.id); }));
  }
  private async postMinecraft(packet: Json, body: Json): Promise<void> {
    if (!this.state.running || !this.gatewayReady || packet.expiresAt <= Date.now() || Date.now() < (this.state.postDue ?? 0)) return;
    try {
      const response = await this.externalFetch(new Request(`https://discord.com/api/v10/channels/${this.channelId}/messages`, {
        method: "POST", headers: { Authorization: `Bot ${this.env.USER_BOT_TOKEN}`, "Content-Type": "application/json" }, body: JSON.stringify(body), redirect: "manual", signal: AbortSignal.timeout(10_000) }));
      if (response.status === 429) {
        this.state.postDue = Math.max(this.state.postDue ?? 0, retryAfterDeadline(response.headers.get("Retry-After")));
        this.state.lastError = "discord_rate_limited"; this.persist(); return;
      }
      if (!response.ok) { this.state.lastError = "discord_post_failed"; this.persist(); return; }
      if (response.headers.get("X-RateLimit-Remaining") === "0") {
        this.state.postDue = Math.max(this.state.postDue ?? 0, retryAfterDeadline(response.headers.get("X-RateLimit-Reset-After")));
        this.persist();
      }
      const posted = await response.json<Json>();
      if (!snowflake.test(posted.id ?? "") || posted.channel_id !== this.channelId || posted.author?.id !== this.state.ownBot) { this.state.lastError = "discord_post_invalid_response"; this.persist(); return; }
      // Persist before acknowledging. Stable nonce covers a response lost between Discord and this write.
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_receipts VALUES (?, ?, ?)", packet.id, posted.id, Date.now() + LIFETIME);
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_sources VALUES (?, ?, ?)", posted.id, JSON.stringify({ id: posted.id,
        guild_id: this.env.DISCORD_GUILD_ID, channel_id: this.channelId, author: { id: this.state.ownBot, bot: true }, mapped: true, canonicalId: packet.id, revision: 0 }), Date.now() + LIFETIME);
      this.prune(); this.state.lastAck = Date.now(); this.persist();
      if (packet.expiresAt > Date.now()) this.ack(packet.id, posted.id);
    } catch { this.state.lastError = "discord_post_failed"; this.persist(); }
  }

  private nativeOperation(event: string, data: Json, eventId: string, descriptionHash?: string): void {
    if (!data || data.guild_id !== this.env.DISCORD_GUILD_ID || data.channel_id !== this.channelId ||
        !snowflake.test(data.id ?? data.message_id ?? "") || !minecraftId.test(eventId)) return;
    this.prune(); const sourceId = data.id ?? data.message_id;
    const source = this.ctx.storage.sql.exec<{ value: string; expires: number }>("SELECT value, expires FROM gateway_sources WHERE id = ?", sourceId).toArray()[0];
    if (!source) return;
    const preview = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM gateway_previews WHERE id = ?", sourceId).toArray()[0];
    const previous = { ...(preview ? JSON.parse(preview.value) : {}), ...JSON.parse(source.value) };
    // Our own mirrored reactions/card edits already have authoritative backend updates; suppress feedback loops.
    if (event === "MESSAGE_UPDATE" && previous.mapped && Array.isArray(previous.expectedEdits) && previous.expectedEdits.some((row: Json) => row.until > Date.now() && descriptionHash === row.hash)) return;
    if (event === "MESSAGE_UPDATE" && !previous.mapped && typeof data.content !== "string" && !preview) return;
    const packet = discordOperation(event, data, previous, eventId, this.env.DISCORD_GUILD_ID!, this.channelId!, this.state.ownBot!);
    if (!packet) return;
    packet.scope = this.scope; packet.room = this.room;
    const id = `op:${packet.id}`;
    if (this.ctx.storage.sql.exec("SELECT id FROM gateway_seen WHERE id = ?", id).toArray().length ||
        this.ctx.storage.sql.exec<{ count: number }>("SELECT COUNT(*) AS count FROM gateway_pending").one().count >= MAX_PENDING) return;
    const expires = Math.min(source.expires, Date.now() + TTL);
    this.ctx.storage.sql.exec("INSERT INTO gateway_seen VALUES (?, ?)", id, source.expires);
    this.ctx.storage.sql.exec("INSERT INTO gateway_pending VALUES (?, ?, ?, ?)", id, JSON.stringify(packet), expires, Date.now());
    if (event === "MESSAGE_UPDATE") {
      const merged = { ...previous, ...data };
      // Existing two-minute recovery body stays bounded; routing-only metadata lives fifteen minutes.
      const attachments = discordMedia(merged).map(item => ({ id: item.id, filename: item.name, url: item.url,
        content_type: item.mime, size: item.bytes, width: item.width, height: item.height }));
      const recovery = { ...JSON.parse(source.value), content: typeof merged.content === "string" ? merged.content.slice(0, 4096) : "",
        embeds: previous.mapped ? [{ description: packet.text.replace(/[\\`*_~|>]/g, "\\$&") }] : [],
        attachments, text: packet.text, media: packet.media, mentions: [], links: discordMessageLinks({ content: packet.text }) };
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_previews VALUES (?, ?, ?)", sourceId, JSON.stringify(recovery), Math.min(source.expires, Date.now() + TTL));
    }
    this.flushDiscord();
  }
  private operationAck(packet: Json): void {
    if (this.bridgeReady) this.bridge?.send(JSON.stringify({ type: "discord_operation_ack", id: packet.id, scope: this.scope, room: this.room,
      messageId: packet.messageId, channelId: this.channelId, message_id: packet.discordMessageId, revision: packet.revision }));
  }
  private operationError(packet: Json, error: string): void {
    if (this.bridgeReady) this.bridge?.send(JSON.stringify({ type: "discord_operation_result", id: packet.id, scope: this.scope, room: this.room, channelId: this.channelId, error }));
  }
  private async postOperation(packet: Json): Promise<void> {
    this.prune();
    const known = this.ctx.storage.sql.exec<{ message_id: string; revision: number; discord_id: string }>("SELECT message_id, revision, discord_id FROM gateway_operations WHERE id = ?", packet.id).toArray()[0];
    if (known) {
      if (known.message_id === packet.messageId && known.revision === packet.revision && known.discord_id === packet.discordMessageId) this.operationAck(packet);
      return;
    }
    const mapping = this.ctx.storage.sql.exec<{ discord_id: string }>("SELECT discord_id FROM gateway_receipts WHERE id = ?", packet.messageId).toArray()[0];
    const source = this.ctx.storage.sql.exec<{ value: string }>("SELECT value FROM gateway_sources WHERE id = ?", packet.discordMessageId).toArray()[0];
    const metadata = source ? JSON.parse(source.value) : undefined;
    if (!metadata || packet.operation !== "reaction" && (!mapping || mapping.discord_id !== packet.discordMessageId || metadata.author?.id !== this.state.ownBot || !metadata.mapped)) {
      this.operationError(packet, "destination_unavailable"); return;
    }
    if (metadata.revision >= packet.revision) { this.operationError(packet, "superseded"); return; }
    if (!this.state.running || !this.gatewayReady || packet.expiresAt <= Date.now() || Date.now() < (this.state.postDue ?? 0)) return;
    const endpoint = `https://discord.com/api/v10/channels/${this.channelId}/messages/${packet.discordMessageId}`;
    const request = (url: string, method: string, body?: Json): Request => new Request(url, { method,
      headers: { Authorization: `Bot ${this.env.USER_BOT_TOKEN}`, ...(body ? { "Content-Type": "application/json" } : {}) },
      ...(body ? { body: JSON.stringify(body) } : {}), redirect: "manual", signal: AbortSignal.timeout(10_000) });
    try {
      const current = await this.externalFetch(request(endpoint, "GET"));
      if (current.status === 429) { await current.body?.cancel(); this.state.postDue = retryAfterDeadline(current.headers.get("Retry-After")); this.persist(); return; }
      const absent = current.status === 404;
      if (absent) await current.body?.cancel();
      if (!absent) {
        if (!current.ok) { await current.body?.cancel(); this.operationError(packet, current.status === 403 ? "not_authorized" : "temporarily_unavailable"); return; }
        const found = await current.json<Json>();
        if (found.id !== packet.discordMessageId || found.channel_id !== this.channelId || found.author?.id !== metadata.author.id ||
            packet.operation !== "reaction" && found.author?.id !== this.state.ownBot) { this.operationError(packet, "not_authorized"); return; }
      }
      if (absent && packet.operation !== "delete") { this.operationError(packet, "destination_unavailable"); return; }
      if (!absent) {
        let method = "DELETE", url = endpoint, body: Json | undefined;
        if (packet.operation === "edit") {
          body = minecraftCard({ ...packet, type: "irc_message", source: "minecraft", id: packet.messageId, message_id: packet.messageId,
            from: packet.sender?.name, mentions: [] }, this.scope, this.room) ?? undefined;
          if (!body) { this.operationError(packet, "invalid_message"); return; }
          body.content = ""; delete body.message_reference; method = "PATCH";
          const hash = hex(await crypto.subtle.digest("SHA-256", encoder.encode(body.embeds[0].description)));
          metadata.expectedEdits = [...(Array.isArray(metadata.expectedEdits) ? metadata.expectedEdits.filter((row: Json) => row.until > Date.now()) : []), { hash, until: Date.now() + 15_000 }].slice(-8);
          this.ctx.storage.sql.exec("UPDATE gateway_sources SET value = ? WHERE id = ?", JSON.stringify(metadata), packet.discordMessageId);
        } else if (packet.operation === "reaction") {
          method = packet.active ? "PUT" : "DELETE"; url += `/reactions/${encodeURIComponent(packet.emoji)}/@me`;
        }
        if (!validBridgeOperation(packet, this.scope, this.room) || !this.state.running) return;
        const response = await this.externalFetch(request(url, method, body));
        if (response.status === 429) { await response.body?.cancel(); this.state.postDue = retryAfterDeadline(response.headers.get("Retry-After")); this.persist(); return; }
        if (!response.ok && !(packet.operation === "delete" && response.status === 404)) {
          await response.body?.cancel(); this.operationError(packet, response.status === 403 ? "not_authorized" : response.status === 404 ? "destination_unavailable" : "temporarily_unavailable"); return;
        }
        if (packet.operation === "edit") {
          const edited = await response.json<Json>();
          if (edited.id !== packet.discordMessageId || edited.channel_id !== this.channelId || edited.author?.id !== this.state.ownBot) {
            this.operationError(packet, "invalid_destination_response"); return;
          }
        } else await response.body?.cancel();
      }
      // Mutations are idempotent PUT/PATCH/DELETE; receipts precede ACK and survive eviction.
      this.ctx.storage.sql.exec("INSERT OR REPLACE INTO gateway_operations VALUES (?, ?, ?, ?, ?)", packet.id, packet.messageId, packet.revision, packet.discordMessageId, Date.now() + LIFETIME);
      metadata.revision = packet.revision;
      this.ctx.storage.sql.exec("UPDATE gateway_sources SET value = ? WHERE id = ?", JSON.stringify(metadata), packet.discordMessageId);
      this.prune(); this.operationAck(packet);
    } catch { this.operationError(packet, "temporarily_unavailable"); }
  }

  private ack(id: string, messageId: string): void {
    if (this.bridgeReady) this.bridge?.send(JSON.stringify({ type: "discord_ack", scope: this.scope, room: this.room, channelId: this.channelId, id, message_id: messageId }));
  }
}
