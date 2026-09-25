package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt

object CosmeticsConfig : CategoryKt("Cosmetics") {
    var names by boolean(true) {
        name = TranslatableValue.literal("Show Cosmetic Names")
        description = TranslatableValue.literal("Replace complete known usernames in rendered text on Hypixel. UUIDs, commands, messages and game data keep real identities.")
    }
    var originalTooltips by boolean(true) {
        name = TranslatableValue.literal("Keep original names in item tooltips")
        description = TranslatableValue.literal("Keep real usernames in tooltip text. Also preserves other hover tooltips for consistent identity inspection.")
    }
    var scaling by boolean(true) {
        name = TranslatableValue.literal("Show Sky-Hawk player scaling")
        description = TranslatableValue.literal("Render supported players at their server-stored visual scale (0.5–2.0). Collision, reach, movement and camera remain unchanged.")
    }
}
