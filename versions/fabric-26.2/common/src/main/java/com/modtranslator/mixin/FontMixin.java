package com.modtranslator.mixin;

import com.modtranslator.client.RenderTranslation;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Custom mod renderers and matching measurement/wrapping paths. No network call runs here. */
@Mixin(Font.class)
public abstract class FontMixin {
    @ModifyVariable(method = {
            "prepareText(Ljava/lang/String;FFIZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            "width(Ljava/lang/String;)I",
            "plainSubstrByWidth(Ljava/lang/String;IZ)Ljava/lang/String;"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String modtranslator$string(String source) { return RenderTranslation.string(source); }

    @ModifyVariable(method = {
            "prepareText(Lnet/minecraft/util/FormattedCharSequence;FFIZZI)Lnet/minecraft/client/gui/Font$PreparedText;",
            "prepare8xTextOutline(Lnet/minecraft/util/FormattedCharSequence;FFI)Lnet/minecraft/client/gui/Font$PreparedText;",
            "width(Lnet/minecraft/util/FormattedCharSequence;)I"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedCharSequence modtranslator$sequence(FormattedCharSequence source) { return RenderTranslation.sequence(source); }

    @ModifyVariable(method = {
            "width(Lnet/minecraft/network/chat/FormattedText;)I",
            "substrByWidth(Lnet/minecraft/network/chat/FormattedText;I)Lnet/minecraft/network/chat/FormattedText;",
            "wordWrapHeight(Lnet/minecraft/network/chat/FormattedText;I)I",
            "split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;",
            "splitIgnoringLanguage(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedText modtranslator$formatted(FormattedText source) { return RenderTranslation.formatted(source); }
}
