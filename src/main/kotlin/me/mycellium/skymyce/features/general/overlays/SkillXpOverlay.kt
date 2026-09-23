package me.mycellium.skymyce.features.general.overlays

import me.mycellium.skymyce.SkyMyceModule
import me.mycellium.skymyce.config.misc.GeneralConfig
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.VerticalAlignment
import me.mycellium.skymyce.hud.hudLabel
import me.mycellium.skymyce.hud.updateText
import me.mycellium.skymyce.hud.widget.Widget
import me.mycellium.skymyce.hud.widget.Anchor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import tech.thatgravyboat.skyblockapi.api.events.base.Subscription
import tech.thatgravyboat.skyblockapi.api.events.info.ActionBarWidget
import tech.thatgravyboat.skyblockapi.api.events.info.ActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.RenderActionBarWidgetEvent
import tech.thatgravyboat.skyblockapi.api.events.info.SkillXpLiteralActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.events.info.SkillXpPercentActionBarWidgetChangeEvent
import tech.thatgravyboat.skyblockapi.api.remote.hypixel.HypixelSkillAPI.Skill
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object SkillXpOverlay : SkyMyceModule() {
    private var display = ""
    private var skill: Skill? = null
    private var timeSince: Duration = 0.milliseconds
    private val icon by lazy { UIComponents.item(ItemStack(Items.BARRIER)) }
    private val label by lazy { hudLabel() }
    private val content by lazy {
        UIContainers.horizontalFlow(Sizing.content(), Sizing.content()).gap(5).apply {
            verticalAlignment(VerticalAlignment.CENTER)
            allowOverflow(true)
            children(listOf(icon, label))
        }
    }

    val widget = Widget("skill_xp_display", "Skill Xp Display", 480, 290, anchor = Anchor.CENTER) {
        if (!GeneralConfig.displayXp) return@Widget null

        val now = System.currentTimeMillis().milliseconds
        if (now - timeSince > 5.seconds) return@Widget null

        val item = skill?.skillItem() ?: Items.BARRIER
        if (icon.stack().item != item) icon.stack(ItemStack(item))
        label.updateText(display)
        content
    }

    @Subscription
    fun onWidgetRender(event: RenderActionBarWidgetEvent) {
        if (!GeneralConfig.displayXp) return

        if (event.widget == ActionBarWidget.SKILL_XP || event.widget == ActionBarWidget.SKILL_XP_LITERAL) {
            event.cancel()
        }
    }

    @Subscription
    fun onWidgetEvent(event: ActionBarWidgetChangeEvent) {
        when (event) {
            is SkillXpPercentActionBarWidgetChangeEvent -> {
                display = event.new
                skill = event.skill
                timeSince = System.currentTimeMillis().milliseconds
            }

            is SkillXpLiteralActionBarWidgetChangeEvent -> {
                display = event.new
                skill = event.skill
                timeSince = System.currentTimeMillis().milliseconds
            }
        }
    }

    fun Skill.skillItem(): Item = when(this) {
        Skill.COMBAT -> Items.STONE_SWORD
        Skill.FARMING -> Items.GOLDEN_HOE
        Skill.FISHING -> Items.FISHING_ROD
        Skill.MINING -> Items.STONE_PICKAXE
        Skill.FORAGING -> Items.JUNGLE_SAPLING
        Skill.ENCHANTING -> Items.ENCHANTING_TABLE
        Skill.ALCHEMY -> Items.BREWING_STAND
        Skill.CARPENTRY -> Items.CRAFTING_TABLE
        Skill.RUNECRAFTING -> Items.MAGMA_CREAM
        Skill.TAMING -> Items.POLAR_BEAR_SPAWN_EGG
        Skill.SOCIAL -> Items.EMERALD
        Skill.HUNTING -> Items.LEAD
    } ?: Items.BARRIER
}
