package com.modtranslator.mixin;

import com.modtranslator.client.FtbQuestViewRefresh;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.client.gui.quests.ViewQuestPanel", remap = false)
public abstract class FtbQuestViewMixin {
    @Inject(method = "draw",
            at = @At("HEAD"), require = 0)
    private void modtranslator$refreshOpenQuest(CallbackInfo callback) { FtbQuestViewRefresh.refresh(this); }
}
