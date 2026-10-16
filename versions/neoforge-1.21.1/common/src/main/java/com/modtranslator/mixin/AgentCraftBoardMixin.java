package com.modtranslator.mixin;

import com.modtranslator.client.AgentCraftTranslation;
import com.modtranslator.client.TranslationRefreshGate;
import com.modtranslator.core.TranslationService;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Pseudo
@Mixin(targets="dev.agentcraft.client.taskwall.TaskBoard",remap=false)
public abstract class AgentCraftBoardMixin {
    @Shadow private long taskSeq;
    @Unique private final TranslationRefreshGate traduzmods$refresh=new TranslationRefreshGate();
    @Inject(method="sync",at=@At("HEAD"),require=0)
    private void traduzmods$invalidate(CallbackInfoReturnable<Boolean> info) {
        if(traduzmods$refresh.changed(TranslationService.revision(),System.nanoTime()))taskSeq=Long.MIN_VALUE;
    }
    @Inject(method="titleText",at=@At("HEAD"),cancellable=true,require=0)
    private static void traduzmods$title(@Coerce Object task,CallbackInfoReturnable<String> info) {
        String result=AgentCraftTranslation.title(task);if(result!=null)info.setReturnValue(result);
    }
}
