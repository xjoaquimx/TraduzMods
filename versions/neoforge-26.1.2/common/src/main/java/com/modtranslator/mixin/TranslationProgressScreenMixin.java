package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressHud;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class TranslationProgressScreenMixin {
    @Inject(method="extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",at=@At("HEAD"))
    private void modtranslator$refreshAetherPage(CallbackInfo info) { com.modtranslator.client.AetherPageRefresh.refresh(this); }

    @Inject(method="extractRenderStateWithTooltipAndSubtitles(Lnet/minecraft/client/gui/GuiGraphicsExtractor;IIF)V",at=@At("TAIL"))
    private void traduzmods$renderProgress(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick,CallbackInfo info) {
        TranslationProgressHud.render(graphics);
    }
}
