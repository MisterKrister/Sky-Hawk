# Relay cosmetics and private messages

## Player commands

Both `/sm` and `/skymyce` support:

| Command | Purpose |
| --- | --- |
| `cosmetics` | Show the local account's server-stored name, scale and revision; toggle rendering independently. |
| `cosmetics link` | Request a five-minute, single-use linking code from an authenticated Hypixel relay session. |
| `msg <real IGN> <message>` | Send an ordinary relay-only private message to a permitted friend. |
| `chat <real IGN>` | Select a friend for normal typed chat and open the chat input. |
| `chat` | Leave relay chat and resume the current Hypixel chat channel. |
| `reply <message>` / `r <message>` | Reply to the last valid user conversation. |
| `relaymsg <IGN> <message>` | Deprecated compatibility adapter; old tokenized LFG replies remain supported. |

Hypixel's global `/msg` and `/r` are untouched. Suggestions use cached real friend/relay identities, never cosmetic names. Messages accept 1–256 characters, reject control/formatting characters, and require an available capable relay and permitted friend identity. Ordinary text such as “yes” cannot trigger an LFG action. New invitation buttons use the separate validated `lfgreply` route; old `relaymsg yes <token>` buttons retain token/expiry validation.

With `/sm chat <IGN>`, plain chat goes only to the selected friend through the relay. Incoming messages and one-off `/sm msg` or `/sm reply` commands do not change that selection. Commands remain commands. Switch out with `/chat a/all`, `/chat p/party`, `/chat g/guild`, `/chat o/officer`, `/chat c/co/coop/co-op`, or `/sm chat` without a name. The server still handles the requested Hypixel channel; relay chat cannot verify whether that channel is available to you. One-off `/pc <message>` and `/gc <message>` commands do not change the relay selection. A relay reconnect, rate limit, or temporary departure from SkyBlock never silently makes your next private message public: failed sends stay blocked until you switch channels. Disconnecting from the Minecraft server or changing accounts clears the selection. Nothing is persisted and no friend-list scan is triggered.

Outgoing text appears immediately without a `[sending…]` suffix. It is not a delivery confirmation: only an acknowledgement from the recipient produces **Delivered**. Forwarding/server acceptance is insufficient. Offline recipients, unsupported clients, disallowed identities, timeouts and rate limits report failure. No ordinary message silently falls back into Hypixel chat. Existing LFG fallback behavior remains separate. Incoming messages have a clickable **Reply** draft action; it never sends automatically. Reply targets reset with the relay/account session.

Private message bodies are neither stored by the relay nor written through Sky-Hawk's chat display to Minecraft's ordinary chat logger. Other installed mods or local screen capture software are outside that guarantee. Cloudflare terminates TLS; this is not end-to-end encryption.

## Cosmetics and Discord

The extended cosmetics system now supports styled names and independent X/Y/Z visual sizes. Commands are self-service only in bot-commands `1552057526969835612`. In-game linking issues a short, expiring, single-use code; `/cosmetics unlink confirm:true` revokes ownership and resets cosmetics. The existing news channel `1552585927086440459` is unchanged.

See [the current cosmetics operator and rendering guide](cosmetics-discord.md) for exact permissions, secrets, command registration, safe name JSON, migrations, display surfaces, protocol compatibility, and validation. The former administrator target argument is intentionally removed: Discord never accepts a typed UUID or IGN as account ownership.
