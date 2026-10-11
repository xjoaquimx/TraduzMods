package com.modtranslator.fabric;

import com.modtranslator.core.TranslationService;
import com.modtranslator.core.ModTextPolicy;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

import java.util.Set;
import java.util.stream.Collectors;

public final class ModTranslatorFabric implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ModTextPolicy.registerModNames(FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getName()).toList());
        Set<String> modIds = FabricLoader.getInstance().getAllMods().stream()
                .map(mod -> mod.getMetadata().getId())
                .collect(Collectors.toUnmodifiableSet());
        TranslationService.initialize(FabricLoader.getInstance().getConfigDir(), modIds);
    }
}
