package com.modtranslator.mixin;

import com.modtranslator.client.TranslationRefreshGate;
import com.modtranslator.core.TranslationService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="dev.agentcraft.client.agents.AgentCardScreen",remap=false)
public abstract class AgentCraftScreenMixin {
    @Shadow private long revision;
    @Unique private final TranslationRefreshGate traduzmods$refresh=new TranslationRefreshGate();
    @Inject(method="refresh",at=@At("HEAD"),require=0)
    private void traduzmods$invalidate(CallbackInfo info) {
        if(traduzmods$refresh.changed(TranslationService.revision(),System.nanoTime()))revision=Long.MIN_VALUE;
    }
}
