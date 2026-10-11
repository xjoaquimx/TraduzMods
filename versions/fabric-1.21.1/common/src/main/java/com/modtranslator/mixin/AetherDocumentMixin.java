package com.modtranslator.mixin;

import com.modtranslator.client.AetherPageRefresh;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets = {
        "com.aetherteam.aetherii.client.gui.screen.AlphaInfoScreen",
        "com.aetherteam.aetherii.client.gui.screen.guidebook.DiscoveryDescriptionScreen"
}, remap = false)
public abstract class AetherDocumentMixin {
    @ModifyVariable(method = "createPages", at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private Component modtranslator$visibleDocument(Component source) { return AetherPageRefresh.capture(this, source); }
}
