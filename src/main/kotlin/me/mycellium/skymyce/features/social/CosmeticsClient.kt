package me.mycellium.skymyce.features.social

import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.CosmeticsConfig
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendRelay
import me.mycellium.skymyce.utils.MC
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.CompletableFuture

data class CosmeticProfile(val uuid: UUID, val name: String?, val scale: Float, val revision: Long, val updatedAt: Long,
    val nameStyle: Component? = name?.let(Component::literal), val scaleX: Float = scale, val scaleY: Float = scale, val scaleZ: Float = scale)
fun parseCosmeticProfile(json: JsonObject): CosmeticProfile? = runCatching {
    val legacy = setOf("uuid", "name", "scale", "revision", "updatedAt")
    val extended = json.has("scaleX")
    require(json.keySet() == if (extended) legacy + setOf("nameStyle", "scaleX", "scaleY", "scaleZ") else legacy)
    val id = json.get("uuid").also { require(it.asJsonPrimitive.isString) }.asString.also { require(it.matches(Regex("[a-f0-9]{32}"))) }
    val uuid = UUID.fromString("${id.take(8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}")
    val name = json.get("name").takeUnless { it.isJsonNull }?.also { require(it.asJsonPrimitive.isString) }?.asString?.also {
        require(it.length in 1..32 && it.matches(Regex("[\\p{L}\\p{N}\\p{S} _.'•·»«-]+")) && it.none { char -> char == '§' || char == '@' } && it == it.trim() && Normalizer.normalize(it, Normalizer.Form.NFKC) == it)
    }
    val scale = json.get("scale").asDouble.also { require(it.isFinite() && it in .5..2.0 && json.get("scale").asJsonPrimitive.isNumber) }.toFloat()
    fun integer(key: String) = json.get(key).also { require(it.asJsonPrimitive.isNumber && it.asString.matches(Regex("[0-9]{1,16}"))) }.asLong.also { require(it in 0..9007199254740991L) }
    val revision = integer("revision"); val time = integer("updatedAt")
    require(revision != 0L || (name == null && scale == 1f && time == 0L))
    require(revision == 0L || time > 0)
    fun axis(key: String): Float = if (!extended) scale else json.get(key).also { require(it.asJsonPrimitive.isNumber) }.asDouble
        .also { require(it.isFinite() && it in .5..2.0) }.toFloat()
    val style = if (!extended) name?.let(Component::literal) else json.get("nameStyle").takeUnless { it.isJsonNull }?.let(::parseCosmeticText)
    require(style?.string == name)
    val x = axis("scaleX"); val y = axis("scaleY"); val z = axis("scaleZ")
    require(scale == y && (revision != 0L || x == 1f && y == 1f && z == 1f))
    CosmeticProfile(uuid, name, scale, revision, time, style, x, y, z)
}.getOrNull()

/** A reset remains a revisioned record. Reads are O(1), including in render-state extraction. */
class CosmeticCache(private val maximum: Int = 512) {
    init { require(maximum in 1..512) }
    private data class Entry(val profile: CosmeticProfile, val received: Long)
    private val values = linkedMapOf<UUID, Entry>()
    fun apply(profile: CosmeticProfile, now: Long): Boolean {
        val previous = values[profile.uuid]?.profile
        if (previous != null && (profile.revision < previous.revision || profile.revision == previous.revision && profile != previous)) return false
        values.remove(profile.uuid); values[profile.uuid] = Entry(profile, now)
        while (values.size > maximum) values.remove(values.keys.first())
        return true
    }
    fun get(uuid: UUID, now: Long): CosmeticProfile? = values[uuid]?.takeIf { now - it.received in 0..600000 }?.profile
    fun clear() = values.clear()
    fun expire(now: Long): Boolean = values.entries.removeIf { now - it.value.received !in 0..600000 }
    val size get() = values.size
}

object CosmeticsClient : SkyMyceModule() {
    private val cache = CosmeticCache()
    private val checked = linkedMapOf<UUID, Long>()
    private val usernames = linkedMapOf<UUID, String>()
    private var lookup: CompletableFuture<JsonObject?>? = null
    private var generation = 0L
    private var nextTick = 0L
    private var identity: UUID? = null
    private var enabled = false
    private var forceOwn = false
    var revision = 0L
        private set
    var status = "Waiting for Hypixel"
        private set
    var linkCode: String? = null
        private set
    var linkExpires = 0L
        private set

