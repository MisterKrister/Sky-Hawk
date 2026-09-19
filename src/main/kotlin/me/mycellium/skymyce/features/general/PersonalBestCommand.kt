package me.mycellium.skymyce.features.general

import com.google.gson.JsonObject
import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendModMessage
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import kotlin.time.Duration.Companion.seconds

object PersonalBestCommand {
    fun handlePbCommand(args: String) {
        val floorArg = args.trim().takeIf { it.isNotEmpty() }

        Scheduling.schedule(0.seconds) {
            try {
                val playerUuid = MC.player.uuid.toString().replace("-", "")
                val response = Http.getResult<JsonObject>(
                    "https://api.hypixel.net/v2/skyblock/profiles",
                    queries = mapOf("uuid" to playerUuid)
                ).getOrNull()

                if (response == null || !response["success"].asBoolean) {
                    sendModMessage("Failed to fetch profile data from Hypixel API", ChatChannel.PARTY)
                    return@schedule
                }

                val profiles = response.getAsJsonArray("profiles")
                if (profiles == null || profiles.size() == 0) {
                    sendModMessage("No SkyBlock profiles found", ChatChannel.PARTY)
                    return@schedule
                }

                // Find selected profile or first profile
                val profile = profiles.firstOrNull {
                    it.asJsonObject.get("selected")?.asBoolean == true
                } ?: profiles.first()

                val members = profile.asJsonObject.getAsJsonObject("members")
                val memberData = members?.getAsJsonObject(playerUuid)
                    ?: members?.entrySet()?.firstOrNull { it.key.equals(playerUuid, ignoreCase = true) }?.value?.asJsonObject

                if (memberData == null) {
                    sendModMessage("Could not find player data in profile", ChatChannel.PARTY)
                    return@schedule
                }

                val dungeons = memberData.getAsJsonObject("dungeons")
                if (dungeons == null) {
                    sendModMessage("No dungeon data found", ChatChannel.PARTY)
                    return@schedule
                }

                val dungeonTypes = dungeons.getAsJsonObject("dungeon_types")
                if (dungeonTypes == null) {
                    sendModMessage("No dungeon types data found", ChatChannel.PARTY)
                    return@schedule
                }

                if (floorArg != null) {
                    // Specific floor requested
                    val floor = parseFloor(floorArg)
                    if (floor == null) {
                        sendModMessage("Invalid floor: $floorArg", ChatChannel.PARTY)
                        return@schedule
                    }

                    val pb = getFloorPb(dungeonTypes, floor)
                    if (pb != null) {
                        val timeFormatted = formatTime(pb)
                        sendModMessage("${floor.name} PB: $timeFormatted", ChatChannel.PARTY)
                    } else {
                        sendModMessage("No ${floor.name} PB found", ChatChannel.PARTY)
                    }
                } else {
                    // Find highest beaten floor
                    val highestFloor = findHighestBeatenFloor(dungeonTypes)
                    if (highestFloor != null) {
                        val pb = getFloorPb(dungeonTypes, highestFloor)
                        if (pb != null) {
                            val timeFormatted = formatTime(pb)
                            sendModMessage("Highest: ${highestFloor.name} PB: $timeFormatted", ChatChannel.PARTY)
                        } else {
                            sendModMessage("Highest beaten floor: ${highestFloor.name} (no PB data)", ChatChannel.PARTY)
                        }
                    } else {
                        sendModMessage("No completed dungeon floors found", ChatChannel.PARTY)
                    }
                }
            } catch (e: Exception) {
                sendModMessage("Error fetching PB: ${e.message}", ChatChannel.PARTY)
            }
        }
    }

    private fun getFloorPb(dungeonTypes: JsonObject, floor: DungeonFloor): Long? {
        val dungeonType = when (floor) {
            DungeonFloor.E, DungeonFloor.F1, DungeonFloor.F2, DungeonFloor.F3,
            DungeonFloor.F4, DungeonFloor.F5, DungeonFloor.F6, DungeonFloor.F7 -> "catacombs"
            DungeonFloor.M1, DungeonFloor.M2, DungeonFloor.M3, DungeonFloor.M4,
            DungeonFloor.M5, DungeonFloor.M6, DungeonFloor.M7 -> "master_catacombs"
        }

        val floorNumber = when (floor) {
            DungeonFloor.E -> "0"
            else -> floor.floorNumber.toString()
        }

        val dungeonData = dungeonTypes.getAsJsonObject(dungeonType) ?: return null

        // Try fastest_time_s_plus first (S+ runs), then fastest_time_s, then fastest_time
        val fastestTimeSPlus = dungeonData.getAsJsonObject("fastest_time_s_plus")
        val fastestTimeS = dungeonData.getAsJsonObject("fastest_time_s")
        val fastestTime = dungeonData.getAsJsonObject("fastest_time")

        return fastestTimeSPlus?.get(floorNumber)?.asLong
            ?: fastestTimeS?.get(floorNumber)?.asLong
            ?: fastestTime?.get(floorNumber)?.asLong
    }

