package me.mycellium.skymyce.features.instances.dungeons.friends

import com.teamresourceful.resourcefulconfig.api.client.ResourcefulConfigScreen
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI

class DungeonFriendsScreen : BaseOwoScreen<FlowLayout>() {
    private var floor = DungeonFriends.partyFloor ?: DungeonFloor.F1
    private var floorChosen = DungeonFriends.partyFloor != null
    private var sort = FriendSort.CATACOMBS
    private var ascending = false
    private var missingClasses: Set<DungeonClass>? = null
    private var settingsOpen = false
    private var editingFriend: String? = null
    private var templateDraft = DungeonFriendsSettings.messageTemplate
    private var classesDraft = emptySet<DungeonClass>()
    private var availableDraft = emptySet<DungeonClass>()
    private var pbLimitDraft = ""
    private var seenPartyRevision = DungeonFriends.partyRevision
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var partyStatus: LabelComponent
    private lateinit var apiStatus: LabelComponent
    private lateinit var listStatus: LabelComponent
    private lateinit var floorButton: ButtonComponent
    private lateinit var classButton: ButtonComponent
    private lateinit var sortButton: ButtonComponent
    private val sortHeaders = mutableMapOf<FriendSort, Pair<ButtonComponent, String>>()
    private val actions = mutableListOf<ButtonComponent>()
    private val joinActions = mutableListOf<ButtonComponent>()
    private var renderedState: List<Any?> = emptyList()
    private val panelWidth get() = minOf(700, width - 24)
    private val compact get() = panelWidth < 600

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)

    override fun build(root: FlowLayout) {
        sortHeaders.clear()
        root.surface(Surface.blur(3.0f, 10.0f).and(Surface.flat(0x66090D12)))
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(if (settingsOpen) settingsPanel() else friendsPanel())
        if (!settingsOpen) updateResults()
    }

    override fun tick() {
        super.tick()
        if (settingsOpen || !::results.isInitialized) return
        if (seenPartyRevision != DungeonFriends.partyRevision) {
            seenPartyRevision = DungeonFriends.partyRevision
            missingClasses = null
            DungeonFriends.partyFloor?.let { floor = it; floorChosen = true }
        }
        if (!floorChosen) {
            MC.instance.player?.let { player ->
                DungeonFriendStatsCache.get(player.name.string)?.highestFloor?.let { floor = it }
            }
        }
        val state = listOf(
            DungeonFriendStatsCache.version, DungeonFriends.scanner.online.toMap(), floor, sort, ascending, wantedClasses(),
            DungeonFriendsSettings.secondaryClasses, DungeonFriends.replies.version,
        )
        if (state != renderedState) {
            renderedState = state
            updateResults()
        }
        actions.forEach { it.active(DungeonFriends.canAct) }
        joinActions.forEach { it.active(DungeonFriends.canJoin(floor)) }
        partyStatus.text(Component.literal(DungeonFriends.partyStatus + if (DungeonFriends.partyFull) "  Full" else ""))
        partyStatus.color(Color.ofRgb(if (DungeonFriends.partyFull) 0xF18C8C else CYAN))
        partyStatus.tooltip(Component.literal(DungeonFriendsSettings.availability.let {
            if (it.enabled) "Available for ${it.floor.name}: ${it.classes.joinToString { clazz -> clazz.displayName }}\nS+ PB at most ${formatDungeonTime(it.maxPbMillis)}"
            else "Auto join is off. Configure Available classes and an S+ PB limit."
        }))
        apiStatus.text(Component.literal(when {
            !LocationAPI.onHypixel -> "Join Hypixel to load player stats"
            DungeonFriends.joining.status.isNotEmpty() -> DungeonFriends.joining.status
            !DungeonFriendStatsCache.canFetch -> "Use SkyBlockPv / SkyBlocker or add an API key"
            else -> DungeonFriendStatsCache.status.ifEmpty { "Cached stats ready; refresh updates Cata > 40" }
        }))
        apiStatus.color(Color.ofRgb(if (!DungeonFriendStatsCache.canFetch) 0xE6BD79 else MUTED))
        listStatus.tooltip(Component.literal(DungeonFriends.scanner.status))
    }

    private fun label(text: String, color: Int = WHITE) = UIComponents.label(Component.literal(text))
        .color(Color.ofRgb(color)).shadow(false)

    private fun button(text: String, size: Int, onPress: (ButtonComponent) -> Unit) =
        UIComponents.button(Component.literal(text), onPress).apply {
            sizing(Sizing.fixed(size), Sizing.fixed(18))
            renderer(ButtonComponent.Renderer.flat(0xFF1D2933.toInt(), 0xFF304B5B.toInt(), 0xFF151C23.toInt()))
            textShadow(false)
        }

    private fun row() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
        verticalAlignment(VerticalAlignment.CENTER)
    }

    private fun separator() = UIContainers.horizontalFlow(Sizing.fill(), Sizing.fixed(1))
        .surface(Surface.flat(0xFF2C3944.toInt()))

    private fun panel() = UIContainers.verticalFlow(Sizing.fixed(panelWidth), Sizing.fixed(minOf(430, height - 24))).apply {
        surface(Surface.flat(0xF510171E.toInt()).and(Surface.outline(0xFF2C3944.toInt())))
        padding(Insets.of(12))
        gap(8)
    }

    private fun title(text: String) = row().apply {
        gap(8)
        child(UIContainers.verticalFlow(Sizing.fixed(3), Sizing.fixed(18)).surface(Surface.flat(0xFF67CCF2.toInt())))
        child(label("§l$text").horizontalSizing(Sizing.expand()))
    }

    private fun friendsPanel(): FlowLayout = panel().apply {
        child(title("Dungeon Friends").apply {
            floorButton = button(floor.name + " v", 42) { button ->
                menu(button) { dropdown ->
                    FRIEND_FLOORS.forEach { value ->
                        dropdown.button(Component.literal(value.name)) {
                            floor = value
                            floorChosen = true
                            updateResults()
                        }
                    }
                }
            }
            floorButton.tooltip(Component.literal("Dungeon floor"))
            child(floorButton)
        })
        child(row().apply {
            gap(6)
            sortButton = button("Sort: ${sort.label}", 116) { button ->
                menu(button) { dropdown ->
                    FriendSort.entries.forEach { value ->
                        dropdown.button(Component.literal(value.label)) { changeSort(value) }
                    }
                }
            }
            sortButton.tooltip(Component.literal("Choose Cata, a class, or S+ PB. Select it again to reverse the order."))
            child(sortButton)
            classButton = button("Missing", 104) { button ->
                menu(button) { dropdown ->
                    DungeonClass.entries.forEach { value ->
                        dropdown.checkbox(Component.literal(value.displayName), value in wantedClasses()) { checked ->
                            missingClasses = if (checked) wantedClasses() + value else wantedClasses() - value
                            updateResults()
                        }
                    }
                    dropdown.button(Component.literal("Use party classes")) { missingClasses = null; updateResults() }
                    dropdown.button(Component.literal("Clear (show all)")) { missingClasses = emptySet(); updateResults() }
                }
            }
            classButton.tooltip(Component.literal("Check the classes you need. Clear all boxes to show every class."))
            child(classButton)
            if (!compact) {
                child(UIContainers.horizontalFlow(Sizing.expand(), Sizing.fixed(1)))
                toolbarActions(this)
            }
        })
        child(row().apply {
            gap(6)
            partyStatus = label(DungeonFriends.partyStatus, CYAN)
            child(partyStatus.horizontalSizing(Sizing.expand()))
            if (compact) toolbarActions(this)
            else child(label("PARTY FINDER", MUTED))
        })
        child(separator())
        child(row().apply {
            listStatus = label("ONLINE FRIENDS", CYAN)
            child(listStatus.horizontalSizing(Sizing.expand()))
            if (!compact) {
                child(sortHeader("CATA", 32, FriendSort.CATACOMBS))
                displayedClasses.forEach { (clazz, heading) ->
                    child(sortHeader(heading, 42, FriendSort.entries.first { it.dungeonClass == clazz }))
                }
                child(sortHeader("S+ PB", 70, FriendSort.PB))
                child(label("ACTIONS", MUTED).horizontalSizing(Sizing.fixed(140)))
            }
            padding(Insets.horizontal(6))
            // Match the list's scrollbar gutter so headers align with row values.
            margins(Insets.right(4))
        })
        results = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
            scrollbarThiccness(2)
            padding(Insets.right(4))
        }
        child(results)
        child(separator())
        child(row().apply {
            gap(8)
            apiStatus = label("Loading SkyBlock stats...", MUTED)
            child(apiStatus.horizontalSizing(Sizing.expand()))
            child(button("API setup", 62) { openApiSettings() })
        })
    }

    private fun toolbarActions(row: FlowLayout) {
        row.child(button(if (DungeonFriendsSettings.availability.enabled) "§aAvailable" else "Available", 76) { openSettings(null) })
        row.child(button("Refresh", 50) {
            DungeonFriends.scanner.refresh(DungeonFriends.now())
            DungeonFriendStatsCache.refresh()
        })
        row.child(button("Settings", 54) { openSettings(null) })
    }

    private fun openApiSettings() {
        MC.instance.setScreen(ResourcefulConfigScreen.make(SkyMyce.config)
            .withParent(this).withQuery("Hypixel API Key").build())
    }

    private fun wantedClasses(): Set<DungeonClass> = missingClasses ?: DungeonFriends.openClasses

    private fun messageClass(friend: OnlineDungeonFriend): DungeonClass? {
        val wanted = wantedClasses()
        val stats = DungeonFriendStatsCache.get(friend.name)
        return stats?.bestClass?.takeIf { it in wanted }
            ?: DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()]?.firstOrNull { it in wanted }
            ?: wanted.firstOrNull()
    }

    private fun changeSort(value: FriendSort) {
        ascending = if (sort == value) !ascending else value == FriendSort.PB
        sort = value
        updateResults()
    }

    private fun sortHeader(text: String, width: Int, value: FriendSort) = button(text, width) { changeSort(value) }.apply {
        sortHeaders[value] = this to text
        tooltip(Component.literal("Sort by ${value.label}; click again to reverse"))
    }

    private fun updateResults() {
        if (settingsOpen || !::results.isInitialized) return
        floorButton.message = Component.literal("§b${floor.name} v")
        val wanted = wantedClasses()
        classButton.message = Component.literal("Missing (${wanted.size})")
        sortButton.message = Component.literal("Sort: ${sort.label} ${if (ascending) "↑" else "↓"}")
        sortHeaders.forEach { (value, header) ->
            header.first.message = Component.literal("${if (sort == value) "§b" else "§7"}${header.second}")
        }
        val stats = DungeonFriendStatsCache.stats
        val friends = DungeonFriends.scanner.online.values.filter { friend ->
            val data = stats[friend.name.lowercase()]
            (data?.eligible(floor) != false) && matchesFriendClass(
                data, DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty(), wanted,
            )
        }.sortedWith(friendComparator(stats, floor, sort, ascending))
        listStatus.text(Component.literal("FRIENDS  ${friends.size}/${DungeonFriends.scanner.online.size}"))
        actions.clear()
        joinActions.clear()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(3)
            if (friends.isEmpty()) child(label("No matching friends. Try another floor or clear Missing.", MUTED)
                .horizontalSizing(Sizing.fill()).margins(Insets.of(8)))
            friends.forEach { friend -> child(friendRow(friend, stats[friend.name.lowercase()])) }
        })
    }

    private fun friendRow(friend: OnlineDungeonFriend, stats: DungeonFriendStats?): FlowLayout =
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            padding(Insets.of(6))
            gap(5)
            surface(Surface.flat(0xFF17212A.toInt()))
            val secondary = DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty()
            val reply = DungeonFriends.replies.get(friend.name)
            val accepted = reply?.status == LfgReplyStatus.ACCEPTED
            val availability = stats?.state?.takeIf { it != StatsState.AVAILABLE }?.label
                ?: if (stats == null) "Waiting for SkyBlock stats" else "SkyBlock profile stats"
            val identity = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
                gap(3)
                child(label(friend.name).horizontalSizing(Sizing.fill()))
                child(label(friend.badge, MUTED).horizontalSizing(Sizing.fill()))
                if (reply != null) child(label(reply.status.label).horizontalSizing(Sizing.fill()))
                tooltip(Component.literal("${friend.location}\n$availability${reply?.text?.takeIf { it.isNotEmpty() }?.let { "\nReply: $it" }.orEmpty()}"))
            }
            child(row().apply {
                child(identity)
                if (!compact) statColumns(this, stats, false)
                child(row().apply {
                    horizontalSizing(Sizing.fixed(140))
                    horizontalAlignment(HorizontalAlignment.RIGHT)
                    gap(3)
                    val invite = button("/p", 24) { DungeonFriends.invite(friend) }.active(DungeonFriends.canAct)
                    if (accepted) invite.renderer(ButtonComponent.Renderer.flat(0xFF246545.toInt(), 0xFF34865C.toInt(), 0xFF151C23.toInt()))
                    invite.tooltip(Component.literal("Invite ${friend.name}; disabled when the party is full"))
                    val message = button("/msg", 34) { DungeonFriends.message(friend, floor, messageClass(friend)) }.active(DungeonFriends.canAct)
                    message.tooltip(Component.literal(lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, messageClass(friend), floor)))
                    actions += invite
                    actions += message
                    child(invite)
                    child(message)
                    val join = button("Join", 34) { DungeonFriends.join(friend, floor) }.active(DungeonFriends.canJoin(floor))
                    join.tooltip(Component.literal("Ask ${friend.name} for an invite, then auto join if their S+ PB qualifies.\nRequires Available classes and a PB limit for ${floor.name}; you must be solo in SkyBlock."))
                    joinActions += join
                    child(join)
                    child(button("Edit", 30) { openSettings(friend.name) }.tooltip(Component.literal("Edit secondary classes")))
                })
            })
            if (compact) {
                child(row().apply {
                    child(label("Cata ${stats?.catacombs ?: "—"}", MUTED).horizontalSizing(Sizing.expand()))
                    child(label("${floor.name} S+  ${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—"}", CYAN))
                })
                child(row().apply { statColumns(this, stats, true) })
            }
            if (secondary.isNotEmpty()) child(label("Also: ${secondary.joinToString { it.displayName }}", MUTED)
                .horizontalSizing(Sizing.fill()))
        }

    private fun statColumns(row: FlowLayout, stats: DungeonFriendStats?, titles: Boolean) {
        val best = stats?.bestClass
        if (!titles) row.child(label(stats?.catacombs?.toString() ?: "—").horizontalSizing(Sizing.fixed(32)))
        displayedClasses.forEach { (clazz, heading) ->
            row.child(UIContainers.verticalFlow(if (titles) Sizing.fill(20) else Sizing.fixed(42), Sizing.content()).apply {
                gap(3)
                if (titles) child(label(heading, MUTED))
                child(label(stats?.classes?.get(clazz)?.toString() ?: "—", if (clazz == best) CYAN else WHITE))
                tooltip(Component.literal("${clazz.displayName}${if (clazz == best) " (highest class)" else ""}\n${stats?.state?.label ?: "Waiting for SkyBlock stats"}"))
            })
        }
        if (!titles) row.child(label(stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "—", CYAN)
            .horizontalSizing(Sizing.fixed(70))
            .tooltip(Component.literal("${floor.name} fastest S+ completion\n${stats?.sPlusTimes?.get(floor)?.let(::formatDungeonTime) ?: "No published S+ time"}")))
    }

    private fun openSettings(friend: String?) {
        editingFriend = friend
        templateDraft = DungeonFriendsSettings.messageTemplate
        classesDraft = DungeonFriendsSettings.secondaryClasses[friend?.lowercase()].orEmpty()
        availableDraft = DungeonFriendsSettings.availability.classes
        pbLimitDraft = DungeonFriendsSettings.availability.maxPbMillis?.let(::formatDungeonTime).orEmpty()
        settingsOpen = true
        rebuild()
    }

    private fun settingsPanel(): FlowLayout = panel().apply {
        child(title(editingFriend?.let { "Classes: $it" } ?: "LFG Settings"))
        child(separator())
        child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), settingsContent()).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(CYAN)))
            scrollbarThiccness(2)
            padding(Insets.right(4))
        })
    }

    private fun settingsContent(): FlowLayout = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(8)
        if (editingFriend == null) {
            child(label("AVAILABLE FOR ${floor.name}", CYAN))
            child(label("Check the classes you can play. Eligible party invitations will be accepted while you are solo in SkyBlock. Uncheck all to stop auto joining.", MUTED)
                .horizontalSizing(Sizing.fill()))
            DungeonClass.entries.forEach { clazz ->
                child(button("${if (clazz in availableDraft) "[x]" else "[ ]"} ${clazz.displayName}", 116) { button ->
                    availableDraft = if (clazz in availableDraft) availableDraft - clazz else availableDraft + clazz
                    button.message = Component.literal("${if (clazz in availableDraft) "[x]" else "[ ]"} ${clazz.displayName}")
                })
            }
            child(label("S+ PB LIMIT (m:ss or m:ss.sss)", CYAN))
            child(label("Inviters and incoming Join requests must have a verified ${floor.name} S+ PB at or faster than this time. Hidden or unknown PBs never qualify.", MUTED)
                .horizontalSizing(Sizing.fill()))
            child(UIComponents.textBox(Sizing.fill()).apply {
                setMaxLength(10)
                text(pbLimitDraft)
                setHint(Component.literal("Example: 5:30.000"))
                onChanged().subscribe { pbLimitDraft = it }
            })
            child(separator())
        }
        child(label("MESSAGE TEMPLATE", CYAN))
        child(label("Use {name}, {class} and {floor}.", MUTED).horizontalSizing(Sizing.fill()))
        child(UIComponents.textBox(Sizing.fill()).apply {
            setMaxLength(220)
            text(templateDraft)
            setHint(Component.literal("Message sent by the /msg button"))
            onChanged().subscribe { templateDraft = it }
        })
        editingFriend?.let { name ->
            child(label("SECONDARY CLASSES", CYAN))
            val stats = DungeonFriendStatsCache.get(name)
            DungeonClass.entries.forEach { clazz ->
                child(row().apply {
                    gap(8)
                    child(button("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}", 104) { button ->
                        classesDraft = if (clazz in classesDraft) classesDraft - clazz else classesDraft + clazz
                        button.message = Component.literal("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}")
                    })
                    child(label("Level ${stats?.classes?.get(clazz) ?: "—"}", CYAN))
                })
            }
        }
        child(label("PERSONAL BESTS", CYAN))
        child(label("Uses installed SkyBlocker / SkyBlockPv, with your Hypixel API key as a fallback. Stats are saved between sessions; repeat refreshes only update Cata > 40.", MUTED)
            .horizontalSizing(Sizing.fill()))
        child(button("API setup", 80) { openApiSettings() })
        val feedback = label(DungeonFriendsSettings.error.orEmpty(), 0xF18C8C)
        child(feedback.horizontalSizing(Sizing.fill()))
        child(row().apply {
            gap(6)
            child(button("Save", 58) {
                val available = if (editingFriend == null) DungeonAvailability(floor, availableDraft, parsePbLimit(pbLimitDraft))
                    else DungeonFriendsSettings.availability
                if (editingFriend == null && ((pbLimitDraft.isNotBlank() && available.maxPbMillis == null) ||
                    (available.classes.isNotEmpty() && !available.enabled))) {
                    feedback.text(Component.literal("Enter a positive PB limit such as 5:30 before enabling classes."))
                    return@button
                }
                val classes = DungeonFriendsSettings.secondaryClasses.toMutableMap()
                editingFriend?.lowercase()?.let { name ->
                    if (classesDraft.isEmpty()) classes.remove(name) else classes[name] = classesDraft
                }
                val changedAvailability = available != DungeonFriendsSettings.availability
                if (DungeonFriendsSettings.save(templateDraft, classes, available)) {
                    if (changedAvailability) DungeonFriends.joining.clear()
                    settingsOpen = false
                    rebuild()
                } else feedback.text(Component.literal(DungeonFriendsSettings.error ?: "Use a nonempty message (up to 220 characters)."))
            })
            child(button("Cancel", 58) {
                settingsOpen = false
                rebuild()
            })
        })
    }

    private fun menu(button: ButtonComponent, entries: (DropdownComponent) -> Unit) {
        DropdownComponent.openContextMenu(this, uiAdapter.rootComponent, { root, dropdown -> root.child(dropdown) },
            button.x.toDouble(), (button.y + button.height).toDouble(), entries)
    }

    private fun rebuild() {
        uiAdapter.rootComponent.clearChildren()
        build(uiAdapter.rootComponent)
        uiAdapter.inflateAndMount()
    }

    companion object {
        private const val CYAN = 0x67CCF2
        private const val WHITE = 0xEDF3F7
        private const val MUTED = 0x91A2AF
        private val displayedClasses = listOf(
            DungeonClass.HEALER to "HEAL", DungeonClass.MAGE to "MAGE", DungeonClass.BERSERKER to "BERS",
            DungeonClass.ARCHER to "ARCH", DungeonClass.TANK to "TANK",
        )
    }
}