    override fun init() { ClientTickEvents.END_CLIENT_TICK.register { tick() } }
    override fun tick() {
        val now = System.currentTimeMillis()
        if (!LocationAPI.onHypixel || MC.instance.player == null || identity != null && identity != MC.instance.user.profileId) {
            if (identity != null || enabled) clear()
            status = "Join Hypixel to synchronize cosmetics"; return
        }
        identity = MC.instance.user.profileId
        val wanted = CosmeticsConfig.names || CosmeticsConfig.scaling
        if (CosmeticNames.enabled != CosmeticsConfig.names) { CosmeticNames.enabled = CosmeticsConfig.names; MC.instance.gui.chat.rescaleChat() }
        if (enabled && !wanted) {
            DungeonFriendRelay.disableCosmetics()
            clear(); identity = MC.instance.user.profileId; status = "Cosmetic rendering disabled"
        }
        enabled = wanted
        if (now < nextTick) return
        nextTick = now + 1000
        if (cache.expire(now)) revision++
        // Hypixel's signed-in tab profiles provide current UUID/name pairs without extra Mojang calls.
        val roster = MC.connection?.onlinePlayers.orEmpty().asSequence().take(256).toList()
        usernames[MC.instance.user.profileId] = MC.instance.user.name
        roster.forEach { player ->
            usernames.entries.removeIf { it.key != player.profile.id() && it.value.equals(player.profile.name(), true) }
            usernames[player.profile.id()] = player.profile.name()
        }
        while (usernames.size > 512) usernames.remove(usernames.keys.first())
        updateNames(now)
        if (linkCode != null && now >= linkExpires) { linkCode = null; revision++ }
        if (!wanted && !forceOwn) return
        if (!DungeonFriendRelay.connected || !DungeonFriendRelay.cosmeticsAvailable) {
            status = if (DungeonFriendRelay.connected) "Relay does not support cosmetics" else "Relay offline • cached cosmetics expire after 10 minutes"; return
        }
        if (lookup != null) return
        // Bounded tab roster scan once per second, never inside a renderer or tied to a friends/menu scan.
        val visible = (listOf(MC.instance.user.profileId) + if (wanted) roster.map { it.profile.id() } else emptyList()).distinct()
        val ids = visible.filter { (checked[it] ?: 0) + 300000 <= now }.take(16)
        if (ids.isEmpty()) return
        val token = generation
        val request = DungeonFriendRelay.cosmeticsRequest("cosmetics_get", mapOf("uuids" to ids.map { it.toString().replace("-", "") })) ?: return
        ids.forEach { checked.putIfAbsent(it, 0) } // Accept a newer live update while this lookup is still pending.
        forceOwn = false
        lookup = request
        request.whenComplete { result, _ -> MC.instance.execute {
            if (token != generation) return@execute
            lookup = null
            val records = result?.get("records")?.takeIf { it.isJsonArray }?.asJsonArray
            val parsed = records?.takeIf { it.size() <= 16 }?.mapNotNull { runCatching { parseCosmeticProfile(it.asJsonObject) }.getOrNull() }
            if (parsed == null || parsed.size != ids.size || parsed.map { it.uuid }.toSet() != ids.toSet()) {
                status = "Cosmetics unavailable • retrying in 10 seconds"; nextTick = System.currentTimeMillis() + 10000
            } else {
                val received = System.currentTimeMillis()
                parsed.forEach { cache.apply(it, received); checked[it.uuid] = received }
                while (checked.size > 512) checked.remove(checked.keys.first())
                status = "Cosmetics synchronized • visual only"; revision++
                updateNames(received)
            }
            if (!enabled) DungeonFriendRelay.disableCosmetics()
        } }
    }
    fun ready() { generation++; lookup?.cancel(false); lookup = null; checked.clear(); nextTick = 0; revision++ }
    fun closed() { generation++; lookup?.cancel(false); lookup = null; linkCode = null; revision++ }
    fun clear() {
        closed(); cache.clear(); checked.clear(); usernames.clear()
        val namesEnabled = CosmeticNames.enabled
        CosmeticNames.enabled = false
        val namesChanged = CosmeticNames.update(emptyMap())
        if (namesEnabled || namesChanged) MC.instance.gui.chat.rescaleChat()
        identity = null; enabled = false; forceOwn = false; nextTick = 0
    }
    fun event(json: JsonObject) {
        if (!enabled || !LocationAPI.onHypixel) return
        val record = parseCosmeticProfile(json) ?: return
        // Bloom subscription false positives are ignored; only requested/visible identities enter the cache.
        if (record.uuid !in checked) return
        if (cache.apply(record, System.currentTimeMillis())) { revision++; updateNames(System.currentTimeMillis()) }
    }
    fun own() = cache.get(MC.instance.user.profileId, System.currentTimeMillis())
    fun refreshOwn() { checked.remove(MC.instance.user.profileId); forceOwn = true; nextTick = 0 }
    fun requestLink() {
        linkCode = null
        if (!LocationAPI.onHypixel || !DungeonFriendRelay.cosmeticsAvailable) { status = "An authenticated cosmetics-enabled relay connection is required"; revision++; return }
        val request = DungeonFriendRelay.cosmeticsRequest("cosmetics_link") ?: run { status = "Relay busy • retry shortly"; revision++; return }
        val token = generation
        request.whenComplete { result, _ -> MC.instance.execute {
            if (token != generation) return@execute
            val code = runCatching { result?.get("code")?.asString?.takeIf { it.matches(Regex("[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){2}|[a-f0-9]{32}")) } }.getOrNull()
            val expires = runCatching { result?.get("expiresAt")?.asLong }.getOrNull()
            val now = System.currentTimeMillis()
            if (code != null && expires != null && expires in now + 1..now + 360000) {
                linkCode = code; linkExpires = expires; status = "Use /cosmetics link in bot-commands (1552057526969835612) within five minutes"
            } else status = when (result?.get("error")?.asString) {
                "not_configured" -> "The relay operator has not configured Discord interactions"
                "rate_limited" -> "Wait a minute before requesting another link code"
                else -> "Could not issue a link code • reconnect and retry"
            }
            revision++
        } }
    }
    private fun updateNames(now: Long) {
        val known = usernames.entries.mapNotNull { (uuid, real) -> cache.get(uuid, now)?.nameStyle?.let { real to it } }.toMap()
        if (CosmeticNames.update(known)) MC.instance.gui.chat.rescaleChat()
    }
    @JvmStatic fun rendering(uuid: UUID): CosmeticProfile? = if (enabled && LocationAPI.onHypixel) cache.get(uuid, System.currentTimeMillis()) else null
}
