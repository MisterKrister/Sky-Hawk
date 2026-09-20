package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.config.instances.dungeons.DungeonsConfig
import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.hud.HudElement
import me.mycellium.skymyce.hud.elements.PanelElement
import me.mycellium.skymyce.hud.elements.StackElement
import me.mycellium.skymyce.hud.elements.TextElement
import me.mycellium.skymyce.hud.widget.Widget

object DungeonSplitsWidget : SkyMyceModule() {
    val widget = Widget("dungeon_splits", "Dungeon Splits", 200, 100) {
        if (!DungeonsConfig.runSplitsHud || !DungeonSplits.isActive()) return@Widget null

        val lines = mutableListOf<HudElement>()
        lines += TextElement("§b§l${DungeonSplits.floor()?.name ?: "Dungeon"} Splits")
        DungeonSplits.currentSplits().forEach { split ->
            val best = DungeonSplits.bestSplits().firstOrNull { it.name == split.name }?.segmentMillis
            val comparison = best?.let { " §7(§b${DungeonSplits.formatTime(split.segmentMillis - it)}§7)" } ?: ""
            lines += TextElement("§7${split.name}: §e${DungeonSplits.formatTime(split.segmentMillis)}$comparison")
        }
        val last = DungeonSplits.currentSplits().lastOrNull()
        val activeBest = DungeonSplits.bestSplits().firstOrNull { it.name == DungeonSplits.activeSplitName() }?.segmentMillis
        val activeSegment = DungeonSplits.elapsedMillis() - (last?.totalMillis ?: 0L)
        val suffix = activeBest?.let { " §7(§b${DungeonSplits.formatTime(activeSegment - it)}§7)" } ?: ""
        lines += TextElement("§fCurrent: §e${DungeonSplits.formatTime(activeSegment)}$suffix")
        PanelElement(StackElement(children = lines), padding = 6)
    }
}
