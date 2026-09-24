package me.mycellium.mixin;

import com.teamresourceful.resourcefulconfig.api.client.ResourcefulConfigScreenBuilder;
import com.teamresourceful.resourcefulconfig.api.types.ResourcefulConfig;
import me.mycellium.skymyce.SkyMyce;
import me.mycellium.skymyce.config.SettingsScreen;
import me.mycellium.skymyce.config.SettingsTab;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Resourceful Config's Mod Menu factory uses this builder; other mods retain their own screens. */
@Mixin(value = ResourcefulConfigScreenBuilder.class, remap = false)
public class ResourcefulConfigScreenBuilderMixin {
    @Shadow @Final private ResourcefulConfig config;
    @Shadow private Screen parent;

    @Inject(method = "build", at = @At("HEAD"), cancellable = true)
    private void skymyce$settingsScreen(CallbackInfoReturnable<Screen> callback) {
        if (config == SkyMyce.INSTANCE.getConfig()) callback.setReturnValue(new SettingsScreen(parent, SettingsTab.GENERAL));
    }
}
