package com.modtranslator.forge;

import com.modtranslator.core.TranslationService;
import com.modtranslator.core.ModTextPolicy;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.Set;
import java.util.stream.Collectors;

@Mod("modtranslator")
public final class ModTranslatorForge {
    public ModTranslatorForge() {
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
