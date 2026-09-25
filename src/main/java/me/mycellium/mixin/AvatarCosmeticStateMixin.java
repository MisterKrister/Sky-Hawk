package me.mycellium.mixin;

import me.mycellium.skymyce.features.social.CosmeticProfile;
import me.mycellium.skymyce.features.social.CosmeticAvatarState;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(AvatarRenderState.class)
public class AvatarCosmeticStateMixin implements CosmeticAvatarState {
    @Unique private CosmeticProfile skymyce$cosmetics;
    public CosmeticProfile skymyce$getCosmetics() { return skymyce$cosmetics; }
    public void skymyce$setCosmetics(CosmeticProfile profile) { skymyce$cosmetics = profile; }
}
