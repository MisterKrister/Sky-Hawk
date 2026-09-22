package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import com.mojang.authlib.GameProfile
import net.fabricmc.loader.api.FabricLoader
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/** Optional provider integration follows DungeonProgressHud; no extra bundled mod or API key is needed. */
object DungeonFriendProfileProvider {
    val available: Boolean get() = listOf("skyblocker", "skyblockpv").any { FabricLoader.getInstance().isModLoaded(it) }

    fun fetch(name: String, uuid: UUID): DungeonFriendStats? {
        if (FabricLoader.getInstance().isModLoaded("skyblocker")) {
            runCatching {
                val api = Class.forName("de.hysky.skyblocker.utils.ProfileUtils")
                val future = api.getMethod("fetchFullProfileByUuid", String::class.java)
                    .invoke(null, uuid.toString().replace("-", "")) as CompletableFuture<*>
                val json = future.get(30, TimeUnit.SECONDS) as JsonObject
                DungeonFriendStats.fromProfiles(json, uuid.toString().replace("-", ""))
            }.getOrNull()?.let { return it }
        }
        if (FabricLoader.getInstance().isModLoaded("skyblockpv")) {
            runCatching {
                val api = Class.forName("me.owdding.skyblockpv.api.ProfileAPI")
                val response = CompletableFuture<List<*>>()
                val callback: (List<*>) -> Unit = { response.complete(it) }
                api.getMethod("getProfiles", GameProfile::class.java, String::class.java, kotlin.Function1::class.java)
                    .invoke(api.getField("INSTANCE").get(null), GameProfile(uuid, name), "skymyce", callback)
                val profiles = response.get(30, TimeUnit.SECONDS).filterNotNull()
                val selected = profiles.firstOrNull { getter(it, "getSelected") == true }
                    ?: profiles.singleOrNull() ?: error("No selected profile")
                val backing = getter(selected, "getBackingProfile") ?: error("No profile data")
                val future = getter(backing, "getDungeonData") as CompletableFuture<*>
                fromViewerDungeonData(future.get(30, TimeUnit.SECONDS) ?: error("No dungeon data"))
            }.getOrNull()?.let { return it }
        }
        return null // The caller can fall back to the user's Hypixel key or retry later.
    }

    internal fun fromViewerDungeonData(data: Any): DungeonFriendStats {
        val types = getter(data, "getDungeonTypes") as? Map<*, *> ?: error("Missing dungeon types")
        if (types["catacombs"] == null) return DungeonFriendStats(StatsState.HIDDEN)
        val json = JsonObject()
        val classes = JsonObject()
        (getter(data, "getClassExperience") as? Map<*, *>).orEmpty().forEach { (name, xp) ->
            if (name is String && xp is Number) classes.add(name, JsonObject().apply { addProperty("experience", xp) })
        }
        json.add("player_classes", classes)
        (getter(data, "getSelectedClass") as? String)?.let { json.addProperty("selected_dungeon_class", it) }
        val dungeonTypes = JsonObject()
        types.forEach { (name, type) ->
            if (name !is String || type == null) return@forEach
            val dungeon = JsonObject()
            dungeon.addProperty("experience", getter(type, "getExperience") as? Number)
            val completions = JsonObject()
            val fastest = JsonObject()
            val sPlus = JsonObject()
            (getter(type, "getFloors") as? Map<*, *>).orEmpty().forEach { (floor, value) ->
                if (value != null) {
                    val key = floor.toString()
                    completions.addProperty(key, getter(value, "getCompletions") as? Number)
                    viewerTime(value, "getFastestTime-")?.let { fastest.addProperty(key, it) }
                    viewerTime(value, "getFastestTimeSplus-")?.let { sPlus.addProperty(key, it) }
                }
            }
            dungeon.add("tier_completions", completions)
            dungeon.add("fastest_time", fastest)
            dungeon.add("fastest_time_s_plus", sPlus)
            dungeonTypes.add(name, dungeon)
        }
        json.add("dungeon_types", dungeonTypes)
        return DungeonFriendStats.fromDungeons(json)
    }

    private fun getter(instance: Any, name: String): Any? = instance.javaClass.getMethod(name).invoke(instance)

    private fun viewerTime(floor: Any, prefix: String): Long? {
        // Kotlin Duration getters return an encoded value, not milliseconds.
        val getter = floor.javaClass.methods.single { it.name.startsWith(prefix) && it.parameterCount == 0 }
        val encoded = getter.invoke(floor) as Long
        val duration = Duration::class.java.getMethod("box-impl", Long::class.javaPrimitiveType).invoke(null, encoded) as Duration
        return duration.takeIf { it.isFinite() && it.isPositive() }?.inWholeMilliseconds
    }
}
