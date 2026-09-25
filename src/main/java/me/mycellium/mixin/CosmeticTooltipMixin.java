package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.mycellium.skymyce.config.misc.CosmeticsConfig;
import me.mycellium.skymyce.features.social.CosmeticNames;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipPositioner;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public class CosmeticTooltipMixin {
    @WrapMethod(method = "tooltip")
    private void skymyce$tooltip(Font font, List<ClientTooltipComponent> lines, int x, int y, ClientTooltipPositioner positioner, Identifier sprite, Operation<Void> original) {
        boolean preserve = CosmeticsConfig.INSTANCE.getOriginalTooltips();
        if (preserve) CosmeticNames.enterOriginal();
        try { original.call(font, lines, x, y, positioner, sprite); }
        finally { if (preserve) CosmeticNames.exitOriginal(); }
    }
    @WrapMethod(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/world/item/ItemStack;II)V")
    private void skymyce$itemLayout(Font font, ItemStack item, int x, int y, Operation<Void> original) {
        boolean preserve = CosmeticsConfig.INSTANCE.getOriginalTooltips();
        if (preserve) CosmeticNames.enterOriginal();
        try { original.call(font, item, x, y); }
        finally { if (preserve) CosmeticNames.exitOriginal(); }
    }
}
