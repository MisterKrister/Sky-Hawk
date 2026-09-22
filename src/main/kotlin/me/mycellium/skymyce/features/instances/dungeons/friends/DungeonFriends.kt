package me.mycellium.skymyce.features.instances.dungeons.friends

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.api.events.ChatChannel
import me.mycellium.skymyce.api.events.PlayerMessageEvent
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsConfig
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.config.instances.dungeons.PartyListing
import me.mycellium.skymyce.utils.MC
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonAPI
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.hypixel.PartyInfoEvent
import tech.thatgravyboat.skyblockapi.api.events.location.ServerDisconnectEvent
import tech.thatgravyboat.skyblockapi.api.events.profile.ProfileChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.api.profile.friends.FriendsAPI
import tech.thatgravyboat.skyblockapi.api.profile.party.PartyAPI

object DungeonFriends : SkyMyceModule() {
    val replies = DungeonLfgReplies()
    var scanner = FriendListScanner()
        private set
    private var party = DungeonFriendParty()
    private var previousRoster: Pair<Set<String>, Int>? = null
    private var nextPartyRequest = 0L
    private var nextAction = 0L
    private var sendingScan = false
    val neededClass: DungeonClass? get() = party.neededClass

    val partySize: Int get() = maxOf(party.size, PartyAPI.size, 1)
    val partyFull: Boolean get() = partySize >= 5
    val canAct: Boolean get() = LocationAPI.onHypixel && party.canInvite && !partyFull && now() >= nextAction
    val openClasses: Set<DungeonClass> get() = party.openClasses
    val partyStatus: String get() = if (!party.ready) "Checking party size..." else "Party $partySize/5"

