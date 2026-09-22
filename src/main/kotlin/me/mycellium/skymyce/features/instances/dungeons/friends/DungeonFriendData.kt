package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonObject
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

val FRIEND_FLOORS = DungeonFloor.entries.filter { it != DungeonFloor.E }
private val minimumLevels = listOf(1, 3, 5, 9, 14, 19, 24, 24, 26, 28, 30, 32, 34, 36)
private val dungeonXp = listOf(
    50, 75, 110, 160, 230, 330, 470, 670, 950, 1340,
    1890, 2665, 3760, 5260, 7380, 10300, 14400, 20000, 27600, 38000,
    52500, 71500, 97000, 132000, 180000, 243000, 328000, 445000, 600000, 800000,
    1065000, 1410000, 1900000, 2500000, 3300000, 4300000, 5600000, 7200000, 9200000, 12000000,
    15000000, 19000000, 24000000, 30000000, 38000000, 48000000, 60000000, 75000000, 93000000, 116250000,
)

fun dungeonLevel(experience: Double): Int {
    var remaining = experience.takeIf { it.isFinite() && it >= 0 } ?: return 0
    for ((level, required) in dungeonXp.withIndex()) {
        if (remaining < required) return level
        remaining -= required
    }
    return 50
}

fun parseDungeonClass(name: String): DungeonClass? = DungeonClass.entries.firstOrNull {
    it.name.equals(name, true) || (it == DungeonClass.BERSERKER && name.equals("Berserk", true))
}

enum class StatsState(val label: String) {
    AVAILABLE("Available"), HIDDEN("API hidden"), UNAVAILABLE("API unavailable"), NO_PROFILE("No SkyBlock profile"),
}

data class DungeonFriendStats(
    val state: StatsState,
    val catacombs: Int? = null,
    val classes: Map<DungeonClass, Int> = emptyMap(),
    val completionTimes: Map<DungeonFloor, Long> = emptyMap(),
    val sPlusTimes: Map<DungeonFloor, Long> = emptyMap(),
    val completedFloors: Set<DungeonFloor> = emptySet(),
    val selectedClass: DungeonClass? = null,
) {
    val bestClass: DungeonClass? get() = classes.maxByOrNull { it.value }?.key
    val highestFloor: DungeonFloor? get() = FRIEND_FLOORS.lastOrNull { it in completedFloors }

    fun eligible(floor: DungeonFloor): Boolean = when (state) {
        StatsState.HIDDEN, StatsState.UNAVAILABLE -> true
        StatsState.NO_PROFILE -> false
        StatsState.AVAILABLE -> floor in completionTimes &&
            (catacombs ?: 0) >= minimumLevels[FRIEND_FLOORS.indexOf(floor)]
    }

    companion object {
        fun fromProfiles(response: JsonObject, uuid: String): DungeonFriendStats {
            require(response.get("success")?.asBoolean == true) { "Unsuccessful profile response" }
            val profiles = response.get("profiles")?.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { it.takeIf { it.isJsonObject }?.asJsonObject }.orEmpty()
            if (profiles.isEmpty()) return DungeonFriendStats(StatsState.NO_PROFILE)
            val profile = profiles.firstOrNull { it.get("selected")?.asBoolean == true }
                ?: profiles.maxByOrNull { it.obj("members")?.obj(uuid)?.number("last_save") ?: 0.0 }!!
            val member = profile.obj("members")?.obj(uuid)
                ?: return DungeonFriendStats(StatsState.UNAVAILABLE)
            val dungeons = member.obj("dungeons")
                ?: return DungeonFriendStats(StatsState.HIDDEN)
            if (listOf("skills", "dungeons").any { setting ->
                member.obj("api_settings")?.get(setting)?.let { !it.isJsonNull && !it.asBoolean } == true
            }) return DungeonFriendStats(StatsState.HIDDEN)

            val types = dungeons.obj("dungeon_types")
            val cata = types?.obj("catacombs")
            if (cata != null && cata.number("experience") == null) return DungeonFriendStats(StatsState.HIDDEN)
            val completionTimes = mutableMapOf<DungeonFloor, Long>()
            val sPlusTimes = mutableMapOf<DungeonFloor, Long>()
            val completed = mutableSetOf<DungeonFloor>()
            for (floor in FRIEND_FLOORS) {
                val data = types?.obj(if (floor.name.startsWith("M")) "master_catacombs" else "catacombs")
                val key = floor.floorNumber.toString()
                val times = listOf("fastest_time", "fastest_time_s", "fastest_time_s_plus")
                    .mapNotNull { data?.obj(it)?.number(key)?.toLong()?.takeIf { time -> time > 0 } }
                times.minOrNull()?.let { completionTimes[floor] = it }
                data?.obj("fastest_time_s_plus")?.number(key)?.toLong()?.takeIf { it > 0 }
                    ?.let { sPlusTimes[floor] = it }
                if (times.isNotEmpty() || (data?.obj("tier_completions")?.number(key) ?: 0.0) > 0) completed += floor
            }
            val classes = dungeons.obj("player_classes")
            return DungeonFriendStats(
                state = StatsState.AVAILABLE,
                catacombs = dungeonLevel(cata?.number("experience") ?: 0.0),
                classes = DungeonClass.entries.associateWith {
                    dungeonLevel(classes?.obj(it.name.lowercase())?.number("experience") ?: 0.0)
                },
                completionTimes = completionTimes,
                sPlusTimes = sPlusTimes,
                completedFloors = completed,
                selectedClass = dungeons.get("selected_dungeon_class")?.takeIf { it.isJsonPrimitive }
                    ?.asString?.let(::parseDungeonClass),
            )
        }
    }
}

