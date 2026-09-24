package me.mycellium.skymyce.features.digest

import me.mycellium.skymyce.SkyMyceModule
import tech.thatgravyboat.skyblockapi.api.area.hub.ElectionAPI
import tech.thatgravyboat.skyblockapi.api.data.ElectionJson
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.chat.ChatReceivedEvent
import tech.thatgravyboat.skyblockapi.api.events.dungeon.DungeonStartEvent
import tech.thatgravyboat.skyblockapi.api.events.info.MayorChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.ScoreboardUpdateEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.ContainerInitializedEvent
import tech.thatgravyboat.skyblockapi.api.events.screen.InventoryChangeEvent
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import tech.thatgravyboat.skyblockapi.api.location.SkyBlockIsland
import tech.thatgravyboat.skyblockapi.utils.extentions.cleanName
import tech.thatgravyboat.skyblockapi.utils.extentions.getRawLore
import java.time.Instant
import java.time.ZoneOffset

enum class DailyState { UNKNOWN, READY, ACTIVE, COMPLETE, EMPTY, UNAVAILABLE }

data class DigestForgeSlot(
    val index: Int,
    val item: String,
    val state: DailyState,
    val readyAt: Long = 0,
    val updatedAt: Long = 0,
)

data class DailyTask(
    val id: String,
    val title: String,
    val state: DailyState = DailyState.UNKNOWN,
    val detail: String = "Not detected yet",
    val updatedAt: Long = 0,
    val readyAt: Long = 0,
    val source: String = "Not detected",
    val manual: Boolean = false,
    val progress: Int = 0,
    val total: Int = 0,
    val slots: List<DigestForgeSlot> = emptyList(),
)

data class DigestPerk(val name: String, val description: String)
data class DigestCalendarEvent(val name: String, val startsAt: Long, val endsAt: Long, val source: String)
data class GlobalDigest(
    val mayor: String? = null,
    val perks: List<DigestPerk> = emptyList(),
    val events: List<DigestCalendarEvent> = emptyList(),
    val updatedAt: Long = 0,
)

/** Pure observations; never infer completion from the absence of a message or inventory item. */
object DigestActivityParser {
    const val DAY = 86_400_000L
    val titles = linkedMapOf(
        "forge" to "Forge",
        "experiments" to "Experimentation / Superpairs",
        "puzzler" to "Puzzler",
        "fetchur" to "Fetchur",
        "dungeons" to "Dungeon daily activities",
        "mayor" to "Mayor activities & events",
    )
    private val durationPart = Regex("(\\d+)\\s*(d(?:ays?)?|h(?:ours?)?|m(?:inutes?)?|s(?:econds?)?)(?![a-z])", RegexOption.IGNORE_CASE)
    private val puzzler = Regex("Puzzler gave you .{1,100} for solving the puzzle!")
    private val charges = Regex("(?:Stored |Available )?Charges: (\\d{1,2})(?:/\\d{1,2})?", RegexOption.IGNORE_CASE)
    private val dungeonXp = Regex("\\s*\\+[\\d,.]+ Catacombs Experience(?: \\((?:Team|Daily) Bonus\\))?\\s*")

    fun duration(text: String): Long? {
        val value = text.trim().lowercase()
        if (value.length !in 1..80) return null
        val matches = durationPart.findAll(value).toList()
        if (matches.isEmpty() || durationPart.replace(value, "").any { !it.isWhitespace() }) return null
        val result = matches.sumOf { match ->
            val factor = when (match.groupValues[2].first()) { 'd' -> DAY; 'h' -> 3_600_000L; 'm' -> 60_000L; else -> 1_000L }
            (match.groupValues[1].toLongOrNull() ?: return null).takeIf { it <= 365_000 }?.times(factor) ?: return null
        }
        return result.takeIf { it in 0..365 * DAY }
    }

