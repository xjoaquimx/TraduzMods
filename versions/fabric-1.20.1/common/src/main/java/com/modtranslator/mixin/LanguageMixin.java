package com.modtranslator.mixin;

import com.modtranslator.core.TranslationService;
import net.minecraft.client.resources.language.ClientLanguage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The concrete two-argument method also serves TranslatableContents with a fallback. */
@Mixin(ClientLanguage.class)
public abstract class LanguageMixin {
    @Inject(method = "getOrDefault(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true)
    private void modtranslator$translateModStrings(String key, String fallback, CallbackInfoReturnable<String> callback) {
        if (!TranslationService.isPortugueseActive()) return;
        String source = callback.getReturnValue();
        callback.setReturnValue(TranslationService.translateIfCachedOrQueue(key, source));
    }
}
