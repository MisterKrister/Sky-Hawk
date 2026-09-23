package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.MC
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.item.calculator.getItemValue
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

internal const val FRIEND_WEALTH_TTL = 900000L

data class FriendWealth(
    val networth: Double? = null, val purse: Double? = null, val bank: Double? = null,
    val wardrobe: Double? = null, val profile: String = "", val status: String = "",
    val fetchedAt: Long = System.currentTimeMillis(),
    val hasProfile: Boolean? = null, val uuid: String? = null, val expires: Long = fetchedAt + FRIEND_WEALTH_TTL,
) {
    fun shouldRefresh(now: Long, online: Boolean, skyBlockLocation: Boolean): Boolean = when (hasProfile) {
        false -> skyBlockLocation && now - fetchedAt >= 300000
        true -> (online || expires == 0L) && now >= expires
        null -> now - fetchedAt >= 60000
    }
}

internal fun sharedFriendWealth(record: JsonObject, name: String, uuid: UUID?, now: Long): FriendWealth? = runCatching {
    if (!record.get("name").asString.equals(name, true)) return null
    val id = record.get("uuid").asString
    if (!id.matches(Regex("[a-f0-9]{32}")) || (uuid != null && id != uuid.toString().replace("-", ""))) return null
    val json = record.getAsJsonObject("wealth")
    val present = json.getAsJsonPrimitive("hasProfile").takeIf { it.isBoolean }?.asBoolean ?: return null
    val timestamp = record.getAsJsonPrimitive("fetchedAt")
    if (!timestamp.isNumber || !timestamp.asDouble.isFinite() || timestamp.asDouble != timestamp.asLong.toDouble()) return null
    val fetched = timestamp.asLong
    val ttl = if (present) FRIEND_WEALTH_TTL else 604800000L
    if (fetched < 0 || fetched <= now - ttl || fetched > now + 60000) return null
    fun money(key: String): Double? = json.get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber)
        it.asDouble.also { amount -> require(present && amount.isFinite() && amount in 0.0..9007199254740991.0) }
    }
    fun text(key: String, limit: Int) = json.get(key)?.takeUnless { it.isJsonNull }?.let {
        require(it.isJsonPrimitive && it.asJsonPrimitive.isString)
        it.asString
    }.orEmpty().also { value ->
        require(value.length <= limit && value.all { it in ' '..'~' })
    }
    val resolved = UUID.fromString(id.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
    FriendWealth(money("networth"), money("purse"), money("bank"), money("wardrobe"), text("profile", 64),
        text("status", 128), minOf(fetched, now), present, resolved.toString(), minOf(fetched, now) + ttl)
}.getOrNull()

internal fun publicCoins(json: JsonObject?, vararg path: String): Double? = runCatching {
    var value: com.google.gson.JsonElement? = json
    path.forEach { value = value?.asJsonObject?.get(it) }
    value?.asDouble?.takeIf { it.isFinite() && it >= 0 }
}.getOrNull()

internal fun hasPublicInventory(member: JsonObject?): Boolean = runCatching {
    member?.getAsJsonObject("inventory")?.getAsJsonObject("inv_contents")?.get("data")?.asString?.isNotBlank() == true
}.getOrDefault(false)

/** Checks the complete roster in the background, reusing confirmed profile results across sessions. */
object FriendWealthCache {
    private val entries = mutableMapOf<String, FriendWealth>()
    private var loading: String? = null
    private var nextRequest = 0L
    private var store: FriendWealthStore? = null
    private var dirty = false
    private var nextSave = 0L
    private val newFriends = linkedSetOf<String>()
    private val manualRefresh = linkedSetOf<String>()
    private val waiting = mutableMapOf<String, Long>()
    private var nextManualRefresh = 0L
    var version = 0L
        private set
    private val providerAvailable get() = FabricLoader.getInstance().isModLoaded("skyblockpv")
    val available get() = providerAvailable || DungeonFriendRelay.sharedWealthAvailable
    val busy get() = loading != null
    fun get(name: String) = entries[name.lowercase()]
    fun isLoading(name: String) = loading == name.lowercase()

    fun initialize(file: Path) {
        try {
            val persistence = FriendWealthStore(file)
            entries.putAll(persistence.load())
            store = persistence
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not read friend wealth cache; original file preserved")
        }
        version++
    }

