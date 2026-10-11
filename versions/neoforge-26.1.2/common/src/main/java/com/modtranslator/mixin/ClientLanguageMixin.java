package com.modtranslator.mixin;

import com.modtranslator.core.TranslationService;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Runs on each client language/resource reload, without waiting for a screen to request a key. */
@Mixin(ClientLanguage.class)
public abstract class ClientLanguageMixin {
    @Inject(
            method = "loadFrom(Lnet/minecraft/server/packs/resources/ResourceManager;Ljava/util/List;Z)Lnet/minecraft/client/resources/language/ClientLanguage;",
            at = @At("RETURN")
    )
    private static void modtranslator$discoverMissingPortuguese(
            ResourceManager resourceManager,
            List<String> languageStack,
            boolean defaultRightToLeft,
            CallbackInfoReturnable<ClientLanguage> callback
    ) {
        TranslationService.languageLoaded(resourceManager, languageStack);
    }
}
