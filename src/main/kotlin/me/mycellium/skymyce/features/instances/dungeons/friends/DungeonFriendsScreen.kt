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
import me.mycellium.skymyce.config.misc.PartyCommandsConfig
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor
import tech.thatgravyboat.skyblockapi.api.location.LocationAPI

class DungeonFriendsScreen : BaseOwoScreen<FlowLayout>() {
    private var floor = DungeonFloor.F1
    private var floorChosen = false
    private var sort = FriendSort.CATACOMBS
    private var filter = "Next needed"
    private var chosenClass: DungeonClass? = null
    private var settingsOpen = false
    private var editingFriend: String? = null
    private var templateDraft = DungeonFriendsSettings.messageTemplate
    private var classesDraft = emptySet<DungeonClass>()
    private lateinit var results: ScrollContainer<FlowLayout>
    private lateinit var partyStatus: LabelComponent
    private lateinit var apiStatus: LabelComponent
    private lateinit var listStatus: LabelComponent
    private lateinit var floorButton: ButtonComponent
    private lateinit var classButton: ButtonComponent
    private val actions = mutableListOf<ButtonComponent>()
    private var renderedState: List<Any?> = emptyList()
    private val panelWidth get() = minOf(660, width - 24)
    private val compact get() = panelWidth < 560

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)

    override fun build(root: FlowLayout) {
        root.surface(Surface.blur(3.0f, 10.0f).and(Surface.flat(0x66090D12)))
        root.alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        root.child(if (settingsOpen) settingsPanel() else friendsPanel())
        if (!settingsOpen) updateResults()
    }

    override fun tick() {
        super.tick()
        if (settingsOpen || !::results.isInitialized) return
        if (!floorChosen) {
            MC.instance.player?.let { player ->
                DungeonFriendStatsCache.get(player.name.string)?.highestFloor?.let { floor = it }
            }
        }
        val state = listOf(
            DungeonFriendStatsCache.version, DungeonFriends.scanner.online.toMap(), floor, sort, filter, chosenClass,
            DungeonFriends.neededClass, DungeonFriends.openClasses, DungeonFriendsSettings.secondaryClasses,
        )
        if (state != renderedState) {
            renderedState = state
            updateResults()
        }
        actions.forEach { it.active(DungeonFriends.canAct) }
        partyStatus.text(Component.literal(DungeonFriends.partyStatus + if (DungeonFriends.partyFull) "  Full" else ""))
        partyStatus.color(Color.ofRgb(if (DungeonFriends.partyFull) 0xF18C8C else CYAN))
        apiStatus.text(Component.literal(when {
            !LocationAPI.onHypixel -> "Join Hypixel to load player stats"
            PartyCommandsConfig.hypixelApiKey.isBlank() -> "Add your Hypixel API key to load PBs"
            else -> DungeonFriendStatsCache.status.ifEmpty { "SkyBlock stats up to date" }
        }))
        apiStatus.color(Color.ofRgb(if (PartyCommandsConfig.hypixelApiKey.isBlank()) 0xE6BD79 else MUTED))
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
            child(button("Sort: ${sort.label}", 110) { button ->
                menu(button) { dropdown ->
                    FriendSort.entries.forEach { value ->
                        dropdown.button(Component.literal(value.label)) {
                            sort = value
                            button.message = Component.literal("Sort: ${sort.label}")
                            updateResults()
                        }
                    }
                }
            })
            classButton = button("Next: Berserker", 116) { button ->
                menu(button) { dropdown ->
                    listOf("Next needed", "All needed", "All classes").forEach { value ->
                        dropdown.button(Component.literal(value)) {
                            filter = value
                            chosenClass = null
                            updateResults()
                        }
                    }
                    DungeonClass.entries.forEach { value ->
                        dropdown.button(Component.literal(value.displayName)) {
                            filter = "Selected"
                            chosenClass = value
                            updateResults()
                        }
                    }
                }
            }
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
                child(label("CATA", MUTED).horizontalSizing(Sizing.fixed(32)))
                displayedClasses.forEach { (_, heading) ->
                    child(label(heading, MUTED).horizontalSizing(Sizing.fixed(42)))
                }
                child(label("S+ PB", CYAN).horizontalSizing(Sizing.fixed(70)))
                child(label("ACTIONS", MUTED).horizontalSizing(Sizing.fixed(100)))
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

    private fun selectedClass(): DungeonClass? = chosenClass ?: DungeonFriends.neededClass

    private fun updateResults() {
        if (settingsOpen || !::results.isInitialized) return
        floorButton.message = Component.literal("§b${floor.name} v")
        classButton.message = Component.literal(when (filter) {
            "Selected" -> "Class: ${chosenClass?.displayName}"
            "Next needed" -> "Next: ${DungeonFriends.neededClass?.displayName ?: "None"}"
            else -> filter
        })
        val wanted = when (filter) {
            "Next needed" -> setOfNotNull(DungeonFriends.neededClass)
            "All needed" -> DungeonFriends.openClasses
            "Selected" -> setOfNotNull(chosenClass)
            else -> emptySet()
        }
        val stats = DungeonFriendStatsCache.stats
        val friends = DungeonFriends.scanner.online.values.filter { friend ->
            val data = stats[friend.name.lowercase()]
            (data?.eligible(floor) != false) && matchesFriendClass(
                data, DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty(), wanted,
            )
        }.sortedWith(friendComparator(stats, floor, selectedClass(), sort))
        listStatus.text(Component.literal("FRIENDS  ${friends.size}/${DungeonFriends.scanner.online.size}"))
        actions.clear()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(3)
            if (friends.isEmpty()) child(label("No matching friends. Try another floor or All classes.", MUTED)
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
            val availability = stats?.state?.takeIf { it != StatsState.AVAILABLE }?.label
                ?: if (stats == null) "Waiting for SkyBlock stats" else "SkyBlock profile stats"
            val identity = UIContainers.verticalFlow(Sizing.expand(), Sizing.content()).apply {
                gap(3)
                child(label(friend.name).horizontalSizing(Sizing.fill()))
                child(label(friend.badge, MUTED).horizontalSizing(Sizing.fill()))
                tooltip(Component.literal("${friend.location}\n$availability"))
            }
            child(row().apply {
                child(identity)
                if (!compact) statColumns(this, stats, false)
                child(row().apply {
                    horizontalSizing(Sizing.fixed(100))
                    horizontalAlignment(HorizontalAlignment.RIGHT)
                    gap(3)
                    val invite = button("/p", 24) { DungeonFriends.invite(friend) }.active(DungeonFriends.canAct)
                    invite.tooltip(Component.literal("Invite ${friend.name}; disabled when the party is full"))
                    val message = button("/msg", 34) { DungeonFriends.message(friend, floor, selectedClass()) }.active(DungeonFriends.canAct)
                    message.tooltip(Component.literal(lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, selectedClass(), floor)))
                    actions += invite
                    actions += message
                    child(invite)
                    child(message)
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
        child(label("PBs and class levels load from Hypixel's SkyBlock API. Add your API key once to load the player list.", MUTED)
            .horizontalSizing(Sizing.fill()))
        child(button("API setup", 80) { openApiSettings() })
        val feedback = label(DungeonFriendsSettings.error.orEmpty(), 0xF18C8C)
        child(feedback.horizontalSizing(Sizing.fill()))
        child(row().apply {
            gap(6)
            child(button("Save", 58) {
                val classes = DungeonFriendsSettings.secondaryClasses.toMutableMap()
                editingFriend?.lowercase()?.let { name ->
                    if (classesDraft.isEmpty()) classes.remove(name) else classes[name] = classesDraft
                }
                if (DungeonFriendsSettings.save(templateDraft, classes)) {
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
