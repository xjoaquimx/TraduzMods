package com.modtranslator.mixin;

import com.modtranslator.core.TranslationService;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A completed async translation must invalidate Minecraft's per-component language cache. */
@Mixin(TranslatableContents.class)
public abstract class TranslatableContentsMixin {
    @Shadow private Language decomposedWith;
    @Unique private long modtranslator$revision = Long.MIN_VALUE;

    @Inject(method = "decompose()V", at = @At("HEAD"))
    private void modtranslator$invalidateTranslation(CallbackInfo callback) {
        long revision = TranslationService.revision();
        if (modtranslator$revision != revision) {
            modtranslator$revision = revision;
            decomposedWith = null;
        }
    }
}
