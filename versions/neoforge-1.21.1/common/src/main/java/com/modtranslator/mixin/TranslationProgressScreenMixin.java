package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressHud;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class TranslationProgressScreenMixin {
    @Inject(method="renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",at=@At("HEAD"))
    private void modtranslator$refreshAetherPage(CallbackInfo info) { com.modtranslator.client.AetherPageRefresh.refresh(this); }

    @Inject(method="renderWithTooltip(Lnet/minecraft/client/gui/GuiGraphics;IIF)V",at=@At("TAIL"))
    private void traduzmods$renderProgress(GuiGraphics graphics,int mouseX,int mouseY,float partialTick,CallbackInfo info) {
        TranslationProgressHud.render(graphics);
    }
}
