# Auctions, dungeon records and shared themes

This incremental update keeps Minecraft 26.1.2 and mod version **2.4.2**. It uses the existing owo UI, Resourceful Config, SkyBlockAPI scheduler and Gradle assertion checks. No NEU code or assets are copied.

## Auction browser

Open `/sm ah` or `/skymyce ah`. Search matches all supplied words against item name, category and available lore. Filters include category, rarity, BIN/auction/all and inclusive minimum/maximum displayed price. Sorting supports price in both directions, ending soonest, newest, name and rarity. **Reset** clears filters; **Favorites** stores up to eight complete searches, evicting the oldest when full. Preferences survive restarts.

Cards keep prominent item icons beside wrapping item names, and distinguish **BIN price**, **Current bid** and **Starting bid**. A bid is not a purchase price. Inspect a listing for seller UUID, available lore, enchantments/upgrades and the item tooltip. Missing custom item models use a vanilla display-model fallback without discarding the decoded item's lore or upgrades. **Open in Hypixel** explicitly runs the normal `viewauction` flow only while in SkyBlock and before expiry. Hypixel retains bidding/purchase confirmation. There is no automatic buying, bidding or sniping.

The status line reports partial/complete coverage, page counts, loading and stale/error status. Results cover only the pages actually indexed. Fetches include the requested `page` query parameter and proceed one at a time, 500 ms apart, up to 200 pages/80,000 listings. A later-page item is searchable once that page arrives. Refresh has a one-minute cooldown. Closing the browser invalidates the active generation; reopening during cooldown uses the cached snapshot.

Pages with different API `lastUpdated` values are never combined. If the upstream snapshot changes mid-refresh, loading stops and explains why; it does not enter a retry loop. A previous complete snapshot stays visible until a coherent replacement completes. Listings deduplicate by auction UUID. Expired items are removed periodically and cannot be opened by the action button.

HTTP and parsing run off-thread with eight-second connection/read timeouts, a twelve-second body deadline checked between bounded reads, no redirects and an 8 MiB response ceiling. Encoded item data is limited to 32 KiB per listing, compressed data to 24 KiB, decoded NBT to 2 MiB/depth 32, and retained encoded payloads to 48 MiB. Only visible items enter the bounded decode queue/cache. Missing definitions leave usable listing metadata. The API snapshot is session-memory data; saved searches, not the entire market, persist on disk. A first-ever offline opening therefore shows an honest unavailable/empty state.

## Dungeon dashboard

Open `/sm dungeon` or `/skymyce dungeon`. The four views share floor/mode controls and a collapsible filter panel:

- **Overview:** runs, known tracked duration, average timed run, gross value, chest/reroll costs, net profit, Catacombs/class XP and chest counts. Unknown costs remain unknown; partial values are labeled. Empty samples never produce NaN or infinite averages.
- **Loot:** item search, chest type, catalogued RNG-only and recorded-value filters; stable name/count/value/observed-rate sorting with direction. The observed rate means confirmed chests containing that item divided by confirmed sampled chests. It is **not** an official drop chance. Old aggregates lack a reliable numerator and show Unknown.
- **RNG Timeline:** personal acquisitions grouped by local date, newest/oldest order, item/activity/floor/chest/date/session filters, and available receipt/run context. Unknown historical prices or costs are not backfilled from today's market.
- **RNG Museum:** maintained rare-item catalog, obtained counts, first/latest acquisition, available floors, per-item History and permanent Pins. Undiscovered entries say **Not recorded**. This is not Hypixel's Museum donation system.

**Filters** selects All-time totals versus Timestamped records, account/profile scope, inclusive local date range and recent sessions. Date/session filters automatically require records. **Clear all** restores defaults while keeping the selected view. Browsing a floor never changes `DungeonTracker.currentFloor`, which remains owned by live detection. Query work is debounced and performed off-thread; at most 20 rows are mounted per page.

All-time totals preserve the original aggregate save, including its lack of account ownership. They are explicitly labeled **unscoped** and cannot honestly be split into historical profiles/dates/sessions. Choose **Current profile**, **All local profiles**, or **Unscoped legacy** for timestamped history. Offline, All local profiles and Unscoped legacy remain available. Normal and Master floors stay separate.

Older builds could add time since 1970 when a completion arrived without a start, producing roughly 20,000 days. On load, negative totals or totals exceeding one day per recorded run are treated as invalid. Before changing anything, the exact save is copied to `dungeon_tracker.json.before-time-repair`. Only the invalid time total is reset; run counts, loot, chest/reroll costs, valuables, XP and other floors are preserved. An additive `timedRuns` field distinguishes unknown historical durations from newly measured runs. Repair is idempotent and runs off the render thread through the existing atomic file writer. If the backup or repair fails, the original remains and recording pauses.

