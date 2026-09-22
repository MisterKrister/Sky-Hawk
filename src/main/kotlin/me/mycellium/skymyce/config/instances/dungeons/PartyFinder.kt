package me.mycellium.skymyce.config.instances.dungeons

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.utils.Utils.displayDevMessage
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriends
import me.mycellium.skymyce.features.instances.dungeons.friends.parseDungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import net.minecraft.core.component.DataComponents
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import java.util.UUID

private val MEMBER_LINE_REGEX = Regex("^(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16}):\\s*(\\w+)\\s+\\(\\d+\\)$")

data class PartyListing(
    val slotIndex: Int,
    val leaderName: String,
    val leaderUuid: UUID?,
    val memberUsername: List<String>,
    val note: String?,
    val memberClasses: Map<String, DungeonClass> = emptyMap(),
)

object PartyFinder : SkyMyceModule() {
    @Subscription
    fun onContainerOpen(event: ContainerInitializedEvent) {
        val title = event.title.trim()

        if (title.startsWith("Party Finder", ignoreCase = true)) {
            displayDevMessage("[PartyFinder] Detected container: title='$title', slots=${event.containerSlots.size}")
            val listings = parsePartyListings(event)
            displayDevMessage("[PartyFinder] Parsed ${listings.size} listing(s): $listings")
            DungeonFriends.onListings(listings)
        }
    }
}

private fun parsePartyListings(event: ContainerInitializedEvent): List<PartyListing> {
    return event.containerSlots
        .filter { it.index in 10..43 }
        .mapNotNull { slot ->
            val item = slot.item
            if (item.isEmpty) {
                displayDevMessage("[PartyFinder] Slot ${slot.index}: empty")
                return@mapNotNull null
            }
            if (item.item != Items.PLAYER_HEAD) {
                displayDevMessage("[PartyFinder] Slot ${slot.index}: ignored ${item.item}")
                return@mapNotNull null
            }

            displayDevMessage("[PartyFinder] Slot ${slot.index}: detected player head")
            parsePartyItem(slot.index, item)
        }
}

private fun parsePartyItem(slotIndex: Int, item: ItemStack): PartyListing? {
    val profileComponent = item.get(DataComponents.PROFILE)
    val leaderUuid: UUID? = profileComponent?.partialProfile()?.id

    val loreLines = item.get(DataComponents.LORE)?.lines?.map { it.string }
    if (loreLines == null) {
        displayDevMessage("[PartyFinder] Slot $slotIndex: player head has no lore")
        return null
    }
    displayDevMessage("[PartyFinder] Slot $slotIndex: profileUuid=$leaderUuid, lore=$loreLines")

    var leaderName: String? = null
    val members = mutableListOf<String>()
    val classes = mutableMapOf<String, DungeonClass>()
    var note: String? = null
    var inMembersSection = false

    for (line in loreLines) {
        val cleanLine = line.trim()

        when {
            cleanLine.equals("Members:", ignoreCase = true) -> {
                inMembersSection = true
            }
            cleanLine.startsWith("Note:", ignoreCase = true) -> {
                note = cleanLine.substringAfter("Note:").trim()
                displayDevMessage("[PartyFinder] Slot $slotIndex: detected note='$note'")
            }
            inMembersSection && cleanLine.equals("Empty", ignoreCase = true) -> {
                inMembersSection = false
            }
            inMembersSection -> {
                val memberMatch = MEMBER_LINE_REGEX.matchEntire(cleanLine)
                if (memberMatch != null) {
                    val memberName = memberMatch.groupValues[1]
                    members.add(memberName)
                    parseDungeonClass(memberMatch.groupValues[2])?.let { classes[memberName] = it }
                    leaderName = leaderName ?: memberName
                    displayDevMessage("[PartyFinder] Slot $slotIndex: detected member='$memberName'")
                }
            }
        }
    }

    val resolvedLeaderName = leaderName
    if (resolvedLeaderName == null) {
        displayDevMessage("[PartyFinder] Slot $slotIndex: no party leader found")
        return null
    }

    val listing = PartyListing(slotIndex, resolvedLeaderName, leaderUuid, members, note, classes)
    displayDevMessage("[PartyFinder] Slot $slotIndex: detected listing=$listing")
    return listing
}
