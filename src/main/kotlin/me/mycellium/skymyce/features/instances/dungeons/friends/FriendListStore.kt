package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Complete rosters and online snapshots are saved only after a successful scan, per account. */
class FriendListStore(private val file: Path) {
    fun load(): List<OnlineDungeonFriend>? = load("online")
    fun loadAll(): List<OnlineDungeonFriend>? = load("all")

    private fun load(field: String): List<OnlineDungeonFriend>? {
        if (!Files.exists(file)) return null
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt in 1..2) { "Unknown friend list cache format" }
        val entries = root.get(field)?.takeUnless { it.isJsonNull }
            ?: return if (field == "all") null else error("Missing friend list")
        return entries.asJsonArray.map {
            val friend = it.asJsonObject
            val name = friend.get("name").asString
            val location = friend.get("location").asString
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")) && location.length <= 256)
            val color = friend.get("rankColor")?.takeUnless { it.isJsonNull }?.asInt
            require(color == null || color in 0..0xFFFFFF)
            OnlineDungeonFriend(name, location, color, friend.get("bestFriend")?.asBoolean ?: false)
        }
    }

    fun save(online: Collection<OnlineDungeonFriend>, all: Collection<OnlineDungeonFriend>? = null) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "friends", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use {
                GsonBuilder().setPrettyPrinting().create().toJson(mapOf("version" to 2, "online" to online, "all" to all), it)
            }
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
