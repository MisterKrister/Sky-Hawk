package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

/** Queue and cache belong to the client thread; only the single in-flight request runs off-thread. */
object DungeonFriendStatsCache {
    private data class Cached(val stats: DungeonFriendStats, val expires: Long)
    private val cache = mutableMapOf<String, Cached>()
    private val uuids = mutableMapOf<String, String>()
    private val pending = linkedMapOf<String, UUID?>()
    private var busy = false
    private var generation = 0L
    private var nextRequest = 0L
    private var apiKey = ""
    var status = ""
        private set
    var version = 0L
        private set

    val stats: Map<String, DungeonFriendStats> get() = cache.mapValues { it.value.stats }
    fun get(name: String): DungeonFriendStats? = cache[name.lowercase()]?.stats

    fun request(name: String, uuid: UUID? = null) {
        if (!name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return
        val key = name.lowercase()
        if ((cache[key]?.expires ?: 0L) > System.currentTimeMillis()) return
        if (key !in pending) pending[key] = uuid
    }

    fun clear() {
        generation++
        cache.clear()
        pending.clear()
        uuids.clear()
        version++
    }

    fun tick() {
        val key = PartyCommandsConfig.hypixelApiKey.trim()
        if (key != apiKey) {
            apiKey = key
            clear()
            nextRequest = 0L
        }
        if (key.isEmpty()) {
            status = "Set your Hypixel API key under General > Party Commands for stats"
            pending.clear()
            return
        }
        val now = System.currentTimeMillis()
        if (busy || now < nextRequest) return
        val request = pending.entries.firstOrNull() ?: return
        pending.remove(request.key)
        if ((cache[request.key]?.expires ?: 0L) > now) return
        val knownUuid = request.value?.toString()?.replace("-", "") ?: uuids[request.key]
        busy = true
        val token = generation
        status = "Loading dungeon stats"
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
                result = Http.get(
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
                            message = "Hypixel API key rejected; check General > Party Commands"
                            DungeonFriendStats(StatsState.UNAVAILABLE)
                        }
                        else -> {
                            check(isOk)
                            DungeonFriendStats.fromProfiles(JsonParser.parseString(body).asJsonObject, resolvedUuid)
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
                val uuid = resolvedUuid
                val retry = retryAfter
                val feedback = message
                MC.instance.execute {
                    busy = false
                    if (token == generation) {
                        val time = System.currentTimeMillis()
                        if (uuid != null) uuids[request.key] = uuid
                        // A failed refresh must not erase previously verified eligibility.
                        val previous = cache[request.key]?.stats
                        val value = if (fetched.state == StatsState.UNAVAILABLE) previous ?: fetched else fetched
                        cache[request.key] = Cached(value, time + if (fetched.state == StatsState.UNAVAILABLE) 60000 else 600000)
                        nextRequest = time + retry
                        status = feedback
                        version++
                    }
                }
            }
        }
    }
}
