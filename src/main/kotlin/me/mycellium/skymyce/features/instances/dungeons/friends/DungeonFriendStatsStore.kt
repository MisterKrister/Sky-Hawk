package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

data class CachedDungeonFriend(val stats: DungeonFriendStats, val uuid: String?, val expires: Long) {
    // Failed first lookups may retry; successfully checked low/hidden profiles stay cached across sessions.
    fun shouldRefresh(now: Long): Boolean = now >= expires &&
        (stats.state == StatsState.UNAVAILABLE || (stats.catacombs ?: 0) > 40)
}

/** Only normalized stats/UUIDs are persisted, never API credentials or private messages. */
class DungeonFriendStatsStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): Map<String, CachedDungeonFriend> {
        if (!Files.exists(file)) return emptyMap()
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt == 1) { "Unknown dungeon friend cache format" }
        return root.getAsJsonObject("players").entrySet().associate { (name, json) ->
            require(name.matches(Regex("[a-z0-9_]{1,16}")))
            val value = gson.fromJson(json, CachedDungeonFriend::class.java)
            require(value.uuid == null || value.uuid.matches(Regex("[a-fA-F0-9]{32}")))
            require(value.expires >= 0)
            val stats = requireNotNull(value.stats)
            require(stats.state in StatsState.entries)
            require(stats.catacombs == null || stats.catacombs >= 0)
            requireNotNull(stats.classes).forEach { (key, level) -> require(key in DungeonClass.entries && level >= 0) }
            listOf(requireNotNull(stats.completionTimes), requireNotNull(stats.sPlusTimes)).forEach { times ->
                times.forEach { (floor, time) -> require(floor in FRIEND_FLOORS && time > 0) }
            }
            require(requireNotNull(stats.completedFloors).all { it in FRIEND_FLOORS })
            name to value
        }
    }

    fun save(players: Map<String, CachedDungeonFriend>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "dungeon_friend_stats", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(mapOf("version" to 1, "players" to players), it) }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
