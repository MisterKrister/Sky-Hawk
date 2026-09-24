package me.mycellium.skymyce.features.instances.dungeons.friends

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.hud.HudTheme
import me.mycellium.skymyce.hud.themed
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.utils.NumberUtils.condense
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class FriendsSocialScreen(private var tab: Tab = Tab.FRIENDS) : BaseOwoScreen<FlowLayout>() {
    enum class Tab(val label: String) { FRIENDS("Friends"), LENDING("Lending log"), WEALTH("Friend wealth") }
    private var opened = false
    private var search = ""
    private var outstanding = false
    private var historyLimit = 50
    private var sort = 0
    private var ascending = false
    private var previous: List<Any?> = emptyList()
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var refreshButton: ButtonComponent
    private lateinit var scanStatus: LabelComponent
    private val panelWidth get() = minOf(700, width - 24)

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)

    override fun init() {
        if (!opened) { opened = true; DungeonFriends.refreshFriends(automatic = true, full = true) }
        super.init()
    }

    override fun removed() { opened = false; super.removed() }

    override fun build(root: FlowLayout) {
        DungeonFriendsSettings.load()
        root.surface(HudTheme.backdrop)
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(UIContainers.verticalFlow(Sizing.fixed(panelWidth), Sizing.fixed(minOf(430, height - 24))).apply {
            padding(Insets.of(12)); gap(8)
            surface(HudTheme.panel())
            child(row().apply {
                child(label(when (tab) { Tab.FRIENDS -> "§lFriends"; Tab.LENDING -> "§lWho Has My Gear?"; Tab.WEALTH -> "§lFriend Wealth" }, CYAN)
                    .horizontalSizing(Sizing.expand()))
                refreshButton = button("Refresh", 72) {
                    DungeonFriends.refreshFriends(full = true)
                    if (tab == Tab.WEALTH) FriendWealthCache.refresh()
                    updateRows()
                }
                child(refreshButton)
                child(button("Close", 44) { onClose() })
            })
            child(row().apply {
                Tab.entries.forEach { value ->
                    child(button("${if (tab == value) "§b" else ""}${value.label}", if (value == Tab.FRIENDS) 62 else 94) {
                        tab = value; rebuild()
                    })
                }
            })
            child(row().apply {
                child(label("Username", MUTED))
                val field = UIComponents.textBox(Sizing.expand()).apply {
                    setMaxLength(16); text(search); setHint(Component.literal("Search usernames..."))
                    onChanged().subscribe { search = it; updateRows() }
                }
                child(field)
                child(button("Clear", 40) { field.text("") })
            })
            scanStatus = label(DungeonFriends.scanner.status, MUTED).apply { horizontalSizing(Sizing.fill()) }
            child(scanStatus)
            if (tab == Tab.WEALTH) {
                child(label("Estimates • selected profile • bank includes shared co-op funds", MUTED).horizontalSizing(Sizing.fill()))
                child(row().apply {
                    gap(0)
                    listOf("Networth", "Purse", "Bank", "Wardrobe").forEachIndexed { index, title ->
                        child(button("${if (sort == index) "§b" else ""}$title", 78) {
                            ascending = if (sort == index) !ascending else false
                            sort = index; updateRows()
                        }.horizontalSizing(Sizing.fill(25)).tooltip(Component.literal("Sort by $title; click again to reverse")))
                    }
                })
            } else if (tab == Tab.LENDING) {
                child(row().apply {
                    child(button(if (outstanding) "Outstanding loans" else "All trades", 124) {
                        outstanding = !outstanding; rebuild()
                    }.tooltip(Component.literal("Switch between trade history and items still lent out")))
                    child(label("Local to this account", MUTED).horizontalSizing(Sizing.expand()))
                })
            }
            results = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
                scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
                scrollbarThiccness(2); padding(Insets.right(4))
            }
            child(results)
        })
        updateRows()
    }

    override fun tick() {
        super.tick()
        val ledger = if (tab == Tab.LENDING) GearLending.ledger else null
        refreshButton.active(LocationAPI.onHypixel && !DungeonFriends.scanner.scanning)
        refreshButton.message = Component.literal(if (DungeonFriends.scanner.scanning) "Refreshing" else "Refresh")
        refreshButton.tooltip(Component.literal(if (!LocationAPI.onHypixel) "Join Hypixel to refresh" else
            "Refresh the full friend list. Automatic refreshes are limited to once per minute; cached profile checks are reused."))
        val status = if (LocationAPI.onHypixel) DungeonFriends.scanner.status else "Join Hypixel to refresh friends"
        if (scanStatus.text().string != status) scanStatus.text(Component.literal(status))
        val state = listOf(FriendWealthCache.version, DungeonFriends.scanner.all.toMap(), ledger, ledger?.trades, ledger?.error,
            DungeonFriendsSettings.error, LocationAPI.onHypixel)
        if (state != previous) { previous = state; updateRows() }
    }

    private fun updateRows() {
        if (!::results.isInitialized) return
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(6)
            when (tab) { Tab.FRIENDS -> friendRows(this); Tab.LENDING -> lendingRows(this); Tab.WEALTH -> wealthRows(this) }
        })
    }

    private fun friendRows(list: FlowLayout) {
        if (!LocationAPI.onHypixel) { list.child(label("Join Hypixel to view this account's friends.", MUTED)); return }
        val friends = DungeonFriends.scanner.all.values.filter { matchesFriendName(it.name, search) }
            .sortedWith(compareBy<OnlineDungeonFriend> { it.activity == FriendActivity.OFFLINE }
                .thenByDescending { it.bestFriend }.thenBy { it.name.lowercase() })
        list.child(label("FRIENDS  ${friends.size}/${DungeonFriends.scanner.all.size} • ${DungeonFriends.scanner.online.size} online", CYAN))
        if (friends.isEmpty()) list.child(label("No matching friends.", MUTED))
        friends.forEach { friend ->
            list.child(card().apply {
                surface(surface().and(Surface { graphics, component ->
                    graphics.fill(component.x(), component.y(), component.x() + 2, component.y() + component.height(),
                        0xFF000000.toInt() or friend.activity.color)
                }))
                child(row().apply {
                    child(label("${if (friend.bestFriend) "§l" else ""}${friend.name}").horizontalSizing(Sizing.expand()))
                    child(label(friend.activity.label, friend.activity.color))
                })
                child(label(friend.location, MUTED).horizontalSizing(Sizing.fill()))
                val data = FriendWealthCache.get(friend.name)
                child(label(when (data?.hasProfile) {
                    true -> "SkyBlock profile${data.profile.takeIf { it.isNotEmpty() }?.let { ": $it" }.orEmpty()}"
                    false -> "No SkyBlock profile"
                    null -> if (!FriendWealthCache.available) "Profile lookup requires SkyBlockPv" else data?.status ?: "Checking SkyBlock profile..."
                }, MUTED).horizontalSizing(Sizing.fill()))
            })
        }
    }

    private fun lendingRows(list: FlowLayout) {
        val ledger = GearLending.ledger
        if (ledger == null) { list.child(label("Join a game to view this account's lending log.", MUTED)); return }
        ledger.error?.let { list.child(label(it, RED).horizontalSizing(Sizing.fill())) }
        val trades = ledger.trades.asReversed().filter { matchesFriendName(it.friend, search) &&
            (!outstanding || it.sent.any { item -> item.loan == LoanState.LENT }) }
        if (trades.isEmpty()) list.child(label(if (outstanding) "No outstanding loans match your search."
            else "Completed trades with friends appear here. Mark the items you lent after trading.", MUTED).horizontalSizing(Sizing.fill()))
        trades.take(historyLimit).forEach { trade ->
            list.child(card().apply {
                val date = DATE.format(Instant.ofEpochMilli(trade.time).atZone(ZoneId.systemDefault()))
                child(label("${trade.friend} • ${trade.profile ?: "Unknown profile"} • $date", CYAN).horizontalSizing(Sizing.fill()))
                trade.sent.forEachIndexed { index, item ->
                    if (outstanding && item.loan != LoanState.LENT) return@forEachIndexed
                    child(row().apply {
                        val text = "${item.count}× ${item.name}${when (item.loan) { LoanState.LENT -> " • Lent"; LoanState.RETURNED -> " • Returned"; else -> "" }}"
                        child(label(text, if (item.loan == LoanState.LENT) 0xE8BF71 else WHITE).horizontalSizing(Sizing.expand())
                            .tooltip(Component.literal("Sent to ${trade.friend}\n${item.id}${item.uuid?.let { "\n$it" }.orEmpty()}")))
                        child(button(when (item.loan) { LoanState.NONE -> "Mark as lent"; LoanState.LENT -> "Returned"; LoanState.RETURNED -> "Reopen loan" }, 90) {
                            ledger.mark(trade.id, index, if (item.loan == LoanState.LENT) LoanState.RETURNED else LoanState.LENT)
                            updateRows()
                        }.tooltip(Component.literal(if (item.loan == LoanState.LENT) "Mark these items as returned by ${trade.friend}"
                            else "Mark as lent to ${trade.friend}")))
                        if (item.loan != LoanState.NONE) child(button("Undo", 38) {
                            ledger.mark(trade.id, index, LoanState.NONE); updateRows()
                        }.tooltip(Component.literal("Remove the lending mark; keep the trade record")))
                    })
                }
                if (!outstanding) {
                    trade.sentCoins?.let { child(label("Sent: $it coins", MUTED)) }
                    val received = trade.received.map { "${it.count}× ${it.name}" } + listOfNotNull(trade.receivedCoins?.let { "$it coins" })
                    child(label("Received: ${received.joinToString().ifEmpty { "nothing" }}", MUTED).horizontalSizing(Sizing.fill()))
                }
            })
        }
        if (trades.size > historyLimit) list.child(button("Show older trades", 120) { historyLimit += 50; updateRows() })
    }

    private fun wealthRows(list: FlowLayout) {
        DungeonFriendsSettings.error?.let { list.child(label(it, RED).horizontalSizing(Sizing.fill())) }
        if (!FriendWealthCache.available) {
            list.child(label("Install SkyBlockPv to load public profiles and networth estimates.", MUTED).horizontalSizing(Sizing.fill())); return
        }
        if (!LocationAPI.onHypixel) { list.child(label("Join Hypixel to view this account's friends.", MUTED)); return }
        val friends = DungeonFriends.scanner.all.values.filter { matchesFriendName(it.name, search) && FriendWealthCache.get(it.name)?.hasProfile != false }
            .sortedWith(compareBy<OnlineDungeonFriend> { values(FriendWealthCache.get(it.name))[sort] == null }
                .thenBy { values(FriendWealthCache.get(it.name))[sort]?.let { value -> if (ascending) value else -value } ?: 0.0 }
                .thenBy { it.name.lowercase() })
        if (friends.isEmpty()) list.child(label("No matching SkyBlock friends. Clear search or click Refresh.", MUTED).horizontalSizing(Sizing.fill()))
        friends.forEachIndexed { index, friend ->
            val data = FriendWealthCache.get(friend.name)
            list.child(card().apply {
                child(label("${index + 1}. ${friend.name}${data?.profile?.takeIf { it.isNotEmpty() }?.let { " • $it" }.orEmpty()}", CYAN))
                child(label(friend.activity.label, friend.activity.color))
                child(row().apply {
                    gap(0)
                    listOf("Networth", "Purse", "Bank", "Wardrobe").zip(values(data)).forEach { (name, value) ->
                        child(UIContainers.verticalFlow(Sizing.fill(25), Sizing.content()).apply {
                            gap(3); child(label(name, MUTED)); child(label(value?.let(::condense) ?: "—"))
                            tooltip(Component.literal("$name: ${value?.let { "%,.0f coins".format(it) } ?: "unavailable"}" +
                                data?.let { "\nFetched ${DATE.format(Instant.ofEpochMilli(it.fetchedAt).atZone(ZoneId.systemDefault()))}" }.orEmpty()))
                        })
                    }
                })
                val status = if (FriendWealthCache.isLoading(friend.name)) "Loading public profile..." else data?.status ?: "Queued..."
                if (status.isNotEmpty()) child(label(status, MUTED).horizontalSizing(Sizing.fill()))
            })
        }
    }

    private fun values(data: FriendWealth?) = listOf(data?.networth, data?.purse, data?.bank, data?.wardrobe)
    private fun label(text: String, color: Int = WHITE) = UIComponents.label(Component.literal(text)).color(Color.ofRgb(color)).shadow(false)
    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply { gap(6); verticalAlignment(VerticalAlignment.CENTER) }
    private fun card() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(6); padding(Insets.of(6)); surface(HudTheme.panel(true))
    }
    private fun button(text: String, width: Int, action: () -> Unit) = UIComponents.button(Component.literal(text)) { action() }.apply {
        sizing(Sizing.fixed(width), Sizing.fixed(18)); textShadow(false)
        themed()
    }
    private fun rebuild() { uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount() }
    companion object {
        private val CYAN get() = HudTheme.ACCENT
        private const val WHITE = 0xEDF3F7
        private const val MUTED = 0x91A2AF
        private const val RED = 0xF18C8C
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    }
}