    fun save(force: Boolean = false) {
        val persistence = store ?: return
        if (!dirty || (!force && System.currentTimeMillis() < nextSave)) return
        nextSave = System.currentTimeMillis() + 5000
        try { persistence.save(entries); dirty = false }
        catch (_: Exception) { SkyMyce.logger.warn("Could not save friend wealth cache; will retry") }
    }

    fun checkNewFriend(name: String) { newFriends += name.lowercase() }

    fun refresh() {
        val now = System.currentTimeMillis()
        if (now < nextManualRefresh) return
        nextManualRefresh = now + 60000
        manualRefresh += entries.filterValues { it.hasProfile == true }.keys
        version++
    }

    fun tick(friends: Collection<OnlineDungeonFriend>, online: Set<String>) {
        save()
        if (!available || busy) return
        val now = System.currentTimeMillis()
        if (now < nextRequest || (!DungeonFriendRelay.sharedWealthAvailable && now < DungeonFriendProfileProvider.nextViewerRequest)) return
        val candidates = friends.filter { it.name.matches(Regex("[A-Za-z0-9_]{1,16}")) && now >= (waiting[it.name.lowercase()] ?: 0L) }
        candidates.forEach { friend ->
            val key = friend.name.lowercase()
            val uuid = FriendsAPI.getFriend(friend.name)?.uuid?.toString()
            val entry = get(key)
            if (entry?.uuid != null && uuid != null && uuid != entry.uuid) { entries.remove(key); dirty = true; version++ }
        }
        val friend = candidates.firstOrNull { it.name.lowercase() in newFriends }
            ?: candidates.firstOrNull { it.name.lowercase() in manualRefresh }
            ?: candidates.firstOrNull { get(it.name) == null }
            ?: candidates.firstOrNull { friend ->
                get(friend.name)?.shouldRefresh(now, friend.name.lowercase() in online,
                    friend.location.contains("SkyBlock", true) || friend.activity == FriendActivity.IN_RUN) == true
            } ?: return
        val name = friend.name
        val newlyAdded = name.lowercase() in newFriends
        loading = name.lowercase()
        version++
        val knownUuid = FriendsAPI.getFriend(name)?.uuid ?: get(name)?.uuid?.let(UUID::fromString)
        val alreadyAbsent = !newlyAdded && get(name) == null && DungeonFriendStatsCache.get(name)?.state == StatsState.NO_PROFILE &&
            !friend.location.contains("SkyBlock", true) && friend.activity != FriendActivity.IN_RUN
        val sharedLookup = DungeonFriendRelay.lookupWealth(name, knownUuid?.toString()?.replace("-", ""),
            newlyAdded || name.lowercase() in manualRefresh || get(name)?.hasProfile == false)
        Scheduling.schedule(0.seconds) {
            var retryAt = 0L
            var sharedHit = false
            var upload: String? = null
            val result = runCatching {
                val response = sharedLookup?.get(6, TimeUnit.SECONDS)
                retryAt = response?.get("retryAt")?.asLong?.coerceIn(0, System.currentTimeMillis() + 120000) ?: 0L
                if (retryAt > System.currentTimeMillis()) return@runCatching null
                val shared = response?.get("record")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.let { sharedFriendWealth(it, name, knownUuid, System.currentTimeMillis()) }
                if (shared != null) { sharedHit = true; return@runCatching shared }
                upload = response?.get("upload")?.asString?.takeIf { it.matches(Regex("[a-f0-9]{32}")) }
                if (providerAvailable && System.currentTimeMillis() < DungeonFriendProfileProvider.nextViewerRequest) {
                    retryAt = DungeonFriendProfileProvider.nextViewerRequest
                    return@runCatching null
                }
                // The same public UUID lookup as the dungeon stats cache; no private credentials are sent.
                val uuid = knownUuid ?: Http.get("https://api.mojang.com/users/profiles/minecraft/$name") {
                    val body = asText()
                    check(isOk)
                    val id = JsonParser.parseString(body).asJsonObject.get("id").asString
                    require(id.matches(Regex("[a-fA-F0-9]{32}")))
                    UUID.fromString(id.replace(Regex("(.{8})(.{4})(.{4})(.{4})(.{12})"), "$1-$2-$3-$4-$5"))
                }
                (if (alreadyAbsent) FriendWealth(hasProfile = false, status = "No SkyBlock profile") else fetch(uuid))
                    .copy(uuid = uuid.toString())
            }.getOrElse { FriendWealth(status = "API unavailable or rate limited; retrying later") }
            MC.instance.execute {
                loading = null
                if (result == null) {
                    waiting[name.lowercase()] = retryAt
                    nextRequest = System.currentTimeMillis() + 1000
                    version++
                    return@execute
                }
                waiting.remove(name.lowercase())
                val retryable = result.hasProfile == null || result.status == "SkyBlock profile found; wealth data unavailable"
                if (retryable) waiting[name.lowercase()] = System.currentTimeMillis() + 60000
                else { newFriends.remove(name.lowercase()); manualRefresh.remove(name.lowercase()) }
                nextRequest = System.currentTimeMillis() + if (sharedHit) 1000 else if (result.hasProfile == null) 60000 else 10000
                val previous = entries[name.lowercase()]
                entries[name.lowercase()] = if (retryable && previous?.hasProfile != null)
                    previous.copy(hasProfile = result.hasProfile ?: previous.hasProfile, uuid = result.uuid ?: previous.uuid,
                        status = result.status, expires = System.currentTimeMillis() + 60000) else result
                if (result.hasProfile == true && DungeonFriendStatsCache.get(name)?.state == StatsState.NO_PROFILE)
                    DungeonFriendStatsCache.request(name, result.uuid?.let(UUID::fromString), force = true)
                if (!sharedHit && upload != null && (result.hasProfile == false ||
                    listOfNotNull(result.networth, result.purse, result.bank, result.wardrobe).isNotEmpty()))
                    DungeonFriendRelay.publishWealth(name, result, upload)
                dirty = true
                version++
            }
        }
    }

