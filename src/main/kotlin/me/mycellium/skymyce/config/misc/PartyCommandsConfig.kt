package me.mycellium.skymyce.config.misc

import com.teamresourceful.resourcefulconfig.api.types.options.TranslatableValue
import com.teamresourceful.resourcefulconfigkt.api.CategoryKt
import me.mycellium.skymyce.features.general.PartyCommandTypes

object PartyCommandsConfig : CategoryKt("Party Commands") {
    init {
        separator {
            title = "Party Commands"
            description = "Adds useful party commands using the '!' prefix (e.g. !warp, !kick, !promote)"
        }
    }

    val enabledPartyCommands by select<PartyCommandTypes> {
        name = TranslatableValue.literal("Enabled Commands")
        description = TranslatableValue.literal("Choose which ! commands party members may use with your mod. Unselected commands are ignored.")
    }

    val partyCommandCooldown by double(0.5) {
        name = TranslatableValue.literal("Cooldown")
        description = TranslatableValue.literal("The cooldown (in seconds) between handling party commands")

        slider = true
        range = 0.0..10.0
    }

    var hypixelApiKey by string("") {
        name = TranslatableValue.literal("Hypixel API Key")
        description = TranslatableValue.literal("Optional fallback for dungeon friends and !pb when SkyBlocker / SkyBlockPv cannot provide stats")
    }
}
