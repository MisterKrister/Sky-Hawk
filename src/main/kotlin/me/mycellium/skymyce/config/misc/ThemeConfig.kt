package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object ThemeConfig : CategoryKt("Theme & Appearance") {
    var migratedDigestTheme by boolean(false) { condition = { false } }

    fun migrateDigestTheme() {
        if (migratedDigestTheme) return
        if (primary == 0x67CCF2) primary = DailyDigestConfig.accent.color
        if (opacity == 90 && DailyDigestConfig.cardOpacity != 96) opacity = DailyDigestConfig.cardOpacity.coerceIn(20, 100)
        migratedDigestTheme = true
    }
    var primary by int(0x67CCF2) {
        name = TranslatableValue.literal("Primary accent")
        description = TranslatableValue.literal("Main highlight used throughout Sky-Hawk menus and HUD panels.")
        range = 0..0xFFFFFF
    }
    var secondary by int(0xC2A2F5) {
        name = TranslatableValue.literal("Secondary accent")
        description = TranslatableValue.literal("Secondary highlight for special information and selection outlines.")
        range = 0..0xFFFFFF
    }
    var opacity by int(90) {
        name = TranslatableValue.literal("Panel opacity")
        description = TranslatableValue.literal("Opacity of all Sky-Hawk panels and buttons, from transparent to solid. Text remains fully readable.")
        range = 20..100
        slider = true
    }
}
