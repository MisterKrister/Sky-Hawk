package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Confirmed profiles and confirmed absences survive restarts; failed API lookups are never negative evidence. */
class FriendWealthStore(private val file: Path) {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun load(): Map<String, FriendWealth> {
        if (!Files.exists(file)) return emptyMap()
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
        require(root.get("version")?.asInt == 1)
        return root.getAsJsonObject("players").entrySet().associate { (name, json) ->
            require(name.matches(Regex("[a-z0-9_]{1,16}")))
            val entry = gson.fromJson(json, FriendWealth::class.java)
            UUID.fromString(requireNotNull(entry.uuid))
            require(entry.hasProfile != null && entry.fetchedAt >= 0 && entry.expires >= 0)
            require(entry.profile.length <= 64 && entry.status.length <= 256)
            require(listOfNotNull(entry.networth, entry.purse, entry.bank, entry.wardrobe).all { it.isFinite() && it >= 0 })
            name to entry
        }
    }

    fun save(players: Map<String, FriendWealth>) {
        Files.createDirectories(file.parent)
        val temporary = Files.createTempFile(file.parent, "friend-wealth", ".tmp")
        try {
            val confirmed = players.filterValues { it.hasProfile != null && it.uuid != null }
            Files.newBufferedWriter(temporary).use { gson.toJson(mapOf("version" to 1, "players" to confirmed), it) }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}
