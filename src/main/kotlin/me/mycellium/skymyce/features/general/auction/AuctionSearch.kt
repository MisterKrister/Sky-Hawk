package me.mycellium.skymyce.features.general.auction

import me.mycellium.skymyce.api.AuctionAPI
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import tech.thatgravyboat.skyblockapi.utils.extentions.stripColor

/** The screen's query state and result selection, kept separate from UI code. */
data class AuctionSearch(
    val query: String = "",
    val category: String? = null,
    val rarity: SkyBlockRarity? = null,
    val listingType: ListingType = ListingType.ALL,
    val sort: Sort = Sort.ENDING_SOON,
    val page: Int = 0,
) {
    fun results(auctions: List<AuctionAPI.ActiveAuction>): List<AuctionAPI.ActiveAuction> {
        val normalizedQuery = query.trim()
        return auctions.asSequence()
            .filter { !it.expired }
            .filter { category == null || it.category.equals(category, ignoreCase = true) }
            .filter { rarity == null || it.rarity == rarity }
            .filter { listingType.matches(it) }
            .filter { auction ->
                normalizedQuery.isEmpty() || auction.name.contains(normalizedQuery, ignoreCase = true) ||
                    auction.lore.stripColor().contains(normalizedQuery, ignoreCase = true)
            }
            .sortedWith(sort.comparator)
            .toList()
    }

    fun withQuery(query: String) = copy(query = query, page = 0)
    fun withCategory(category: String?) = copy(category = category, page = 0)
    fun withRarity(rarity: SkyBlockRarity?) = copy(rarity = rarity, page = 0)
    fun withListingType(listingType: ListingType) = copy(listingType = listingType, page = 0)
    fun withSort(sort: Sort) = copy(sort = sort, page = 0)

    enum class Sort(val label: String, val comparator: Comparator<AuctionAPI.ActiveAuction>) {
        PRICE_LOW_TO_HIGH("Low to High", compareBy { it.price }),
        PRICE_HIGH_TO_LOW("High to Low", compareByDescending { it.price }),
        ENDING_SOON("Ending soon", compareBy { it.remaining }),
        RARITY("Rarity", compareBy { it.rarity.ordinal }),
        NEWEST("Newest", compareByDescending { it.start }),
    }

    enum class ListingType(val label: String) {
        ALL("All listings"),
        BIN("BIN only"),
        BID("Auction bids only");

        fun matches(auction: AuctionAPI.ActiveAuction) = this == ALL || (this == BIN) == auction.isBin
    }
}