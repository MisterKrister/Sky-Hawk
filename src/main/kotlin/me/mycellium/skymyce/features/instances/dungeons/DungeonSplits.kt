package me.mycellium.skymyce.features.instances.dungeons

import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.level.PacketReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.location.IslandChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import java.io.File

@Suppress("unused", "UNUSED_PARAMETER")
object DungeonSplits : SkyMyceModule() {
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val type = object : TypeToken<MutableMap<String, FloorSplits>>() {}.type
    private val saveFile: File = SkyMyce.configPath.resolve("dungeon_splits.json").toFile()

    private val stored: MutableMap<String, FloorSplits> by lazy {
        if (!saveFile.exists()) mutableMapOf()
        else saveFile.reader().use { gson.fromJson<MutableMap<String, FloorSplits>>(it, type) ?: mutableMapOf() }
    }

    private var runStart = 0L
    private var splitStart = 0L
    private var activeSplit: String? = null
    private var activeFloor: DungeonFloor? = null
    private var nextSplit = 0
    private var current = mutableListOf<RecordedSplit>()

    data class SplitDefinition(val name: String, val message: Regex)
    data class RecordedSplit(val name: String, val segmentMillis: Long, val totalMillis: Long)
    data class FloorSplits(
        val bestSegments: MutableMap<String, Long> = mutableMapOf(),
        val runs: MutableList<List<RecordedSplit>> = mutableListOf()
    )

    private val common = listOf(
        SplitDefinition("Blood Open", Regex("^The BLOOD DOOR has been opened!$")),
        SplitDefinition("Portal", Regex("^\\[BOSS] The Watcher: You have proven yourself\\. You may pass\\.$"))
    )

    private val floorSplits = mapOf(
        "ENTRANCE" to emptyList(),
        "F1" to listOf(SplitDefinition("Bonzo", Regex("^\\[BOSS] Bonzo: Gratz for making it this far, but I'm basically unbeatable\\.$"))),
        "F2" to listOf(SplitDefinition("Scarf", Regex("^\\[BOSS] Scarf: This is where the journey ends for you, Adventurers\\.$"))),
        "F3" to listOf(SplitDefinition("The Guardians", Regex("^\\[BOSS] The Professor: I was burdened with terrible news recently\\.\\.\\.$"))),
        "F4" to listOf(SplitDefinition("Thorn", Regex("^\\[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!$"))),
        "F5" to listOf(SplitDefinition("Livid", Regex("^\\[BOSS] Livid: Welcome, you've arrived right on time\\. I am Livid, the Master of Shadows\\.$"))),
        "F6" to listOf(SplitDefinition("Terracottas", Regex("^\\[BOSS] Sadan: So you made it all the way here\\.\\.\\. Now you wish to defy me\\? Sadan\\?!$"))),
        "F7" to listOf(SplitDefinition("Maxor", Regex("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$"))),
        "M7" to listOf(
            SplitDefinition("Maxor", Regex("^\\[BOSS] Maxor: WELL! WELL! WELL! LOOK WHO'S HERE!$")),
            SplitDefinition("Storm", Regex("^\\[BOSS] Storm: Pathetic Maxor, just like expected\\.$")),
            SplitDefinition("S1", Regex("^\\[BOSS] Goldor: Who dares trespass into my domain\\?$")),
            SplitDefinition("S2", Regex("^The gate has been destroyed!$")),
            SplitDefinition("S3", Regex("^The gate has been destroyed!$")),
            SplitDefinition("Core", Regex("^The Core entrance is opening!$")),
            SplitDefinition("Necron", Regex("^\\[BOSS] Necron: You went further than any human before, congratulations\\.$")),
            SplitDefinition("Dragons", Regex("^\\[BOSS] Necron: All this, for nothing\\.\\.\\.$")),
        ),
    )

    private val completion = Regex("^\\s*☠ Defeated .+ in 0?[\\dhms ]+\\s*(\\(NEW RECORD!\\))?$")
    private val dungeonStart = Regex("^\\[NPC] Mort: Here, I found this map when I first entered the dungeon\\.$")

    @Subscription
    fun onPacket(event: PacketReceivedEvent) {
        val packet = event.packet as? ClientboundSystemChatPacket ?: return
        handleMessage(packet.content().string)
    }

    private fun handleMessage(message: String) {
        if (!DungeonsConfig.runSplits) return
        if (dungeonStart.matches(message)) {
            startRun()
            return
        }
        if (runStart == 0L || nextSplit >= definitions().size) return

        val definition = definitions()[nextSplit]
        if (!definition.message.matches(message)) return

        val now = System.currentTimeMillis()
        activeSplit?.let {
            current += RecordedSplit(it, now - splitStart, now - runStart)
        }
        nextSplit++
        if (definition.name == "Complete") {
            finishRun()
        } else {
            activeSplit = definition.name
            splitStart = now
        }
    }

    private fun startRun() {
        activeFloor = DungeonAPI.dungeonFloor
        if (activeFloor == null) return
        runStart = System.currentTimeMillis()
        splitStart = 0L
        activeSplit = null
        nextSplit = 0
        current = mutableListOf()
    }

    @Subscription
    fun onIslandChange(event: IslandChangeEvent) {
        if (event.new != SkyBlockIsland.THE_CATACOMBS) reset()
    }

    private fun definitions(): List<SplitDefinition> {
        val floor = activeFloor?.name ?: DungeonAPI.dungeonFloor?.name ?: return emptyList()
        return common + (floorSplits[floor] ?: emptyList()) + SplitDefinition("Complete", completion).let(::listOf)
    }

    private fun finishRun() {
        val floor = activeFloor ?: return
        val data = stored.getOrPut(floor.name) { FloorSplits() }
        current.forEach {
            val old = data.bestSegments[it.name]
            if (old == null || it.segmentMillis < old) data.bestSegments[it.name] = it.segmentMillis
        }
        data.runs.add(current.toList())
        while (data.runs.size > 50) data.runs.removeAt(0)
        save()
        runStart = 0L
        splitStart = 0L
        activeSplit = null
    }

    private fun reset() {
        runStart = 0L
        splitStart = 0L
        activeSplit = null
        current.clear()
        nextSplit = 0
    }

    private fun save() {
        saveFile.parentFile.mkdirs()
        saveFile.writer().use { gson.toJson(stored, type, it) }
    }

    fun isActive(): Boolean = runStart != 0L
    fun floor(): DungeonFloor? = activeFloor ?: DungeonAPI.dungeonFloor
    fun activeSplitName(): String? = activeSplit
    fun currentSplits(): List<RecordedSplit> = current.toList()
    fun bestSplits(): List<RecordedSplit> {
        val data = stored[floor()?.name] ?: return emptyList()
        var total = 0L
        return definitions().mapNotNull { definition ->
            val segment = data.bestSegments[definition.name] ?: return@mapNotNull null
            total += segment
            RecordedSplit(definition.name, segment, total)
        }
    }
    fun recentRuns(): List<List<RecordedSplit>> = stored[floor()?.name]?.runs?.asReversed() ?: emptyList()
    fun elapsedMillis(): Long = if (runStart == 0L) 0L else System.currentTimeMillis() - runStart

    fun formatTime(milliseconds: Long): String {
        val seconds = milliseconds / 1000
        return "%d:%02d.%d".format(seconds / 60, seconds % 60, (milliseconds % 1000) / 100)
    }
}