    private fun findHighestBeatenFloor(dungeonTypes: JsonObject): DungeonFloor? {
        // Priority order: M7 -> M6 -> ... -> M1 -> F7 -> F6 -> ... -> F1 -> E
        val masterFloors = listOf(
            DungeonFloor.M7, DungeonFloor.M6, DungeonFloor.M5, DungeonFloor.M4,
            DungeonFloor.M3, DungeonFloor.M2, DungeonFloor.M1
        )
        val normalFloors = listOf(
            DungeonFloor.F7, DungeonFloor.F6, DungeonFloor.F5, DungeonFloor.F4,
            DungeonFloor.F3, DungeonFloor.F2, DungeonFloor.F1, DungeonFloor.E
        )

        // Check master floors first
        for (floor in masterFloors) {
            if (hasCompletions(dungeonTypes, floor)) {
                return floor
            }
        }

        // Check normal floors
        for (floor in normalFloors) {
            if (hasCompletions(dungeonTypes, floor)) {
                return floor
            }
        }

        return null
    }

    private fun hasCompletions(dungeonTypes: JsonObject, floor: DungeonFloor): Boolean {
        val dungeonType = when (floor) {
            DungeonFloor.E, DungeonFloor.F1, DungeonFloor.F2, DungeonFloor.F3,
            DungeonFloor.F4, DungeonFloor.F5, DungeonFloor.F6, DungeonFloor.F7 -> "catacombs"
            DungeonFloor.M1, DungeonFloor.M2, DungeonFloor.M3, DungeonFloor.M4,
            DungeonFloor.M5, DungeonFloor.M6, DungeonFloor.M7 -> "master_catacombs"
        }

        val floorNumber = when (floor) {
            DungeonFloor.E -> "0"
            else -> floor.floorNumber.toString()
        }

        val dungeonData = dungeonTypes.getAsJsonObject(dungeonType) ?: return false
        val tierCompletions = dungeonData.getAsJsonObject("tier_completions") ?: return false

        return tierCompletions.get(floorNumber)?.asInt?.let { it > 0 } ?: false
    }

    private fun formatTime(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "${minutes}m ${seconds}s"
    }

    private fun parseFloor(input: String): DungeonFloor? {
        val clean = input.trim().uppercase()
        DungeonFloor.getByName(clean)?.let { return it }
        return when (clean) {
            "ENTRANCE", "ENTRY", "CATACOMBS ENTRANCE" -> DungeonFloor.E
            "FLOOR 1", "FLOOR I", "FLOOR1", "CATACOMBS 1" -> DungeonFloor.F1
            "FLOOR 2", "FLOOR II", "FLOOR2", "CATACOMBS 2" -> DungeonFloor.F2
            "FLOOR 3", "FLOOR III", "FLOOR3", "CATACOMBS 3" -> DungeonFloor.F3
            "FLOOR 4", "FLOOR IV", "FLOOR4", "CATACOMBS 4" -> DungeonFloor.F4
            "FLOOR 5", "FLOOR V", "FLOOR5", "CATACOMBS 5" -> DungeonFloor.F5
            "FLOOR 6", "FLOOR VI", "FLOOR6", "CATACOMBS 6" -> DungeonFloor.F6
            "FLOOR 7", "FLOOR VII", "FLOOR7", "CATACOMBS 7" -> DungeonFloor.F7
            "MASTER 1", "MASTER I", "MM1", "MASTER1" -> DungeonFloor.M1
            "MASTER 2", "MASTER II", "MM2", "MASTER2" -> DungeonFloor.M2
            "MASTER 3", "MASTER III", "MM3", "MASTER3" -> DungeonFloor.M3
            "MASTER 4", "MASTER IV", "MM4", "MASTER4" -> DungeonFloor.M4
            "MASTER 5", "MASTER V", "MM5", "MASTER5" -> DungeonFloor.M5
            "MASTER 6", "MASTER VI", "MM6", "MASTER6" -> DungeonFloor.M6
            "MASTER 7", "MASTER VII", "MM7", "MASTER7" -> DungeonFloor.M7
            else -> null
        }
    }
}
