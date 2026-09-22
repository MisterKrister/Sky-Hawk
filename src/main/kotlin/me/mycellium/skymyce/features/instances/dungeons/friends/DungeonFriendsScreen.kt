package me.mycellium.skymyce.features.instances.dungeons.friends

import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.DropdownComponent
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.ScrollContainer
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.config.instances.dungeons.DungeonFriendsSettings
import me.mycellium.skymyce.utils.MC
import net.minecraft.network.chat.Component
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonClass
import tech.thatgravyboat.skyblockapi.api.area.dungeon.DungeonFloor

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
    private lateinit var status: LabelComponent
    private lateinit var floorButton: ButtonComponent
    private lateinit var classButton: ButtonComponent
    private val actions = mutableListOf<ButtonComponent>()
    private var renderedState: List<Any?> = emptyList()

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)

    override fun build(root: FlowLayout) {
        root.surface(Surface.blur(3.0f, 10.0f))
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
        status.text(Component.literal(buildString {
            append(if (DungeonFriends.partyFull) "§c" else "§7")
            append(DungeonFriends.partyStatus)
            append(" | ${DungeonFriends.scanner.status}")
            if (DungeonFriendStatsCache.status.isNotEmpty()) append("\n§7${DungeonFriendStatsCache.status}")
        }))
    }

    private fun panel() = UIContainers.verticalFlow(Sizing.fill(94), Sizing.fill(90)).apply {
        surface(Surface.DARK_PANEL)
        padding(Insets.of(8))
        gap(5)
    }

    private fun friendsPanel(): FlowLayout = panel().apply {
        child(UIComponents.label(Component.literal("§b§lDungeon Friends & LFG")))
        child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(4)
            floorButton = UIComponents.button(Component.literal("Floor: ${floor.name}")) { button ->
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
            child(floorButton)
            child(UIComponents.button(Component.literal("Sort: ${sort.label}")) { button ->
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
            child(UIComponents.button(Component.literal("Refresh")) { DungeonFriends.scanner.refresh(DungeonFriends.now()) })
            child(UIComponents.button(Component.literal("Settings")) { openSettings(null) })
        })
        child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            classButton = UIComponents.button(Component.literal("Classes")) { button ->
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
        })
        status = UIComponents.label(Component.literal("Checking friends and party..."))
        status.horizontalSizing(Sizing.fill())
        child(status)
        results = UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), UIContainers.verticalFlow(Sizing.fill(), Sizing.content())).apply {
            surface(Surface.PANEL_INSET)
            padding(Insets.of(4))
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(0x55FFFF)))
            scrollbarThiccness(2)
        }
        child(results)
    }

    private fun selectedClass(): DungeonClass? = chosenClass ?: DungeonFriends.neededClass

    private fun updateResults() {
        if (settingsOpen || !::results.isInitialized) return
        floorButton.message = Component.literal("Floor: ${floor.name}")
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
        actions.clear()
        results.child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(5)
            if (friends.isEmpty()) child(UIComponents.label(Component.literal("§7No online friends match this floor and class.")))
            friends.forEach { friend -> child(friendRow(friend, stats[friend.name.lowercase()])) }
        })
    }

    private fun friendRow(friend: OnlineDungeonFriend, stats: DungeonFriendStats?): FlowLayout =
        UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
            padding(Insets.of(4))
            gap(3)
            surface(Surface.flat(0xAA222222.toInt()))
            val best = stats?.bestClass
            val bestText = best?.let { "${it.displayName} ${stats.classes[it]}" } ?: "Class: Unknown"
            val secondary = DungeonFriendsSettings.secondaryClasses[friend.name.lowercase()].orEmpty()
            child(UIComponents.label(Component.literal("§f${friend.name}  ${friend.badge}  §7Cata: ${stats?.catacombs ?: "Unknown"} | $bestText"))
                .horizontalSizing(Sizing.fill())
                .tooltip(Component.literal(friend.location)))
            val pb = stats?.completionTimes?.get(floor)
            val sPlus = stats?.sPlusTimes?.get(floor)
            child(UIComponents.label(Component.literal("§7${floor.name} PB: ${formatDungeonTime(pb)} | S+: ${formatDungeonTime(sPlus)}"))
                .horizontalSizing(Sizing.fill()))
            val extra = secondary.joinToString { it.displayName }.ifEmpty { "None" }
            val availability = stats?.state?.takeIf { it != StatsState.AVAILABLE }?.label
                ?: if (stats == null) "Stats pending / unknown" else null
            child(UIComponents.label(Component.literal("§7Secondary: $extra${availability?.let { " | $it" }.orEmpty()}"))
                .horizontalSizing(Sizing.fill()))
            child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(4)
                val invite = UIComponents.button(Component.literal("/p")) { DungeonFriends.invite(friend) }
                    .active(DungeonFriends.canAct)
                invite.tooltip(Component.literal("Invite ${friend.name}; disabled when the party is full"))
                val message = UIComponents.button(Component.literal("/msg")) { DungeonFriends.message(friend, floor, selectedClass()) }
                    .active(DungeonFriends.canAct)
                message.tooltip(Component.literal(lfgMessage(DungeonFriendsSettings.messageTemplate, friend.name, selectedClass(), floor)))
                actions += invite
                actions += message
                child(invite)
                child(message)
                child(UIComponents.button(Component.literal("Edit classes")) { openSettings(friend.name) })
            })
        }

    private fun openSettings(friend: String?) {
        editingFriend = friend
        templateDraft = DungeonFriendsSettings.messageTemplate
        classesDraft = DungeonFriendsSettings.secondaryClasses[friend?.lowercase()].orEmpty()
        settingsOpen = true
        rebuild()
    }

    private fun settingsPanel(): FlowLayout = panel().apply {
        val content = settingsContent()
        child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), content).apply {
            scrollbar(ScrollContainer.Scrollbar.flat(Color.ofRgb(0x55FFFF)))
            scrollbarThiccness(2)
        })
    }

    private fun settingsContent(): FlowLayout = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
        gap(5)
        child(UIComponents.label(Component.literal("§b§lLFG Settings")))
        child(UIComponents.label(Component.literal("Message template: {name}, {class}, {floor}")))
        child(UIComponents.textBox(Sizing.fill()).apply {
            setMaxLength(220)
            text(templateDraft)
            setHint(Component.literal("Message sent by the /msg button"))
            onChanged().subscribe { templateDraft = it }
        })
        editingFriend?.let { name ->
            child(UIComponents.label(Component.literal("§7Secondary classes for $name:")))
            child(UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).apply {
                gap(2)
                DungeonClass.entries.forEach { clazz ->
                    child(UIComponents.button(Component.literal("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}")) { button ->
                        classesDraft = if (clazz in classesDraft) classesDraft - clazz else classesDraft + clazz
                        button.message = Component.literal("${if (clazz in classesDraft) "[x]" else "[ ]"} ${clazz.displayName}")
                    })
                }
            })
        }
        val feedback = UIComponents.label(Component.literal(DungeonFriendsSettings.error.orEmpty()))
        feedback.horizontalSizing(Sizing.fill())
        child(feedback)
        child(UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
            gap(4)
            child(UIComponents.button(Component.literal("Save")) {
                val classes = DungeonFriendsSettings.secondaryClasses.toMutableMap()
                editingFriend?.lowercase()?.let { name ->
                    if (classesDraft.isEmpty()) classes.remove(name) else classes[name] = classesDraft
                }
                if (DungeonFriendsSettings.save(templateDraft, classes)) {
                    settingsOpen = false
                    rebuild()
                } else feedback.text(Component.literal("§c${DungeonFriendsSettings.error ?: "Could not save. Use a nonempty message (up to 220 characters)."}"))
            })
            child(UIComponents.button(Component.literal("Cancel")) {
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
}