    fun nextReset(now: Long, offsetHours: Int = 0): Long = Instant.ofEpochMilli(now)
        .atOffset(ZoneOffset.ofHours(offsetHours)).toLocalDate().plusDays(1)
        .atStartOfDay().toInstant(ZoneOffset.ofHours(offsetHours)).toEpochMilli()

    fun chat(text: String, now: Long, dwarvenMines: Boolean, privateIsland: Boolean): DailyTask? = when {
        dwarvenMines && puzzler.matches(text) -> DailyTask("puzzler", titles.getValue("puzzler"), DailyState.COMPLETE,
            "Puzzle solved", now, now + DAY, "Puzzler reward message; 24-hour cooldown")
        dwarvenMines && text in setOf("[NPC] Fetchur: thanks thats probably what i needed", "[NPC] Fetchur: come back another time, maybe tmrw") ->
            DailyTask("fetchur", titles.getValue("fetchur"), DailyState.COMPLETE, "Today's request completed", now,
                nextReset(now, -5), "Fetchur response; resets at 00:00 UTC-5")
        privateIsland && text == "You claimed the Superpairs rewards!" -> DailyTask("experiments", titles.getValue("experiments"),
            DailyState.COMPLETE, "Rewards claimed; open the table to check remaining charges", now, 0,
            "Confirmed Superpairs claim; remaining charges have not been checked")
        else -> null
    }

