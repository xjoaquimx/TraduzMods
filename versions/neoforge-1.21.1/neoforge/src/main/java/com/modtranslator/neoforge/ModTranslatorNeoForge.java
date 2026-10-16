package com.modtranslator.neoforge;

import com.modtranslator.core.TranslationService;
import com.modtranslator.core.ModTextPolicy;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLPaths;

import java.util.Set;
import java.util.stream.Collectors;

@Mod("modtranslator")
public final class ModTranslatorNeoForge {
    public ModTranslatorNeoForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ModTextPolicy.registerModNames(ModList.get().getMods().stream()
                    .map(mod -> mod.getDisplayName()).toList());
            Set<String> modIds = ModList.get().getMods().stream()
                    .map(mod -> mod.getModId())
                    .collect(Collectors.toUnmodifiableSet());
            TranslationService.initialize(FMLPaths.CONFIGDIR.get(), modIds);
        }
    }
}
