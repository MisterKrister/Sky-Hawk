package me.mycellium.skymyce.features.general.overlays

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GeneralConfig
import me.mycellium.skymyce.hud.hudColumn
import me.mycellium.skymyce.hud.updateTextLines
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.ServerUtils
import kotlin.math.round

object ClientInfoOverlay : SkyMyceModule() {
    private val content by lazy { hudColumn() }

    val widget = Widget("client_info", "Client Info", 10, 10, 1f) {
        if (!GeneralConfig.clientInfo) return@Widget null

        content.updateTextLines(
            listOf(
                "§9FPS: §f${MC.fps}",
                "§9Ping: §f${ServerUtils.currentPing}ms §7(${ServerUtils.averagePing}ms avg)",
                "§9TPS: §f${round(ServerUtils.tps * 100) / 100}",
                "§9XYZ: §f${MC.player.blockX} ${MC.player.blockY} ${MC.player.blockZ}",
            )
        )
    }
}
