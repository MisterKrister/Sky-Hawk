package me.mycellium.skymyce.features.instances.dungeons.tracker

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.instances.dungeons.DungeonTrackerConfig
import me.mycellium.skymyce.hud.hudPanel
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.TextUtils
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import kotlin.collections.get
import kotlin.math.round
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object DungeonTrackerWidget : SkyMyceModule() {
    private val content by lazy { hudPanel(padding = 6) }

    val widget = Widget("dungeon_tracker", "Dungeon Tracker", 200, 100) {
        if (!DungeonTrackerConfig.dungeonTrackerWidget) return@Widget null
        if (!SkyBlockIsland.inAnyIsland(SkyBlockIsland.DUNGEON_HUB, SkyBlockIsland.THE_CATACOMBS)) return@Widget null
        if (DungeonAPI.started && !DungeonAPI.completed) return@Widget null

        val stats = DungeonTracker.profitData[DungeonTracker.currentFloor]
        val title = DungeonTracker.currentFloor?.name ?: "?"

        val lines = mutableListOf<String>()

        lines += "§b§l$title Dungeon Tracker"
        when {
            (DungeonTracker.currentFloor == null) -> {
                lines += "§7Unknown floor!"
                lines += "§8(Try queueing a party)"
            }

            (stats == null) -> {
                lines += "§7No dungeon data yet."
                lines += "§8(Try playing runs)"
            }

            else -> {
                lines += "§8/skymyce dungeon"
                lines += ""
                val time = if (stats.timedRunCount > 0) stats.totalTimeMillis.milliseconds.inWholeSeconds.seconds.toString() else "Unknown"
                val average = stats.averageRunMillis?.toLong()?.milliseconds?.inWholeSeconds?.seconds?.toString() ?: "Unknown"
                lines += "§7Time: §e$time§7 (§e$average§7/run)"
                if (stats.incompleteTime) lines += "§8Time known for ${stats.timedRunCount}/${stats.totalRuns} runs"
                fun rate(value: Double) = stats.hourlyRate(value)?.let(NumberUtils::condense) ?: "—"
                lines += "§7Runs: §a${stats.totalRuns}§7 (§a${rate(stats.totalRuns.toDouble())}§7/h)"
                lines += "§7Chests: §a${stats.totalChestsOpened}§7 (§b${stats.totalRerolled} rerolls§7)"
                lines += ""
                lines += "§7Profit: ${TextUtils.parseProfit(stats.netProfit)}§7 (${rate(stats.netProfit)}§7/h)"

                lines +=
                    "§7Cata Exp: §b${NumberUtils.condense(stats.totalXp)} §7(§b${
                        rate(stats.totalXp)
                    }§7/h)"
            }
        }

        content.updateTextLines(lines)
    }
}
