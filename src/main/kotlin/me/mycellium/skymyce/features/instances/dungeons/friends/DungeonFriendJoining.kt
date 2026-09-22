package me.mycellium.skymyce.features.instances.dungeons.friends

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

data class DungeonAvailability(
    val floor: DungeonFloor = DungeonFloor.F7,
    val classes: Set<DungeonClass> = emptySet(),
    val maxPbMillis: Long? = null,
) {
    val enabled get() = classes.isNotEmpty() && maxPbMillis != null && maxPbMillis > 0
    fun accepts(stats: DungeonFriendStats?, requestedFloor: DungeonFloor): Boolean =
        requestedFloor == floor && maxPbMillis != null && stats?.state == StatsState.AVAILABLE &&
            stats.eligible(floor) && stats.sPlusTimes[floor]?.let { it > 0 && it <= maxPbMillis } == true
}

fun parsePbLimit(text: String): Long? {
    val match = Regex("^(\\d{1,3}):([0-5]\\d)(?:\\.(\\d{1,3}))?$").matchEntire(text.trim()) ?: return null
    val (minutes, seconds, fraction) = match.destructured
    return (minutes.toLong() * 60000 + seconds.toLong() * 1000 + fraction.padEnd(3, '0').toLong())
        .takeIf { it > 0 }
}

private const val NAME = "(?:\\[[^]]+]\\s*)?([A-Za-z0-9_]{1,16})"
fun partyInviter(message: String): String? = Regex("^$NAME has invited you to join (?:their|his|her) party![^\\n]*$")
    .matchEntire(message.replace(Regex("§."), "").trim())?.groupValues?.get(1)
fun joinedPartyLeader(message: String): String? = Regex("^You have joined $NAME's? party!$")
    .matchEntire(message.replace(Regex("§."), "").trim())?.groupValues?.get(1)

fun serverPartyInviter(component: Component): String? {
    val name = component.string.lines().firstNotNullOfOrNull(::partyInviter) ?: return null
    fun hasAccept(part: Component): Boolean {
        val command = (part.style.clickEvent as? ClickEvent.RunCommand)?.command?.trim()?.removePrefix("/")
        return command?.matches(Regex("(?i)(?:p|party) accept ${Regex.escape(name)}")) == true || part.siblings.any(::hasAccept)
    }
    return name.takeIf { hasAccept(component) }
}

data class SavedDungeonParty(
    val leader: String,
    val members: Set<String>,
    val classes: Map<String, DungeonClass>,
    val floor: DungeonFloor?,
    val savedAt: Long,
) {
    fun matches(leader: String?, roster: Set<String>, now: Long): Boolean =
        this.leader.equals(leader, true) && members == roster.map { it.lowercase() }.toSet() &&
            now >= savedAt && now - savedAt <= 86400000
}

data class DungeonJoinRequest(val floor: DungeonFloor, val classes: Set<DungeonClass>, val token: String) {
    fun message() = "Invite me for ${floor.name} as ${classes.sortedBy { it.ordinal }.joinToString("/") { it.displayName }} [SkyMyce Join $token]"
    companion object {
        fun parse(text: String): DungeonJoinRequest? {
            val match = Regex("^Invite me for ([FM][1-7]) as ([A-Za-z/]+) \\[SkyMyce Join ([a-f0-9]{16})]$")
                .matchEntire(text) ?: return null
            val classes = match.groupValues[2].split('/').map { parseDungeonClass(it) ?: return null }.toSet()
            if (classes.isEmpty() || classes.size > 5) return null
            return DungeonJoinRequest(DungeonFloor.valueOf(match.groupValues[1]), classes, match.groupValues[3])
        }
    }
}

data class JoinPartyContext(
    val availability: DungeonAvailability,
    val solo: Boolean,
    val canInvite: Boolean,
    val partySize: Int,
    val floor: DungeonFloor,
    val missing: Set<DungeonClass>,
    val members: Set<String>,
)

/** Short-lived exchanges only: an offer in private chat is never treated as a server invitation. */
class DungeonFriendJoining {
    private data class Request(val data: DungeonJoinRequest, val expires: Long, var offered: DungeonClass? = null, var invited: Boolean = false)
    private val incoming = linkedMapOf<String, Request>()
    private val invitations = linkedMapOf<String, Long>()
    private val recent = mutableMapOf<String, Long>()
    private var outgoing: Pair<String, Request>? = null
    private var acceptingUntil = 0L
    var accepted: Pair<String, Pair<DungeonFloor, DungeonClass>>? = null
        private set
    var status = ""
        private set
    fun busy(now: Long): Boolean = outgoing != null || now < acceptingUntil

