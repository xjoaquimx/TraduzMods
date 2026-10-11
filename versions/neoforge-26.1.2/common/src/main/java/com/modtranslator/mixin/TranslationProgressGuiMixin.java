package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class TranslationProgressGuiMixin {
    @Inject(method="extractRenderState(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",at=@At("TAIL"))
    private void traduzmods$renderProgress(GuiGraphicsExtractor graphics,DeltaTracker deltaTracker,CallbackInfo info) {
        if(Minecraft.getInstance().screen==null) TranslationProgressHud.render(graphics);
    }
}
