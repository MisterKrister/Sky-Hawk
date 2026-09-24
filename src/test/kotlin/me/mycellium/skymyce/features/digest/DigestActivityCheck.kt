package me.mycellium.skymyce.features.digest

import java.time.Instant

fun checkDigestActivities() {
    val now = Instant.parse("2026-09-24T04:59:00Z").toEpochMilli()
    check(DigestActivityParser.titles.size == 6)
    check(DigestActivityParser.duration("1d 2h 3m 4s") == 93_784_000L)
    check(DigestActivityParser.duration("not a duration") == null)
    check(DigestActivityParser.duration("9999999999999999999999d") == null)
    check(DigestActivityParser.duration("1h unexpected") == null)

    val puzzler = DigestActivityParser.chat("Puzzler gave you 1,000 Mithril Powder for solving the puzzle!", now, true, false)!!
    check(puzzler.state == DailyState.COMPLETE && puzzler.readyAt == now + DigestActivityParser.DAY)
    check(DigestActivityParser.display(puzzler, puzzler.readyAt - 1).state == DailyState.COMPLETE)
    check(DigestActivityParser.display(puzzler, puzzler.readyAt).state == DailyState.READY)
    val expiredManual = DigestActivityParser.display(puzzler.copy(manual = true), puzzler.readyAt)
    check(expiredManual.state == DailyState.UNKNOWN && expiredManual.detail.contains("Manual note expired"))
    check(DigestActivityParser.chat("Puzzler gave you 1,000 Mithril Powder for solving the puzzle!", now, false, false) == null)
    check(DigestActivityParser.chat("[VIP] Someone: Puzzler gave you 1,000 Mithril Powder for solving the puzzle!", now, true, false) == null)
    val fetchur = DigestActivityParser.chat("[NPC] Fetchur: thanks thats probably what i needed", now, true, false)!!
    check(fetchur.readyAt == now + 60_000)
    check(DigestActivityParser.display(fetchur, now + 60_000).state == DailyState.READY)
    check(DigestActivityParser.chat("[NPC] Fetchur: come back another time, maybe tmrw", now, true, false)?.state == DailyState.COMPLETE)
    check(DigestActivityParser.chat("[NPC] Fetchur: hello", now, true, false) == null)

    val claimed = DigestActivityParser.chat("You claimed the Superpairs rewards!", now, false, true)!!
    check(claimed.state == DailyState.COMPLETE && claimed.readyAt == 0L)
    check(claimed.detail.contains("remaining charges"))
    check(DigestActivityParser.chat("You claimed the Superpairs rewards!", now, false, false) == null)
    check(DigestActivityParser.experiment("Superpairs", listOf("Stored Charges: 2/3"), now)?.state == DailyState.READY)
    val experiment = DigestActivityParser.experiment("Superpairs", listOf("Stored Charges: 0/3", "Next Charge: 1h 5m"), now)!!
    check(experiment.state == DailyState.COMPLETE && experiment.readyAt == now + 3_900_000)
    check(DigestActivityParser.display(experiment, experiment.readyAt).state == DailyState.READY)
    check(DigestActivityParser.experiment("Random Item", listOf("Click to play!"), now) == null)
    check(DigestActivityParser.experiment("Superpairs", listOf("New unrecognized interface"), now) == null)

    val forging = DigestActivityParser.forge(1, "Refined Mithril", listOf("Time Remaining: 1h 30m"), now)!!
    check(forging.state == DailyState.ACTIVE && forging.readyAt == now + 5_400_000)
    check(DigestActivityParser.forgeTask(listOf(forging), now).state == DailyState.ACTIVE)
    check(DigestActivityParser.forgeTask(listOf(forging), now).detail.contains("6 unknown"))
    check(DigestActivityParser.forgeTask(listOf(forging), forging.readyAt).slots.first().state == DailyState.COMPLETE)
    check(DigestActivityParser.forgeTask(listOf(forging), forging.readyAt).slots.drop(1).all { it.state == DailyState.UNKNOWN })
    check(DigestActivityParser.forge(2, "Refined Titanium", listOf("Time Remaining: Completed!", "Click to collect!"), now)?.state == DailyState.COMPLETE)
    check(DigestActivityParser.forge(3, "Empty Slot", emptyList(), now)?.state == DailyState.EMPTY)
    check(DigestActivityParser.forge(4, "Locked Slot", emptyList(), now)?.state == DailyState.UNAVAILABLE)
    check(DigestActivityParser.forge(3, "Slot #3", emptyList(), now, 0x55FF55)?.state == DailyState.EMPTY)
    check(DigestActivityParser.forge(7, "Slot #7", emptyList(), now, 0xFF5555)?.state == DailyState.UNAVAILABLE)
    check(DigestActivityParser.forge(5, "Unknown Item", emptyList(), now) == null)
    check(DigestActivityParser.forge(100, "Empty Slot", emptyList(), now) == null)
    check(DigestActivityParser.forgeTask(emptyList(), now).state == DailyState.UNKNOWN)
    check(DigestActivityParser.forgeTask(emptyList(), now).detail == "Open The Forge to detect slots")
    check(DigestActivityParser.forge(1, "a".repeat(121), listOf("Time Remaining: 1h"), now) == null)

    check(DigestActivityParser.isDungeonCompletion("    +14,232 Catacombs Experience"))
    check(!DigestActivityParser.isDungeonCompletion("[VIP] Someone: +14,232 Catacombs Experience"))
    var dungeon: DailyTask? = null
    repeat(5) { dungeon = DigestActivityParser.dungeon(dungeon, now + it) }
    val observedDungeon = requireNotNull(dungeon)
    check(observedDungeon.progress == 5 && observedDungeon.state == DailyState.ACTIVE)
    check(observedDungeon.detail.contains("unverified"))
    check(DigestActivityParser.display(observedDungeon, observedDungeon.readyAt).state == DailyState.UNKNOWN)
    check(DigestActivityParser.dungeon(observedDungeon, observedDungeon.readyAt).progress == 1)

    val fiesta = DigestActivityParser.calendar("Mining Fiesta", listOf("Starts in: 1h 20m", "Duration: 5h"), now)!!
    check(fiesta.startsAt == now + 4_800_000 && fiesta.endsAt == fiesta.startsAt + 18_000_000)
    val spooky = DigestActivityParser.calendar("Spooky Festival", listOf("Ends in: 15m"), now)!!
    check(spooky.startsAt == now && spooky.endsAt == now + 900_000)
    check(DigestActivityParser.calendar("Mining Fiesta", listOf("Starts in: someday"), now) == null)
    check(DigestActivityParser.calendar("a".repeat(81), listOf("Starts in: 1h"), now) == null)
}

