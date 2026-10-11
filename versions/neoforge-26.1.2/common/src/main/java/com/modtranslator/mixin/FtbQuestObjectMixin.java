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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Change only the display parser's argument; raw getters, setters and serialization stay original. */
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbquests.quest.QuestObjectBase", remap = false)
public abstract class FtbQuestObjectMixin {
    @Shadow private Component cachedTitle;
    @Unique private long modtranslator$questTitleRevision = Long.MIN_VALUE;

    @Inject(method = "getTitle()Lnet/minecraft/network/chat/Component;", at = @At("HEAD"), require = 0)
    private void modtranslator$refreshTitle(CallbackInfoReturnable<Component> callback) {
        long revision = TranslationService.revision();
        if (modtranslator$questTitleRevision != revision && FtbQuestClient.isClient(this)) {
            modtranslator$questTitleRevision = revision;
            cachedTitle = null;
        }
    }

    @ModifyArg(method = "getTitle()Lnet/minecraft/network/chat/Component;",
            at = @At(value = "INVOKE", target = "Ldev/ftb/mods/ftbquests/util/TextUtils;parseRawText(Ljava/lang/String;Lnet/minecraft/core/HolderLookup$Provider;)Lnet/minecraft/network/chat/Component;"),
            index = 0, require = 0)
    private String modtranslator$wholeTitle(String source) { return FtbQuestClient.translate(this, source); }

    @Inject(method = "getTitle", at = @At("RETURN"), cancellable = true, require = 0)
    private void modtranslator$itemGroupCaption(CallbackInfoReturnable<Component> callback) {
        Component title = callback.getReturnValue();
        if (title == null || !"Logs".equals(title.getString()) || !FtbQuestClient.isClient(this)) return;
        String translated = TranslationService.translateQuest("Logs");
        if (!"Logs".equals(translated)) callback.setReturnValue(Component.literal(translated).setStyle(title.getStyle()));
    }
}