    fun request(name: String, data: DungeonJoinRequest, now: Long): String {
        outgoing = name.lowercase() to Request(data, now + 60000)
        status = "Join requested from $name"
        return "msg $name ${data.message()}"
    }

    fun receiveRequest(name: String, data: DungeonJoinRequest, now: Long): Boolean {
        prune(now)
        val key = name.lowercase()
        if (key in incoming || (recent[key] ?: 0) > now || incoming.size >= 5) return false
        incoming[key] = Request(data, now + 60000)
        recent[key] = now + 60000
        return true
    }

    fun receiveOffer(name: String, message: String, now: Long) {
        prune(now)
        val (target, request) = outgoing ?: return
        if (!target.equals(name, true)) return
        val match = Regex("^Inviting you for ([FM][1-7]) as ([A-Za-z]+) \\[SkyMyce Ready ([a-f0-9]{16})]$")
            .matchEntire(message) ?: return
        val clazz = parseDungeonClass(match.groupValues[2]) ?: return
        if (match.groupValues[1] == request.data.floor.name && match.groupValues[3] == request.data.token && clazz in request.data.classes) {
            request.offered = clazz
        }
    }

    fun invited(name: String, now: Long) {
        prune(now)
        if (invitations.size < 5) invitations.putIfAbsent(name.lowercase(), now + 55000)
    }

    fun nextCommand(context: JoinPartyContext, now: Long, stats: (String) -> DungeonFriendStats?): String? {
        prune(now)
        if (now < acceptingUntil) return null
        if (context.solo && context.availability.enabled) {
            for (name in invitations.keys.toList()) {
                val request = outgoing?.takeIf { it.first == name }?.second
                // A directed Join request must not accept a different person's invitation.
                if (outgoing != null && request == null) continue
                val floor = request?.data?.floor ?: context.availability.floor
                val offered = request?.offered
                val choices = request?.data?.classes ?: context.availability.classes
                val clazz = if (offered != null) offered.takeIf { it in context.availability.classes } ?: continue
                    else choices.firstOrNull { it in context.availability.classes } ?: continue
                val inviterStats = stats(name)
                if (!context.availability.accepts(inviterStats, floor)) {
                    status = if (inviterStats == null) "Checking $name's S+ PB" else "$name does not meet the ${floor.name} S+ PB limit"
                    continue
                }
                accepted = name to (floor to clazz)
                acceptingUntil = now + 10000
                invitations.clear()
                outgoing = null
                status = "Joining $name as ${clazz.displayName}"
                return "party accept $name"
            }
        }
        if (outgoing != null || !context.canInvite || context.partySize >= 5) return null
        incoming.keys.removeAll(context.members)
        for ((name, request) in incoming) {
            if (request.invited || request.data.floor != context.floor || !context.availability.accepts(stats(name), context.floor)) continue
            val otherReservations = incoming.filter { it.key != name && it.value.offered != null }
            if (context.partySize + otherReservations.size >= 5) continue
            val reservedClasses = otherReservations.values.mapNotNull { it.offered }.toSet()
            val clazz = request.offered ?: request.data.classes.firstOrNull { it in context.missing && it !in reservedClasses } ?: continue
            if (clazz !in context.missing || clazz in reservedClasses) continue
            if (request.offered == null) {
                request.offered = clazz
                status = "Offering ${clazz.displayName} to $name"
                return "msg $name Inviting you for ${context.floor.name} as ${clazz.displayName} [SkyMyce Ready ${request.data.token}]"
            }
            request.invited = true
            status = "Invited $name as ${clazz.displayName}"
            return "party invite $name"
        }
        return null
    }

    fun classFor(name: String): DungeonClass? = incoming[name.lowercase()]?.takeIf { it.invited }?.offered

    fun prune(now: Long) {
        incoming.entries.removeIf { it.value.expires <= now }
        invitations.entries.removeIf { it.value <= now }
        recent.entries.removeIf { it.value <= now }
        if (outgoing?.second?.expires?.let { it <= now } == true) { outgoing = null; status = "Join request expired" }
        if (now >= acceptingUntil) accepted = null
    }

    fun clear() {
        incoming.clear(); invitations.clear(); recent.clear(); outgoing = null; accepted = null; acceptingUntil = 0; status = ""
    }
}
