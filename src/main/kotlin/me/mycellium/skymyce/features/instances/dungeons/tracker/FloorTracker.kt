package me.mycellium.skymyce.features.instances.dungeons.tracker

import com.mojang.serialization.Codec
import com.mojang.serialization.codecs.RecordCodecBuilder
import me.mycellium.skymyce.api.DungeonChest
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

data class FloorTracker(
    var totalRuns: Int = 0,
    var totalTimeMillis: Long = 0L,
    var totalXp: Double = 0.0,
    var classXp: MutableMap<DungeonClass, Double> = mutableMapOf(),
    var chests: MutableMap<DungeonChest, ChestTracker> = mutableMapOf(),
) {
    val totalChestsOpened: Int
        get() = chests.values.sumOf { it.chestCount }

    val totalChestsCost: Double
        get() = chests.values.sumOf { it.chestCost }

    val totalRerolled: Int
        get() = chests.values.sumOf { it.rerollCount }

    val totalRerolledCost: Double
        get() = chests.values.sumOf { it.rerollCost }

    val netProfit: Double
        get() = chests.values.sumOf { it.netProfit }

    val grossProfit: Double
        get() = chests.values.sumOf { it.grossProfit }

    val totalTimeHours: Double
        get() = totalTimeMillis.milliseconds.toDouble(DurationUnit.HOURS)

    companion object {
        val CODEC: Codec<FloorTracker> = RecordCodecBuilder.create { instance ->
            instance.group(
                Codec.INT.fieldOf("totalRuns").forGetter(FloorTracker::totalRuns),
                Codec.LONG.fieldOf("totalTimeMillis").forGetter(FloorTracker::totalTimeMillis),
                Codec.DOUBLE.fieldOf("totalXp").forGetter(FloorTracker::totalXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonClass.valueOf(it) }, { it.name }),
                    Codec.DOUBLE
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("classXp").forGetter(FloorTracker::classXp),
                Codec.unboundedMap(
                    Codec.STRING.xmap({ DungeonChest.valueOf(it) }, { it.name }),
                    ChestTracker.CODEC
                ).xmap({ it.toMutableMap() }, { it }).fieldOf("chests").forGetter(FloorTracker::chests),
            ).apply(instance, ::FloorTracker)
        }
    }
}