    override fun init() {
        DungeonFriendsSettings.load()
        DungeonFriendStatsCache.initialize(SkyMyce.configPath.resolve("dungeon_friend_stats.json"))
        ClientLifecycleEvents.CLIENT_STOPPING.register { DungeonFriendStatsCache.save(true) }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (!sendingScan && command.matches(Regex("(?i)(?:f|friend) list(?: \\d+)?"))) scanner.manualCommand(now())
            true
        }
    }

    override fun tick() {
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        replies.prune(now())
        syncParty()
        if (now() >= nextPartyRequest) {
            val sent = HypixelModAPI.getInstance().sendPacket(ServerboundPartyInfoPacket())
            nextPartyRequest = now() + if (sent) 60000 else 5000
        }
        val active = DungeonFriendsConfig.enabled && LocationAPI.isOnSkyBlock
        if (active || MC.screen is DungeonFriendsScreen) {
            scanner.tick(now())?.let { command ->
                sendingScan = true
                try { sendCommand(command) } finally { sendingScan = false }
            }
            DungeonFriendStatsCache.request(MC.player.name.string, MC.player.uuid)
            scanner.online.values.forEach { friend ->
                DungeonFriendStatsCache.request(friend.name, FriendsAPI.getFriend(friend.name)?.uuid)
            }
            PartyAPI.members.forEach { member -> member.name?.let { DungeonFriendStatsCache.request(it, member.uuid) } }
        } else if (scanner.scanning) scanner.manualCommand(now())
        DungeonFriendStatsCache.tick()
    }

    private fun syncParty() {
        val self = MC.player.name.string.lowercase()
        val names = PartyAPI.members.mapNotNull { member ->
            member.name ?: FriendsAPI.friends.firstOrNull { it.uuid == member.uuid && member.uuid != null }?.name
        }.map { it.lowercase() }.toSet() + self
        val roster = names to PartyAPI.size.coerceAtLeast(1)
        if (roster != previousRoster) {
            party.roster(names, roster.second)
            previousRoster = roster
        }
        for (name in party.members) {
            replies.forget(name)
            // Last selected class is a fallback, never a replacement for a class observed in the current party.
            if (name !in party.classes) DungeonFriendStatsCache.get(name)?.selectedClass?.let { party.classes[name] = it }
        }
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            (DungeonAPI.teammates + listOfNotNull(DungeonAPI.ownPlayer)).forEach { player ->
                if (player.name.lowercase() in party.members) player.dungeonClass?.let { party.classes[player.name.lowercase()] = it }
            }
        }
        party.advance()
    }

    fun onListings(listings: List<PartyListing>) {
        if (MC.instance.player == null) return
        val self = MC.player.name.string
        // Browsing another party's listing must never change our own composition.
        val own = listings.firstOrNull { it.memberUsername.any { name -> name.equals(self, true) } } ?: return
        party.roster(own.memberUsername, own.memberUsername.size, true)
        party.classes.clear()
        party.classes.putAll(own.memberClasses.mapKeys { it.key.lowercase() })
        party.advance()
    }

    @Subscription(priority = Subscription.LOW, receiveCancelled = true)
    fun onPartyInfo(event: PartyInfoEvent) {
        if (MC.instance.player == null) return
        if (!event.inParty) party = DungeonFriendParty()
        previousRoster = null
        syncParty()
        party.roster(party.members.toList(), if (event.inParty) event.members.size else 1, true)
    }

    @Subscription(priority = Subscription.LOW, receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        val message = event.text
        val normalized = message.lines().joinToString("\n") { line ->
            val nickname = Regex("^(\\S+)\\* is ").find(line.trim())?.groupValues?.get(1)
            val friend = nickname?.let { nick -> FriendsAPI.friends.firstOrNull { it.nickname == nick } }
            if (friend != null) line.replaceFirst("$nickname*", friend.name) else line
        }
        if (scanner.receive(normalized, now())) event.cancel()
        FRIEND_NOTICE.matchEntire(message)?.let {
            scanner.notification(it.groupValues[1], it.groupValues[2] == "joined")
        }
        message.lines().mapNotNull(::newlyAddedFriend).forEach { name ->
            scanner.notification(name, true)
            DungeonFriendStatsCache.request(name, FriendsAPI.getFriend(name)?.uuid, force = true)
            scanner.refresh(now())
        }
        if (message.startsWith("You removed ") && message.endsWith(" from your friends list!")) {
            val name = message.substringAfter("You removed ").substringBefore(" from your friends list!").substringAfterLast(' ')
            scanner.notification(name, false)
        }
        syncParty()
        party.chat(message, MC.player.name.string)
        if (message.startsWith("You have joined ") && message.endsWith(" party!")) {
            nextPartyRequest = minOf(nextPartyRequest, now() + 2000)
        }
    }

    @Subscription
    fun onPrivateReply(event: PlayerMessageEvent) {
        if (LocationAPI.onHypixel && event.type == ChatChannel.PRIVATE) replies.receive(event.player, event.message, now())
    }

    fun invite(friend: OnlineDungeonFriend) {
        if (action(friend, "p ${friend.name}")) replies.forget(friend.name)
    }

    fun message(friend: OnlineDungeonFriend, floor: DungeonFloor, clazz: DungeonClass?) {
        val text = lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, clazz, floor)
        if (text.isNotEmpty() && action(friend, "msg ${friend.name} $text")) replies.sent(friend.name, now())
    }

    private fun action(friend: OnlineDungeonFriend, command: String): Boolean {
        // Recheck at the send boundary, including clicks from rows rendered before the fifth member joined.
        if (!canAct || MC.connection == null || friend.name.lowercase() !in scanner.online || !friend.name.matches(Regex("[A-Za-z0-9_]{1,16}"))) return false
        nextAction = now() + 1000
        sendCommand(command)
        return true
    }

    @Subscription
    fun onDisconnect(event: ServerDisconnectEvent) {
        scanner = FriendListScanner()
        party = DungeonFriendParty()
        previousRoster = null
        nextPartyRequest = 0
        nextAction = 0
        replies.clear()
        DungeonFriendStatsCache.disconnect()
    }

    @Subscription
    fun onProfileChange(event: ProfileChangeEvent) {
        MC.instance.player?.let { DungeonFriendStatsCache.request(it.name.string, it.uuid, force = true) }
    }

    fun now(): Long = System.nanoTime() / 1000000

    private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
    private val FRIEND_NOTICE = Regex("^Friend > $PLAYER (joined|left)(?:\\.| the (?:server|network)[!.]?)?$")
}