    private fun fetch(uuid: UUID): FriendWealth {
        val profile = try { DungeonFriendProfileProvider.fetchViewerProfile(uuid) }
        catch (failure: Exception) {
            // Some providers reject Hypixel's valid profiles:null response; verify it through the raw provider.
            val present = DungeonFriendProfileProvider.profilePresence(uuid) ?: throw failure
            return FriendWealth(hasProfile = present, status = if (present) "SkyBlock profile found; wealth data unavailable" else "No SkyBlock profile")
        }
            ?: return FriendWealth(hasProfile = false, status = "No SkyBlock profile")
        return runCatching { fromProfile(profile) }.getOrElse {
            FriendWealth(hasProfile = true, status = "SkyBlock profile found; wealth data unavailable")
        }
    }

    private fun fromProfile(profile: Any): FriendWealth {
        val backing = read(profile, "getBackingProfile") ?: error("No profile")
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        fun await(value: Any?) = (value as CompletableFuture<*>).get(
            (deadline - System.nanoTime()).coerceAtLeast(1), TimeUnit.NANOSECONDS)
        val currency = await(read(backing, "getCurrency"))
        val banking = await(read(backing, "getBank"))
        val member = currency?.let { read(it, "getJson") as? JsonObject }
        val inventory = if (hasPublicInventory(member)) await(read(backing, "getInventory")) else null
        val purse = publicCoins(member, "currencies", "coin_purse")
        val bankJson = banking?.let { read(it, "getJson") as? JsonObject }
        val personal = banking?.let { read(it, "getMember") as? JsonObject }
        val bank = publicCoins(bankJson, "banking", "balance")?.let {
            it + (publicCoins(personal, "profile", "bank_account") ?: 0.0)
        }
        val total = if (inventory != null && bank != null && purse != null) {
            val result = await(read(profile, "getNetWorth")) as Pair<*, *>
            (result.first as Number).toDouble().takeIf { it.isFinite() && it >= 0 }
        } else null
        val wardrobe = inventory?.let { read(it, "getLoadouts") }?.let { loadouts ->
            val sets = read(loadouts, "getArmorSets") as Map<*, *>
            val items = sets.values.filterNotNull().flatMap { read(it, "getStacks") as List<*> }
                .filterIsInstance<ItemStack>().filterNot { it.isEmpty }
            val values = items.map { it.getItemValue() }
            // Missing market prices must not make an unpriced wardrobe appear worthless.
            if (values.any { it.rawPrice <= 0 }) null else values.sumOf { it.price.toDouble() }
        }
        val profileName = read(profile, "getId")?.let { read(it, "getName") as? String }.orEmpty()
        return FriendWealth(total, purse, bank, wardrobe, profileName, when {
            inventory == null -> "Inventory API disabled"
            bank == null -> "Bank API disabled; total unavailable"
            purse == null -> "Purse unavailable; total unavailable"
            wardrobe == null -> "Wardrobe or some item prices unavailable"
            else -> ""
        }, hasProfile = true)
    }

    private fun read(instance: Any, method: String): Any? = instance.javaClass.getMethod(method).invoke(instance)
}
