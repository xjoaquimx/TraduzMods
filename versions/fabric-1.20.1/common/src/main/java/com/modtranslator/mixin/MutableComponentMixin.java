package com.modtranslator.mixin;

import com.modtranslator.core.TranslationService;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MutableComponent.class)
public abstract class MutableComponentMixin {
    @Shadow private Language decomposedWith;
    @Unique private long modtranslator$revision = Long.MIN_VALUE;

    @Inject(method = "getVisualOrderText()Lnet/minecraft/util/FormattedCharSequence;", at = @At("HEAD"))
    private void modtranslator$invalidateVisualText(CallbackInfoReturnable<FormattedCharSequence> callback) {
        long revision = TranslationService.revision();
        if (modtranslator$revision != revision) {
            modtranslator$revision = revision;
            decomposedWith = null;
        }
    }
}
