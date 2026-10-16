package com.modtranslator.mixin;

import com.modtranslator.client.AgentCraftTranslation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Pseudo
@Mixin(targets="dev.agentcraft.client.ui.TextUtil",remap=false)
public abstract class AgentCraftTextMixin {
    @ModifyVariable(method={"ellipsize","wrap","wrapPlain"},at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private static String traduzmods$beforeLayout(String source){return AgentCraftTranslation.text(source);}
}
