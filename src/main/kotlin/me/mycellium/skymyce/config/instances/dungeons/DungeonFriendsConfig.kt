package me.mycellium.skymyce.config.instances.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendsScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.parseDungeonClass
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object DungeonFriendsConfig : CategoryKt("Dungeon Friends") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Scan Dungeon Friends")
        description = TranslatableValue.literal("Silently refresh online friends every minute while in SkyBlock")
    }

    init {
        button {
            title = "Dungeon Friend & LFG Assistant"
            text = "Open"
            description = "Online dungeon friends, floor stats and quick invites (/sm friends)"
            onClick { MC.instance.execute { MC.instance.setScreen(DungeonFriendsScreen()) } }
        }
    }
}

/** User-entered friend preferences live alongside the existing dungeon tracker data. */
object DungeonFriendsSettings {
    private val file = SkyMyce.configPath.resolve("dungeon_friends.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    var messageTemplate = "Hey {name}, want to play {class} on {floor}?"
        private set
    var secondaryClasses: Map<String, Set<DungeonClass>> = emptyMap()
        private set
    var error: String? = null
        private set
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        if (!Files.exists(file)) return
        try {
            val json = Files.newBufferedReader(file).use { JsonParser.parseReader(it).asJsonObject }
            val template = json.get("messageTemplate")?.asString ?: messageTemplate
            require(template.isNotBlank() && template.none { it < ' ' })
            val secondary = json.getAsJsonObject("secondaryClasses")?.entrySet()?.associate { (name, value) ->
                require(name.matches(Regex("[A-Za-z0-9_]{1,16}")))
                name.lowercase() to value.asJsonArray.map { parseDungeonClass(it.asString) ?: error("Invalid class") }.toSet()
            }.orEmpty()
            messageTemplate = template
            secondaryClasses = secondary
        } catch (_: Exception) {
            error = "Could not read dungeon_friends.json. Repair or rename it before saving."
            SkyMyce.logger.warn("Could not read dungeon friend preferences; original file preserved")
        }
    }

    fun save(template: String = messageTemplate, classes: Map<String, Set<DungeonClass>> = secondaryClasses): Boolean {
        load()
        if (error != null) return false
        if (template.isBlank() || template.any { it < ' ' || it == '§' } || template.length > 220) return false
        try {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, "dungeon_friends", ".tmp")
            try {
                Files.newBufferedWriter(temporary).use { writer ->
                    gson.toJson(mapOf("messageTemplate" to template, "secondaryClasses" to classes), writer)
                }
                try {
                    Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
            messageTemplate = template
            secondaryClasses = classes
            return true
        } catch (_: Exception) {
            SkyMyce.logger.warn("Could not save dungeon friend preferences")
            return false
        }
    }
}
