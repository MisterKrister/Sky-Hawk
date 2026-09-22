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
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ClickEvent
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
import java.util.UUID

object DungeonFriends : SkyMyceModule() {
    val replies = DungeonLfgReplies()
    val joining = DungeonFriendJoining()
    var scanner = FriendListScanner()
        private set
    private var party = DungeonFriendParty()
    private var previousRoster: Pair<Set<String>, Int>? = null
    private var nextPartyRequest = 0L
    private var nextAction = 0L
    private var sendingScan = false
    private var selectedListing: Pair<PartyListing, Long>? = null
    private var awaitingRoster = false
    private var restoredParty = false
    private var currentLeader: String? = null
    private val fallbackMessages = ArrayDeque<Triple<String, String, Boolean>>()
    var partyFloor: DungeonFloor? = null
        private set
    var partyRevision = 0L
        private set
    val neededClass: DungeonClass? get() = party.neededClass

    val partySize: Int get() = maxOf(party.size, PartyAPI.size, 1)
    val partyFull: Boolean get() = partySize >= 5
    val canAct: Boolean get() = LocationAPI.onHypixel && party.canInvite && !partyFull && now() >= nextAction
    val openClasses: Set<DungeonClass> get() = party.openClasses
    val partyStatus: String get() = if (!party.ready) "Checking party size..." else "Party $partySize/5"
    private val solo get() = party.ready && !PartyAPI.inParty && partySize == 1

    fun canJoin(floor: DungeonFloor): Boolean = LocationAPI.isOnSkyBlock && solo && now() >= nextAction &&
        DungeonFriendRelay.connected && !joining.busy(now()) && DungeonFriendsSettings.availability.let { it.enabled && it.floor == floor }

    override fun init() {
        DungeonFriendsSettings.load()
        DungeonFriendStatsCache.initialize(SkyMyce.configPath.resolve("dungeon_friend_stats.json"))
        ClientLifecycleEvents.CLIENT_STOPPING.register { DungeonFriendStatsCache.save(true); DungeonFriendRelay.disconnect() }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientSendMessageEvents.ALLOW_COMMAND.register { command ->
            if (!sendingScan && command.matches(Regex("(?i)(?:f|friend) list(?: \\d+)?"))) scanner.manualCommand(now())
            true
        }
    }

    override fun tick() {
        DungeonFriendRelay.tick(LocationAPI.isOnSkyBlock && MC.instance.player != null)
        if (!LocationAPI.isOnSkyBlock) fallbackMessages.clear()
        if (!LocationAPI.onHypixel || MC.instance.player == null) return
        replies.prune(now())
        joining.prune(now())
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
        if (LocationAPI.isOnSkyBlock && now() >= nextAction && MC.connection != null) {
            val self = MC.player
            val canInvite = DungeonFriendRelay.connected && canAct && (!PartyAPI.inParty || PartyAPI.allInvite || PartyAPI.leader?.uuid == self.uuid ||
                PartyAPI.leader?.name.equals(self.name.string, true) || PartyAPI.members.any { it.uuid == self.uuid && it.role.name == "MOD" })
            val context = JoinPartyContext(DungeonFriendsSettings.availability, solo, canInvite, partySize,
                partyFloor ?: DungeonFriendsSettings.availability.floor, openClasses, party.members.toSet())
            joining.nextCommand(context, now(), DungeonFriendStatsCache::verified)?.let {
                sendJoinAction(it)
                nextAction = now() + 1000
            }
            if (now() >= nextAction && fallbackMessages.isNotEmpty()) {
                val (name, text, reply) = fallbackMessages.removeFirst()
                if (isFriend(name) && (reply || canAct)) {
                    sendCommand("msg $name $text")
                    nextAction = now() + 1000
                }
            }
        }
    }

