package me.mycellium.skymyce.features.digest

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The digest's local day is independent of Hypixel activity reset times and profile changes. */
class DigestDailyGate {
    val lastOpened = mutableMapOf<String, String>()
    private var joined = false
    private var readyAt = Long.MAX_VALUE

    fun enter(now: Long, delayMillis: Long) {
        joined = true
        readyAt = now + delayMillis.coerceIn(2000, 30000)
    }

    fun leave() { joined = false; readyAt = Long.MAX_VALUE }

    fun shouldOpen(account: String?, now: Long, date: LocalDate, enabled: Boolean,
                   stateLoaded: Boolean, profileReady: Boolean, screenFree: Boolean): Boolean =
        joined && enabled && stateLoaded && profileReady && screenFree && account != null && now >= readyAt &&
            lastOpened[account]?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.let { day -> day >= date } } != true

    fun opened(account: String, date: LocalDate) {
        val previous = lastOpened[account]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (previous == null || date > previous) lastOpened[account] = date.toString()
        while (lastOpened.size > 64) lastOpened.remove(lastOpened.minBy { it.value }.key)
    }

    companion object {
        fun localDate(now: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        fun nextMidnight(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
            localDate(now, zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        fun profileKey(account: String, profile: String, alpha: Boolean): String =
            "$account/${if (alpha) "alpha" else "main"}/${profile.trim().lowercase()}"
    }
}
