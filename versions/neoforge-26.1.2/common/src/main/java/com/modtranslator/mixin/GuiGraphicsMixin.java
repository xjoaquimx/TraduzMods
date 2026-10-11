package com.modtranslator.mixin;

import com.modtranslator.client.RenderTranslation;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Translate before GUI extraction, so dimensions and hover hitboxes use translated glyphs. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class GuiGraphicsMixin {
    @ModifyVariable(method = {
            "text(Lnet/minecraft/client/gui/Font;Ljava/lang/String;IIIZ)V",
            "centeredText(Lnet/minecraft/client/gui/Font;Ljava/lang/String;III)V"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String modtranslator$string(String source) { return RenderTranslation.string(source); }

    @ModifyVariable(method = {
            "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIZ)V",
            "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;III)V",
            "textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component modtranslator$component(Component source) { return RenderTranslation.component(source); }

    @ModifyVariable(method = {
            "text(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;IIIZ)V",
            "centeredText(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)V"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedCharSequence modtranslator$sequence(FormattedCharSequence source) { return RenderTranslation.sequence(source); }

    @ModifyVariable(method = "textWithWordWrap(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/FormattedText;IIIIZ)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedText modtranslator$wrapped(FormattedText source) { return RenderTranslation.formatted(source); }
}
