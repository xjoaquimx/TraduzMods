package com.modtranslator.mixin;

import com.modtranslator.client.TranslationProgressControl;
import com.modtranslator.client.TranslationProgressHud;
import com.modtranslator.core.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MouseHandler.class)
public abstract class TranslationProgressMouseMixin {
    @Unique private boolean traduzmods$controlPressed;
    @Inject(method="onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",at=@At("HEAD"),cancellable=true)
    private void traduzmods$control(long handle,MouseButtonInfo input,int action,CallbackInfo info) {
        int button=input.button();
        var game=Minecraft.getInstance();var window=game.getWindow();
        if(handle!=window.handle()||button!=0)return;
        if(action==0&&traduzmods$controlPressed){traduzmods$controlPressed=false;info.cancel();return;}
        if(action!=1)return;
        double mx=game.mouseHandler.xpos()*window.getGuiScaledWidth()/Math.max(1,window.getScreenWidth());
        double my=game.mouseHandler.ypos()*window.getGuiScaledHeight()/Math.max(1,window.getScreenHeight());
        var placement=TranslationProgressHud.currentPlacement();
        boolean viewVisible=TranslationProgressHud.currentView()!=null;
        if(TranslationProgressControl.hitMinimize(game.gui.screen()!=null,game.gui.hud.isHidden(),viewVisible,placement,mx,my)) {
            traduzmods$controlPressed=true;
            TranslationService.toggleTranslationPanelMinimized();info.cancel();return;
        }
        if(!placement.expandedInput())return;
        if(TranslationProgressControl.hit(game.gui.screen()!=null,game.gui.hud.isHidden(),TranslationProgressHud.currentView()!=null,
                window.getGuiScaledWidth(),window.getGuiScaledHeight(),mx,my)) {
            traduzmods$controlPressed=true;
            TranslationService.toggleTranslationPaused();info.cancel();
        } else if(TranslationProgressControl.hitCache(game.gui.screen()!=null,game.gui.hud.isHidden(),TranslationProgressHud.currentView()!=null,
                window.getGuiScaledWidth(),window.getGuiScaledHeight(),mx,my)) {
            traduzmods$controlPressed=true;
            TranslationService.toggleCachedTranslationDisplay();info.cancel();
        }
    }
}
