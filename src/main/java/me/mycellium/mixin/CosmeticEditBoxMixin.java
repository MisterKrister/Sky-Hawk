package me.mycellium.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import me.mycellium.skymyce.features.social.CosmeticNames;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;

/** Typed commands/searches are editable real text, not cosmetic labels. */
@Mixin(EditBox.class)
public class CosmeticEditBoxMixin {
    @WrapMethod(method = "updateTextPosition")
    private void skymyce$realPosition(Operation<Void> original) {
        CosmeticNames.enterOriginal();
        try { original.call(); } finally { CosmeticNames.exitOriginal(); }
    }
    @WrapMethod(method = "getScreenX")
    private int skymyce$realCursor(int index, Operation<Integer> original) {
        CosmeticNames.enterOriginal();
        try { return original.call(index); } finally { CosmeticNames.exitOriginal(); }
    }
    @WrapMethod(method = "extractWidgetRenderState")
    private void skymyce$realInput(GuiGraphicsExtractor graphics, int x, int y, float delta, Operation<Void> original) {
        CosmeticNames.enterOriginal();
        try { original.call(graphics, x, y, delta); } finally { CosmeticNames.exitOriginal(); }
    }
}
