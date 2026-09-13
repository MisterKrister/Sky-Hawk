package me.mycellium.skymyce.api

import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyceModule
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import net.minecraft.world.item.ItemStack
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.legacyStack
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import tech.thatgravyboat.skyblockapi.utils.http.Http
import java.io.ByteArrayInputStream
import java.util.Base64
import kotlin.collections.ArrayDeque
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.any
import kotlin.collections.contains
import kotlin.collections.emptyList
import kotlin.collections.emptyMap
import kotlin.collections.firstNotNullOfOrNull
import kotlin.collections.firstOrNull
import kotlin.collections.flatMap
import kotlin.collections.map
import kotlin.collections.none
import kotlin.collections.plus
import kotlin.collections.toSortedMap
import kotlin.jvm.optionals.getOrNull
import kotlin.ranges.coerceAtLeast
import kotlin.ranges.until
import kotlin.text.orEmpty
import kotlin.time.Duration.Companion.seconds

private const val URL = "https://api.hypixel.net/v2/skyblock/auctions"

/** A lazy, page-by-page cache for Hypixel's auction API. */
object AuctionAPI : SkyMyceModule() {
    private val cacheLock = Any()
    private val queuedPages = ArrayDeque<Pair<Long, Int>>()
    private var requestGeneration = 0L

    var auctions: List<ActiveAuction> = emptyList()
        private set
    var cachedPages: Map<Int, AuctionPage> = emptyMap()
        private set
    var totalPages: Int? = null
        private set
    var totalAuctions: Int? = null
        private set
    var lastUpdated: Long? = null
        private set
    var isRefreshing: Boolean = false
        private set
    var snapshotVersion: Long = 0
        private set

    /** Starts a fresh browser cache by requesting only API page zero. */
    fun beginBrowserSession() {
        synchronized(cacheLock) {
            requestGeneration++
            queuedPages.clear()
            auctions = emptyList()
            cachedPages = emptyMap()
            totalPages = null
            totalAuctions = null
            lastUpdated = null
            snapshotVersion++
            queuePageLocked(requestGeneration, 0)
        }
        startQueueWorker()
    }

    /** Queues an individual API page once. Duplicate and already-cached requests are ignored. */
    fun requestPage(page: Int) {
        synchronized(cacheLock) {
            val pageCount = totalPages ?: return
            if (page !in 0 until pageCount || page in cachedPages || queuedPages.any { it.second == page }) return
            queuePageLocked(requestGeneration, page)
        }
        startQueueWorker()
    }

    /** Loads the next missing API page, used as the player browses forward. */
    fun requestNextPage() {
        val nextPage = synchronized(cacheLock) {
            val pageCount = totalPages ?: return
            (0 until pageCount).firstOrNull { it !in cachedPages && queuedPages.none { queued -> queued.second == it } }
        } ?: return
        requestPage(nextPage)
    }

    /** Backwards-compatible entry point for commands and existing callers. */
    fun getData() = beginBrowserSession()

    private fun queuePageLocked(generation: Long, page: Int) {
        queuedPages.addLast(generation to page)
    }

    private fun startQueueWorker() {
        synchronized(cacheLock) {
            if (isRefreshing) return
            isRefreshing = true
        }

        Scheduling.schedule(0.seconds) {
            while (true) {
                val request = synchronized(cacheLock) { queuedPages.removeFirstOrNull() } ?: break
                val loadedPage = fetchPage(request.second)

                synchronized(cacheLock) {
                    if (request.first != requestGeneration || loadedPage == null) continue
                    applyPage(loadedPage)
                }
            }

            synchronized(cacheLock) { isRefreshing = false }
        }
    }

    private fun applyPage(page: AuctionPage) {
        // A newly rotated API dataset must never be mixed into the current cache.
        if (lastUpdated != null && lastUpdated != page.lastUpdated) {
            beginBrowserSession()
            return
        }

        cachedPages = cachedPages + (page.page to page)
        totalPages = page.totalPages
        totalAuctions = page.totalAuctions
        lastUpdated = page.lastUpdated
        auctions = cachedPages.toSortedMap().values.flatMap { it.auctions }
        snapshotVersion++
    }

    private suspend fun fetchPage(page: Int): AuctionPage? {
        val response = Http.getResult<JsonObject>("$URL").getOrNull() ?: return null
        if (!response["success"].asBoolean) return null

        return AuctionPage(
            page = response["page"].asInt,
            totalPages = response["totalPages"].asInt,
            totalAuctions = response["totalAuctions"].asInt,
            lastUpdated = response["lastUpdated"].asLong,
            auctions = response.getAsJsonArray("auctions").map { parseAuction(it.asJsonObject) },
        )
    }

    private fun parseAuction(auction: JsonObject): ActiveAuction {
        val startingBid = auction["starting_bid"].asLong
        val highestBid = auction.get("highest_bid_amount")?.asLong ?: startingBid

        return ActiveAuction(
            uuid = auction["uuid"].asString,
            auctioneer = auction["auctioneer"].asString,
            start = auction["start"].asLong,
            end = auction["end"].asLong,
            name = auction.get("item_name")?.asString.orEmpty(),
            lore = auction.get("item_lore")?.asString.orEmpty(),
            category = auction["category"].asString,
            rarity = SkyBlockRarity.fromName(auction.get("tier").asString),
            isBin = auction.get("bin")?.asBoolean ?: false,
            price = highestBid.coerceAtLeast(startingBid),
            item = decodeItem(auction) ?: ItemStack.EMPTY,
        )
    }

    /** Supports both the current string payload and the documented { data: ... } payload. */
    private fun decodeItem(auction: JsonObject): ItemStack? = runCatching {
        val encoded = auction["item_bytes"].let { bytes ->
            if (bytes.isJsonPrimitive) bytes.asString else bytes.asJsonObject["data"].asString
        }
        val nbt = NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(encoded)), NbtAccounter.unlimitedHeap())
        nbt.getList("i").getOrNull()?.firstNotNullOfOrNull { it.legacyStack() }
    }.getOrNull()

    data class AuctionPage(
        val page: Int,
        val totalPages: Int,
        val totalAuctions: Int,
        val lastUpdated: Long,
        val auctions: List<ActiveAuction>,
    )

    data class ActiveAuction(
        val uuid: String,
        val auctioneer: String,
        val start: Long,
        val end: Long,
        val name: String,
        val lore: String,
        val category: String,
        val rarity: SkyBlockRarity,
        val isBin: Boolean,
        val price: Long,
        val item: ItemStack,
    ) {
        val remaining: Long get() = end - System.currentTimeMillis()
        val expired: Boolean get() = remaining <= 0L
    }
}
