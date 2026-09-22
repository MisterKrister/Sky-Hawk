package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.utils.MC
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun relayUri(text: String): URI? = runCatching {
    require(text.length <= 512)
    URI(text.trim()).also {
        require(it.scheme == "wss" || (it.scheme == "ws" && it.host in setOf("localhost", "127.0.0.1", "[::1]")))
        require(!it.host.isNullOrBlank() && it.userInfo == null && it.fragment == null && it.path == "/websocket")
        require(it.rawQuery == null || it.rawQuery.matches(Regex("room=[a-z0-9_-]{1,32}")))
    }
}.getOrNull()

/** Only a receipt from the addressed player's mod cancels the fallback. */
class RelayDeliveries {
    private data class Pending(val name: String, val deadline: Long, val received: () -> Unit, val failed: () -> Unit)
    private val pending = linkedMapOf<String, Pending>()
    val full get() = pending.size >= 16
    fun add(id: String, name: String, now: Long, received: () -> Unit, failed: () -> Unit) {
        check(!full && id !in pending)
        pending[id] = Pending(name, now + 5000, received, failed)
    }
    fun acknowledge(id: String, from: String) {
        val item = pending[id]?.takeIf { it.name.equals(from, true) } ?: return
        pending.remove(id)
        item.received()
    }
    fun fail(id: String) { pending.remove(id)?.failed?.invoke() }
    fun tick(now: Long) { pending.filterValues { now >= it.deadline }.keys.toList().forEach(::fail) }
    fun clear(failed: Boolean = false) {
        val old = pending.values.toList()
        pending.clear()
        if (failed) old.forEach { it.failed() }
    }
}

/** Network callbacks enter the Minecraft thread before touching game state or pending deliveries. */
object DungeonFriendRelay {
    private val gson = Gson()
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val deliveries = RelayDeliveries()
    private val statsRequests = mutableMapOf<String, Pair<Long, CompletableFuture<JsonObject?>>>()
    var sharedStatsAvailable = false
        private set
    private var socket: WebSocket? = null
    private var sending: CompletableFuture<*> = CompletableFuture.completedFuture(null)
    private var generation = 0L
    private var identity = ""
    private var connecting = false
    private var authenticating = false
    private var nextAttempt = 0L
    private var retryDelay = 1000L
    private var deadline = 0L
    private var nextPing = 0L
    var connected = false
        private set
    var status = "Relay not configured"
        private set

