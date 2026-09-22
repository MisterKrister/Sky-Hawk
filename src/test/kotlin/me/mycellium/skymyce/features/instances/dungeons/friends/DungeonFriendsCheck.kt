package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import me.mycellium.mixin.LabelComponentMixin
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.*
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.*
import java.nio.file.Files
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** Run with ./gradlew dungeonFriendsCheck; requires no Minecraft client or API credentials. */
fun main() {
    // Hovering a full-width label's blank area has no text style, even when it has a location tooltip.
    val hoverFix = LabelComponentMixin::class.java.getDeclaredMethod("skymyce\$nonNullHoverStyle", Style::class.java)
        .apply { isAccessible = true }
    val mixin = LabelComponentMixin()
    check(hoverFix.invoke(mixin, null) === Style.EMPTY)
    val textStyle = Style.EMPTY.withHoverEvent(HoverEvent.ShowText(Component.literal("Friend location")))
    check(hoverFix.invoke(mixin, textStyle) === textStyle)

    val scanner = FriendListScanner()
    check(!scanner.receive("Friends (Page 1 of 1)", 0))
    check(scanner.tick(0) == "friend list 1")
    check(scanner.tick(1) == null)
    check(!scanner.receive("Party > Alice: hello", 10))
    check(!scanner.receive("Alice is asking for a party", 10))
    check(scanner.receive("--------------------\nFriends (Page 1 of 2)\nAlice is in Dungeons\nBob is offline\n--------------------", 100))
    check(scanner.online.keys == setOf("alice"))
    check(scanner.online["alice"]?.badge == "§cIn Run")
    check(scanner.tick(1299) == null)
    check(scanner.tick(1300) == "friend list 2")
    check(scanner.receive("--------------------", 1310))
    check(scanner.receive("<< Friends (Page 2 of 2)", 1311))
    check(scanner.receive("[MVP+] Carol is in Dungeon Hub", 1312))
    check(scanner.receive("--------------------", 1313))
    check(!scanner.scanning)
    check(scanner.online.keys == setOf("alice", "carol"))
    check(scanner.online["carol"]?.badge == "§aIdle")
    scanner.notification("Alice", false)
    scanner.notification("Dave", true)
    check(scanner.online.keys == setOf("carol", "dave"))
    scanner.refresh(10000)
    check(scanner.tick(10000) == "friend list 1")
    scanner.tick(20000)
    check(!scanner.scanning && scanner.online.size == 2) // Partial/failed scans don't erase known friends.
    check(!scanner.receive("--------------------", 20001))
    scanner.refresh(21000)
    scanner.tick(21000)
    scanner.manualCommand(21001)
    check(scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 21002))
    check(!scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 21003))
    check(scanner.tick(81001) == "friend list 1")
    check(scanner.receive("--------------------\nFriends (Page 1 of 1)\nEve is in Hub\n--------------------", 81002))
    check(scanner.online.keys == setOf("eve"))
    scanner.refresh(85000)
    scanner.tick(85000)
    check(scanner.receive("You don't have any friends!", 85001))
    check(!scanner.scanning && scanner.online.isEmpty())

    fun parse(member: String) = DungeonFriendStats.fromProfiles(JsonParser.parseString(
        """{"success":true,"profiles":[{"selected":true,"members":{"abc":$member}}]}"""
    ).asJsonObject, "abc")
    val hidden = parse("{}")
    check(hidden.state == StatsState.HIDDEN && hidden.eligible(M7))
    val empty = parse("""{"dungeons":{}}""")
    check(empty.state == StatsState.AVAILABLE && !empty.eligible(F1))
    check(DungeonFriendStats.fromProfiles(JsonParser.parseString("""{"success":true,"profiles":null}""").asJsonObject, "abc").state == StatsState.NO_PROFILE)
    val known = parse("""{
        "dungeons": {
            "selected_dungeon_class":"archer",
            "player_classes":{"archer":{"experience":999999999},"mage":{"experience":125}},
            "dungeon_types":{
                "catacombs":{"experience":999999999,"fastest_time":{"7":400000},"fastest_time_s_plus":{"7":420000}},
                "master_catacombs":{"tier_completions":{"1":1,"7":1},"fastest_time_s":{"1":100000}}
            }
        }
    }""")
    check(known.catacombs == 52 && known.bestClass == ARCHER && known.classes.size == 2)
    check(known.classes[MAGE] == 2 && known.selectedClass == ARCHER)
    check(known.highestFloor == M7) // Completion count sets defaults, but isn't a PB for eligibility.
    check(known.eligible(F7) && known.eligible(M1) && !known.eligible(M7))
    check(known.completionTimes[F7] == 400000L && known.sPlusTimes[F7] == 420000L)
    check(!known.copy(catacombs = 23).eligible(F7))
    check(known.copy(catacombs = 24).eligible(F7))
    check(!known.copy(catacombs = 35, completionTimes = mapOf(M7 to 1L)).eligible(M7))
    check(known.copy(catacombs = 36, completionTimes = mapOf(M7 to 1L)).eligible(M7))
    check(dungeonLevel(49.0) == 0 && dungeonLevel(50.0) == 1 && dungeonLevel(125.0) == 2)
    check(dungeonLevel(Double.NaN) == 0 && dungeonLevel(-1.0) == 0)
    val malformed = parse("""{"dungeons":{"dungeon_types":{"catacombs":{"experience":null,"fastest_time":{"7":0,"6":-1,"5":"bad"}}}}}""")
    check(malformed.completionTimes.isEmpty())

    // Each floor and mode has its own S+ PB; never substitute an ordinary completion.
    fun times(offset: Int) = (1..7).joinToString(",") { "\"$it\":${offset + it * 1000}" }
    val allFloors = parse("""{"dungeons":{
        "player_classes":{"healer":{"experience":0},"mage":{"experience":50},
            "berserk":{"experience":125},"archer":{"experience":235},"tank":{"experience":395}},
        "dungeon_types":{
            "catacombs":{"experience":999999999,"fastest_time":{${times(60000)}},"fastest_time_s_plus":{${times(120000)}}},
            "master_catacombs":{"fastest_time":{${times(180000)}},"fastest_time_s_plus":{${times(240000)}}}
        }
    }}""")
    for (floor in FRIEND_FLOORS) {
        val master = floor.name.startsWith("M")
        check(allFloors.sPlusTimes[floor] == (if (master) 240000L else 120000L) + floor.floorNumber * 1000L)
        check(allFloors.completionTimes[floor] == (if (master) 180000L else 60000L) + floor.floorNumber * 1000L)
    }
    check(allFloors.classes == mapOf(HEALER to 0, MAGE to 1, BERSERKER to 2, ARCHER to 3, TANK to 4))
    check(allFloors.bestClass == TANK)
    check(parse("""{"dungeons":{"player_classes":{"berserker":{"experience":125}}}}""").classes[BERSERKER] == 2)
    check(parse("""{"dungeons":{"player_classes":{"berserk":{"experience":235},"berserker":{"experience":50}}}}""").classes[BERSERKER] == 3)
    check(dungeonLevel(769809640.0) == 51 && dungeonLevel(569809640.0) == 50)
    check(known.sPlusTimes[M1] == null && known.completionTimes[M1] == 100000L)
    val selectedProfile = DungeonFriendStats.fromProfiles(JsonParser.parseString("""{"success":true,"profiles":[
        {"members":{"abc":{"last_save":999999,"dungeons":{}}}},
        {"selected":true,"members":{"other":{"dungeons":{}},"abc":{"dungeons":{"dungeon_types":{
            "catacombs":{"experience":125,"fastest_time_s_plus":{"1":65000}}
        }}}}}
    ]}""").asJsonObject, "abc")
    check(selectedProfile.catacombs == 2 && selectedProfile.sPlusTimes[F1] == 65000L)

    // Repeated list ticks and invalid names must not grow the pending request queue.
    DungeonFriendStatsCache.clear()
    DungeonFriendStatsCache.request("Alice")
    DungeonFriendStatsCache.request("ALICE")
    DungeonFriendStatsCache.request("Bob")
    DungeonFriendStatsCache.request("invalid/name")
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    DungeonFriendStatsCache.refresh()
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.status.isEmpty())

    check(matchesFriendClass(hidden, emptySet(), setOf(TANK)))
    check(matchesFriendClass(known.copy(classes = emptyMap()), emptySet(), setOf(TANK)))
    check(matchesFriendClass(known, setOf(TANK), setOf(TANK)))
    check(!matchesFriendClass(known, emptySet(), setOf(TANK)))
    check(parseDungeonClass("Berserk") == BERSERKER)
    check(nextMissingClass(ARCHER, listOf(ARCHER)) == BERSERKER)
    check(nextMissingClass(TANK, listOf(ARCHER)) == TANK)
    check(nextMissingClass(TANK, tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.entries) == null)

    val party = DungeonFriendParty()
    check(!party.canInvite)
    party.roster(listOf("Self", "Alice", "Bob", "Carol"), 4, true)
    party.classes.putAll(mapOf("self" to MAGE, "alice" to HEALER, "bob" to TANK, "carol" to BERSERKER))
    party.advance()
    check(party.canInvite && party.neededClass == ARCHER)
    party.chat("Party Finder > [MVP+] Dave joined the dungeon group! (Archer Level 42)", "Self")
    check(party.full && !party.canInvite && party.neededClass == null)
    party.chat("[Party] Dave joined the party.", "Self")
    check(party.size == 5) // The finder and party announcements describe the same join.
    party.roster(listOf("Self", "Alice", "Bob", "Carol"), 4)
    party.chat("Dave has left the party.", "Self")
    check(party.canInvite && party.size == 4 && party.neededClass == ARCHER)
    party.roster(listOf("Self"), 5, true) // UUID-only API members still count toward capacity.
    check(party.full)
    party.chat("You left the party.", "Self")
    check(party.canInvite && party.size == 1 && party.classes.isEmpty())
    party.chat("Party > Alice: Dave joined the party.", "Self")
    check(party.size == 1)
    party.chat("You have joined [MVP+] Alice's party!", "Self")
    check(!party.canInvite) // A join announcement doesn't disclose every member of an existing party.
    party.roster(listOf("Self", "Alice", "Bob", "Carol", "Dave"), 5, true)
    check(party.full && !party.canInvite)

    val friends = listOf(OnlineDungeonFriend("Unknown", "Hub"), OnlineDungeonFriend("Fast", "Dungeons"), OnlineDungeonFriend("Slow", "Hub"))
    val stats = mapOf("unknown" to hidden, "fast" to known.copy(sPlusTimes = mapOf(F7 to 1000)), "slow" to known.copy(sPlusTimes = mapOf(F7 to 2000)))
    check(friends.sortedWith(friendComparator(stats, F7, FriendSort.PB)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(stats, F7, FriendSort.PB, false)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    val levels = stats + mapOf("fast" to known.copy(catacombs = 45, classes = mapOf(ARCHER to 30, MAGE to 50)),
        "slow" to known.copy(catacombs = 41, classes = mapOf(ARCHER to 50, MAGE to 30)))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.CATACOMBS)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.CATACOMBS, true)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.ARCHER)).map { it.name } == listOf("Slow", "Fast", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.MAGE)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(friends.sortedWith(friendComparator(levels, F7, FriendSort.ARCHER, true)).map { it.name } == listOf("Fast", "Slow", "Unknown"))
    check(lfgMessage("Hi {name}, {class} for {floor}?", "Alice", ARCHER, M7) == "Hi Alice, Archer for M7?")
    check(lfgMessage("Hi {name}, {class} for {floor}?", "Alice", null, M7) == "Hi Alice, any class for M7?")
    check(!lfgMessage("hello\n/p someone", "Alice", TANK, F7).contains('\n'))
    check(lfgMessage("x".repeat(300), "Alice", TANK, F7).length + "msg Alice ".length <= 256)
    check(formatDungeonTime(61234) == "1:01.234")

    check(newlyAddedFriend("You are now friends with aryanepstein") == "aryanepstein")
    check(newlyAddedFriend("§aYou are now friends with [MVP++] Alice!") == "Alice")
    check(newlyAddedFriend("From Alice: You are now friends with Bob") == null)
    check(newlyAddedFriend("You are now friends with invalid/name") == null)
    check(newlyAddedFriend("You sent a friend request to Alice!") == null)

    val replies = DungeonLfgReplies()
    replies.receive("Alice", "yes", 0)
    check(replies.get("Alice") == null)
    replies.sent("Alice", 100)
    replies.receive("ALICE", "sure 1s", 200)
    check(replies.get("alice")?.status == LfgReplyStatus.ACCEPTED)
    replies.receive("alice", "no thanks", 300)
    check(replies.get("Alice")?.status == LfgReplyStatus.DECLINED)
    listOf("yes", "yeah", "yes inv me", "invite me", "I'm down", "OK!", "Sure, 1s", "sounds good").forEach {
        check(classifyLfgReply(it) == LfgReplyStatus.ACCEPTED)
    }
    listOf("yesterday", "yes but not now", "maybe", "sure?", "yes if you wait", "no", "I said yes yesterday").forEach {
        check(classifyLfgReply(it) != LfgReplyStatus.ACCEPTED)
    }
    replies.receive("Alice", "yes", 300101)
    check(replies.get("Alice") == null)
    replies.sent("Alice", 400000)
    check(replies.get("Alice")?.status == LfgReplyStatus.WAITING)
    replies.forget("Alice")
    check(replies.get("Alice") == null)

    // Match SkyBlockPv's public getters, including Kotlin's encoded Duration representation.
    val viewerData = ViewerDungeons(
        mapOf("catacombs" to ViewerType(999999999, mapOf("7" to ViewerFloor(5, 300000.milliseconds, 310000.milliseconds))),
            "master_catacombs" to ViewerType(0, mapOf("1" to ViewerFloor(1, 90000.milliseconds, Duration.INFINITE)))),
        mapOf("berserk" to 125L, "archer" to 235L), "berserk",
    )
    val viewerStats = DungeonFriendProfileProvider.fromViewerDungeonData(viewerData)
    check(viewerStats.catacombs == 52 && viewerStats.classes[BERSERKER] == 2)
    check(viewerStats.sPlusTimes[F7] == 310000L && viewerStats.completionTimes[F7] == 300000L)
    check(viewerStats.sPlusTimes[M1] == null && viewerStats.completionTimes[M1] == 90000L)
    check(viewerStats.selectedClass == BERSERKER)

    val savedDirectory = Files.createTempDirectory("dungeon-friends-check")
    val savedFile = savedDirectory.resolve("stats.json")
    try {
        val store = DungeonFriendStatsStore(savedFile)
        val saved = mapOf(
            "low" to CachedDungeonFriend(known.copy(catacombs = 40), "a".repeat(32), 1),
            "high" to CachedDungeonFriend(known.copy(catacombs = 41), "b".repeat(32), 1),
            "hidden" to CachedDungeonFriend(hidden, null, 1),
        )
        check(!saved.getValue("low").shouldRefresh(1000))
        check(saved.getValue("high").shouldRefresh(1000))
        check(!saved.getValue("high").shouldRefresh(0))
        check(!saved.getValue("hidden").shouldRefresh(1000))
        check(CachedDungeonFriend(DungeonFriendStats(StatsState.UNAVAILABLE), null, 1).shouldRefresh(1000))
        store.save(saved)
        check(DungeonFriendStatsStore(savedFile).load() == saved)
        DungeonFriendStatsCache.clear()
        DungeonFriendStatsCache.initialize(savedFile)
        DungeonFriendStatsCache.request("low")
        DungeonFriendStatsCache.request("high")
        DungeonFriendStatsCache.request("hidden")
        DungeonFriendStatsCache.request("newfriend")
        check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
        DungeonFriendStatsCache.refresh()
        check(DungeonFriendStatsCache.get("low")?.catacombs == 40)
        DungeonFriendStatsCache.disconnect()
        DungeonFriendStatsCache.clear()
        DungeonFriendStatsCache.initialize(savedFile)
        check(DungeonFriendStatsCache.get("high")?.sPlusTimes == known.sPlusTimes)
        DungeonFriendStatsCache.request("low", force = true) // New-friend notification explicitly checks a re-added friend.
        check(DungeonFriendStatsCache.status == "Loading PBs: 1 queued")
        store.save(mapOf("invalid" to CachedDungeonFriend(known, "bad-uuid", 1)))
        check(runCatching { store.load() }.isFailure)
        Files.writeString(savedFile, "{bad json")
        check(runCatching { store.load() }.isFailure)
        check(Files.readString(savedFile) == "{bad json")
        DungeonFriendStatsCache.clear()
    } finally {
        Files.deleteIfExists(savedFile)
        Files.deleteIfExists(savedDirectory)
    }
    println("Dungeon Friends checks passed")
}

data class ViewerFloor(val completions: Long, val fastestTime: Duration, val fastestTimeSplus: Duration)
data class ViewerType(val experience: Long, val floors: Map<String, ViewerFloor>)
data class ViewerDungeons(val dungeonTypes: Map<String, ViewerType>, val classExperience: Map<String, Long>, val selectedClass: String)
