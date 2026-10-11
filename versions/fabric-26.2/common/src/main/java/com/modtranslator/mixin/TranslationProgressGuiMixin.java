package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressHud;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Minecraft 26.2 composes screens and the world HUD in one GUI extraction. */
@Mixin(Gui.class)
public abstract class TranslationProgressGuiMixin {
    @Shadow @Final private GuiRenderState guiRenderState;
    @Inject(method="extractRenderState(Lnet/minecraft/client/DeltaTracker;ZZ)V",at=@At("TAIL"))
    private void traduzmods$renderProgress(DeltaTracker deltaTracker,boolean renderHud,boolean renderScreen,CallbackInfo info) {
        var game=Minecraft.getInstance();
        var view=TranslationProgressHud.currentView();
        if(view==null || game.gui.hud.isHidden())return;
        TranslationProgressHud.render(new GuiGraphicsExtractor(game,guiRenderState,0,0),view);
    }
}
