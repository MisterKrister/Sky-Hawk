package me.mycellium.skymyce.config.instances.dungeons

import me.mycellium.skymyce.SkyMyceModule
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import me.mycellium.skymyce.utils.Utils.displayMessage

object PartyFinder : SkyMyceModule() {
    @Subscription
    fun onContainerOpen(event: ContainerInitializedEvent) {
        val title = event.title.trim()

        if (title.startsWith("Party Finder", ignoreCase = true)) {
            displayMessage(string = "Found Party Finder")
            displayMessage("grok is crying in a corner")
        }
    }
}
