package me.mycellium.skymyce.features.instances.dungeons.friends

import com.google.gson.JsonParser
import com.google.gson.Gson
import me.mycellium.mixin.LabelComponentMixin
import me.mycellium.skymyce.config.instances.dungeons.partyListingFromLore
import me.mycellium.skymyce.config.instances.dungeons.partyListingFloor
import net.minecraft.network.chat.ClickEvent
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
    val progress = DungeonRefreshProgress()
    check(progress.update(0, 100, 0, 0) == 0)
    check(progress.update(3, 97, 0, 0) == 3)
    check(progress.update(4, 96, 0, 0) == 4)
    check(progress.update(4, 196, 0, 0) == 4) // Discovering another page never goes backwards.
    check(progress.update(200, 0, 0, 1) == 99)
    check(progress.update(200, 0, 1, 0) == 100)
    check(progress.update(200, 10, 0, 1) == 0) // A new refresh starts a new percentage.
    check(relayUri("wss://example.workers.dev/websocket") != null)
    check(relayUri("ws://127.0.0.1:8787/websocket?room=testing") != null)
    listOf("http://example.com/websocket", "ws://example.com/websocket", "wss://user:password@example.com/websocket",
        "wss://example.com/websocket#fragment", "wss://example.com/wrong", "wss://example.com/websocket?token=secret").forEach { check(relayUri(it) == null) }
    val deliveries = RelayDeliveries()
    var acknowledged = 0
    var fallback = 0
    deliveries.add("first", "Alice", 0, { acknowledged++ }, { fallback++ })
    deliveries.acknowledge("first", "Bob")
    deliveries.tick(4999)
    check(acknowledged == 0 && fallback == 0)
    deliveries.acknowledge("first", "ALICE")
    deliveries.tick(5000)
    check(acknowledged == 1 && fallback == 0)
    deliveries.add("second", "Alice", 10000, { acknowledged++ }, { fallback++ })
    deliveries.tick(15000)
    deliveries.acknowledge("second", "Alice")
    deliveries.fail("second")
    check(acknowledged == 1 && fallback == 1) // Late/missing receipts fall back exactly once.
    deliveries.add("third", "Bob", 20000, { acknowledged++ }, { fallback++ })
    deliveries.clear()
    deliveries.tick(30000)
    check(fallback == 1) // Leaving the server cancels unsent messages.
    deliveries.add("fourth", "Bob", 30000, { acknowledged++ }, { fallback++ })
    deliveries.clear(failed = true)
    check(fallback == 2) // A connection failure while still playing triggers fallback.
    var testReceived = false
    deliveries.add("lfg", "Alice", 40000, { acknowledged++ }, { fallback++ })
    deliveries.add("connection-test", "Alice", 40000, { testReceived = true }, { error("Test receipt was lost") })
    deliveries.acknowledge("unrelated", "Alice")
    check(!testReceived)
    deliveries.acknowledge("connection-test", "Alice")
    check(testReceived && acknowledged == 1) // A diagnostic receipt cannot accept an outstanding LFG message.
    deliveries.tick(45000)
    check(fallback == 3)
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
    check(known.catacombs == 52 && known.classes[ARCHER] == 52 && known.classes.size == 2)
    check(known.classes[MAGE] == 2 && known.selectedClass == ARCHER)
    check(known.highestFloor == M7) // Completion count sets defaults, but isn't a PB for eligibility.
    check(known.eligible(F7) && known.eligible(M1) && !known.eligible(M7))
    check(known.completionTimes[F7] == 400000L && known.sPlusTimes[F7] == 420000L)
    val sharedJson = JsonParser.parseString(Gson().toJson(mapOf("name" to "Alice", "uuid" to "a".repeat(32),
        "stats" to known, "fetchedAt" to 1000000L))).asJsonObject
    val cloudStats = sharedDungeonFriend(sharedJson, "ALICE", "a".repeat(32), 1000100)!!
    check(cloudStats.stats == known && cloudStats.verifiedUntil == 1600000L)
    check(sharedDungeonFriend(sharedJson, "Bob", null, 1000100) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", "b".repeat(32), 1000100) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 1600000) == null)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 900000) == null)
    sharedJson.getAsJsonObject("stats").getAsJsonObject("sPlusTimes").addProperty("F7", -1)
    check(sharedDungeonFriend(sharedJson, "Alice", null, 1000100) == null)
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
    check(allFloors.selectedClass == null)
    check(parse("""{"dungeons":{"player_classes":{"berserker":{"experience":125}}}}""").classes[BERSERKER] == 2)
    check(parse("""{"dungeons":{"player_classes":{"berserk":{"experience":235},"berserker":{"experience":50}}}}""").classes[BERSERKER] == 3)
    check(dungeonLevel(769809640.0) == 51 && dungeonLevel(569809640.0) == 50)
    check(known.sPlusTimes[M1] == null && known.completionTimes[M1] == 100000L)
    checkJoining(known, hidden)
    val selectedProfile = DungeonFriendStats.fromProfiles(JsonParser.parseString("""{"success":true,"profiles":[
        {"members":{"abc":{"last_save":999999,"dungeons":{}}}},
        {"selected":true,"members":{"other":{"dungeons":{}},"abc":{"dungeons":{"dungeon_types":{
            "catacombs":{"experience":125,"fastest_time_s_plus":{"1":65000}}
        }}}}}
    ]}""").asJsonObject, "abc")
    check(selectedProfile.catacombs == 2 && selectedProfile.sPlusTimes[F1] == 65000L)

    // Repeated list ticks and invalid names must not grow the pending request queue.
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.pendingCount == 0)
    DungeonFriendStatsCache.request("Alice")
    DungeonFriendStatsCache.request("ALICE")
    DungeonFriendStatsCache.request("Bob")
    DungeonFriendStatsCache.request("invalid/name")
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    check(DungeonFriendStatsCache.pendingCount == 2)
    DungeonFriendStatsCache.refresh()
    check(DungeonFriendStatsCache.status == "Loading PBs: 2 queued")
    check(DungeonFriendStatsCache.pendingCount == 2)
    DungeonFriendStatsCache.clear()
    check(DungeonFriendStatsCache.status.isEmpty())
    check(DungeonFriendStatsCache.pendingCount == 0)

    check(matchesFriendClass(hidden, emptySet(), setOf(TANK)))
    check(matchesFriendClass(known.copy(selectedClass = null), emptySet(), setOf(TANK)))
    check(matchesFriendClass(known.copy(classes = emptyMap()), emptySet(), setOf(ARCHER)))
    check(!matchesFriendClass(known.copy(classes = emptyMap()), emptySet(), setOf(TANK)))
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
    // The last-played class wins even when another class has more XP.
    check(viewerStats.classes.getValue(ARCHER) > viewerStats.classes.getValue(BERSERKER))
    check(matchesFriendClass(viewerStats, emptySet(), setOf(BERSERKER)))
    check(!matchesFriendClass(viewerStats, emptySet(), setOf(ARCHER)))
    check(matchesFriendClass(viewerStats, setOf(ARCHER), setOf(ARCHER)))
    check(matchesFriendClass(viewerStats, emptySet(), emptySet()))

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
        val oldCache = JsonParser.parseString(Files.readString(savedFile)).asJsonObject
        oldCache.getAsJsonObject("players").entrySet().forEach { it.value.asJsonObject.remove("verifiedUntil") }
        Files.writeString(savedFile, oldCache.toString())
        check(DungeonFriendStatsStore(savedFile).load().values.all { it.verifiedUntil == 0L })
        store.save(saved)
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
        check(DungeonFriendStatsCache.pendingCount == 0)
        DungeonFriendStatsCache.clear()
        DungeonFriendStatsCache.initialize(savedFile)
        check(DungeonFriendStatsCache.get("high")?.sPlusTimes == known.sPlusTimes)
        check(DungeonFriendStatsCache.verified("high") == null) // Displayed old PBs cannot authorize automatic actions.
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

