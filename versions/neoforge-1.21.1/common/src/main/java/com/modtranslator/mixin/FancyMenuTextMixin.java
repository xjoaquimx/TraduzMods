package com.modtranslator.mixin;
import com.modtranslator.client.FancyMenuText;
import com.modtranslator.core.TranslationService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Optional FancyMenu integration, no dependency on its Minecraft-specific renderer API. */
@Pseudo
@Mixin(targets="de.keksuccino.fancymenu.util.rendering.text.markdown.MarkdownRenderer",remap=false)
public abstract class FancyMenuTextMixin {
    @Shadow protected String renderText;
    @Shadow protected String text;
    @Unique private long modtranslator$revision=-1,modtranslator$refreshAt;
    @Inject(method="buildRenderText()Ljava/lang/String;",at=@At("RETURN"),cancellable=true)
    private void modtranslator$wholeText(CallbackInfoReturnable<String> callback) {
        // Resolved placeholders can contain player names or user values; only static prose and line breaks here.
        if(text!=null&&text.replace("%n%","").contains("%"))return;
        callback.setReturnValue(FancyMenuText.translate(callback.getReturnValue()));
    }
    @Inject(method="tick()V",at=@At("HEAD"))
    private void modtranslator$refresh(CallbackInfo callback) {
        long revision=TranslationService.revision();
        long now=System.currentTimeMillis();
        if(revision!=modtranslator$revision&&now>=modtranslator$refreshAt) {modtranslator$revision=revision;modtranslator$refreshAt=now+1000;renderText=null;}
    }
}
