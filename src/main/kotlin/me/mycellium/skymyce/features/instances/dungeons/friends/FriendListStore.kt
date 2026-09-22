package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Each account keeps its last known online list. A missing file permits its one initial scan. */
class FriendListStore(private val file: Path) {
    fun load(): List<OnlineDungeonFriend>? {
        if (!Files.exists(file)) return null
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt == 1) { "Unknown friend list cache format" }
        return root.getAsJsonArray("online").map {
            val friend = it.asJsonObject
            val name = friend.get("name").asString
            val location = friend.get("location").asString
            require(name.matches(Regex("[A-Za-z0-9_]{1,16}")) && location.length <= 256)
            OnlineDungeonFriend(name, location)
        }
    }

    fun save(online: Collection<OnlineDungeonFriend>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "friends", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use {
                GsonBuilder().setPrettyPrinting().create().toJson(mapOf("version" to 1, "online" to online), it)
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
