package me.mycellium.skymyce.config

import com.mojang.blaze3d.platform.InputConstants
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfigButton
import com.teamresourceful.resourcefulconfig.api.types.elements.ResourcefulConfigEntryElement
import com.teamresourceful.resourcefulconfig.api.types.entries.ResourcefulConfigValueEntry
import com.teamresourceful.resourcefulconfig.api.types.options.EntryType
import com.teamresourceful.resourcefulconfig.api.types.options.Option
import io.wispforest.owo.ui.base.BaseOwoScreen
import io.wispforest.owo.ui.component.*
import io.wispforest.owo.ui.container.*
import io.wispforest.owo.ui.core.*
import me.mycellium.skymyce.SkyMyce
import me.mycellium.skymyce.commands.SkyMyceCommands
import me.mycellium.skymyce.config.instances.dungeons.PartyFinder
import me.mycellium.skymyce.features.digest.DailyDigest
import me.mycellium.skymyce.hud.*
import me.mycellium.skymyce.hud.widget.WidgetEditorScreen
import me.mycellium.skymyce.features.instances.dungeons.friends.FriendsSocialScreen
import me.mycellium.skymyce.utils.MC
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import tech.thatgravyboat.skyblockapi.utils.Scheduling
import kotlin.math.roundToInt
import kotlin.time.Duration

enum class SettingsTab(val title: String) {
    GENERAL("General"), PARTY("Party Finder"), COMBAT("Dungeons & Combat"), SOCIAL("Social & Sharing"), THEME("Theme & Appearance")
}

/** Navigation only: the persisted Resourceful Config paths and existing consumers stay intact. */
fun settingsTab(path: String): SettingsTab = when {
    path.startsWith("Theme & Appearance/") -> SettingsTab.THEME
    path.startsWith("Party Finder/") -> SettingsTab.PARTY
    path.contains("/Daily Digest/") && path.substringAfterLast('/') in setOf("receiveRng", "shareRng", "publicRngName", "retainedRngEvents") -> SettingsTab.SOCIAL
    path.contains("Dungeon Friends/") || path.contains("Party Commands/") || path.startsWith("Cosmetics/") -> SettingsTab.SOCIAL
    path.startsWith("Dungeons/") || path.startsWith("Instances/") || path.contains("Loadout Keybinds/") ||
        path.contains("Wardrobe Keybinds/") || path.contains("Wardrobe/") || path.substringAfterLast('/').startsWith("displayAbility") -> SettingsTab.COMBAT
    else -> SettingsTab.GENERAL
}

data class SettingRow(val path: String, val element: ResourcefulConfigEntryElement) {
    val entry get() = element.entry() as ResourcefulConfigValueEntry
    val title get() = entry.options().title().toLocalizedString().ifBlank { element.id() }
    val tip get() = entry.options().comment().toLocalizedString().ifBlank {
        when {
            entry.options().hasOption(Option.KEYBIND) -> "Choose the keyboard key for $title. Escape cancels; Backspace clears it."
            element.id() == "enabled" -> "Enable or disable ${path.substringBeforeLast('/').substringAfterLast('/')} features."
            else -> "Configure ${title.lowercase()} for ${path.substringBeforeLast('/').ifEmpty { "Sky-Hawk" }}."
        }
    }
}

fun settingRows(config: ResourcefulConfig, prefix: String = ""): List<SettingRow> = buildList {
    config.elements().filterIsInstance<ResourcefulConfigEntryElement>().filter { !it.isHidden && it.entry() is ResourcefulConfigValueEntry }
        .forEach { add(SettingRow(prefix + it.id(), it)) }
    config.categories().forEach { (id, child) -> addAll(settingRows(child, "$prefix$id/")) }
}

fun parseThemeHex(value: String): Int? = value.removePrefix("#").takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.toInt(16)
internal fun settingsRow(): FlowLayout = UIContainers.horizontalFlow(Sizing.fill(), Sizing.content()).apply {
    gap(5); verticalAlignment(VerticalAlignment.CENTER)
}

