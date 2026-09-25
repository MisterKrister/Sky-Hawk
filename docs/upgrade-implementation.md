# Integrated upgrade notes

Baseline: Minecraft 26.1.2, Java 25, Kotlin 2.4.0, Fabric Loader 0.19.3, Fabric API 0.145.1+26.1, owo-lib 0.13.1+26.1, Resourceful Config 4.0.1 / ConfigKt 4.0.0-beta.1, SkyBlockAPI 4.2.19. Mod version stays 2.4.2. Existing Gradle assertion checks and relay Miniflare tests pass before changes.

Implementation sequence:
1. Bounded, generation-aware auction indexing and search, then retained responsive browser and saved searches.
2. Shared theme inheritance, validated import/export and per-widget editing without changing layout storage.
3. Tracker dashboard, timestamped run records, and durable personal acquisition history, reusing Digest confirmation and catalog. Preserve and label unscoped legacy aggregates/events; never infer their owner.
4. Server-owned cosmetics with signed Discord HTTP interactions, Minecraft-bound link challenges, bounded authenticated synchronization and version-specific visual hooks.
5. Dedicated relay user messaging, separate from tokenized LFG controls, with acknowledged delivery and compatibility commands.
6. Integrated checks, rendered client validation, migration/operator documentation and explicit remaining live checks.

Constraints: preserve existing config keys and save formats; additive files/migrations and recoverable originals; no production deployment, command registration, live data changes or git push in this task. Existing uncommitted news User-Agent/channel changes and FIX_PLAN.md are unrelated and retained.

Inspection findings: auction fetch omitted its page query and decoded unlimited NBT; browser only discovered more API pages through its next button. Tracker stored aggregate totals plus timestamps of price-threshold valuables, and its view selection wrote the live recording floor. Digest has a bounded opt-in RNG feed, confirmed dungeon inventory increases, and a maintained rare-item catalog; its retention must not own the permanent collection. Relay already verifies signed Minecraft identities, persists SQLite state per room and retains capabilities/receipts in hibernating socket attachments. Cowshed integration polls Discord REST; it does not contain a gateway bot or interaction handler. Ordinary messages must not reuse the existing LFG reply parser.