    fun tick(active: Boolean) {
        val url = DungeonFriendsSettings.relayUrl
        val user = MC.instance.user
        val key = "$url|${user.profileId}"
        if (!active || url.isBlank()) {
            if (identity.isNotEmpty()) disconnect()
            status = if (url.isBlank()) "Relay not configured" else "Relay connects in SkyBlock"
            return
        }
        if (key != identity) { disconnect(); identity = key }
        val now = DungeonFriends.now()
        deliveries.tick(now)
        statsRequests.filterValues { it.first <= now }.keys.toList().forEach { statsRequests.remove(it)?.second?.complete(null) }
        if ((connecting || socket != null) && now >= deadline) failed("Relay timed out; reconnecting")
        if (connected && now >= nextPing) { packet("ping"); nextPing = now + 45000 }
        if (socket != null || connecting || now < nextAttempt) return
        val uri = relayUri(url) ?: run { status = "Invalid relay URL in Settings"; return }
        connecting = true
        authenticating = false
        deadline = now + 35000
        status = "Connecting to relay..."
        val token = generation
        val sessionService = MC.instance.services().sessionService()
        val keyManager = MC.instance.profileKeyPairManager
        val listener = object : WebSocket.Listener {
            private val text = StringBuilder()
            override fun onOpen(webSocket: WebSocket) {
                MC.instance.execute {
                    if (token != generation) webSocket.abort() else { socket = webSocket; connecting = false }
                }
                webSocket.request(1)
            }
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                if (text.length + data.length > 8192) {
                    MC.instance.execute { if (token == generation) failed("Relay sent an oversized message") }
                    return null
                }
                text.append(data)
                if (last) {
                    val message = text.toString()
                    text.setLength(0)
                    MC.instance.execute {
                        if (token == generation) {
                            deadline = DungeonFriends.now() + if (connected) 90000 else 30000
                            if (message != "pong") runCatching {
                                val json = JsonParser.parseString(message).asJsonObject
                                when (json.get("type")?.asString) {
                                    "challenge" -> {
                                        check(!authenticating && !connected && json.get("protocol")?.asInt == 2)
                                        val serverId = json.get("serverId").asString
                                        check(serverId.matches(Regex("[a-f0-9]{32}")))
                                        authenticating = true
                                        status = "Verifying Minecraft account..."
                                        keyManager.prepareKeyPair().thenApplyAsync { optional ->
                                            val pair = optional.orElseThrow { IllegalStateException("Minecraft account key unavailable") }
                                            val certificate = pair.publicKey().data()
                                            check(!certificate.hasExpired())
                                            val profile = sessionService.fetchProfile(user.profileId, true)?.profile()
                                                ?: error("Signed Minecraft profile unavailable")
                                            check(profile.id() == user.profileId && profile.name().equals(user.name, true))
                                            val textures = sessionService.getPackedTextures(profile)
                                            check(textures != null && textures.hasSignature())
                                            // Minecraft manages the private key. Only public, signed proof leaves the client.
                                            val uuid = user.profileId.toString().replace("-", "")
                                            val proof = Signature.getInstance("SHA256withRSA").run {
                                                initSign(pair.privateKey())
                                                update("SkyMyce relay v2\n$serverId\n$uuid\n${user.name}".toByteArray(Charsets.UTF_8))
                                                sign()
                                            }
                                            val base64 = Base64.getEncoder()
                                            mapOf("type" to "authenticate", "name" to user.name, "uuid" to uuid,
                                                "expires" to certificate.expiresAt().toEpochMilli(),
                                                "publicKey" to base64.encodeToString(certificate.key().encoded),
                                                "keySignature" to base64.encodeToString(certificate.keySignature()),
                                                "proof" to base64.encodeToString(proof),
                                                "profile" to textures.value(), "profileSignature" to textures.signature())
                                        }.whenComplete { proof, error -> MC.instance.execute {
                                            if (token == generation) {
                                                if (error != null) failed("Minecraft signed proof unavailable; restart Minecraft and reconnect")
                                                else packet(proof)
                                            }
                                        } }
                                    }
                                    "ready" -> {
                                        check(authenticating && json.get("protocol")?.asInt == 2)
                                        check(json.get("uuid").asString == user.profileId.toString().replace("-", ""))
                                        check(json.get("name").asString.equals(user.name, true))
                                        connected = true
                                        sharedStatsAvailable = json.get("statsCache")?.asBoolean == true
                                        retryDelay = 1000
                                        deadline = DungeonFriends.now() + 90000
                                        nextPing = DungeonFriends.now() + 45000
                                        status = "Relay connected"
                                    }
                                    "message", "ack" -> {
                                        check(connected)
                                        val id = json.get("id").asString
                                        val from = json.get("from").asString
                                        val uuid = json.get("uuid").asString
                                        check(id.matches(Regex("[a-f0-9]{32}")) && from.matches(Regex("[A-Za-z0-9_]{1,16}")) && uuid.matches(Regex("[a-f0-9]{32}")))
                                        if (json.get("type").asString == "ack") deliveries.acknowledge(id, from)
                                        else {
                                            val body = json.get("text").asString
                                            check(body.length in 1..256 && body.none { it < ' ' || it == '\u007f' || it == '§' })
                                            if (DungeonFriends.onRelayMessage(from, uuid, body)) packet(mapOf("type" to "ack", "to" to from, "id" to id))
                                        }
                                    }
                                    "stats_result" -> { check(connected); statsRequests.remove(json.get("id").asString)?.second?.complete(json) }
                                    "error" -> { check(connected); deliveries.fail(json.get("id").asString) }
                                    else -> error("Unknown relay packet")
                                }
                            }.onFailure { failed("Invalid relay response; reconnecting") }
                        }
                    }
                }
                webSocket.request(1)
                return null
            }
            override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                MC.instance.execute { if (token == generation) failed("Unexpected relay data") }
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                MC.instance.execute { if (token == generation) {
                    val detail = reason.filter { it >= ' ' && it != '\u007f' && it != '§' }.take(120)
                    failed(if (statusCode == 4001) "Relay account connected elsewhere; use Reconnect"
                        else "Relay closed ($statusCode): ${detail.ifEmpty { "connection lost" }}")
                    if (statusCode == 4001) nextAttempt = Long.MAX_VALUE
                } }
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) {
                MC.instance.execute { if (token == generation) failed("Relay unavailable; reconnecting") }
            }
        }
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(uri, listener)
            .whenComplete { _, error -> if (error != null) MC.instance.execute {
                if (token == generation) failed("Relay unavailable; reconnecting")
            } }
    }

    fun send(name: String, text: String, received: () -> Unit = {}, failed: () -> Unit = {}): Boolean {
        if (!connected || deliveries.full || !name.matches(Regex("[A-Za-z0-9_]{1,16}")) ||
            text.length !in 1..256 || text.any { it < ' ' || it == '\u007f' || it == '§' }) return false
        val id = UUID.randomUUID().toString().replace("-", "")
        deliveries.add(id, name, DungeonFriends.now(), received, failed)
        packet(mapOf("type" to "message", "id" to id, "to" to name, "text" to text))
        return true
    }

    fun lookupStats(name: String, uuid: String?): CompletableFuture<JsonObject?>? {
        if (!sharedStatsAvailable || statsRequests.size >= 8) return null
        val id = UUID.randomUUID().toString().replace("-", "")
        val future = CompletableFuture<JsonObject?>()
        statsRequests[id] = DungeonFriends.now() + 5000 to future
        packet(buildMap { put("type", "stats_get"); put("id", id); put("name", name); uuid?.let { put("uuid", it) } })
        return future
    }

    fun publishStats(name: String, uuid: String, stats: DungeonFriendStats, fetchedAt: Long, upload: String) {
        if (!sharedStatsAvailable) return
        packet(mapOf("type" to "stats_put", "id" to UUID.randomUUID().toString().replace("-", ""), "name" to name,
            "uuid" to uuid, "stats" to stats, "fetchedAt" to fetchedAt, "upload" to upload))
    }

    private fun packet(data: Any) {
        val ws = socket ?: return
        val token = generation
        val text = if (data is String) data else gson.toJson(data)
        sending = sending.thenCompose { ws.sendText(text, true) }.whenComplete { _, error ->
            if (error != null) MC.instance.execute { if (token == generation) failed("Relay send failed; reconnecting") }
        }
    }

    private fun failed(message: String) {
        generation++
        socket?.abort()
        socket = null
        connected = false
        sharedStatsAvailable = false
        statsRequests.values.forEach { it.second.complete(null) }
        statsRequests.clear()
        connecting = false
        sending = CompletableFuture.completedFuture(null)
        nextAttempt = DungeonFriends.now() + retryDelay
        retryDelay = (retryDelay * 2).coerceAtMost(60000)
        status = message
        if (message != "Relay disconnected") SkyMyce.logger.warn("{}", message)
        deliveries.clear(failed = true)
    }

    fun disconnect() {
        deliveries.clear()
        failed("Relay disconnected")
        identity = ""
        retryDelay = 1000
        nextAttempt = 0
    }
}