class SettingsScreen(private val parent: Screen? = null, private var tab: SettingsTab = SettingsTab.GENERAL) :
    BaseOwoScreen<FlowLayout>(Component.literal("Sky-Hawk Settings")) {
    private val rows by lazy { settingRows(SkyMyce.config) }
    private var keyCapture: Pair<SettingRow, ButtonComponent>? = null
    private var changed = false
    private var query = ""
    private var builtWidth = 0
    private var builtHeight = 0
    private lateinit var body: FlowLayout
    private lateinit var heading: LabelComponent
    private var selectedTabButton: ButtonComponent? = null
    private val expanded = mutableSetOf<String>()

    override fun createAdapter(): OwoUIAdapter<FlowLayout> = OwoUIAdapter.create(this, UIContainers::verticalFlow)
    override fun isPauseScreen() = false
    override fun init() {
        SkyMyceCommands.syncSettingsKey()
        super.init()
        if (builtWidth != width || builtHeight != height) rebuild()
    }
    override fun build(root: FlowLayout) {
        builtWidth = width; builtHeight = height
        root.surface(HudTheme.backdrop).alignment(HorizontalAlignment.CENTER, VerticalAlignment.CENTER)
        val panelWidth = (width - 20).coerceIn(1, 800)
        root.child(column().apply {
            sizing(Sizing.fixed(panelWidth), Sizing.fixed((height - 20).coerceIn(1, 520)))
            padding(Insets.of(10)); surface(HudTheme.panel())
            child(settingsRow().apply {
                heading = label("SKY-HAWK  /  SETTINGS", HudTheme.ACCENT)
                child(heading.horizontalSizing(Sizing.expand()))
                child(button("Open…", 55, "Open a Sky-Hawk feature from one menu") { anchor -> openActions(anchor) })
                child(button("Done", 48, "Save settings and return") { onClose() })
            })
            // Two rows at small GUI sizes; long category names are never truncated.
            val perRow = when { panelWidth < 360 -> 2; panelWidth < 620 -> 3; else -> 5 }
            SettingsTab.entries.chunked(perRow).forEach { tabs -> child(settingsRow().apply {
                tabs.forEach { choice -> child(button(choice.title, ((panelWidth - 20 - 5 * (perRow - 1)) / perRow),
                    "Show ${choice.title.lowercase()} settings") { tab = choice; keyCapture = null; rebuild() }.apply {
                    if (choice == tab) { selectedTabButton = this; message = Component.literal(choice.title).withColor(HudTheme.ACCENT) }
                }) }
            }) }
            child(UIComponents.textBox(Sizing.fill()).apply {
                setMaxLength(80); text(query); setHint(Component.literal("Search this category…"))
                tip("Search setting names and descriptions in the selected category")
                onChanged().subscribe { query = it; populate() }
            })
            body = column()
            child(UIContainers.verticalScroll(Sizing.fill(), Sizing.expand(), body).apply { scrollbarThiccness(2); scrollStep(20) })
            child(label("Changes apply immediately • saved on close • Tab to navigate", HudTheme.MUTED))
        })
        populate()
    }

    private fun populate() {
        body.clearChildren()
        if (tab == SettingsTab.THEME) {
            body.child(label("Preset → global overrides → optional widget overrides", HudTheme.MUTED))
            body.child(button("Customize shared theme", 190, "Presets, colors, opacity, spacing, preview and JSON import/export") {
                MC.instance.setScreen(ThemeEditorScreen(this))
            })
            body.child(button("Per-widget themes", 190, "Open the HUD editor and right-click a widget to customize or reset its inherited theme") {
                MC.instance.setScreen(WidgetEditorScreen)
            })
            body.child(label("Theme changes preserve HUD positions, anchors and scale. Reset overrides to make them inherit again.", HudTheme.MUTED))
            return
        }
        val visible = rows.filter { settingsTab(it.path) == tab && (query.isBlank() || "${it.title} ${it.tip}".contains(query, true)) }
        if (visible.isEmpty()) { body.child(label("No matching settings", HudTheme.MUTED)); return }
        visible.groupBy { it.path.substringBeforeLast('/', "Basics").substringAfterLast('/') }.forEach { (group, values) ->
            val id = "${tab.name}/$group"
            val essential = values.filter { it.element.id() in setOf("openConfigKey", "enabled", "automatic", "news", "matchClass", "matchPb", "receiveRng", "shareRng", "primary", "secondary", "opacity") }
            body.child(column().apply {
                padding(Insets.of(8)); surface(HudTheme.panel(true))
                child(label(group, HudTheme.SECONDARY))
                val main = if (query.isNotBlank()) values else essential.ifEmpty { values.take(2) }
                main.forEach { child(editor(it)) }
                val advanced = values - main.toSet()
                if (advanced.isNotEmpty()) child(UIContainers.collapsible(Sizing.fill(), Sizing.content(),
                    Component.literal("More options (${advanced.size})").withColor(HudTheme.TEXT), id in expanded).apply {
                    tip("Expand additional $group settings")
                    onToggled().subscribe { if (it) expanded.add(id) else expanded.remove(id) }
                    advanced.forEach { child(editor(it)) }
                })
            })
        }
    }

    private fun editor(setting: SettingRow): FlowLayout = column().apply {
        gap(4); padding(Insets.of(3)); tip(setting.tip)
        val entry = setting.entry
        if (entry.type() == EntryType.BOOLEAN) {
            child(settingsRow().apply {
                child(label(setting.title).horizontalSizing(Sizing.expand()))
                child(button(if (entry.boolean) "On" else "Off", 65, setting.tip) { b ->
                    if (entry.setBoolean(!entry.boolean)) edited()
                    b.message = Component.literal(if (entry.boolean) "On" else "Off")
                })
            })
            return@apply
        }
        child(label(setting.title))
        when {
            setting.path in setOf("Theme & Appearance/primary", "Theme & Appearance/secondary") -> child(colorEditor(setting))
            entry.isArray -> child(UIContainers.collapsible(Sizing.fill(), Sizing.content(), Component.literal("Choose options (${entry.array.size} selected)").withColor(HudTheme.TEXT), false).apply {
                tip(setting.tip)
                entry.objectType().enumConstants?.forEach { option ->
                    child(button(option.toString(), 140, setting.tip) { button ->
                        val values = entry.array.toMutableList()
                        if (!values.remove(option)) values.add(option)
                        if (entry.setArray(values.toTypedArray())) edited()
                        button.message = Component.literal("${if (option in entry.array) "✓ " else ""}$option")
                        (titleLayout().children().first() as LabelComponent).text(Component.literal("Choose options (${entry.array.size} selected)").withStyle(net.minecraft.ChatFormatting.UNDERLINE))
                    }.apply { message = Component.literal("${if (option in entry.array) "✓ " else ""}$option") })
                }
            })
            entry.options().hasOption(Option.KEYBIND) -> child(button(keyName(entry.int), 130, setting.tip) { b ->
                keyCapture?.let { it.second.message = Component.literal(keyName(it.first.entry.int)) }
                keyCapture = setting to b; b.message = Component.literal("Press a key…")
            })
            entry.type() == EntryType.ENUM -> child(button(entry.enum.toString(), 150, setting.tip) { b ->
                val options = entry.objectType().enumConstants.filterIsInstance<Enum<*>>()
                if (entry.setEnum(options[(options.indexOf(entry.enum) + 1) % options.size])) edited()
                b.message = Component.literal(entry.enum.toString())
            })
            entry.type() == EntryType.STRING -> {
                val secret = setting.element.id().contains("key", true)
                child(UIComponents.textBox(Sizing.fill()).apply {
                    setMaxLength(256); text(if (secret) "" else entry.string)
                    if (secret) setHint(Component.literal(if (entry.string.isBlank()) "No key configured" else "Key saved • enter replacement"))
                    tip(setting.tip)
                    onChanged().subscribe { if ((!secret || it.isNotBlank()) && entry.setString(it)) edited() }
                })
                if (secret) child(button("Clear key", 72, "Remove the saved optional API key") { if (entry.setString("")) { edited(); populate() } })
            }
            else -> {
                val range = entry.options().getOption(Option.RANGE)
                if (range != null && entry.options().hasOption(Option.SLIDER)) {
                    val number = (entry.get() as Number).toDouble()
                    val valueLabel = label(numberText(number))
                    child(valueLabel)
                    child(ThemeSlider().apply {
                        value((number - range.min) / (range.max - range.min)); tip(setting.tip)
                        onChanged().subscribe { amount ->
                            val n = range.min + amount * (range.max - range.min)
                            val ok = if (entry.type() == EntryType.INTEGER) entry.setInt(n.roundToInt()) else entry.setDouble(n)
                            if (ok) { edited(); valueLabel.text(Component.literal(numberText((entry.get() as Number).toDouble()))) }
                        }
                    })
                }
            }
        }
    }

    private fun colorEditor(setting: SettingRow): FlowLayout = column().apply {
        val entry = setting.entry
        val hex = UIComponents.textBox(Sizing.fixed(100)).apply { setMaxLength(7); text("#%06X".format(entry.int)); tip("Hex RGB, for example #67CCF2. Invalid text keeps the previous color.") }
        val picker = ColorPickerComponent().apply { sizing(Sizing.fill(), Sizing.fixed(72)); selectedColor(Color.ofRgb(entry.int)); tip("Choose hue, saturation and brightness. The RGB controls also support keyboard input.") }
        var updating = false
        val sliders = mutableListOf<ThemeSlider>()
        fun update(color: Int) {
            if (updating) return
            updating = true
            if (entry.setInt(color)) edited()
            hex.text("#%06X".format(color)); picker.selectedColor(Color.ofRgb(color))
            sliders.forEachIndexed { i, slider -> slider.value((color shr ((2 - i) * 8) and 255) / 255.0) }
            updating = false
        }
        hex.onChanged().subscribe { if (!updating) parseThemeHex(it)?.let(::update) }
        picker.onChanged().subscribe { if (!updating) update(it.rgb()) }
        child(hex)
        val controls = UIContainers.collapsible(Sizing.fill(), Sizing.content(), Component.literal("Color picker & RGB").withColor(HudTheme.TEXT), false).apply {
            tip("Expand the color picker and individual red, green and blue sliders")
            child(picker)
        }
        child(controls)
        listOf("Red", "Green", "Blue").forEachIndexed { index, name ->
            val shift = (2 - index) * 8
            controls.child(label(name, HudTheme.MUTED))
            val slider = ThemeSlider().apply {
                value((entry.int shr shift and 255) / 255.0); tip("$name channel, 0–255. Use arrow keys for fine adjustments.")
                scrollStep(1.0 / 255)
                onChanged().subscribe { if (!updating) update((entry.int and (255 shl shift).inv()) or ((it * 255).roundToInt() shl shift)) }
            }
            sliders += slider; controls.child(slider)
        }
    }

    private fun openActions(anchor: ButtonComponent) {
        DropdownComponent.openContextMenu(this, uiAdapter.rootComponent, { root, menu -> root.child(menu) }, anchor.x.toDouble(), (anchor.y + anchor.height).toDouble()) { menu ->
            menu.surface(HudTheme.panel())
            menu.button(Component.literal("Daily Digest")) { DailyDigest.open() }
            menu.button(Component.literal("Friends & lending")) { MC.instance.setScreen(FriendsSocialScreen()) }
            menu.button(Component.literal("HUD editor")) { MC.instance.setScreen(WidgetEditorScreen) }
            menu.button(Component.literal("Cosmetics & Discord link")) { MC.instance.setScreen(me.mycellium.skymyce.features.social.CosmeticsScreen()) }
            fun actions(config: ResourcefulConfig) {
                config.elements().filterIsInstance<ResourcefulConfigButton>().forEach { action -> menu.button(Component.literal(action.title())) { action.invoke() } }
                config.categories().values.forEach(::actions)
            }
            actions(SkyMyce.config)
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        keyCapture?.let { (setting, button) ->
            if (event.key != GLFW.GLFW_KEY_ESCAPE && setting.entry.setInt(if (event.key == GLFW.GLFW_KEY_BACKSPACE) GLFW.GLFW_KEY_UNKNOWN else event.key)) edited()
            if (setting.path == "openConfigKey") SkyMyceCommands.updateSettingsKey()
            button.message = Component.literal(keyName(setting.entry.int)); keyCapture = null; return true
        }
        return super.keyPressed(event)
    }
    override fun removed() {
        if (changed) {
            changed = false
            Scheduling.schedule(Duration.ZERO) {
                synchronized(SAVE_LOCK) {
                    runCatching {
                        SkyMyce.config.save()
                        // Persist the native Controls mapping alongside the existing config key.
                        MC.instance.options.save()
                    }.onFailure { SkyMyce.logger.warn("Could not save Sky-Hawk settings") }
                }
            }
            DailyDigest.settingsChanged()
        }
        super.removed()
    }
    override fun onClose() { MC.instance.setScreen(parent) }
    private fun edited() {
        changed = true; PartyFinder.invalidateMatcher()
        heading.color(Color.ofRgb(HudTheme.ACCENT))
        selectedTabButton?.message = Component.literal(tab.title).withColor(HudTheme.ACCENT)
        body.children().filterIsInstance<FlowLayout>().forEach { group ->
            (group.children().firstOrNull() as? LabelComponent)?.color(Color.ofRgb(HudTheme.SECONDARY))
        }
    }
    private fun rebuild() { if (uiAdapter == null) return; uiAdapter.rootComponent.clearChildren(); build(uiAdapter.rootComponent); uiAdapter.inflateAndMount() }
    private fun keyName(key: Int) = if (key == GLFW.GLFW_KEY_UNKNOWN) "Unbound" else InputConstants.Type.KEYSYM.getOrCreate(key).displayName.string
    private fun numberText(value: Double) = "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
    private fun column() = UIContainers.verticalFlow(Sizing.fill(), Sizing.content()).gap(6)
    private fun label(text: String, color: Int = HudTheme.TEXT) = UIComponents.label(Component.literal(text)).apply { color(Color.ofRgb(color)); shadow(HudTheme.SHADOW); horizontalSizing(Sizing.fill()) }
    private fun button(text: String, width: Int, tooltip: String, action: (ButtonComponent) -> Unit) = UIComponents.button(Component.literal(text), action).themed().apply {
        sizing(Sizing.fixed(width), Sizing.fixed(20)); tip(tooltip)
    }
    private fun <T : UIComponent> T.tip(text: String): T = wrappedTooltip(Component.literal(text))
    companion object { private val SAVE_LOCK = Any() }
}
