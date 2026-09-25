package me.mycellium.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ProfileKeyPairManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Relay identity proofs need the account manager, independently of the chat-signing policy getter. */
@Mixin(Minecraft.class)
public interface MinecraftAccountKeyAccessor {
    @Accessor("profileKeyPairManager")
    ProfileKeyPairManager skymyce$getAccountKeyPairManager();
}
