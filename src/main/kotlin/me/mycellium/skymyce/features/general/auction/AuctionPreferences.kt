package me.mycellium.skymyce.features.general.auction

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.utils.AtomicJsonFile
import me.mycellium.skymyce.utils.StateWriter
import me.mycellium.skymyce.utils.MC
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.time.Duration.Companion.milliseconds

data class SavedAuctionSearches(val version: Int = 1, val search: AuctionSearch = AuctionSearch(), val favorites: List<AuctionSearch> = emptyList())

internal fun parseAuctionPreferences(json: JsonElement?): SavedAuctionSearches {
    if (json == null) return SavedAuctionSearches()
    val root = json.asJsonObject
    require(root.get("version")?.asInt == 1)
    fun search(value: JsonElement?): AuctionSearch {
        val data = value?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        fun text(key: String, limit: Int) = runCatching { data.get(key)?.asString?.take(limit) }.getOrNull()
        fun money(key: String) = runCatching { data.get(key)?.asLong?.takeIf { it in 0..9007199254740991L } }.getOrNull()
        return AuctionSearch(text("query", 160).orEmpty(), text("category", 32),
            text("rarity", 32)?.takeIf { it in AUCTION_RARITIES },
            AuctionSearch.ListingType.entries.find { it.name == text("listingType", 32) } ?: AuctionSearch.ListingType.ALL,
            AuctionSearch.Sort.entries.find { it.name == text("sort", 32) } ?: AuctionSearch.Sort.ENDING_SOON,
            minPrice = money("minPrice"), maxPrice = money("maxPrice"))
    }
    return SavedAuctionSearches(search = search(root.get("search")), favorites = root.get("favorites")
        ?.takeIf { it.isJsonArray }?.asJsonArray?.take(8)?.map(::search)?.distinct().orEmpty())
}

object AuctionPreferences {
    var value = SavedAuctionSearches()
        private set
    var revision = 0L
        private set
    private var loaded = false
    private var edited = false
    private lateinit var writer: StateWriter<SavedAuctionSearches>
    fun initialize() {
        val file = AtomicJsonFile(SkyMyce.configPath.resolve("auction_searches.json"))
        writer = StateWriter({ file.write(Gson().toJsonTree(it)) },
            { action -> Scheduling.schedule(500.milliseconds) { action() } },
            { SkyMyce.logger.warn("Could not save auction preferences; previous file retained") })
        Scheduling.schedule(0.milliseconds) {
            val saved = runCatching { parseAuctionPreferences(file.read()) }.getOrElse {
                file.preserveOriginal(); SavedAuctionSearches()
            }
            MC.instance.execute { if (!edited) value = saved; loaded = true; revision++; if (edited) writer.submit(value) }
        }
        Runtime.getRuntime().addShutdownHook(Thread({ runCatching { writer.flush() } }, "Sky-Hawk-Auction-Preferences"))
    }
    fun update(search: AuctionSearch, favorites: List<AuctionSearch> = value.favorites) {
        edited = true
        value = SavedAuctionSearches(search = search.copy(page = 0), favorites = favorites.take(8).map { it.copy(page = 0) }.distinct())
        revision++
        if (loaded) writer.submit(value)
    }
}
