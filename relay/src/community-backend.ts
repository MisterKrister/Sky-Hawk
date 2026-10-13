type CommunityBackendConfig = {
  BACKEND_BOT_APPLICATION_ID?: string; DISCORD_APPLICATION_ID?: string;
  SKY_MYCE_BACKEND_URL?: string; SKY_MYCE_BACKEND?: Fetcher;
};
const MAX_BODY_BYTES = 96 * 1024;
const roots = new Set(["admin", "account", "blacklist", "session", "skyhawk", "rng-test"]);
const object = (value: unknown): Record<string, unknown> | null =>
  value !== null && typeof value === "object" && !Array.isArray(value) ? value as Record<string, unknown> : null;

function currentBackendInteraction(data: Record<string, unknown>): boolean {
  const command = object(data.data);
  if (!command) return false;
  if ((data.type === 2 || data.type === 4) && (command.type === undefined || command.type === 1))
    return roots.has(String(command.name));
  if (data.type !== 3 || command.component_type !== 2 || typeof command.custom_id !== "string" || command.custom_id.length > 100) return false;
  const id = command.custom_id;
  if (/^sky:(confirm|cancel):[A-Za-z0-9_-]{43}$/.test(id) || /^sky:admin:(info|audit):\d{17,20}$/.test(id)) return true;
  const page = /^sky:page:(devices|sessions|audit|blacklist|search):(\d{17,20}|all):(\d{1,3})$/.exec(id);
  return page !== null && Number(page[3]) >= 1 && Number(page[3]) <= 100;
}

async function boundedEnvelope(request: Request): Promise<{ bytes: Uint8Array; data: Record<string, unknown> } | null> {
  if (Number(request.headers.get("Content-Length") ?? 0) > MAX_BODY_BYTES) return null;
  const reader = request.clone().body?.getReader();
  if (!reader) return null;
  const chunks: Uint8Array[] = []; let length = 0, timedOut = false;
  const timer = setTimeout(() => { timedOut = true; void reader.cancel().catch(() => {}); }, 3000);
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      length += value.length;
      if (length > MAX_BODY_BYTES) return null;
      chunks.push(value);
    }
    if (timedOut) return null;
    const bytes = new Uint8Array(length); let position = 0;
    for (const chunk of chunks) { bytes.set(chunk, position); position += chunk.length; }
    const data = object(JSON.parse(new TextDecoder("utf-8", { fatal: true, ignoreBOM: false }).decode(bytes)));
    return data ? { bytes, data } : null;
  } catch { return null; }
  finally {
    clearTimeout(timer);
    // Cancelling a tee may wait for the untouched legacy reader, so do not block fallthrough on it.
    void reader.cancel().catch(() => {});
  }
}

/** Selects current community commands only; the account Worker verifies every signed identity and actor. */
export async function communityBackendInteraction(request: Request, env: CommunityBackendConfig): Promise<Response | null> {
  if (new URL(request.url).pathname !== "/discord/interactions" || request.method !== "POST") return null;
  const application = env.BACKEND_BOT_APPLICATION_ID ?? env.DISCORD_APPLICATION_ID;
  const signature = request.headers.get("X-Signature-Ed25519") ?? "";
  const timestamp = request.headers.get("X-Signature-Timestamp") ?? "";
  if (!/^\d{17,20}$/.test(application ?? "") || !/^[a-f0-9]{128}$/i.test(signature) || !/^\d{10}$/.test(timestamp)) return null;
  const envelope = await boundedEnvelope(request);
  if (!envelope || envelope.data.application_id !== application || !currentBackendInteraction(envelope.data)) return null;
  const unavailable = () => Response.json(envelope.data.type === 4 ? { type: 8, data: { choices: [] } } :
    { type: 4, data: { content: "This Sky-Myce service is currently unavailable.", flags: 64, allowed_mentions: { parse: [] } } });
  try {
    if (!env.SKY_MYCE_BACKEND || !env.SKY_MYCE_BACKEND_URL) return unavailable();
    const target = new URL(env.SKY_MYCE_BACKEND_URL);
    if (target.protocol !== "https:" || target.username || target.password || target.search || target.hash ||
        target.pathname !== "/" || target.origin === new URL(request.url).origin) return unavailable();
    target.pathname = "/discord/backend/interactions";
    const forwarded = new Request(target, { method: "POST", body: envelope.bytes as Uint8Array<ArrayBuffer>,
      redirect: "manual", signal: AbortSignal.timeout(1500), headers: {
        "Content-Type": request.headers.get("Content-Type") ?? "application/json",
        "X-Signature-Ed25519": signature, "X-Signature-Timestamp": timestamp,
      } });
    const response = await env.SKY_MYCE_BACKEND.fetch(forwarded);
    if (response.status >= 300 && response.status < 400 || response.status >= 500) return unavailable();
    return response;
  } catch { return unavailable(); }
}