    fun experiment(name: String, lore: List<String>, now: Long): DailyTask? {
        if (!name.contains("Superpairs", true) && !name.contains("Experiment", true)) return null
        val lines = lore.take(40).map(String::trim)
        val count = lines.firstNotNullOfOrNull { charges.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        val cooldown = lines.firstNotNullOfOrNull { line ->
            listOf("Next Charge: ", "Next charge in: ", "Next Experiment: ", "Time Remaining: ", "Cooldown: ").firstNotNullOfOrNull {
                prefix -> line.takeIf { it.startsWith(prefix, true) }?.substring(prefix.length)?.let(::duration)
            }
        }
        val state = when {
            count != null && count > 0 -> DailyState.READY
            count == 0 || "Experiments on cooldown!" in lines || cooldown != null -> DailyState.COMPLETE
            lines.any { it == "Click to play!" || it == "Click to start!" || it == "Click to experiment!" } -> DailyState.READY
            else -> return null
        }
        return DailyTask("experiments", titles.getValue("experiments"), state,
            if (state == DailyState.READY) count?.let { "$it available charge(s)" } ?: "Available in the table"
            else "No available charge detected", now, cooldown?.let { now + it } ?: 0,
            "Experimentation Table lore; bonus charges may change availability", progress = count ?: 0)
    }

    fun forge(index: Int, name: String, lore: List<String>, now: Long, color: Int? = null): DigestForgeSlot? {
        if (index !in 1..7 || name.length !in 1..120) return null
        val lines = lore.take(40).map(String::trim)
        val remaining = lines.firstOrNull { it.startsWith("Time Remaining: ") }?.removePrefix("Time Remaining: ")
        val duration = remaining?.let(::duration)
        val state = when {
            remaining == "Completed!" || "Click to collect!" in lines -> DailyState.COMPLETE
            duration != null -> DailyState.ACTIVE
            name == "Empty Slot" || name.startsWith("Slot #") && (color == 0x55FF55 || lines.any { it == "Click to forge!" || it == "Click to select!" }) -> DailyState.EMPTY
            name == "Locked Slot" || name == "Locked" || name.startsWith("Slot #") && color == 0xFF5555 -> DailyState.UNAVAILABLE
            else -> return null
        }
        return DigestForgeSlot(index, if (state in setOf(DailyState.EMPTY, DailyState.UNAVAILABLE)) "" else name,
            state, duration?.let { now + it } ?: 0, now)
    }

    fun forgeTask(slots: List<DigestForgeSlot>, now: Long): DailyTask {
        val current = (1..7).map { index ->
            val slot = slots.firstOrNull { it.index == index } ?: DigestForgeSlot(index, "", DailyState.UNKNOWN)
            if (slot.state == DailyState.ACTIVE && slot.readyAt in 1..now) slot.copy(state = DailyState.COMPLETE) else slot
        }
        val ready = current.count { it.state == DailyState.COMPLETE }
        val active = current.count { it.state == DailyState.ACTIVE }
        val empty = current.count { it.state == DailyState.EMPTY }
        val unknown = current.count { it.state == DailyState.UNKNOWN }
        return DailyTask("forge", titles.getValue("forge"), when {
            ready > 0 -> DailyState.COMPLETE
            active > 0 -> DailyState.ACTIVE
            empty > 0 -> DailyState.EMPTY
            else -> DailyState.UNKNOWN
        }, if (unknown == current.size) "Open The Forge to detect slots"
            else "$ready ready · $active forging · $empty empty" + if (unknown > 0) " · $unknown unknown" else "",
            current.maxOfOrNull { it.updatedAt } ?: 0, current.filter { it.state == DailyState.ACTIVE }.minOfOrNull { it.readyAt } ?: 0,
            "Last observed Forge inventory; undetected slots remain unknown", slots = current)
    }

    fun isDungeonCompletion(text: String): Boolean = dungeonXp.matches(text)

    fun dungeon(previous: DailyTask?, now: Long): DailyTask {
        val runs = (if (previous != null && previous.readyAt > now) previous.progress else 0).coerceIn(0, 5)
        val progress = (runs + 1).coerceAtMost(5)
        return DailyTask("dungeons", titles.getValue("dungeons"), DailyState.ACTIVE,
            "$progress run(s) observed; daily bonus completion is unverified", now, nextReset(now),
            "Catacombs XP after a started run; failed runs may also grant XP. Five daily bonuses reset at UTC midnight; use a manual note if needed.",
            progress = progress, total = 5)
    }

    fun display(task: DailyTask, now: Long): DailyTask {
        if (task.id == "forge") return forgeTask(task.slots, now)
        if (task.readyAt <= 0 || task.readyAt > now) return task
        if (task.manual) return task.copy(state = DailyState.UNKNOWN, detail = "Manual note expired; check the activity again", progress = 0)
        return when (task.id) {
            "puzzler", "fetchur", "experiments" -> task.copy(state = DailyState.READY, detail = "Detected cooldown has reset", progress = 0)
            "dungeons" -> task.copy(state = DailyState.UNKNOWN, detail = "No runs observed since the UTC reset", progress = 0)
            else -> task.copy(state = DailyState.UNKNOWN, detail = "Previous observation expired")
        }
    }

    fun calendar(name: String, lore: List<String>, now: Long): DigestCalendarEvent? {
        val clean = name.trim().removePrefix("Event: ")
        if (clean.length !in 3..80 || clean in setOf("Calendar and Events", "Close", "Go Back", "Calendar", "Event Calendar")) return null
        val starts = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Starts in: ") }?.removePrefix("Starts in: ")?.let(::duration) }
        val ends = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Ends in: ") }?.removePrefix("Ends in: ")?.let(::duration) }
        val duration = lore.firstNotNullOfOrNull { it.trim().takeIf { line -> line.startsWith("Duration: ") }?.removePrefix("Duration: ")?.let(::duration) }
        if (starts == null && ends == null) return null
        val startAt = starts?.let { now + it } ?: now
        return DigestCalendarEvent(clean, startAt, ends?.let { now + it } ?: duration?.let { startAt + it } ?: 0,
            "Calendar and Events inventory")
    }
}

/** The module only reacts to relevant game events. Rendering reads immutable snapshots. */
object DigestActivities : SkyMyceModule() {
    var changed: (() -> Unit)? = null
    var contextReady: () -> Boolean = { false }
    private val tasks = linkedMapOf<String, DailyTask>()
    private var status = GlobalDigest()
    private var lastElectionData: ElectionJson? = null
    private var dungeonAwaitingXp = false
    private val spookyTimer = Regex("Spooky Festival (?:(\\d+):)?(\\d{1,2}):(\\d{2})")

