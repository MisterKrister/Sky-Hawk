package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import java.nio.file.Path
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Queue and cache belong to the client thread; only the single in-flight request runs off-thread. */
object DungeonFriendStatsCache {
    private data class Request(val uuid: UUID?, val force: Boolean)
    private val cache = mutableMapOf<String, CachedDungeonFriend>()
    private val pending = linkedMapOf<String, Request>()
    private var store: DungeonFriendStatsStore? = null
    private var dirty = false
    private var nextSave = 0L
    private var inFlight: String? = null
    private var generation = 0L
    private var nextRequest = 0L
    private var apiKey = ""
    private var feedback = ""
    val status: String get() = feedback.ifEmpty {
        inFlight?.let { "Loading PBs: $it (${pending.size} queued)" }
            ?: if (pending.isNotEmpty()) "Loading PBs: ${pending.size} queued" else ""
    }
    var version = 0L
        private set

    val stats: Map<String, DungeonFriendStats> get() = cache.mapValues { it.value.stats }
    fun get(name: String): DungeonFriendStats? = cache[name.lowercase()]?.stats
    val canFetch: Boolean get() = DungeonFriendProfileProvider.available || PartyCommandsConfig.hypixelApiKey.isNotBlank()

    fun request(name: String, uuid: UUID? = null, force: Boolean = false) {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        val key = name.lowercase()
        val existing = cache[key]
        val changedPlayer = uuid != null && existing?.uuid != null && existing.uuid != uuid.toString().replace("-", "")
        if (changedPlayer) { cache.remove(key); dirty = true; version++ }
        if (key == inFlight || (!force && !changedPlayer && existing?.shouldRefresh(System.currentTimeMillis()) == false)) return
        val queued = pending[key]
        pending[key] = Request(uuid ?: queued?.uuid, force || changedPlayer || queued?.force == true)
    }

    fun initialize(file: Path) {
        val persistence = DungeonFriendStatsStore(file)
        try {
            cache.putAll(persistence.load())
            store = persistence
            version++
        } catch (_: Exception) {
            // Preserve unreadable data rather than silently overwriting it.
            SkyMyce.logger.warn("Could not read saved dungeon friend stats; using memory for this session")
        }
    }

    fun save(force: Boolean = false) {
        val persistence = store ?: return
        val now = System.currentTimeMillis()
        if (!dirty || (!force && now < nextSave)) return
        nextSave = now + 30000
        try {
            persistence.save(cache.toMap())
            dirty = false
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend stats; will retry")
        }
    }

    fun disconnect() {
        save(true)
        generation++
        pending.clear()
        feedback = ""
    }

    fun clear() {
        generation++
        cache.clear()
        pending.clear()
        feedback = ""
        version++
    }

    /** Retain displayed PBs and the server's retry deadline while requesting fresh stats. */
    fun refresh() {
        cache.replaceAll { _, value -> if ((value.stats.catacombs ?: 0) > 40) value.copy(expires = 0L) else value }
    }

    fun tick() {
        save()
        val key = PartyCommandsConfig.hypixelApiKey.trim()
        if (key != apiKey) {
            apiKey = key
            disconnect()
            cache.replaceAll { _, value -> if (value.stats.state == StatsState.UNAVAILABLE) value.copy(expires = 0L) else value }
            nextRequest = 0L
        }
        if (!canFetch) {
            feedback = "Use SkyBlockPv / SkyBlocker or add an API key"
            pending.clear()
            return
        }
        val now = System.currentTimeMillis()
        if (inFlight != null || now < nextRequest) return
        val request = pending.entries.firstOrNull() ?: return
        pending.remove(request.key)
        if (!request.value.force && cache[request.key]?.shouldRefresh(now) == false) return
        val knownUuid = request.value.uuid?.toString()?.replace("-", "") ?: cache[request.key]?.uuid
        inFlight = request.key
        val token = generation
        feedback = ""
        Scheduling.schedule(0.seconds) {
            var resolvedUuid = knownUuid
            var result = DungeonFriendStats(StatsState.UNAVAILABLE)
            var retryAfter = 2000L
            var message = ""
            try {
                if (resolvedUuid == null) {
                    resolvedUuid = Http.get("https://api.mojang.com/users/profiles/minecraft/${request.key}") {
                        val body = asText()
                        check(isOk) { "Name lookup unavailable" }
                        JsonParser.parseString(body).asJsonObject.get("id").asString
                    }
                    delay(2000)
                }
                require(resolvedUuid!!.matches(Regex("[a-fA-F0-9]{32}")))
                val profileUuid = UUID.fromString(resolvedUuid.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                val provided = DungeonFriendProfileProvider.fetch(request.key, profileUuid)
                result = if (provided != null) provided else {
                    check(key.isNotEmpty()) { "Profile providers unavailable" }
                    Http.get(
                        "https://api.hypixel.net/v2/skyblock/profiles",
                        queries = mapOf("uuid" to resolvedUuid),
                        headers = mapOf("API-Key" to key),
                    ) {
                        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
                        val reset = header("RateLimit-Reset")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60L
                        if (header("RateLimit-Remaining")?.toIntOrNull() == 0) retryAfter = maxOf(retryAfter, reset * 1000)
                        val body = asText() // Consume/close the response even on errors.
                        when (statusCode) {
                            429 -> {
                                retryAfter = maxOf(reset, header("Retry-After")?.toLongOrNull()?.coerceIn(1, 3600) ?: 60) * 1000
                                message = "API rate limit reached; waiting before retrying"
                                DungeonFriendStats(StatsState.UNAVAILABLE)
                            }
                            401, 403 -> {
                                retryAfter = 300000
                                message = "API key rejected; update it in API setup"
                                DungeonFriendStats(StatsState.UNAVAILABLE)
                            }
                            else -> {
                                check(isOk)
                                DungeonFriendStats.fromProfiles(JsonParser.parseString(body).asJsonObject, resolvedUuid)
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Stats temporarily unavailable; retrying later"
                retryAfter = maxOf(retryAfter, 10000)
            } finally {
                val fetched = result
                val uuid = resolvedUuid?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                val retry = retryAfter
                val requestFeedback = message
                MC.instance.execute {
                    inFlight = null
                    if (token == generation) {
                        val time = System.currentTimeMillis()
                        // A failed refresh must not erase previously verified eligibility.
                        val previous = cache[request.key]?.stats
                        val value = if (fetched.state == StatsState.UNAVAILABLE) previous ?: fetched else fetched
                        cache[request.key] = CachedDungeonFriend(value, uuid, time + if (fetched.state == StatsState.UNAVAILABLE) 60000 else 600000)
                        dirty = true
                        nextRequest = time + retry
                        feedback = requestFeedback
                        version++
                    }
                }
            }
        }
    }
}
