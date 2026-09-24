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

fun hudColumn(): FlowLayout = UIContainers.verticalFlow(Sizing.content(), Sizing.content()).gap(2).apply {
    allowOverflow(true) // Text shadows extend beyond the measured glyph bounds.
}

fun hudLabel(text: String = ""): LabelComponent = UIComponents.label(Component.literal(text.ifEmpty { " " })).shadow(true)

fun LabelComponent.updateText(text: String) {
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

fun hudPanel(padding: Int = 5, decorated: Boolean = true): FlowLayout = hudColumn().apply {
    padding(Insets.of(padding))
    surface(HudTheme.panel().let { background ->
        if (!decorated) background else background.and { graphics, panel ->
            graphics.fill(panel.x(), panel.y() + 3, panel.x() + 2, panel.y() + panel.height() - 3, HudTheme.alpha(HudTheme.HUD_ACCENT))
        }
    })
}
