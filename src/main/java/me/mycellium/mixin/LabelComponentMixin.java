package me.mycellium.mixin;

import io.wispforest.owo.ui.component.LabelComponent;
import net.minecraft.network.chat.Style;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = LabelComponent.class, remap = false)
public class LabelComponentMixin {
    @ModifyArg(
        method = "drawTooltip",
        at = @At(value = "INVOKE", target = "Lio/wispforest/owo/ui/core/OwoUIGraphics;componentHoverEffect(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Style;II)V"),
        index = 1
    )
    private Style skymyce$nonNullHoverStyle(Style hoveredStyle) {
        // owo 0.13.1 returns null over label padding, but Minecraft 26.1 requires a style.
        // Keep the component's own tooltip and any real text hover event intact.
        return hoveredStyle == null ? Style.EMPTY : hoveredStyle;
    }
}
