package me.mycellium.skymyce.features.general

import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.features.instances.dungeons.friends.DungeonFriendStatsCache
import me.mycellium.skymyce.features.instances.dungeons.friends.FRIEND_FLOORS
import me.mycellium.skymyce.features.instances.dungeons.friends.formatDungeonTime
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendModMessage
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI

/** The original !pb call site had no implementation. Share the assistant's cache instead of another API client. */
object PersonalBestCommand {
    fun handlePbCommand(arguments: String) {
        val floor = FRIEND_FLOORS.firstOrNull { it.name.equals(arguments.trim(), true) }
            ?: DungeonAPI.dungeonFloor?.takeIf { it in FRIEND_FLOORS }
        if (floor == null) {
            sendModMessage("Usage: !pb F1-F7 or M1-M7", ChatChannel.PARTY)
            return
        }
        val name = MC.player.name.string
        DungeonFriendStatsCache.request(name, MC.player.uuid)
        val stats = DungeonFriendStatsCache.get(name)
        val pb = stats?.sPlusTimes?.get(floor)
        sendModMessage("${floor.name} S+ PB: ${formatDungeonTime(pb)}${if (stats == null) " (stats loading; retry shortly)" else ""}", ChatChannel.PARTY)
    }
}
