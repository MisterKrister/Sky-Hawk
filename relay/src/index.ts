import { DurableObject } from "cloudflare:workers";
import { verifyAccount } from "./auth";

type RelayEnv = Env;
type Session = {
  challenge: string;
  expires: number;
  ip: string;
  name?: string;
  uuid?: string;
  checking?: boolean;
  credits: number;
  updated: number;
  seen: string[];
  inbox: { id: string; from: string; expires: number }[];
};
const username = /^[A-Za-z0-9_]{1,16}$/;
const uuid = /^[a-f0-9]{32}$/;
const encoder = new TextEncoder();

export default {
  async fetch(request, env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ service: "SkyMyce relay", version: 2 });
    if (url.pathname !== "/websocket") return new Response("Not found", { status: 404 });
    if (request.method !== "GET" || request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
      return new Response("WebSocket upgrade required", { status: 426 });
    }
    const room = url.searchParams.get("room") ?? "friends";
    if (!/^[a-z0-9_-]{1,32}$/.test(room) || !env.ROOMS.split(",").includes(room)) {
      return new Response("Room not allowed", { status: 403 });
    }
    // Identity is not verified yet; bound attempts by the Cloudflare-provided IP.
    if (!(await env.CONNECT_LIMIT.limit({ key: request.headers.get("CF-Connecting-IP") ?? "local" })).success) {
      return new Response("Too many connection attempts", { status: 429 });
    }
    return env.RELAY_ROOMS.getByName(room).fetch(request);
  },
} satisfies ExportedHandler<RelayEnv>;

export class RelayRoom extends DurableObject<RelayEnv> {
  constructor(ctx: DurableObjectState, env: RelayEnv) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  async fetch(request: Request): Promise<Response> {
    const sockets = this.ctx.getWebSockets();
    const ip = request.headers.get("CF-Connecting-IP") ?? "local";
    if (sockets.length >= 128 || sockets.filter(ws => (ws.deserializeAttachment() as Session).ip === ip).length >= 8) {
      return new Response("Too many connections", { status: 429 });
    }
    const [client, server] = Object.values(new WebSocketPair());
    const now = Date.now();
    const session: Session = {
      challenge: crypto.randomUUID().replaceAll("-", ""), expires: now + 30000, ip,
      credits: 12, updated: now, seen: [], inbox: [],
    };
    this.ctx.acceptWebSocket(server);
    server.serializeAttachment(session);
    server.send(JSON.stringify({ type: "challenge", serverId: session.challenge, protocol: 2 }));
    const alarm = await this.ctx.storage.getAlarm();
    if (alarm === null || alarm > session.expires) await this.ctx.storage.setAlarm(session.expires);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer): Promise<void> {
    const session = ws.deserializeAttachment() as Session;
    if (typeof raw !== "string" || encoder.encode(raw).length > (session.name ? 2048 : 8192)) { ws.close(1009, "Message too large"); return; }
    let data: Record<string, unknown>;
    try {
      data = JSON.parse(raw);
      if (!data || Array.isArray(data) || typeof data !== "object") throw new Error();
    } catch { ws.close(1008, "Invalid JSON message"); return; }

    if (!session.name) {
      if (session.checking || Date.now() > session.expires || data.type !== "authenticate" ||
          typeof data.name !== "string" || !username.test(data.name) || typeof data.uuid !== "string" || !uuid.test(data.uuid)) {
        ws.close(1008, "Authentication required"); return;
      }
      session.checking = true;
      ws.serializeAttachment(session);
      try {
        const profile = await verifyAccount(data, session.challenge);
        if (profile === null || Date.now() > session.expires) {
          ws.close(4003, "Invalid or expired Minecraft proof; update mod or restart Minecraft"); return;
        }
        if (ws.readyState !== WebSocket.OPEN) return;
        for (const other of this.ctx.getWebSockets()) {
          const previous = other.deserializeAttachment() as Session;
          if (other !== ws && (previous.uuid === profile.id || previous.name?.toLowerCase() === profile.name.toLowerCase())) {
            other.close(4001, "Account connected elsewhere");
          }
        }
        session.name = profile.name;
        session.uuid = profile.id;
        session.challenge = "";
        ws.serializeAttachment(session);
        ws.send(JSON.stringify({ type: "ready", name: session.name, uuid: session.uuid, protocol: 2 }));
      } catch { ws.close(1013, "Minecraft verification unavailable; retry later"); }
      return;
    }

    if ((data.type !== "message" && data.type !== "ack") || typeof data.id !== "string" || !/^[a-f0-9]{32}$/.test(data.id) ||
        typeof data.to !== "string" || !username.test(data.to) || (data.type === "message" && (typeof data.text !== "string" ||
        data.text.length < 1 || data.text.length > 256 || /[\x00-\x1f\x7f§]/.test(data.text)))) {
      ws.close(1008, "Invalid relay message"); return;
    }
    const now = Date.now();
    session.credits = Math.min(12, session.credits + (now - session.updated) / 2500);
    session.updated = now;
    if (session.credits < 1) { ws.close(1008, "Message rate exceeded"); return; }
    session.credits--;
    if (data.type === "ack") {
      if (!session.inbox.some(it => it.id === data.id && it.from.toLowerCase() === (data.to as string).toLowerCase() && it.expires > now)) {
        ws.close(1008, "Unsolicited acknowledgement"); return;
      }
      session.inbox = session.inbox.filter(it => it.id !== data.id);
    } else {
      if (session.seen.includes(data.id)) { ws.close(1008, "Duplicate message"); return; }
      session.seen = [...session.seen.slice(-7), data.id];
    }
    ws.serializeAttachment(session);

    // ponytail: scan at most 128 sockets per room; add a recipient index if rooms grow.
    const target = this.ctx.getWebSockets().find(other => other !== ws && other.readyState === WebSocket.OPEN &&
      (other.deserializeAttachment() as Session).name?.toLowerCase() === (data.to as string).toLowerCase());
    if (!target) { ws.send(JSON.stringify({ type: "error", id: data.id, code: "offline" })); return; }
    try {
      if (data.type === "message") {
        const recipient = target.deserializeAttachment() as Session;
        recipient.inbox = [...recipient.inbox.filter(it => it.expires > now).slice(-7), { id: data.id, from: session.name, expires: now + 10000 }];
        target.serializeAttachment(recipient);
      }
      target.send(JSON.stringify({ type: data.type, id: data.id, from: session.name, uuid: session.uuid, text: data.text }));
    } catch { ws.send(JSON.stringify({ type: "error", id: data.id, code: "offline" })); }
  }

  async alarm(): Promise<void> {
    let next = Infinity;
    for (const ws of this.ctx.getWebSockets()) {
      const session = ws.deserializeAttachment() as Session;
      if (session.name) continue;
      if (session.expires <= Date.now()) ws.close(1008, "Authentication timed out");
      else next = Math.min(next, session.expires);
    }
    if (Number.isFinite(next)) await this.ctx.storage.setAlarm(next);
  }

  webSocketClose(ws: WebSocket): void { ws.close(1000, "Client disconnected"); }
  webSocketError(ws: WebSocket): void { ws.close(1011, "Connection error"); }
}