    fun snapshot(): List<DailyTask> = tasks.values.toList()
    fun restore(state: List<DailyTask>) {
        tasks.clear()
        state.filter { it.id in DigestActivityParser.titles }.take(6).forEach { tasks[it.id] = it }
        dungeonAwaitingXp = false
    }
    fun clearContext() { tasks.clear(); dungeonAwaitingXp = false }

    fun current(now: Long = System.currentTimeMillis()): List<DailyTask> = DigestActivityParser.titles.map { (id, title) ->
        if (id == "mayor") {
            val ongoing = status.events.filter { it.startsAt <= now && it.endsAt > now }
            DailyTask(id, title, if (ongoing.isEmpty()) DailyState.UNKNOWN else DailyState.ACTIVE,
                ongoing.joinToString { it.name }.ifEmpty { "Open Calendar and Events to detect current activities" },
                status.updatedAt, source = "Observed event calendar; activity completion is not inferred")
        } else DigestActivityParser.display(tasks[id] ?: DailyTask(id, title), now)
    }

    fun global(): GlobalDigest = status
    fun restoreGlobal(state: GlobalDigest) { status = state }

    fun manual(id: String, complete: Boolean) {
        if (!contextReady() || id !in setOf("puzzler", "fetchur", "experiments", "dungeons")) return
        if (!complete) { tasks.remove(id); changed?.invoke(); return }
        val now = System.currentTimeMillis()
        val readyAt = when (id) {
            "puzzler" -> now + DigestActivityParser.DAY
            "fetchur" -> DigestActivityParser.nextReset(now, -5)
            else -> DigestActivityParser.nextReset(now)
        }
        put(DailyTask(id, DigestActivityParser.titles.getValue(id), DailyState.COMPLETE, "Marked complete by you", now,
            readyAt, "Manual check; not verified by the game", manual = true))
    }

    fun captureGlobal() {
        val mayor = ElectionAPI.mayor ?: return
        val data = ElectionAPI.rawData
        val perks = (mayor.activePerks + ElectionAPI.minister?.activePerks.orEmpty()).distinctBy { it.id }
            .take(16).map { DigestPerk(it.perkName.take(100), it.description.replace(Regex("§."), "").take(600)) }
        if (status.mayor == mayor.candidateName && status.perks == perks && data === lastElectionData) return
        lastElectionData = data
        status = status.copy(mayor = mayor.candidateName, perks = perks, updatedAt = System.currentTimeMillis())
        changed?.invoke()
    }

    @Subscription fun onMayor(event: MayorChangeEvent) = captureGlobal()

    @Subscription(receiveCancelled = true)
    fun onChat(event: ChatReceivedEvent.Pre) {
        if (!LocationAPI.isOnSkyBlock || !contextReady()) return
        val now = System.currentTimeMillis()
        DigestActivityParser.chat(event.text, now, LocationAPI.island == SkyBlockIsland.DWARVEN_MINES,
            LocationAPI.island == SkyBlockIsland.PRIVATE_ISLAND && !LocationAPI.isGuest)?.let(::put)
        if (LocationAPI.island == SkyBlockIsland.THE_CATACOMBS && dungeonAwaitingXp && DigestActivityParser.isDungeonCompletion(event.text)) {
            dungeonAwaitingXp = false
            put(DigestActivityParser.dungeon(tasks["dungeons"], now))
        }
    }

