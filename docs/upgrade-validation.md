# Upgrade validation — 2.4.2

Validation performed on Windows with the checkout's Minecraft 26.1.2 / Java 25 toolchain. No dependency or mod-version upgrade was made. No production Worker deployment, Discord command registration, live-data modification, commit or push was performed.

## Builds and automated checks

The pre-change offline Gradle build and relay checks passed. Baseline logs are local ignored artifacts: `build/upgrade-baseline.log`, `build/upgrade-relay-install.log`, and `build/upgrade-relay-baseline.log`.

| Command | Result |
| --- | --- |
| `gradlew.bat --offline build` with the configured Java 25 runtime | Passed, including `dungeonFriendsCheck`, `hudCheck`, `test` and `check`. Final log: `build/upgrade-final-build.log`. |
| `npm ci` in `relay` | Passed using the existing lockfile. No dependency changes. |
| `npm run check` in `relay` | Passed: Wrangler type generation and TypeScript checking. |
| `npm test` in `relay` | Passed: local `wrangler deploy --dry-run` bundling and Miniflare integration harness. This command does **not** deploy. Final log: `build/upgrade-final-relay.log`. |
| `node --check scripts/register-cosmetics.mjs` and `node scripts/register-cosmetics.mjs` | Passed syntax check and local command-definition dry run; `--apply` was not used. |
| `git diff --check` | Passed. |

There is no separately configured formatter/linter task to claim. Kotlin/Java compilation and TypeScript checking passed; Kotlin reported one non-failing unnecessary-safe-call warning in `ThemeCheck`. Existing optional development mixin/sound warnings and offline account-service failures were observed during the preview client launch; there was no new screen/mixin crash in the loaded test world.

The existing assertion runners now include:

- **AuctionCheck:** requested page URLs/responses, later-page discovery, coherent API generations, deduplication, superseded responses, partial coverage, invalid-row omission, bounded item decoding, stable filters/sorts and saved searches.
- **TrackerCheck:** empty/zero-duration statistics, combined filters and Normal/Master separation, legacy totals, idempotent imports, genuine same-item/same-time repeats, shared receipt provenance, scoped ownership, independent museum retention and offline local queries.
- **ThemeCheck:** preset/global/widget inheritance, explicit overrides versus inheritance, reset behavior, validated imports, preserved placement/scale, configuration field merging and recoverable corrupt files.
- **CosmeticsCheck:** strict public payloads, styled names/XYZ sizes, complete-username matching, displayed widths, revision/reset ordering, bounded cache and outage expiry. Relay cosmetics tests cover signed/tampered/stale interactions, channel restrictions, self-only ownership (no administrator target bypass), expired/replayed link codes, relinking/unlinking, consumption cooldown, persistence, concurrent changes, live updates, late joins, old-client compatibility, hibernation, room isolation and rate limits. See the [extended cosmetics validation and setup](cosmetics-discord.md).
- **RelayMessageCheck / relay social tests:** greedy command text, real-name suggestions, reply session state, receipt-only delivery confirmation, explicit rejection/failure, no ordinary-text LFG actions, legacy token routing, capabilities and no stored message history.
- Existing Party Finder, Dungeon Friends, wealth, Digest, settings and HUD checks remain in the normal verification path and passed.

## Actual client rendering

An isolated, muted Fabric development instance started at 1920×1080, then used a local single-player world and synthetic auction/tracker records. These are screenshots of the **implemented Minecraft screens**, not mockups or live Hypixel results. No fixture data was written into the user's normal Prism instances. The temporary preview driver was removed before the final build.

Checked 1920×1080 at GUI scales 2, 3 and 4, 1280×720 at scale 3, and 960×720 at scale 3. Visually inspected item icons/long names, retained scrolling, compact filter mode, dashboard/timeline/museum, Light theme, HUD editor/per-widget preview, and the offline cosmetics screen. A clipped tooltip found in the small-window check was corrected by sharing the existing settings tooltip-wrapping helper. This is a focused visual check, not an exhaustive interaction/accessibility test of every control and preset.

| Screen | Captured setting |
| --- | --- |
| [Auction browser](images/upgrade-auctions.png) | 1920×1080, GUI 3; visible decoded item icons, long wrapping names and distinct BIN/bid labels. |
| [Tracker overview](images/upgrade-overview.png) | 1920×1080, GUI 3; timestamped fixture summary. |
| [RNG timeline](images/upgrade-timeline.png) | 1920×1080, GUI 4. |
| [RNG museum](images/upgrade-museum.png) | 1920×1080, GUI 2; responsive gallery and item-model fallback. |
| [Compact filters](images/upgrade-small-window.png) | 960×720, GUI 3; filter drawer uses the body instead of crushing results. |