fun checkDigestRng() {
    val now = Instant.parse("2026-09-24T04:59:00Z").toEpochMilli()
    val message = "RARE REWARD! Player_1 found a Necron's Handle in their Bedrock Chest!"
    val drop = DigestRngParser.detect(message, "Player_1", "THE_CATACOMBS", now)!!
    check(drop.item == "NECRON_HANDLE" && drop.activity == "dungeon")
    check(drop.id.matches(Regex("[a-f0-9]{32}")))
    check(DigestRngParser.detect(message, "Player_1", "THE_CATACOMBS", now) == drop)
    check(DigestRngParser.detect(message, "OtherPlayer", "THE_CATACOMBS", now) == null)
    check(DigestRngParser.detect(message, "Player_1", "HUB", now) == null)
    check(DigestRngParser.detect("[VIP] Player_1: $message", "Player_1", "THE_CATACOMBS", now) == null)
    check(DigestRngParser.detect(message.replace("Necron's Handle", "Recombobulator 3000"), "Player_1", "THE_CATACOMBS", now) == null)
    check(DigestRngParser.detect(message.replace("Necron's Handle", "a".repeat(200)), "Player_1", "THE_CATACOMBS", now) == null)
    check(DigestRngParser.detect(message, "a".repeat(17), "THE_CATACOMBS", now) == null)
    val heart = DigestRngParser.detect("CRAZY RARE DROP!  (Warden Heart) (+204% ✯ Magic Find)", "Player_1", "HUB", now)!!
    check(heart.item == "WARDEN_HEART" && heart.activity == "slayer")
    check(DigestRngParser.detect("INSANE DROP! Judgement Core (+204% ✯ Magic Find)", "Player_1", "THE_END", now)?.item == "JUDGEMENT_CORE")
    check(DigestRngParser.detect("CRAZY RARE DROP!  (Foul Flesh) (+204% ✯ Magic Find)", "Player_1", "HUB", now) == null)
    check(DigestRngParser.detect("CRAZY RARE DROP!  (Warden Heart) (+204% ✯ Magic Find)", "Player_1", "PRIVATE_ISLAND", now) == null)
    check(DigestRngCatalog.valid("WARDEN_HEART", "slayer"))
    check(!DigestRngCatalog.valid("WARDEN_HEART", "dungeon"))
    check(!DigestRngCatalog.valid("INVENTORY", "slayer"))
    check(DigestRngCatalog.name("WARDEN_HEART") == "Warden Heart")

    val acquisition = DigestDungeonAcquisition()
    check(acquisition.announce(drop, now) == null)
    // The initial inventory containing an old rare item is only a baseline, never an acquisition.
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 1), now).isEmpty())
    check(acquisition.announce(drop, now) == null)
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 1), now + 100).isEmpty())
    // Moving the old item between slots leaves aggregate counts unchanged.
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 1), now + 200).isEmpty())
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 2), now + 300).single() == drop)
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 2), now + 400).isEmpty())
    acquisition.clear()
    acquisition.seed(emptyMap())
    // Inventory and server chat can arrive in either order.
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 1), now).isEmpty())
    check(acquisition.announce(drop, now + 500) == drop)
    acquisition.seed(emptyMap())
    check(acquisition.announce(drop, now) == null)
    check(acquisition.inventory(mapOf("NECRON_HANDLE" to 1), now + 16_000).isEmpty())
    check(acquisition.announce(drop.copy(occurredAt = now + 20_000), now + 20_000) == null)
    acquisition.clear()
    check(acquisition.announce(drop, now) == null)
}
