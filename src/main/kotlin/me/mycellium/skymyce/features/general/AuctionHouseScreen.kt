package me.mycellium.skymyce.features.general

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.GridLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.api.AuctionAPI
import me.mycellium.skymyce.features.general.auction.AuctionSearch
import me.mycellium.skymyce.utils.NumberUtils
import me.mycellium.skymyce.utils.PlayerUtils.sendCommand
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.component.ItemLore
import tech.thatgravyboat.skyblockapi.api.data.SkyBlockRarity
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A local, read-only browser for the auctions currently cached by [AuctionAPI]. */
class AuctionHouseScreen : BaseOwoScreen<FlowLayout>() {
    private var search = AuctionSearch()
    private lateinit var scroll: ScrollContainer<GridLayout>
    private lateinit var resultCount: LabelComponent
    private lateinit var pageLabel: LabelComponent
    private var renderedSnapshotVersion = -1L
    private var browserSessionStarted = false

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)

    override fun build(root: FlowLayout) {
        root.surface(HudTheme.backdrop)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(mainLayout())
        if (!browserSessionStarted) {
            browserSessionStarted = true
            AuctionAPI.beginBrowserSession()
        }
        updateResults()
    }

    override fun tick() {
        super.tick()
        if (::scroll.isInitialized && renderedSnapshotVersion != AuctionAPI.snapshotVersion) updateResults()
    }

    private fun mainLayout(): FlowLayout = UIContainers.verticalFlow(Sizing.fill(80), Sizing.fill(80)).apply {
        surface(HudTheme.panel())
        padding(Insets.of(10))
        gap(6)

        child(UIComponents.label(Component.literal("§b§lAuction Browser")))
        child(searchField())
        child(filterBar())

        resultCount = UIComponents.label(Component.empty())
        child(resultCount)

        scroll = UIContainers.verticalScroll(Sizing.expand(), Sizing.expand(), emptyGrid()).apply {
            alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
            padding(Insets.of(5))
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(0x55FFFF)))
            scrollbarThiccness(2)
            surface(HudTheme.panel(true))
        }
        child(scroll)
        child(paginationBar())
    }

    private fun searchField(): UIComponent = UIComponents.textBox(Sizing.fill()).apply {
        text(search.query)
        setHint(Component.literal("Search"))
        onChanged().subscribe { query ->
            search = search.withQuery(query)
            updateResults()
        }
    }

    private fun filterBar(): UIComponent = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(4)
        alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        child(UIComponents.button(Component.literal("§7Category: §f${search.category ?: "All"}")) { button ->
            openMenu(button) { dropdown ->
                dropdown.button(Component.literal("§fAll categories")) { selectCategory(null) }
                AuctionAPI.auctions.map { it.category }.distinct().sorted().forEach { category ->
                    dropdown.button(Component.literal("§f$category")) { selectCategory(category) }
                }
            }
        }.themed())
        child(UIComponents.button(Component.literal("§7Rarity: §f${search.rarity ?: "All"}")) { button ->
            openMenu(button) { dropdown ->
                dropdown.button(Component.literal("§fAll rarities")) { selectRarity(null) }
                SkyBlockRarity.entries.forEach { rarity ->
                    dropdown.button(rarity.displayText) { selectRarity(rarity) }
                }
            }
        }.themed())
        child(UIComponents.button(Component.literal("§7Type: §f${search.listingType.label}")) { button ->
            openMenu(button) { dropdown ->
                AuctionSearch.ListingType.entries.forEach { listingType ->
                    dropdown.button(Component.literal("§f${listingType.label}")) { selectListingType(listingType) }
                }
            }
        }.themed())
        child(UIComponents.button(Component.literal("§7Sort: §f${search.sort.label}")) { button ->
            openMenu(button) { dropdown ->
                AuctionSearch.Sort.entries.forEach { sort ->
                    dropdown.button(Component.literal("§f${sort.label}")) { selectSort(sort) }
                }
            }
        }.themed())
        child(UIComponents.button(Component.literal("Refresh cache").withColor(HudTheme.ACCENT)) { AuctionAPI.beginBrowserSession() }.themed()
            .tooltip(Component.literal("§7Discards cached pages and requests page 1 again.")))
    }

    private fun paginationBar(): UIComponent = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(5)
        alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        child(UIComponents.button(Component.literal("<").withColor(HudTheme.ACCENT)) { changePage(-1) }.themed())
        pageLabel = UIComponents.label(Component.empty())
        child(pageLabel)
        child(UIComponents.button(Component.literal(">").withColor(HudTheme.ACCENT)) { changePage(1) }.themed())
    }

    private fun updateResults() {
        if (!::scroll.isInitialized) return

        val results = search.results(AuctionAPI.auctions)
        val pageCount = max(1, ceil(results.size / RESULTS_PER_PAGE.toDouble()).toInt())
        if (search.page >= pageCount) search = search.copy(page = pageCount - 1)

        val firstResult = search.page * RESULTS_PER_PAGE
        val page = results.drop(firstResult).take(RESULTS_PER_PAGE)
        val total = AuctionAPI.totalAuctions?.let { " / ${NumberUtils.condense(it)} total" }.orEmpty()
        resultCount.text(Component.literal("§7${results.size} matching in ${AuctionAPI.cachedPages.size} cached page(s)$total"))
        pageLabel.text(Component.literal("§7Page §f${search.page + 1}§7 / §f$pageCount"))
        scroll.child(createGrid(page))
        renderedSnapshotVersion = AuctionAPI.snapshotVersion
    }

    private fun changePage(change: Int) {
        if (change > 0) AuctionAPI.requestNextPage()
        val pageCount = max(1, ceil(search.results(AuctionAPI.auctions).size / RESULTS_PER_PAGE.toDouble()).toInt())
        search = search.copy(page = (search.page + change).coerceIn(0, pageCount - 1))
        updateResults()
    }

    private fun openMenu(button: ButtonComponent, entries: (DropdownComponent) -> Unit) {
        DropdownComponent.openContextMenu(
            this,
            uiAdapter.rootComponent,
            { root, dropdown -> root.child(dropdown) },
            button.x.toDouble(),
            (button.y + button.height).toDouble(),
            { menu -> menu.surface(HudTheme.panel()); entries(menu) },
        )
    }

    private fun selectCategory(category: String?) {
        search = search.withCategory(category)
        rebuild()
    }

    private fun selectSort(sort: AuctionSearch.Sort) {
        search = search.withSort(sort)
        rebuild()
    }

    private fun selectRarity(rarity: SkyBlockRarity?) {
        search = search.withRarity(rarity)
        rebuild()
    }

    private fun selectListingType(listingType: AuctionSearch.ListingType) {
        search = search.withListingType(listingType)
        rebuild()
    }

    private fun rebuild() {
        uiAdapter.rootComponent.clearChildren()
        build(uiAdapter.rootComponent)
        uiAdapter.inflateAndMount()
    }

    private fun createGrid(auctions: List<AuctionAPI.ActiveAuction>): GridLayout {
        if (auctions.isEmpty()) return emptyGrid("§7No active auctions match these filters.")

        val rows = ceil(auctions.size / COLUMNS.toDouble()).toInt()
        return UIContainers.grid(Sizing.content(), Sizing.content(), rows, COLUMNS).apply {
            alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
            auctions.forEachIndexed { index, auction ->
                child(
                    createAuctionItem(auction),
                    index / COLUMNS,
                    index % COLUMNS
                )
            }
        }
    }

    private fun emptyGrid(message: String = "§7Loading cached auctions..."): GridLayout = UIContainers.grid(
        Sizing.fill(), Sizing.content(), 1, 1
    ).apply {
        child(UIComponents.label(Component.literal(message)), 0, 0)
        alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        padding(Insets.of(12))
    }

    private fun createAuctionItem(auction: AuctionAPI.ActiveAuction): UIComponent {
        val item = auction.item.copy()
        val lore = item.get(DataComponents.LORE)?.lines?.toMutableList() ?: mutableListOf()
        lore += listOf(
            Component.literal("§8§m                                        "),
            Component.literal("§7Category: §b${auction.category}"),
            Component.literal("§7Rarity: §f${auction.rarity}"),
            Component.literal("§7Type: §e${if (auction.isBin) "BIN" else "Auction"}"),
            Component.literal("§7Price: §a${NumberUtils.condense(auction.price)} coins"),
            Component.literal("§7Ends in: §e${formatRemaining(auction)}"),
            Component.literal("§8Seller: ${auction.auctioneer}"),
        )
        item.set(DataComponents.LORE, ItemLore(lore))
        return UIComponents.item(item).apply {
            margins(Insets.of(2))
            setTooltipFromStack(true)
            mouseDown().subscribe { _, _ ->
                sendCommand("viewauction ${auction.uuid}", false)
                true
            }
        }
    }

    private fun formatRemaining(auction: AuctionAPI.ActiveAuction): String = auction.remaining
        .coerceAtLeast(0)
        .milliseconds
        .inWholeSeconds
        .seconds
        .toString()

    private companion object {
        const val COLUMNS = 25
        const val RESULTS_PER_PAGE = 1000

        fun rarityRank(rarity: String): Int = when (rarity.uppercase()) {
            "VERY_SPECIAL" -> 7
            "SPECIAL" -> 6
            "MYTHIC" -> 5
            "LEGENDARY" -> 4
            "EPIC" -> 3
            "RARE" -> 2
            "UNCOMMON" -> 1
            else -> 0
        }
    }
}