New run durations use a monotonic clock, separate from the calendar timestamps stored for date filtering. Missing starts cannot contribute elapsed time, and entering a new dungeon clears the previous run's clock. Timing appears as **Unknown** until a valid duration is recorded, then as partial with the number of timed runs. Averages use only timed runs; all-time XP/profit/runs per hour remain unavailable when their historical time is incomplete. Historical duration cannot be reconstructed from aggregate-only saves, so it is never estimated from today's time or from average PBs.

New runs store detected account UUID, available profile UUID/name, main/alpha server, session ID, floor, timestamps, duration and detected XP. New chest records require a matching inventory receipt after the existing chest action. Clicking a chest alone does not create an acquisition. Kismet usage requires a matching inventory decrease. Essence-only receipts cannot currently be verified by a physical inventory increase and are left unrecorded rather than invented. Prices are estimates captured at acquisition, not guaranteed sale proceeds. Run-level reroll costs cannot be apportioned to a chest-type filter.

## One personal acquisition archive

The tracker and Daily Digest reuse the existing catalog and coalesced inventory confirmation observer. A shared confirmation/item ID reconciles new observations from both paths. Repeated legitimate acquisitions get distinct receipt IDs. Community reports never enter personal collection counts.

Legacy tracker valuables import with an occurrence index, retaining two identical item/timestamp entries. Their ownership stays unknown. Digest personal history retains its recorded account/server/profile-name scope. Older sources lack shared receipt provenance, so similar timestamps are **not** guessed to be duplicates. Such entries show their legacy origin. Import markers make migration idempotent; imports do not publish to the relay.

`config/skymyce/acquisitions.json` is versioned, written atomically, and independent of Daily Digest recent-history settings and the seven-day community feed. Clearing/reducing that feed does not delete collection entries or pins. The archive retains up to 100,000 acquisitions and 100,000 run records, with a 32 MiB file limit. Capacity/save failures retain previous data and show a status; there is no silent personal-history eviction. Corrupt/future-version archives are preserved and recording is paused until repaired/restored. Keep backups before manually archiving old records.

## Shared theme and HUD

Open **Theme & Appearance → Shared theme**. Presets are Dark glass, Minimal, Light and High contrast. Inheritance is **preset → global overrides → optional widget overrides**. Missing widget values continue to follow later global changes; an explicit override remains independent.

Customize primary/secondary accents, panel/card/text/secondary-text/border colors, opacity (20–100%), padding (2–12), pixel-rounded versus square corners, borders and text shadow. Semantic success/warning/error colors remain separate. Values have live previews, hex/RGB/picker input, and an **Inherit** action. **Reset** removes overrides, retaining the selected preset. Changes save on closing the editor.

In `/sm hud`, right-click a widget to edit only its theme. Reset restores inheritance without changing position, anchor, visibility or scale. Existing drag/resize controls remain. **Export** copies version-1 JSON to the clipboard; **Import** accepts at most 16 KiB and validates fields, types and ranges before applying anything. Themes contain data only—no URLs, scripts or resource execution.

## Files and migration

| File | Behavior |
| --- | --- |
| `config/SkyMyce Config.jsonc` | Existing Resourceful schema/keys, additive theme/cosmetics fields; atomic queued saves retain unknown preferences. Original `.json`/`.jsonc` copied once to `.before-theme-v1` before loading/migration. |
| `config/skymyce/widgets.json` | Existing layout fields retained; nullable `theme` added; original preserved before upgrade. |
| `config/skymyce/dungeon_tracker.json` | Aggregate format retained with optional `timedRuns`; invalid duration totals repaired after preserving `.before-time-repair`. No fabricated historical runs. |
| `config/skymyce/acquisitions.json` | Version-1 permanent personal archive; source tracker/Digest files copied to `.before-acquisitions` before import. |
| `config/skymyce/tracker_view.json` | Remembered view/filter preferences. |
| `config/skymyce/auction_searches.json` | Version-1 search/filter/favorites preferences. |

The new JSON stores use bounded reads and atomic replace; recoverable originals are retained during migration. Invalid optional widget/theme fields fall back independently. Restore a backup with Minecraft closed. Never copy unscoped legacy data into a specific profile merely because it is currently logged in.

See [cosmetics and messaging](cosmetics-messaging.md) for commands/operator setup and [validation](upgrade-validation.md) for checks and remaining live tests.
