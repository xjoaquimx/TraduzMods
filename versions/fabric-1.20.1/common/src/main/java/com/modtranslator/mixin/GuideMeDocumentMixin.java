package com.modtranslator.mixin;

import com.modtranslator.client.GuideMePageRefresh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "guideme.internal.screen.DocumentScreen", remap = false)
public abstract class GuideMeDocumentMixin {
    @Inject(method = "ensureDocumentLayout()V", at = @At("HEAD"), require = 0)
    private void modtranslator$layoutTranslatedText(CallbackInfo callback) { GuideMePageRefresh.refresh(this); }
}
