package com.modtranslator.mixin;

import com.modtranslator.client.NpcDialogueText;
import com.modtranslator.core.TranslationService;
import java.util.List;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Rewrap existing NPC dialogue when asynchronous language entries arrive. Originals and signed chat stay intact. */
@Mixin(ChatComponent.class)
public abstract class NpcChatRefreshMixin {
    @Shadow private List<GuiMessage> allMessages;
    @Shadow protected abstract void refreshTrimmedMessages();
    @Unique private long modtranslator$revision=-1,modtranslator$refreshAt;
    @Inject(method="tick()V",at=@At("TAIL"))
    private void modtranslator$refreshNpc(CallbackInfo callback) {
        long revision=TranslationService.revision(),now=System.currentTimeMillis();
        if(!TranslationService.isPortugueseActive()||revision==modtranslator$revision||now<modtranslator$refreshAt)return;
        modtranslator$revision=revision;modtranslator$refreshAt=now+1000;
        boolean npc=false;
        for(GuiMessage message:allMessages) if(NpcDialogueText.isDialogue(message.content())) {
            // Resolving the original structured keys also promotes visible NPC lines over catalog work.
            message.content().getString(); npc=true;
        }
        if(npc)refreshTrimmedMessages();
    }
}
