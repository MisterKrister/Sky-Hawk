package me.mycellium.skymyce.features.instances.dungeons

import me.mycellium.skymyce.config.instances.dungeons.PartyListing
import me.mycellium.skymyce.config.instances.dungeons.partyListingFromLore
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass.*
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor.*

fun checkPartyMatcher() {
    mapOf("5" to 300, "510" to 310, "5:10" to 310, "5min" to 300, "5m" to 300,
        "PB 5:10 tank" to 310, "sub5" to 300, "sub5 tank" to 300, "pb510" to 310, "cata 40, sub 5:10" to 310,
        "§6sub 510§r" to 310, "10:59 PB" to 659, "1059" to 659, "0:59" to 59,
        "sub 6m / 5:10 PB" to 310).forEach { (note, expected) -> check(parsePartyPbSeconds(note) == expected) { note } }
    listOf(null, "", "casual run", "5:99", "5:1", "560", "-5", "5.10", "0", "000", "10000",
        "F7 M5", "cata 40", "40 cata", "5 runs", "need 1 tank", "MP 800", "archer50", "2026-09-24", "5minsx", "https://a/510").forEach {
        check(parsePartyPbSeconds(it) == null) { "Unexpected PB: $it -> ${parsePartyPbSeconds(it)}" }
    }
    val listing = PartyListing(10, "Leader", null, listOf("Leader"), "510", floor = F7, openClasses = setOf(HEALER, MAGE))
    check(partyMatches(listing, setOf(HEALER), 310000, true, true))
    check(!partyMatches(listing, setOf(HEALER), 310001, true, true))
    check(!partyMatches(listing, setOf(HEALER), null, true, true))
    check(!partyMatches(listing, setOf(HEALER), 0, true, true))
    check(!partyMatches(listing, setOf(TANK), 300000, true, true))
    check(partyMatches(listing, setOf(TANK, MAGE), 300000, true, true))
    check(partyMatches(listing, setOf(TANK), 300000, false, true))
    check(partyMatches(listing, setOf(HEALER), null, true, false))
    check(partyMatches(listing.copy(note = "chill"), setOf(HEALER), null, true, true))
    check(partyMatches(listing.copy(openClasses = null), setOf(TANK), 300000, true, true))
    check(!partyMatches(listing.copy(openClasses = emptySet()), setOf(TANK), 300000, true, true))
    check(partyOpenClasses(listOf("Open Classes: Berserk, Healer"), listOf(HEALER)) == setOf(BERSERKER, HEALER))
    check(partyOpenClasses(listOf("Open Classes:", "Healer", "Mage", "", "Members:"), listOf(TANK)) == setOf(HEALER, MAGE))
    check(partyOpenClasses(listOf("Available Classes: Any"), listOf(HEALER))?.size == 5)
    check(partyOpenClasses(listOf("Needed Classes: none"), emptyList()).isNullOrEmpty())
    check(partyOpenClasses(emptyList(), listOf(TANK, MAGE)) == setOf(HEALER, BERSERKER, ARCHER))
    check(partyOpenClasses(emptyList(), emptyList()) == null)
    val lore = listOf("Dungeon: Master Mode Catacombs", "Floor: VII", "Note: sub 5:10", "Members:", "[MVP+] Leader: Tank (50)", "Empty")
    val parsed = partyListingFromLore(10, null, lore)!!
    check(parsed.floor == M7 && parsed.openClasses?.contains(TANK) == false && parsePartyPbSeconds(parsed.note) == 310)
    check(!partyMatches(parsed, setOf(HEALER), mapOf(F7 to 300000L)[parsed.floor], true, true))
    println("Party matcher checks passed: shorthand, boundaries, missing PB, floor isolation, classes and lore")
}
