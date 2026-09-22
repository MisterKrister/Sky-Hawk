# SkyMyce player relay

Live endpoint: `wss://skyblock-relay.skyblock-relay.workers.dev/websocket`

[Health check](https://skyblock-relay.skyblock-relay.workers.dev/health) · [Cloudflare dashboard](https://dash.cloudflare.com/)

This Cloudflare Worker routes messages between verified Minecraft accounts. Its Durable Objects use hibernating WebSockets. Your PC does not need to remain on. It uses SQLite-backed Durable Objects supported by the Workers Free plan; no paid plan, custom domain, database subscription, or personal Hypixel key is required for the relay.

## Finish in Minecraft

1. Install the newly built `build/libs/skymyce-2.4.1.jar` in your Minecraft instance, replacing the previous SkyMyce JAR. Restart Minecraft. Friends need this version too for relay messages.
2. Join SkyBlock and open `/skymyce pf`. The footer should say **Relay connected**. The public relay address is already the default. You can inspect or change it under **Settings → Player Relay**. Leave it blank to disable the connection.
3. For automatic joining, open **Available**, choose the floor/classes you can play, and save a positive S+ limit such as `5:30.000`. Both participants configure their PB requirements. A working SkyBlockPv/SkyBlocker provider or Hypixel API fallback is still needed for fresh PB checks.
4. Press **Join** beside an online friend. Join requests and class offers always use the relay. After the recipient's mod acknowledges the offer and the PB/party checks pass, the normal Hypixel invite and accept commands run. Nothing falls back to private chat for the automatic exchange.
5. Press **/msg** to send an LFG message. It first uses the relay. The recipient's mod automatically acknowledges receipt; you see “their mod received your message,” which does **not** mean the player has accepted. Without that receipt within five seconds, or if the recipient/relay is offline, the same LFG text is sent once through Hypixel `/msg`. The normal party-size and command-cooldown checks still apply.
6. Incoming relay LFG messages appear in Minecraft chat with **Yes**, **No**, and **Reply** actions. You can also type `/skymyce relaymsg Username your reply`. A positive player reply highlights **/p**; it does not automatically invite them.

Anyone with a verified Minecraft account can connect. The mod only processes messages from known friends. Everyone normally uses the `friends` room; `?room=testing` is a separate allowed room for testing, not a private room. Rooms isolate delivery but are not secret or membership-restricted. Messages are addressed to one player, not broadcast.

Temporary connection check: both friends install the updated mod and enter SkyBlock, then run `/skymyce connect <ign>` (or `/sm connect <ign>`). The other player sees the incoming test and their mod replies automatically. Success shows the round-trip time; missing receipts fail within five seconds. It works without Available classes, PB data, or a free party slot, and never falls back to Hypixel chat. Local connection problems appear immediately with the current relay status.

## Local project and future updates

The maintained source is the `relay` folder in the mod repository. A ready-to-run copy is also installed in `C:\Users\krister\skyblock-relay`, where you created the starter. Its original scaffold is backed up in `codex-original-scaffold`.

From PowerShell, use either project folder:

```powershell
cd 'C:\Users\krister\skyblock-relay'
npm run check
npm test
npm run deploy
```

Your current Cloudflare login is already authorized. If it expires, run `npx wrangler login` and approve the browser page, then deploy again. Cloudflare hosts the relay after the command exits. Keep one source copy authoritative when making future changes; edits to one folder do not automatically update the other.

For local development, run `npm run dev` and set the mod's relay URL to `ws://127.0.0.1:8787/websocket?room=testing`. Real mod clients still need Mojang-signed account proofs; there is no production or development authentication bypass. Restore the hosted address after testing.

Protocol 2 replaces the old `hasJoined` check: Minecraft accepted the local session, but returned HTTP 403 when Cloudflare verified it. Both players must install the updated mod. The client obtains its certificate/profile through Minecraft and signs the relay's challenge locally; the relay makes no requests to the blocked Minecraft endpoints. Close codes/reasons now appear in the menu and Minecraft log.

The public trust roots in `src/minecraft-keys.json` came from `https://api.minecraftservices.com/publickeys` on 2026-09-22. If Mojang rotates these keys, refresh them from your PC (only the `playerCertificateKeys` and `profilePropertyKeys` arrays), run the checks, and redeploy. Never accept public trust roots supplied by a connecting player. These are public verification keys, not account secrets.

## Behavior and limits

- The relay sends a unique challenge. Minecraft supplies a Mojang-signed, UUID-bound account certificate and a signed profile name. The mod signs the challenge with its local account key. The relay verifies the certificate, ownership signature, profile signature, matching UUID/name, certificate expiry, and profile age (at most 24 hours), then attaches the verified identity to messages. Mojang's signatures use its standard SHA-1/RSA format; the challenge uses SHA-256/RSA with a protocol-specific prefix. Minecraft access tokens, private keys, passwords, and Hypixel API keys never go to the relay.
- Only the addressed mod can acknowledge a delivered message. Forwarding by the server alone does not count as a client receipt. A dropped acknowledgement can still cause an occasional duplicate LFG message across relay and Hypixel; automatic Join actions never retry through chat.
- Connections retry automatically with backoff up to one minute. Pending messages are not replayed after reconnecting. Leaving SkyBlock cancels pending messages. **Reconnect** in Settings resets the connection; only one connection per account per room is kept.
- Fixed rooms: `friends` and `testing`. Each room allows 128 connections and at most eight from one IP. New connections are limited to 20 per IP per minute per Cloudflare location. Authentication expires after 30 seconds and permits one proof of at most 8 KiB. Authenticated payloads are limited to 2 KiB and text to 256 characters. Each connection can burst 12 messages/receipts, then replenishes one every 2.5 seconds.
- Hibernation preserves authentication, pending receipts, and per-connection limits in socket attachments. Idle ping/pong uses Cloudflare's automatic response without waking application code. The server stores no chat history and emits no message/authentication logs. Cloudflare terminates TLS; this is not end-to-end encrypted messaging.
- The worker's free-plan quotas still apply. Monitor Workers & Pages → **skyblock-relay** → Metrics in Cloudflare. This setup does not enable billing or upgrade your plan.

## Verification

`npm run check` generates Cloudflare types and checks TypeScript. `npm test` packages the production Worker and runs real local WebSocket connections in Miniflare. Only the public trust roots in the in-memory test bundle are replaced with generated test keys. Real signature verification checks cover valid proofs, forged names/UUIDs, certificate/profile expiry, signature tampering, replay rejection, identity stamping, recipient acknowledgements, hibernation/resumption, room isolation, message sizes, and message-rate limits. The mod's Gradle checks also cover receipt timeout/fallback, cancellation, PB checks, and party behavior.

`npm run smoke` checks live protocol/health and unauthenticated rejection without account credentials. An approved real-account check also passed against the deployed relay: signed certificate/profile verification, heartbeats before and after 35 seconds idle, and a clean close. Two-player GUI behavior requires the in-game steps above.

## Cloudflare tools in Codex

The official setup installs 14 Cloudflare skills under `C:\Users\krister\.agents\skills` and five MCP connections in `C:\Users\krister\.codex\config.toml`: cloudflare, cloudflare-docs, cloudflare-bindings, cloudflare-builds, and cloudflare-observability. All four account connections are authorized; documentation needs no login. Restart Codex after this task to load the new connections.

Sources: [Cloudflare agent setup](https://developers.cloudflare.com/agent-setup/prompt.md), [WebSocket hibernation](https://developers.cloudflare.com/durable-objects/examples/websocket-hibernation-server/), [deployment](https://developers.cloudflare.com/durable-objects/get-started/), [Workers rate limiting](https://developers.cloudflare.com/workers/runtime-apis/bindings/rate-limit/), [current Durable Object pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).
