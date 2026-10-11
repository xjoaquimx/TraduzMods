package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class TranslationProgressGuiMixin {
    @Inject(method="render(Lnet/minecraft/client/gui/GuiGraphics;F)V",at=@At("TAIL"))
    private void traduzmods$renderProgress(GuiGraphics graphics,float partialTick,CallbackInfo info) {
        if(Minecraft.getInstance().screen==null) TranslationProgressHud.render(graphics);
    }
}
