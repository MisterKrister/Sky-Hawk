package me.mycellium.skymyce.hud

import io.wispforest.owo.ui.component.ButtonComponent
import io.wispforest.owo.ui.component.SliderComponent
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.Sizing
import me.mycellium.skymyce.config.misc.ThemeConfig
import net.minecraft.client.gui.GuiGraphicsExtractor

/** Shared Sky-Hawk palette used by retained owo HUD components and menus. */
object HudTheme {
    val HUD_ACCENT get() = ACCENT
    val ACCENT get() = ThemeConfig.primary and 0xFFFFFF
    val SECONDARY get() = ThemeConfig.secondary and 0xFFFFFF
    const val PANEL = 0x10171E
    const val CARD = 0x17212A
    const val BORDER = 0x2C3944
    const val TEXT = 0xEDF3F7
    const val MUTED = 0x91A2AF
    const val GREEN = 0x87D6A2
    const val YELLOW = 0xE8BF71
    const val RED = 0xF18C8C
    val PURPLE get() = SECONDARY

    fun alpha(rgb: Int, percent: Int = ThemeConfig.opacity) = (percent.coerceIn(0, 100) * 255 / 100 shl 24) or (rgb and 0xFFFFFF)
    fun blend(a: Int, b: Int, weight: Int): Int {
        val w = weight.coerceIn(0, 100)
        fun channel(shift: Int) = (((a shr shift and 255) * (100 - w) + (b shr shift and 255) * w) / 100) shl shift
        return channel(16) or channel(8) or channel(0)
    }

    /** Five non-overlapping rectangles, no shaders, textures, allocations, or double-blended edges. */
    fun rounded(g: GuiGraphicsExtractor, x: Int, y: Int, width: Int, height: Int, color: Int) {
        if (width <= 0 || height <= 0) return
        if (width < 6 || height < 6) { g.fill(x, y, x + width, y + height, color); return }
        g.fill(x + 2, y, x + width - 2, y + 1, color)
        g.fill(x + 1, y + 1, x + width - 1, y + 2, color)
        g.fill(x, y + 2, x + width, y + height - 2, color)
        g.fill(x + 1, y + height - 2, x + width - 1, y + height - 1, color)
        g.fill(x + 2, y + height - 1, x + width - 2, y + height, color)
    }

    fun panel(card: Boolean = false): Surface = Surface { g, c ->
        rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(blend(if (card) CARD else PANEL, ACCENT, 5)))
    }
    fun tintedPanel(color: () -> Int): Surface = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(color())) }
    val backdrop = Surface { g, c -> rounded(g, c.x(), c.y(), c.width(), c.height(), alpha(0x090D12, 55)) }
    val buttonRenderer = ButtonComponent.Renderer { g, b, _ ->
        val hover = b.isHovered || b.isFocused
        rounded(g, b.x, b.y, b.width, b.height, alpha(blend(CARD, if (b.isFocused) SECONDARY else ACCENT,
            if (!b.active) 2 else if (hover) 35 else 12)))
    }
}

/** The existing slider retains keyboard/mouse behavior; only its background and thumb change. */
class ThemeSlider : SliderComponent(Sizing.fill()) {
    override fun extractWidgetRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        HudTheme.rounded(g, x, y, width, height, HudTheme.alpha(HudTheme.CARD))
        val thumb = (x + value() * (width - 8)).toInt()
        HudTheme.rounded(g, thumb, y + 2, 8, height - 4, HudTheme.alpha(HudTheme.ACCENT))
    }
}

fun ButtonComponent.themed(): ButtonComponent = apply { renderer(HudTheme.buttonRenderer); textShadow(false) }
