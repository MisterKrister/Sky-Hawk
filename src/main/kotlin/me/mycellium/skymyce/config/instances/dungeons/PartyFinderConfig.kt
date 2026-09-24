package me.mycellium.skymyce.config.instances.dungeons

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass

object PartyFinderConfig : CategoryKt("Party Finder") {
    val enabled by boolean(true) {
        name = TranslatableValue.literal("Dim incompatible parties")
        description = TranslatableValue.literal("Shade Party Finder slots that fail your class or PB criteria. Items stay visible and clickable; nothing joins automatically.")
    }
    val matchClass by boolean(true) {
        name = TranslatableValue.literal("Match open classes")
        description = TranslatableValue.literal("Use explicit open-class lore when present, otherwise classes not occupied by listed members. Unknown class information stays visible.")
    }
    val classes by select<DungeonClass> {
        name = TranslatableValue.literal("Classes I play")
        description = TranslatableValue.literal("Any selected class may match. Leave empty to use your current detected dungeon class.")
    }
    val matchPb by boolean(true) {
        name = TranslatableValue.literal("Match note PB requirements")
        description = TranslatableValue.literal("Compare note times with your cached API PB for that floor. Missing or loading PBs do not satisfy a requirement. Notes without a valid time are unrestricted.")
    }
}