private fun checkJoining(known: DungeonFriendStats, hidden: DungeonFriendStats) {
    check(parsePbLimit("5:30") == 330000L && parsePbLimit("0:00.001") == 1L)
    check(parsePbLimit(" 7:00.1 ") == 420100L && parsePbLimit("999:59.999") == 59999999L)
    listOf("", "0:00", "1:60", "-1:00", "5", "5:3", "5:00.0000", "1:00\n/p Bob").forEach { check(parsePbLimit(it) == null) }
    val available = DungeonAvailability(F7, setOf(ARCHER, TANK), 420000)
    check(available.enabled && available.accepts(known, F7))
    check(!available.accepts(known, M7) && !available.accepts(hidden, F7) && !available.accepts(null, F7))
    check(!available.accepts(known.copy(sPlusTimes = emptyMap()), F7))
    check(!available.accepts(known.copy(sPlusTimes = mapOf(F7 to 420001)), F7))
    check(!available.accepts(known.copy(catacombs = 23), F7))
    check(!available.copy(classes = emptySet()).enabled && !available.copy(maxPbMillis = null).enabled)
    check(Gson().fromJson(Gson().toJson(available), DungeonAvailability::class.java) == available)

    val inviteText = "---------------------\n[MVP++] Cessna808 has invited you to join their party!\nYou have 60 seconds to accept. Click here to join!\n---------------------"
    fun invite(command: String) = Component.literal(inviteText).append(Component.literal("Accept")
        .withStyle(Style.EMPTY.withClickEvent(ClickEvent.RunCommand(command))))
    check(serverPartyInviter(invite("/party accept Cessna808")) == "Cessna808")
    check(serverPartyInviter(invite("/p accept Cessna808")) == "Cessna808")
    check(serverPartyInviter(invite("/party accept SomeoneElse")) == null)
    check(serverPartyInviter(Component.literal(inviteText)) == null)
    check(partyInviter("From Alice: Bob has invited you to join their party!") == null)
    check(joinedPartyLeader("You have joined [MVP++] Cessna808's party!") == "Cessna808")
    check(joinedPartyLeader("From Bob: You have joined Alice's party!") == null)
    check(compactPartyNotice("[MVP+] Alice joined the party.", "Self") == "Alice joined")
    check(compactPartyNotice("You have joined aryanepstein's party!", "Self") == "Self joined")
    check(compactPartyNotice("---------------------\nYou have invited [MVP+] Alice to your party! They have 60 seconds to accept.\n---------------------", "Self") == "Alice has been invited")
    check(compactPartyNotice("[MVP+] Host invited [VIP] Alice to the party! They have 60 seconds to accept.", "Self") == "Alice has been invited")
    check(compactPartyNotice("From Bob: Alice joined the party.", "Self") == null)
    check(compactPartyNotice("Alice joined the party.\nUnrelated message", "Self") == null)

    val request = DungeonJoinRequest(F7, setOf(ARCHER, TANK), "0123456789abcdef")
    val lfg = DungeonLfgOffer(request, "Want to join?", 60000)
    check(DungeonLfgOffer.parse(lfg.message(), 0) == lfg)
    check(DungeonLfgOffer.parse("yes", 0) == null)
    check(DungeonLfgOffer.parse(lfg.message().replace("F7", "F8"), 0) == null)
    check(DungeonJoinRequest.parse(request.message()) == request)
    check(DungeonJoinRequest.parse(request.message().replace("F7", "M8")) == null)
    check(DungeonJoinRequest.parse(request.message().replace("Archer", "Unknown")) == null)
    check(DungeonJoinRequest.parse(request.message() + "\n/p Alice") == null)
    val client = DungeonFriendJoining()
    val host = DungeonFriendJoining()
    val clientContext = JoinPartyContext(available, true, true, 1, F7, setOf(HEALER, ARCHER, TANK), setOf("self"))
    val hostContext = clientContext.copy(solo = false, partySize = 4, missing = setOf(TANK), members = setOf("host", "bob", "carol", "dave"))
    check(client.request("Host", request, 100) == "msg Host ${request.message()}")
    check(host.receiveRequest("Self", request, 200))
    check(!host.receiveRequest("SELF", request, 300))
    check(host.nextCommand(hostContext, 300) { null } == null)
    check(host.nextCommand(hostContext, 300) { hidden } == null)
    check(host.nextCommand(hostContext, 300) { known.copy(sPlusTimes = mapOf(F7 to 420001)) } == null)
    check(host.nextCommand(hostContext.copy(partySize = 5), 300) { known } == null)
    check(host.nextCommand(hostContext.copy(canInvite = false), 300) { known } == null)
    check(host.nextCommand(hostContext.copy(floor = M7), 300) { known } == null)
    val offer = host.nextCommand(hostContext, 400) { known }!!
    check(offer == "msg self Inviting you for F7 as Tank [SkyMyce Ready ${request.token}]")
    client.receiveOffer("Host", offer.removePrefix("msg self "), 500)
    check(client.nextCommand(clientContext, 600) { known } == null) // A private offer is not a server invitation.
    check(host.nextCommand(hostContext.copy(missing = emptySet()), 600) { known } == null)
    check(host.nextCommand(hostContext, 700) { known } == null) // Wait for the recipient's mod, not just the relay server.
    host.acknowledged("Self", "ffffffffffffffff")
    host.failed("Self", "ffffffffffffffff")
    check(host.nextCommand(hostContext, 800) { known } == null) // Old receipts/failures cannot affect a newer exchange.
    host.acknowledged("Self", request.token)
    check(host.nextCommand(hostContext, 1500) { known } == "party invite self")
    check(host.nextCommand(hostContext, 1600) { known } == null)
    check(host.classFor("SELF") == TANK)
    check(host.receiveRequest("Other", request, 1600))
    check(host.nextCommand(hostContext, 1700) { known } == null) // Reserve both the last slot and offered class.
    check(host.nextCommand(hostContext.copy(partySize = 3), 1700) { known } == null) // A free seat cannot reuse a reserved class.
    client.invited("Stranger", 1700)
    check(client.nextCommand(clientContext, 1800) { known } == null)
    client.invited("Host", 1800)
    check(client.nextCommand(clientContext.copy(solo = false), 1900) { known } == null)
    check(client.nextCommand(clientContext.copy(availability = available.copy(classes = emptySet())), 1900) { known } == null)
    check(client.nextCommand(clientContext, 1900) { hidden } == null)
    check(client.nextCommand(clientContext, 1900) { null } == null)
    check(client.nextCommand(clientContext, 2000) { known } == "party accept host")
    check(client.accepted == "host" to (F7 to TANK))
    check(client.nextCommand(clientContext, 2001) { known } == null)
    client.clear()
    client.request("Host", request, 3000)
    client.receiveOffer("Stranger", offer.removePrefix("msg self "), 3001)
    client.receiveOffer("Host", offer.removePrefix("msg self ").replace(request.token, "ffffffffffffffff"), 3001)
    client.invited("Host", 3002)
    check(client.nextCommand(clientContext, 3003) { known } == "party accept host")
    check(client.accepted?.second?.second == ARCHER) // Unrelated or stale offers cannot choose the class.
    client.clear()
    client.invited("Host", 3000)
    check(client.nextCommand(clientContext, 3001) { known } == "party accept host") // Availability also accepts manual invitations.
    client.clear()
    client.request("Host", request, 4000)
    client.receiveRequest("Other", request, 4001)
    check(client.nextCommand(clientContext, 4002) { known } == null) // Do not host while waiting to join.
    client.prune(64000)
    check(!client.busy(64000) && client.status == "Join request expired")
    client.clear()
    client.invited("Host", 0)
    check(client.nextCommand(clientContext, 55000) { known } == null)
    host.prune(62000)
    check(host.classFor("Self") == null)

    // Explicit Yes consents to this host even with background availability off.
    val manualClient = DungeonFriendJoining()
    val manualHost = DungeonFriendJoining()
    val manualContext = clientContext.copy(availability = DungeonAvailability())
    val manualHostContext = manualContext.copy(members = setOf("host"))
    manualClient.request("Host", request, 100, manual = true)
    check(manualClient.expectsInvite("Host", 101) && !manualClient.expectsInvite("Stranger", 101))
    check(manualHost.receiveRequest("Self", request, 101, manual = true))
    val manualOffer = manualHost.nextCommand(manualHostContext, 102) { null }!!
    manualClient.receiveOffer("Host", manualOffer.removePrefix("msg self "), 103)
    check(manualHost.nextCommand(manualHostContext, 104) { null } == null)
    manualHost.acknowledged("Self", request.token)
    check(manualHost.nextCommand(manualHostContext, 105) { null } == "party invite self")
    manualClient.invited("Stranger", 105)
    check(manualClient.nextCommand(manualContext, 106) { null } == null)
    manualClient.invited("Host", 107)
    check(manualClient.nextCommand(manualContext, 108) { null } == "party accept host")
    val privateReply = DungeonFriendJoining()
    privateReply.receiveAcceptedReply("Self", request, 0)
    check(privateReply.nextCommand(manualHostContext, 1) { null } == "party invite self")
    check(privateReply.nextCommand(manualHostContext, 2) { null } == null)
    val guarded = DungeonFriendJoining()
    guarded.request("Host", request, 0, manual = true)
    guarded.invited("Host", 1)
    check(guarded.nextCommand(clientContext, 2) { hidden } == null) // Explicit Yes still honors a configured PB limit.

    val listing = partyListingFromLore(10, null, listOf("§7Dungeon: §bMaster Mode", "§7Floor: §bFloor VII", "Members:",
        "§b[MVP+] Leader: Mage (50)", "Alice: Berserk (49)", "Bob: Healer (48)", "Note: quick runs", "Empty"))!!
    check(listing.floor == M7 && listing.leaderName == "Leader")
    check(listing.memberClasses == mapOf("Leader" to MAGE, "Alice" to BERSERKER, "Bob" to HEALER))
    check(partyListingFloor(listOf("Dungeon: The Catacombs", "Floor: Floor IV")) == F4)
    check(partyListingFloor(listOf("Floor: Entrance")) == null)
    val party = DungeonFriendParty()
    party.roster(listing.memberUsername + "Self", 4, true)
    party.classes.putAll(listing.memberClasses.mapKeys { it.key.lowercase() } + ("self" to ARCHER))
    party.roster(listOf("Self", "Leader"), 4, completeNames = false)
    check(party.openClasses == setOf(TANK) && party.members.size == 4)
    val saved = SavedDungeonParty("leader", party.members.toSet(), party.classes.toMap(), M7, 1000)
    val restored = Gson().fromJson(Gson().toJson(saved), SavedDungeonParty::class.java)
    check(saved == restored && restored.matches("Leader", setOf("Leader", "Self", "Alice", "Bob"), 1001))
    check(!restored.matches("Other", party.members, 1001) && !restored.matches("Leader", setOf("Self", "Leader"), 1001))
    check(!restored.matches("Leader", party.members, 86401001) && !restored.matches("Leader", party.members, 999))
    party.roster(listOf("Self", "Leader", "Bob"), 3)
    check(party.openClasses == setOf(BERSERKER, TANK))
    check(party.chat("You left the party.", "Self") && party.classes.isEmpty())
}
