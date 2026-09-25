package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Size
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.UIComponent
import net.minecraft.network.chat.Component

/** Wrap once when built, using the same viewport limit as the settings menu. */
fun <T : UIComponent> T.wrappedTooltip(text: Component): T = apply {
    val mc = me.mycellium.skymyce.utils.MC.instance
    tooltip(mc.font.split(text, minOf(250, (mc.window.guiScaledWidth - 30).coerceAtLeast(80)))
        .map { net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent.create(it) })
}

fun hudColumn(): FlowLayout = UIContainers.verticalFlow(Sizing.content(), Sizing.content()).gap(2).apply {
    allowOverflow(true) // Text shadows extend beyond the measured glyph bounds.
}

fun hudLabel(text: String = ""): LabelComponent = HudTextLabel(text)

/** Existing HUD strings keep their semantic formatting, mapped through the one shared palette. */
internal fun themedHudText(value: String): Component {
    val result = Component.empty()
    var style = net.minecraft.network.chat.Style.EMPTY.withColor(HudTheme.TEXT)
    var start = 0
    var i = 0
    while (i + 1 < value.length) {
        if (value[i] != '§') { i++; continue }
        val code = value[i + 1].lowercaseChar()
        val formatting = net.minecraft.ChatFormatting.getByCode(code)
        if (formatting == null) { i++; continue }
        if (i > start) result.append(Component.literal(value.substring(start, i)).withStyle(style))
        val color = when (code) {
            'f' -> HudTheme.TEXT; '7', '8' -> HudTheme.MUTED
            '9', 'b' -> HudTheme.ACCENT; '5', 'd' -> HudTheme.SECONDARY
            'a', '2' -> HudTheme.GREEN; 'c', '4' -> HudTheme.RED; 'e', '6' -> HudTheme.YELLOW
            else -> null
        }
        style = if (code == 'r') net.minecraft.network.chat.Style.EMPTY.withColor(HudTheme.TEXT)
            else if (color != null) net.minecraft.network.chat.Style.EMPTY.withColor(color) else style.applyFormat(formatting)
        i += 2; start = i
    }
    if (start < value.length) result.append(Component.literal(value.substring(start)).withStyle(style))
    return result
}

internal class HudTextLabel(initial: String) : LabelComponent(Component.empty()) {
    private var source = ""
    private var theme = 0L
    fun update(value: String = source, force: Boolean = false) {
        // Labels belong to one widget; revision also changes whenever its overrides are edited.
        if (!force && value == source && theme == HudTheme.revision) return
        source = value; theme = HudTheme.revision
        text(themedHudText(value.ifEmpty { " " })); shadow(HudTheme.SHADOW)
    }
    init { theme = -1; update(initial) }
}

fun LabelComponent.updateText(text: String) {
    if (this is HudTextLabel) { update(text); return }
    val value = text.ifEmpty { " " }
    if (text().string != value) text(Component.literal(value))
}

internal fun FlowLayout.replaceContent(content: UIComponent) {
    clearChildren()
    // Mutations while detached do not invalidate owo's cached parent sizes. Remeasure before remounting.
    content.inflate(Size.zero())
    child(content)
}

/** Keep the layout and existing labels alive as rows change, so owo can update their bounds. */
fun FlowLayout.updateTextLines(lines: List<String>): FlowLayout = apply {
    configure<FlowLayout> { column ->
        while (column.children().size > lines.size) column.removeChild(column.children().last())
        lines.forEachIndexed { index, text ->
            val label = column.children().getOrNull(index) as? LabelComponent
            if (label == null) column.child(hudLabel(text)) else label.updateText(text)
        }
    }
}

fun hudPanel(padding: Int = HudTheme.PADDING, decorated: Boolean = true): FlowLayout = hudColumn().apply {
    padding(Insets.of(padding))
    surface(HudTheme.panel().let { background ->
        if (!decorated) background else background.and { graphics, panel ->
            graphics.fill(panel.x(), panel.y() + 3, panel.x() + 2, panel.y() + panel.height() - 3, HudTheme.alpha(HudTheme.HUD_ACCENT))
        }
    })
}