    @Subscription fun onDungeonStart(event: DungeonStartEvent) {
        dungeonAwaitingXp = contextReady()
        if (!dungeonAwaitingXp) return
        val now = System.currentTimeMillis()
        val previous = tasks["dungeons"]?.let { DigestActivityParser.display(it, now) }
        if (previous?.state == DailyState.COMPLETE && previous.manual) { dungeonAwaitingXp = false; return }
        put(DailyTask("dungeons", DigestActivityParser.titles.getValue("dungeons"), DailyState.ACTIVE,
            "Run in progress; daily bonus availability is unverified", now, DigestActivityParser.nextReset(now),
            "DungeonStartEvent from the existing dungeon API", progress = previous?.progress ?: 0, total = 5))
    }

    @Subscription fun onContainer(event: ContainerInitializedEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady() || event.title !in setOf("The Forge", "Experimentation Table", "Calendar and Events")) return
        event.containerSlots.forEach { slot -> observe(event.title, slot.index, slot.item.cleanName, slot.item.getRawLore(), slot.item.hoverName.style.color?.value) }
    }

    @Subscription fun onInventory(event: InventoryChangeEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady() || event.isInPlayerInventory || event.title !in setOf("The Forge", "Experimentation Table", "Calendar and Events")) return
        observe(event.title, event.slot.index, event.item.cleanName, event.item.getRawLore(), event.item.hoverName.style.color?.value)
    }

    @Subscription fun onScoreboard(event: ScoreboardUpdateEvent) {
        if (!LocationAPI.isOnSkyBlock || !contextReady()) return
        val match = event.added.firstNotNullOfOrNull { spookyTimer.matchEntire(it.trim()) } ?: return
        val now = System.currentTimeMillis()
        val seconds = (match.groupValues[1].toLongOrNull() ?: 0) * 3600 + match.groupValues[2].toLong() * 60 + match.groupValues[3].toLong()
        if (seconds !in 1..21_600) return
        updateEvent(DigestCalendarEvent("Spooky Festival", now, now + seconds * 1000, "Live scoreboard countdown"))
    }

    private fun observe(title: String, slot: Int, name: String, lore: List<String>, color: Int?) {
        val now = System.currentTimeMillis()
        when (title) {
            "The Forge" -> DigestActivityParser.forge(slot - 9, name, lore, now, color)?.let { forge ->
                val previous = tasks["forge"]?.slots.orEmpty()
                val old = previous.firstOrNull { it.index == forge.index }
                if (old != null && old.item == forge.item && old.state == forge.state && kotlin.math.abs(old.readyAt - forge.readyAt) < 3000) return
                val slots = previous.filterNot { it.index == forge.index } + forge
                put(DigestActivityParser.forgeTask(slots.sortedBy { it.index }, now))
            }
            "Experimentation Table" -> if (!LocationAPI.isGuest) DigestActivityParser.experiment(name, lore, now)?.let(::put)
            "Calendar and Events" -> DigestActivityParser.calendar(name, lore, now)?.let(::updateEvent)
        }
    }

    private fun updateEvent(event: DigestCalendarEvent) {
        val previous = status.events.firstOrNull { it.name == event.name }
        val now = System.currentTimeMillis()
        val next = if (previous != null && previous.startsAt <= now && event.startsAt <= now)
            event.copy(startsAt = previous.startsAt) else event
        // Scoreboards update each second; persist at most one correction per minute, not every countdown tick.
        if (previous != null && kotlin.math.abs(previous.endsAt - next.endsAt) < 60_000 &&
            kotlin.math.abs(previous.startsAt - next.startsAt) < 60_000) return
        status = status.copy(events = (status.events.filter { it.name != next.name && (it.endsAt == 0L && it.startsAt > now || it.endsAt > now) } + next).takeLast(24), updatedAt = now)
        changed?.invoke()
    }

    private fun put(task: DailyTask) {
        val previous = tasks[task.id]
        if (previous == task || previous?.copy(updatedAt = task.updatedAt, slots = task.slots) == task && task.slots.isEmpty()) return
        tasks[task.id] = task
        changed?.invoke()
    }
}