    private fun syncParty() {
        val self = MC.player.name.string.lowercase()
        val names = PartyAPI.members.mapNotNull { member ->
            member.name ?: FriendsAPI.friends.firstOrNull { it.uuid == member.uuid && member.uuid != null }?.name
        }.map { it.lowercase() }.toSet() + self
        val roster = names to PartyAPI.size.coerceAtLeast(1)
        if (roster != previousRoster) {
            party.roster(names, roster.second, completeNames = !awaitingRoster && names.size >= roster.second)
            previousRoster = roster
        }
        currentLeader = PartyAPI.leader?.name ?: currentLeader
        if (!restoredParty && party.ready && names.size >= roster.second && PartyAPI.inParty) {
            DungeonFriendsSettings.lastParty?.takeIf { it.matches(currentLeader, names, System.currentTimeMillis()) }?.let {
                party.classes.putAll(it.classes)
                partyFloor = it.floor
                partyRevision++
            }
            restoredParty = true
        }
        for (name in party.members) {
            replies.forget(name)
            // Last selected class is a fallback, never a replacement for a class observed in the current party.
            if (name !in party.classes) (joining.classFor(name) ?: DungeonFriendStatsCache.get(name)?.selectedClass)?.let { party.classes[name] = it }
        }
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS) {
            (DungeonAPI.teammates + listOfNotNull(DungeonAPI.ownPlayer)).forEach { player ->
                if (player.name.lowercase() in party.members) player.dungeonClass?.let { party.classes[player.name.lowercase()] = it }
            }
        }
        party.advance()
        saveParty()
    }

    private fun saveParty() {
        val leader = currentLeader?.lowercase()?.takeIf { it in party.members } ?: return
        if (!PartyAPI.inParty || !party.ready || !restoredParty || awaitingRoster || party.members.size !in 2..5) return
        val saved = SavedDungeonParty(leader, party.members.toSet(), party.classes.filterKeys { it in party.members },
            partyFloor, System.currentTimeMillis())
        if (saved.copy(savedAt = 0) != DungeonFriendsSettings.lastParty?.copy(savedAt = 0)) DungeonFriendsSettings.save(savedParty = saved)
    }

    fun onListingSelected(listing: PartyListing) {
        selectedListing = listing to now()
    }

    fun onListings(listings: List<PartyListing>) {
        if (MC.instance.player == null) return
        val self = MC.player.name.string
        // Browsing another party's listing must never change our own composition.
        val own = listings.firstOrNull { it.memberUsername.any { name -> name.equals(self, true) } } ?: return
        party.roster(own.memberUsername, own.memberUsername.size, true)
        party.classes.clear()
        party.classes.putAll(own.memberClasses.mapKeys { it.key.lowercase() })
        currentLeader = own.leaderName
        partyFloor = own.floor
        restoredParty = true
        party.advance()
        saveParty()
    }

    @Subscription(priority = Subscription.LOW, receiveCancelled = true)
    fun onPartyInfo(event: PartyInfoEvent) {
        if (MC.instance.player == null) return
        if (!event.inParty) {
            if (party.members.size > 1) partyRevision++
            party = DungeonFriendParty()
            currentLeader = null
            partyFloor = null
            restoredParty = false
        }
        awaitingRoster = false
        previousRoster = null
        syncParty()
        party.roster(party.members.toList(), if (event.inParty) event.members.size else 1, true)
        syncParty()
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
        if (party.chat(message, MC.player.name.string)) {
            currentLeader = null
            partyFloor = null
            awaitingRoster = false
            restoredParty = false
            joining.clear()
            partyRevision++
            nextPartyRequest = 0
        }
        joinedPartyLeader(message)?.let { leader ->
            currentLeader = leader
            awaitingRoster = true
            restoredParty = true
            val accepted = joining.accepted?.second
            val listing = selectedListing?.takeIf { now() - it.second <= 60000 && it.first.leaderName.equals(leader, true) }?.first
            if (listing != null) {
                val members = listing.memberUsername.map { it.lowercase() }.toSet() + MC.player.name.string.lowercase()
                party.roster(members, members.size, false)
                party.classes.putAll(listing.memberClasses.mapKeys { it.key.lowercase() })
            }
            partyFloor = listing?.floor ?: accepted?.first
            (accepted?.second ?: DungeonFriendStatsCache.get(MC.player.name.string)?.selectedClass)?.let {
                party.classes[MC.player.name.string.lowercase()] = it
            }
            party.advance()
            selectedListing = null
            joining.clear()
            partyRevision++
            nextPartyRequest = minOf(nextPartyRequest, now() + 2000)
        }
        serverPartyInviter(event.component)?.let { inviter ->
            if (LocationAPI.isOnSkyBlock && DungeonFriendsSettings.availability.enabled && solo) {
                joining.invited(inviter, now())
                checkJoinStats(inviter)
            }
        }
    }

    @Subscription
    fun onPrivateReply(event: PlayerMessageEvent) {
        if (!LocationAPI.onHypixel || event.type != ChatChannel.PRIVATE) return
        replies.receive(event.player, event.message, now())
    }

    private fun isFriend(name: String) = name.matches(Regex("[A-Za-z0-9_]{1,16}")) &&
        (FriendsAPI.getFriend(name) != null || name.lowercase() in scanner.online)

    fun onRelayMessage(name: String, uuid: String, text: String): Boolean {
        if (!LocationAPI.isOnSkyBlock || !isFriend(name)) return false
        val knownUuid = FriendsAPI.getFriend(name)?.uuid?.toString()?.replace("-", "")
        if (knownUuid != null && knownUuid != uuid) return false
        if (text == CONNECTION_TEST) {
            MC.player.sendSystemMessage(Component.literal("§b[SkyMyce Connect] §fReceived a connection test from $name; replying."))
            return true
        }
        val request = DungeonJoinRequest.parse(text)
        if (request != null) {
            if (joining.receiveRequest(name, request, now())) checkJoinStats(name)
        } else if (text.startsWith("Inviting you for ") && text.contains("[SkyMyce Ready ")) {
            joining.receiveOffer(name, text, now())
        } else {
            val isReply = replies.get(name) != null
            replies.receive(name, text, now())
            val message = Component.literal("§b[SkyMyce Relay] §f$name: $text")
            if (!isReply) {
                message.append(Component.literal(" §a[Yes]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/skymyce relaymsg $name yes")) })
                message.append(Component.literal(" §c[No]").withStyle { it.withClickEvent(ClickEvent.RunCommand("/skymyce relaymsg $name no")) })
                message.append(Component.literal(" §b[Reply]").withStyle { it.withClickEvent(ClickEvent.SuggestCommand("/skymyce relaymsg $name ")) })
            }
            MC.player.sendSystemMessage(message)
        }
        return true
    }

    fun testConnection(name: String) {
        fun report(message: String) {
            MC.instance.player?.sendSystemMessage(Component.literal("§b[SkyMyce Connect] §r$message"))
        }
        val player = MC.instance.player ?: return
        val started = now()
        val problem = when {
            !name.matches(Regex("[A-Za-z0-9_]{1,16}")) -> "Enter a valid Minecraft username."
            !LocationAPI.isOnSkyBlock -> "Join SkyBlock first; the relay connects there."
            name.equals(player.name.string, true) -> "Choose another player to test the connection."
            !DungeonFriendRelay.connected -> "${DungeonFriendRelay.status}. Check /skymyce pf > Settings > Player Relay."
            !isFriend(name) -> "$name is not in your known friends. Add them and refresh /skymyce pf."
            started < nextAction -> "Please wait a moment before testing again."
            else -> null
        }
        if (problem != null) { report("§e$problem"); return }
        nextAction = started + 1000
        if (DungeonFriendRelay.send(name, CONNECTION_TEST, {
            report("§aConnection to $name confirmed (${now() - started} ms round trip).")
        }, {
            report("§cNo relay reply from $name: offline, timed out, or connection lost. Both players need the mod and the same relay room.")
        })) report("§7Testing connection to $name... Waiting up to 5 seconds for their mod.")
        else report("§eCould not send the test: ${DungeonFriendRelay.status}. Try again shortly.")
    }

    private fun sendJoinAction(command: String) {
        if (!command.startsWith("msg ")) { sendCommand(command); return }
        val parts = command.split(' ', limit = 3)
        val name = parts[1]
        val token = parts[2].substringAfterLast(' ').removeSuffix("]")
        if (!DungeonFriendRelay.send(name, parts[2], { joining.acknowledged(name, token) }, { joining.failed(name, token) })) joining.failed(name, token)
    }

    private fun checkJoinStats(name: String) {
        if (DungeonFriendStatsCache.verified(name) == null) {
            DungeonFriendStatsCache.request(name, FriendsAPI.getFriend(name)?.uuid, force = true, priority = true)
        }
    }

    fun join(friend: OnlineDungeonFriend, floor: DungeonFloor) {
        if (!canJoin(floor) || MC.connection == null || friend.name.lowercase() !in scanner.online) return
        if (!friend.name.matches(Regex("[A-Za-z0-9_]{1,16}")) || friend.name.equals(MC.player.name.string, true)) return
        val request = DungeonJoinRequest(floor, DungeonFriendsSettings.availability.classes, UUID.randomUUID().toString().replace("-", "").take(16))
        sendJoinAction(joining.request(friend.name, request, now()))
        nextAction = now() + 1000
        checkJoinStats(friend.name)
    }

    fun invite(friend: OnlineDungeonFriend) {
        if (action(friend, "p ${friend.name}")) replies.forget(friend.name)
    }

    fun message(friend: OnlineDungeonFriend, floor: DungeonFloor, clazz: DungeonClass?) {
        val text = lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, clazz, floor)
        if (text.isEmpty() || !canAct || MC.connection == null || friend.name.lowercase() !in scanner.online || !isFriend(friend.name)) return
        replies.sent(friend.name, now())
        sendLfg(friend.name, text, false)
    }

    fun relayReply(name: String, text: String) {
        if (!LocationAPI.isOnSkyBlock || !isFriend(name) || now() < nextAction || text.length !in 1..220 || text.any { it < ' ' || it == '§' || it == '\u007f' }) return
        sendLfg(name, text, true)
    }

    private fun sendLfg(name: String, text: String, reply: Boolean) {
        nextAction = now() + 1000
        val fallback = {
            if (LocationAPI.isOnSkyBlock && fallbackMessages.size < 16) fallbackMessages.addLast(Triple(name, text, reply))
            Unit
        }
        if (!DungeonFriendRelay.send(name, text, {
            MC.instance.player?.sendSystemMessage(Component.literal("§b[SkyMyce] §7$name's mod received your message; awaiting their reply."))
        }, fallback)) fallback()
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
        fallbackMessages.clear()
        DungeonFriendRelay.disconnect()
        joining.clear()
        selectedListing = null
        awaitingRoster = false
        restoredParty = false
        currentLeader = null
        partyFloor = null
        partyRevision++
        DungeonFriendStatsCache.disconnect()
    }

    @Subscription
    fun onProfileChange(event: ProfileChangeEvent) {
        MC.instance.player?.let { DungeonFriendStatsCache.request(it.name.string, it.uuid, force = true) }
    }

    fun now(): Long = System.nanoTime() / 1000000

    private const val CONNECTION_TEST = "[SkyMyce Connection Test]"
    private const val PLAYER = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
    private val FRIEND_NOTICE = Regex("^Friend > $PLAYER (joined|left)(?:\\.| the (?:server|network)[!.]?)?$")
}
