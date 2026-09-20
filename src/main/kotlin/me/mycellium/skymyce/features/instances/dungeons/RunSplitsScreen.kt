package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

class RunSplitsScreen : Screen(MC.instance, MC.font, Component.literal("Run Splits")) {
    private enum class Page(val title: String) {
        THEORETICAL("Theoretical PB"),
        RUNS("Recorded Splits")
    }

    private var page = Page.THEORETICAL

    override fun init() {
        addRenderableWidget(Button.builder(Component.literal("Theoretical PB")) {
            page = Page.THEORETICAL
        }.bounds(width / 2 - 155, height - 35, 100, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Recorded Splits")) {
            page = Page.RUNS
        }.bounds(width / 2 - 50, height - 35, 110, 20).build())
        addRenderableWidget(Button.builder(Component.literal("Close")) {
            onClose()
        }.bounds(width / 2 + 65, height - 35, 90, 20).build())
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        graphics.fill(0, 0, width, height, 0xCC101018.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val floor = DungeonSplits.floor()?.name ?: "No floor selected"
        graphics.centeredText(font, Component.literal("§b§l$floor Run Splits"), width / 2, 25, 0xFFFFFFFF.toInt())
        graphics.centeredText(font, Component.literal("§7${page.title}"), width / 2, 42, 0xFFFFFFFF.toInt())

        when (page) {
            Page.THEORETICAL -> renderTheoretical(graphics)
            Page.RUNS -> renderRuns(graphics)
        }
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    private fun renderTheoretical(graphics: GuiGraphicsExtractor) {
        val best = DungeonSplits.bestSplits()
        if (best.isEmpty()) {
            graphics.centeredText(font, Component.literal("§7Complete a run to build your theoretical PB."), width / 2, 75, 0xFFFFFFFF.toInt())
            return
        }
        graphics.text(font, "§eSection", width / 2 - 170, 65, 0xFFFFFFFF.toInt())
        graphics.text(font, "§eBest", width / 2 - 40, 65, 0xFFFFFFFF.toInt())
        graphics.text(font, "§eCumulative", width / 2 + 50, 65, 0xFFFFFFFF.toInt())
        best.forEachIndexed { index, split ->
            val y = 80 + index * 15
            graphics.text(font, "§7${split.name}", width / 2 - 170, y, 0xFFFFFFFF.toInt())
            graphics.text(font, "§a${DungeonSplits.formatTime(split.segmentMillis)}", width / 2 - 40, y, 0xFFFFFFFF.toInt())
            graphics.text(font, "§b${DungeonSplits.formatTime(split.totalMillis)}", width / 2 + 50, y, 0xFFFFFFFF.toInt())
        }
        val total = best.last().totalMillis
        graphics.text(font, "§fTheoretical PB: §e${DungeonSplits.formatTime(total)}", width / 2 - 170, 95 + best.size * 15, 0xFFFFFFFF.toInt())
        DungeonSplits.bestRunMillis()?.let {
            graphics.text(font, "§fPersonal Best: §e${DungeonSplits.formatTime(it)}", width / 2 - 170, 110 + best.size * 15, 0xFFFFFFFF.toInt())
        }
    }

    private fun renderRuns(graphics: GuiGraphicsExtractor) {
        val runs = DungeonSplits.recentRuns().take(10)
        if (runs.isEmpty()) {
            graphics.centeredText(font, Component.literal("§7No completed runs recorded yet."), width / 2, 75, 0xFFFFFFFF.toInt())
            return
        }
        graphics.text(font, "§eRun", width / 2 - 170, 65, 0xFFFFFFFF.toInt())
        graphics.text(font, "§eSplits", width / 2 - 120, 65, 0xFFFFFFFF.toInt())
        graphics.text(font, "§eTotal", width / 2 + 80, 65, 0xFFFFFFFF.toInt())
        runs.forEachIndexed { index, run ->
            val y = 80 + index * 15
            val total = run.lastOrNull()?.totalMillis ?: 0L
            graphics.text(font, "§7#${index + 1}", width / 2 - 170, y, 0xFFFFFFFF.toInt())
            graphics.text(font, "§7${run.joinToString(" §8| §7") { "${it.name}: ${DungeonSplits.formatTime(it.segmentMillis)}" }}", width / 2 - 120, y, 0xFFFFFFFF.toInt())
            graphics.text(font, "§b${DungeonSplits.formatTime(total)}", width / 2 + 80, y, 0xFFFFFFFF.toInt())
        }
    }

    override fun keyPressed(input: KeyEvent): Boolean {
        if (input.key() == 256) {
            onClose()
            return true
        }
        return super.keyPressed(input)
    }
}