## Performance evidence and limits

A 45-second Java Flight Recorder sample covered the loaded local world with the auction screen and compact filters. The standard profiling settings enabled file/socket read/write events with a **1 ms threshold**. It recorded zero such I/O events, 183 execution samples (132 render-thread samples; five included Sky-Hawk frames), and a maximum recorded GC pause of **3.01 ms**. Local artifacts: `run/upgrade-ui.jfr` and `build/upgrade-ui-profile-summary.txt`.

This sample supports the inspected code paths; it is **not** a comparative FPS benchmark or proof that all network/game states are spike-free. It excludes sub-threshold I/O and used synthetic data rather than a full live market. Review confirmed HTTP/JSON/NBT decoding, search queries, archive loads and queued writes run through background scheduling; GUI rendering performs no synchronous HTTP/disk operations. Initial Resourceful configuration loading remains startup work, with its original preserved before migration. Shutdown hooks flush pending saves.

Auction indexing is sequential and paced, refreshes have a cooldown, generations discard late work, and visible decoding is bounded. Screens mount limited result pages and rebuild on state/query changes rather than every frame. Cosmetics scan a bounded roster once per second, batch requests, and use constant-time cache reads in rendering. No per-card timer or new full friend-list scan was introduced.

## Required live verification

The local implementation and tests are complete; these external checks remain **unverified** and require an authorized operator/test account:

1. On Hypixel, discover an auction from a later API page, inspect its real item data, and deliberately use **Open in Hypixel**. Confirm server purchase/bid confirmation remains intact. Observe a live rate-limit/snapshot-rollover refresh retaining honest partial/stale status.
2. Complete a normal and Master dungeon run; compare detected time/XP, chest inventory receipts and Kismet consumption with the in-game result. Verify switching tracker view floors does not change recording. Essence-only receipts currently remain unrecorded when no physical inventory increase can verify them.
3. Exercise Party/Invite/Join with two authenticated accounts and the old/new LFG compatibility paths. Exchange ordinary `/sm msg` and `/sm reply`, including the literal text `yes`; reject a non-friend, disconnect before acknowledgement, and confirm no private message appears in Hypixel chat or local chat logs.
4. Configure a staging Worker and Discord application using [operator instructions](cosmetics-messaging.md). Production deployment/registration needs separate authorization. Existing news ingestion should be smoke-tested after the eventual deployment.

### Two-client cosmetics checklist

Use accounts A and B on a staging relay, preferably **not** on each other's friend lists. Both clients must support the capability. B must not need to open a friends/digest menu.

- A joins Hypixel, runs `/sm cosmetics link`, and completes `/cosmetics link` in the verified Discord application. The response/code stays private. Expired and reused codes fail; an unrelated Discord account cannot edit A by typing A's IGN/UUID.
- A sets a styled name and changes each of X/Y/Z separately between 0.5, 1.0 and 2.0; omitted axes stay unchanged. B sees live updates when A is in its roster. Check standing, sneaking, armor, held items, cape/attached layers, nametag and tab formatting. Original names remain available via tooltips/toggles and authoritative for commands/party/API/message routing.
- Observe A first appearing later, B reconnecting, and relay hibernation. Confirm each restores the latest record. Reset A through Discord; B returns to the real name/scale 1.0 and an old cached response cannot undo it.
- Independently disable names/scaling on B, disable both, and re-enable. Own-settings inspection still works when rendering is disabled. Leave Hypixel and confirm neither cosmetic applies elsewhere.
- Temporarily interrupt the relay. Cached cosmetics expire after ten minutes; reconnect resynchronizes. Verify there are no repeated requests in rendering or requests tied to opening Party Finder.
- Check collision/reach/interaction distances, camera and movement remain unchanged; transforms are render-state only. An unmodified client sees ordinary Minecraft appearance. First-person hands are intentionally unchanged.
- Confirm existing news ingestion still works and client payloads contain only UUID/name/nameStyle/scale/scaleX/scaleY/scaleZ/revision/update time, never Discord links, challenge hashes, audit metadata or credentials.

## Migration and release artifact

See [storage/migration details](auction-tracker-themes.md#files-and-migration) before moving real saves. Originals are retained; legacy aggregates remain unscoped and no historical events are uploaded during import. Community history expiration cannot delete the personal archive. Server schema additions are additive within the existing Durable Object and require no new binding.

The built mod is `build/libs/skymyce-2.4.2.jar`. `FIX_PLAN.md` and the pre-existing news configuration/User-Agent changes remain unrelated local work. No release version change or production operation is implied by these checks.