private fun JsonObject.obj(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
private fun JsonObject.number(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }
    ?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() && it >= 0 }

data class OnlineDungeonFriend(val name: String, val location: String) {
    val badge: String get() = when {
        location.contains("Dungeon Hub", true) -> "§aIdle"
        location.contains("Dungeons", true) || location.contains("Catacombs", true) -> "§cIn Run"
        location.contains("Hub", true) || location.contains("Island", true) -> "§aIdle"
        else -> "§7Unknown"
    }
}

enum class FriendSort(val label: String) { CATACOMBS("Catacombs"), CLASS("Class level"), PB("S+ PB") }

fun friendComparator(stats: Map<String, DungeonFriendStats>, floor: DungeonFloor, clazz: DungeonClass?, sort: FriendSort) =
    when (sort) {
        FriendSort.CATACOMBS -> compareByDescending<OnlineDungeonFriend> { stats[it.name.lowercase()]?.catacombs ?: -1 }
        FriendSort.CLASS -> compareByDescending { friend: OnlineDungeonFriend ->
            stats[friend.name.lowercase()]?.let { it.classes[clazz ?: it.bestClass] } ?: -1
        }
        FriendSort.PB -> compareBy { friend: OnlineDungeonFriend -> stats[friend.name.lowercase()]?.sPlusTimes?.get(floor) ?: Long.MAX_VALUE }
    }.thenBy { it.name.lowercase() }

fun matchesFriendClass(stats: DungeonFriendStats?, secondary: Set<DungeonClass>, wanted: Set<DungeonClass>): Boolean =
    wanted.isEmpty() || stats?.bestClass in wanted || secondary.any { it in wanted } ||
        ((stats == null || stats.state == StatsState.HIDDEN || stats.state == StatsState.UNAVAILABLE) && secondary.isEmpty())

fun nextMissingClass(current: DungeonClass?, occupied: Collection<DungeonClass>): DungeonClass? =
    current?.takeIf { it !in occupied } ?: DungeonClass.entries.firstOrNull { it !in occupied }

fun lfgMessage(template: String, name: String, clazz: DungeonClass?, floor: DungeonFloor): String = template
    .replace("{name}", name).replace("{class}", clazz?.displayName ?: "any class").replace("{floor}", floor.name)
    .filter { it >= ' ' && it != '\u007f' && it != '§' }.trim().take((256 - "msg $name ".length).coerceAtLeast(0))

fun formatDungeonTime(millis: Long?): String = millis?.let {
    "%d:%02d.%03d".format(it / 60000, it / 1000 % 60, it % 1000)
} ?: "Unknown"

/** PartyAPI supplies rosters; server announcements fill in class choices and immediate membership changes. */
class DungeonFriendParty {
    val members = mutableSetOf<String>()
    val classes = mutableMapOf<String, DungeonClass>()
    var size = 1
        private set
    var ready = false
        private set
    var neededClass: DungeonClass? = null
        private set
    val full: Boolean get() = size >= 5
    val canInvite: Boolean get() = ready && !full
    val openClasses: Set<DungeonClass> get() = DungeonClass.entries.filter { it !in classes.values }.toSet()

    fun roster(names: Collection<String>, count: Int, confirmed: Boolean = ready) {
        val normalized = names.map { it.lowercase() }.toSet()
        members.clear()
        classes.keys.retainAll(normalized)
        members.addAll(normalized)
        size = maxOf(count, normalized.size, 1)
        ready = confirmed
        advance()
    }

    fun advance() { neededClass = nextMissingClass(neededClass, classes.values) }

    fun chat(message: String, self: String) {
        if (LEAVE.containsMatchIn(message)) {
            members.clear()
            classes.clear()
            roster(listOf(self), 1, true)
        }
        OWN_JOIN.matchEntire(message)?.let {
            members.clear()
            classes.clear()
            // Joining only names the leader; wait for the full roster before enabling invites.
            roster(listOf(self, it.groupValues[1]), 2, false)
        }
        val joined = JOIN.matchEntire(message) ?: CLASS_JOIN.matchEntire(message)
        if (joined != null) {
            if (members.add(joined.groupValues[1].lowercase())) size++
            if (joined.groupValues.size > 2) parseDungeonClass(joined.groupValues[2])?.let {
                classes[joined.groupValues[1].lowercase()] = it
            }
        }
        LEFT.matchEntire(message)?.let {
            val name = it.groupValues[1].lowercase()
            if (members.remove(name)) size = (size - 1).coerceAtLeast(1)
            classes.remove(name)
        }
        advance()
    }

    companion object {
        private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
        private val JOIN = Regex("^(?:\\[Party] )?$PLAYER joined the party\\.$")
        private val OWN_JOIN = Regex("^You have joined $PLAYER's? party!$")
        private val LEFT = Regex("^(?:\\[Party] )?$PLAYER (?:has left|has been removed from) the party\\.$")
        private val CLASS_JOIN = Regex("^Party Finder > $PLAYER joined the dungeon group! \\((\\w+) Level \\d+\\)$")
        private val LEAVE = Regex("^(?:You left the party\\.|You have been kicked from the party by .+|You are not (?:currently )?in a party\\.|The party was disbanded.*|(?:\\[[^]]+] )?[A-Za-z0-9_]{1,16} has disbanded the party!)$")
    }
}
