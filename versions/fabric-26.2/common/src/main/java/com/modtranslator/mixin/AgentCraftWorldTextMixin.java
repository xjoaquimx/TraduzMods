package com.modtranslator.mixin;

import com.modtranslator.client.RenderTranslation;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="dev.agentcraft.client.ui.WorldUi",remap=false)
public abstract class AgentCraftWorldTextMixin {
    @ModifyVariable(method="submitText(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/util/FormattedCharSequence;FFII)V",at=@At("HEAD"),argsOnly=true,ordinal=0,require=0)
    private static FormattedCharSequence traduzmods$worldText(FormattedCharSequence source){return RenderTranslation.sequence(source);}
}
