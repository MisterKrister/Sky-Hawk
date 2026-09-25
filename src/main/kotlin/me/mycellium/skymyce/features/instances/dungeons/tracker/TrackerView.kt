package me.mycellium.skymyce.features.instances.dungeons.tracker

data class TrackerSummary(val runs: Long = 0, val time: Long = 0, val timedRuns: Long = 0,
    val gross: Double = 0.0, val chestCost: Double = 0.0, val rerollCost: Double? = 0.0,
    val cataXp: Double = 0.0, val classXp: Map<String, Double> = emptyMap(), val chests: Int = 0,
    val rerolls: Int = 0, val incompleteValues: Boolean = false) {
    val average get() = safeAverage(time.toDouble(), timedRuns)
    val net get() = rerollCost?.let { gross - chestCost - it }
}
data class LegacyFloorView(val floor: String, val summary: TrackerSummary, val loot: Map<String, List<TrackerLootRow>>,
    val chestCosts: Map<String, Double>, val rerollCosts: Map<String, Double>, val chestCounts: Map<String, Int>)
data class TrackerView(val summary: TrackerSummary = TrackerSummary(), val loot: List<TrackerLootRow> = emptyList(),
    val events: List<AcquisitionEvent> = emptyList(), val usingLegacy: Boolean = false)

/** Runs only when data or filters change, on the background scheduler. */
fun trackerView(archive: AcquisitionArchive, legacy: List<LegacyFloorView>, filter: TrackerFilter, context: AcquisitionContext?): TrackerView {
    val legacyMode = filter.legacyTotals && !filter.needsRecords
    val events = filter.events(archive.events, context, archive.runs)
    if (legacyMode) {
        val floors = legacy.filter { filter.floorMatches(it.floor) }
        val selected = floors.flatMap { floor -> floor.loot.filterKeys { filter.chest == null || filter.chest == it }.values.flatten() }
        val denominator = floors.sumOf { floor -> floor.chestCounts.filterKeys { filter.chest == null || filter.chest == it }.values.sum() }
        val loot = selected.groupBy { it.item }.map { (item, rows) -> TrackerLootRow(item, rows.sumOf { it.count },
            rows.sumOf { it.value ?: 0.0 }, null, denominator) }
        val summaries = floors.map { it.summary }
        return TrackerView(TrackerSummary(summaries.sumOf { it.runs }, summaries.sumOf { it.time }, summaries.sumOf { it.timedRuns },
            loot.sumOf { it.value ?: 0.0 }, floors.sumOf { floor -> floor.chestCosts.filterKeys { filter.chest == null || filter.chest == it }.values.sum() },
            floors.sumOf { floor -> floor.rerollCosts.filterKeys { filter.chest == null || filter.chest == it }.values.sum() },
            summaries.sumOf { it.cataXp }, mergeXp(summaries.map { it.classXp }), denominator, summaries.sumOf { it.rerolls }),
            sortedLoot(loot, filter), events, true)
    }
    val runs = filter.runs(archive.runs, context)
    val receipts = runs.flatMap { it.chests }.filter { filter.chest == null || filter.chest == it.chest }
    val loot = receipts.flatMap { chest -> chest.loot.map { it to chest.id } }.groupBy { it.first.item }.map { (item, entries) ->
        TrackerLootRow(item, entries.sumOf { it.first.count.toLong() }, if (entries.any { it.first.value == null }) null else entries.sumOf { it.first.value!! }, entries.map { it.second }.distinct().size, receipts.size)
    }
    val timed = runs.filter { it.completedAt != null && it.duration != null && it.duration > 0 }
    val rerollsKnown = filter.chest == null && runs.none { it.rerolls > 0 && it.rerollCost == null }
    return TrackerView(TrackerSummary(runs.count { it.completedAt != null }.toLong(), timed.sumOf { it.duration!! }, timed.size.toLong(),
        loot.sumOf { it.value ?: 0.0 }, receipts.sumOf { it.cost }, if (rerollsKnown) runs.sumOf { it.rerollCost ?: 0.0 } else null,
        runs.sumOf { it.cataXp ?: 0.0 }, mergeXp(runs.map { it.classXp }), receipts.size, runs.sumOf { it.rerolls }, loot.any { it.value == null }),
        sortedLoot(loot, filter), events, false)
}
private fun mergeXp(values: List<Map<String, Double>>) = values.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }

/** Captured on the client thread; immutable primitives are safe for background queries. */
fun legacyTrackerView(): List<LegacyFloorView> = DungeonTracker.profitData.map { (floor, data) ->
    fun Double.safe() = takeIf { it.isFinite() && it >= 0 } ?: 0.0
    LegacyFloorView(floor.name, TrackerSummary(data.totalRuns.coerceAtLeast(0).toLong(), data.totalTimeMillis.coerceAtLeast(0),
        if (data.totalTimeMillis > 0) data.totalRuns.coerceAtLeast(0).toLong() else 0, cataXp = data.totalXp.safe(),
        classXp = data.classXp.mapKeys { it.key.name }.mapValues { it.value.safe() }, rerolls = data.totalRerolled.coerceAtLeast(0)),
        data.chests.mapKeys { it.key.name }.mapValues { (_, chest) -> chest.trackedItems.map { (id, loot) ->
            TrackerLootRow(canonicalItemId(id.id), loot.count.coerceAtLeast(0).toLong(), loot.value.safe(), null, chest.chestCount.coerceAtLeast(0)) } },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.rerollCost.safe() },
        data.chests.mapKeys { it.key.name }.mapValues { it.value.chestCount.coerceAtLeast(0) })
}
