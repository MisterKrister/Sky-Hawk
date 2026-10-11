# Persistent Discord global-chat bridge

The `GlobalChatGateway` Durable Object in this relay hosts the User Bot's Discord
Gateway connection on Cloudflare. It connects to the account backend through the
existing `SKY_MYCE_BACKEND` service binding. Account authentication, license checks,
global subscriptions, sender identity, shared quotas and Minecraft delivery remain
owned by `sky-myce-backend`. The administrative interaction forwarder is unchanged.

Global is the only Discord audience enabled in production. Optional legacy `friends`
and `testing` coordinators require explicit `IRC_ROOMS` configuration and remain off
when it is empty. The bridge rejects party-scoped and unscoped IRC packets. Party
chat stays Minecraft-only: the account backend matches authenticated participants'
exact UUID rosters and opt-in consent. Those client rosters are not independent
Hypixel membership attestation; they never authorize a Discord subscription.

## Configuration and activation

The Gateway requires `GLOBAL_CHAT_ENABLED="true"`; an omitted or false value disables
it. The current production configuration enables Global and leaves `IRC_ROOMS` empty.
New coordinators initially remain stopped until authenticated operator activation.
Set these public variables before activation:

- `GLOBAL_CHAT_ENABLED="true"`
- `GLOBAL_CHAT_CHANNEL_ID`: the one permitted global Discord channel
- `DISCORD_GUILD_ID`: its Discord guild
- `USER_BOT_APPLICATION_ID`: the User Bot application and bot-user ID

Store these with `wrangler secret put`; do not put them in source, command-line
arguments, chat, logs or a checked-in environment file:

- `USER_BOT_TOKEN`: Discord User Bot token
- `DISCORD_BRIDGE_SECRET`: the existing HMAC bridge secret configured on the account backend
- `GLOBAL_CHAT_CONTROL_SECRET`: an independent random control secret of at least 32 characters

