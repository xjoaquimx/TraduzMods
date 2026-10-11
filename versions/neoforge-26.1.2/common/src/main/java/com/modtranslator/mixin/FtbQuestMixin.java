package com.modtranslator.mixin;

import com.modtranslator.client.FtbQuestClient;
import com.modtranslator.core.TranslationService;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.List;

@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.Quest", remap = false)
public abstract class FtbQuestMixin {
    @Shadow private Component cachedSubtitle;
    @Shadow private List<Component> cachedDescription;
    @Unique private long modtranslator$questTextRevision = Long.MIN_VALUE;

    @Inject(method = {"getSubtitle()Lnet/minecraft/network/chat/Component;", "getDescription()Ljava/util/List;"},
            at = @At("HEAD"), require = 0)
    private void modtranslator$refreshText(CallbackInfoReturnable<?> callback) {
        long revision = TranslationService.revision();
        if (modtranslator$questTextRevision != revision && FtbQuestClient.isClient(this)) {
            modtranslator$questTextRevision = revision;
            cachedSubtitle = null;
            cachedDescription = null;
        }
    }

    @ModifyArg(method = "getSubtitle()Lnet/minecraft/network/chat/Component;",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/util/TextUtils;parseRawText(Ljava/lang/String;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/network/chat/Component;"),
            index = 0, require = 0)
    private String modtranslator$wholeSubtitle(String source) { return FtbQuestClient.translate(this, source); }

    @ModifyVariable(method = "lambda$getDescription$0(Ljava/lang/String;)Lnet/minecraft/network/chat/Component;",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private String modtranslator$wholeDescription(String source) { return FtbQuestClient.translate(this, source); }
}