The bot must belong to the configured guild and have View Channel, Send Messages,
Read Message History, Embed Links and Add Reactions for the configured channel.
Editing/deleting its own cards requires no Manage Messages permission. Enable Message
Content Intent in Discord's developer portal. The source identifies with Guild
Messages, Guild Message Reactions and Message Content (`512 | 1024 | 32768 = 34304`);
it does not request direct messages, presence or guild-member cache intents. See
Discord's [Gateway intents](https://docs.discord.com/developers/events/gateway#gateway-intents)
and [channel permissions](https://docs.discord.com/developers/topics/permissions).

Deploy the relay after reviewing the binding and additive `v2-global-chat-gateway`
SQLite migration. Stop any previous Node User Bot global bridge before starting the
Cloudflare bridge; the account backend permits one authenticated bridge per channel.

`POST /global-chat/control` requires `Authorization: Bearer <control secret>` and
a JSON body `{"action":"start"}`, `{"action":"stop"}` or `{"action":"status"}`.
The endpoint accepts no client-specified channel, room, bot identity or destination.
Repeated start is idempotent. Start returns 503 if disabled or incompletely configured.
Stop persists the stopped state, closes both sockets, clears undelivered Discord
packets and cancels alarms. A saved running state resumes after a DO restart.
Changing the configured channel/guild/application stops the old context; explicitly
start the reviewed new configuration to activate it.

`GET /global-chat/health` exposes the configured room/scope, negotiated
`richChatVersion`, enabled/configured/running flags, socket-ready flags, pending count,
a delivery time and bounded diagnostic categories. It never
returns credentials, private Gateway session IDs, actor identities or message text.
Examples include `bridge_http_401` (bridge authentication), `bridge_http_409`
(another bridge is active), `gateway_close_4014` (Message Content Intent),
`discord_application_mismatch`, and `discord_rate_limited`.

## Recovery and delivery boundaries

One deterministic coordinator (`global-chat:<configured channel>`) owns both outbound
WebSockets. Discord Hello/Heartbeat/ACK, reconnect, invalid-session and terminal
close codes are handled explicitly. Alarms restore lost sockets with bounded
backoff. Gateway session/Resume URL/sequence persist privately; advancing a sequence
and enqueuing its message share a SQLite transaction. Non-resumable credential,
application or intent failures stop retries and appear in health.
During a Resume for the previously validated bot/session, eligible replay dispatches
before `RESUMED` use the same filtering and atomic queue/sequence path. Identify
traffic before READY cannot deliver messages. Upgrade handshakes use controller
timers that are cleared when response headers arrive: the timeout must not remain
attached to an adopted WebSocket. Stalled handshakes still expire, and generation,
stop, READY and heartbeat guards remain enforced. A populated historical
`lastError` does not override current readiness flags.

Discord messages, replies, user mentions, attachments and delayed embeds use the
negotiated rich protocol with the existing legacy fallback. Webhook posts, own-bot
message creation and mirrored self-update echoes, wrong guild/channel, malformed
IDs, old replayed messages and oversized frames are rejected. Validated edits/deletes
of mapped bot cards follow the rich operation rules below. Role/channel
mentions render as `@role` / `#channel` because this bot does not request guild/member
cache intents. Other bots in the permitted global channel remain supported.

Discord-to-backend pending packets expire within two minutes and are capped at 256.
Seen IDs, bounded preview metadata and Minecraft-to-Discord delivery receipts are
capped at 1,024 rows each. Pending/preview bodies are temporary recovery data, not
a queryable chat history. Expired rows are pruned during alarms and subsequent
requests. Backend quota or temporary-unavailability responses retain pending packets
for bounded retry. Expired packets are never revived by a reconnect.

Minecraft deliveries require the configured Global scope (or an explicitly opted-in
legacy room), verified-name format, matching message IDs and the backend's two-minute
expiry. Party deliveries are rejected. Markdown is escaped and Discord mentions
are disabled. A stable nonce with `enforce_nonce` protects ambiguous REST retries;
the returned Discord ID is persisted before the backend acknowledgment. A duplicate
after a process restart reuses that receipt without posting again. Discord REST
rate limits are respected; 429 responses never create a success receipt.

Outbound client WebSockets keep this one Durable Object active. Cloudflare's server
WebSocket hibernation does not apply, so active duration contributes to the account's
Durable Objects usage. No personal computer needs to remain online. See Cloudflare's
[outbound WebSocket guidance](https://developers.cloudflare.com/durable-objects/best-practices/websockets/)
and Discord's [Gateway lifecycle](https://docs.discord.com/developers/events/gateway).

## Verification

Run `npm run check` and `npm test`. The Gateway test runs production code in Miniflare
workerd with actual WebSockets, SQLite transactions/receipts, alarms and forced DO
eviction. Its in-memory fixtures replace only Discord transport and the account
backend peer; all external network traffic is forbidden. It verifies the HMAC service
binding, strict scope/channel/identity checks, mentions/embeds, duplicate receipts,
rate-limit retry, bounded storage, terminal disconnects and default-disabled behavior.

These local checks do not prove Discord credentials, permissions or production
connectivity. After activation, verify both health readiness flags and perform the
authorized live global-chat smoke test with the real Minecraft session. Party chat
remains Minecraft-only and must never reach Discord.

Historical live verification, October 10, 2026, for mod 2.4.27: Gateway version
`ecd1963b-5bfa-4f2b-b981-be2703d5aeb1` was tested on the existing `skyblock-relay`;
the separate account backend was version `dd0588b1-a721-4695-9b62-ead63185c157` in
`--env backend`. The authorized Minecraft-to-Discord post and
human `hello248` reply returned to native Minecraft with correct reply context and
rendered mention. Final redacted public-health observation passed 39 samples over
96 seconds after a controlled service restart. Earlier failed observations remain
recorded; sound audibility and long-term uptime are unverified. Exact deployment
IDs, source hashes, isolation checks and evidence are in
[the implementation report](<D:/Code/Sky-Myce/docs/implementation-overhaul-2026-10.md>).
The Gateway runtime regression also observes adopted sockets beyond the ten-second
handshake deadline and verifies stalled/canceled attempts and Resume replay.

For mod 2.4.29, rich-protocol source checks and local Gateway runtime tests passed.
On October 11, 2026, the deployed Gateway version
`4fbf8310-04c4-4a2a-b711-56b4c5a72869` passed exact provider bundle verification.
After a controlled stop/start, 32 health samples over 75.805 seconds showed both
transports ready, `richChatVersion: 1` and no pending messages. Guard checks retained
unauthorized control/admin rejection, disabled legacy rooms and Party route rejection.
The October 10 roundtrip does not validate this release's new Minecraft UI, Discord
cards, media, reactions or editing/deletion. Those new live interactions remain
unverified and need a separate authorized smoke test.


## Rich IRC protocol

The rich bridge negotiates `richChatVersion: 1` through the existing authenticated
protocol-3 connection. The Gateway waits for the server's scoped
`bridge_capabilities` acknowledgment before delivering rich events. Initial Minecraft
messages wait in a bounded 64-message/1 MiB queue until that acknowledgment;
reconnect/stop clears the queue, and the backend retains its unacknowledged outbox. Ordinary
Minecraft messages always become one compact Discord embed card, with the verified
Minecraft name, optional server cosmetic name, UUID-derived avatar and a truthful
Minecraft footer. Reply context and extra media links use optional bounded fields.
Human Discord messages are forwarded without deleting or reposting their originals.

`discord_message` carries a bounded structured media list (at most four), source ID,
Discord sender metadata and revision. Discord signing parameters `ex`, `is`, `hm`
are excluded only from attachment identity, preserving the actual signed URL in
delivery. Repeated content/embed/attachment URLs produce one media item; different
ordinary query URLs remain distinct. Expired signed media retains its text-link
fallback. The bridge never downloads arbitrary media or acts as a fetch proxy.

Native Discord edits, deletes and the six supported reactions (👍 ❤️ 😂 🎉 👀 🔥)
travel as deduplicated `discord_operation` packets. Backend-owned `irc_operation`
packets route to verified destination mappings. Edits/deletes additionally require
an original bot-post receipt and a fresh Discord channel/author check; client
requests cannot modify human messages. Reactions use the bot's own aggregate actor,
never impersonating Minecraft users. Expected self-edit hashes are bounded to eight
per destination for fifteen seconds to prevent delayed Gateway feedback loops.
HTTP mutation success is persisted before `discord_operation_ack`; 429 and temporary
failures keep the backend's bounded retry path. Response streams are consumed or
canceled before releasing the operation.

Routing/source IDs, operation receipts and edit revisions live at most fifteen
minutes (1,024 source mappings, 4,096 operation receipts). Existing bounded recovery
previews retain at most two minutes of content and attachment metadata needed for
partial Discord updates. They are not an IRC history API. Configuration identity
changes discard the additional source/operation metadata. No secrets enter health,
message packets or logs.

Only Global chat is hosted by the current production configuration. Optional legacy
room coordinators require an explicit `IRC_ROOMS=friends,testing` selection (or one
of those rooms), distinct `DISCORD_IRC_CHANNEL_ID` / `DISCORD_IRC_TEST_CHANNEL_ID`,
and the same authenticated operator start. Their routes are
`/irc/friends/{health,control}` and `/irc/testing/{health,control}` in the existing
Gateway namespace. Empty `IRC_ROOMS` keeps both off. A room channel overlapping
Global or another configured room is rejected. **Party chat never enters a Discord
formatter, Gateway coordinator or bridge**, including failures and fallbacks.
No new Durable Object binding or storage migration is needed.

Deployment must preserve the existing isolated release wrapper: exact legacy live
ESM handler plus the additive Gateway class and explicit Gateway routes. Do not
publish unrelated dirty Cessna `src/index.ts`, account, admin or licensing changes.
The wrapper operator owns activation; source checks are not proof of deployment or
live Discord delivery. Add Reactions is required only for an explicitly configured
Discord bridge that uses reactions, alongside existing View Channel, Send Messages,
Read Message History and Embed Links permissions. No Manage Messages permission is
needed to edit/delete messages owned by the bot.
