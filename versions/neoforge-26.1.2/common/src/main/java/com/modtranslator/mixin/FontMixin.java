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
            "drawInBatch(Ljava/lang/String;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            "width(Ljava/lang/String;)I",
            "plainSubstrByWidth(Ljava/lang/String;IZ)Ljava/lang/String;"
    }, at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private String modtranslator$string(String source) { return RenderTranslation.string(source); }

    @ModifyVariable(method = "drawInBatch(Lnet/minecraft/network/chat/Component;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component modtranslator$component(Component source) { return RenderTranslation.component(source); }

    @ModifyVariable(method = {
            "drawInBatch(Lnet/minecraft/util/FormattedCharSequence;FFIZLorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/client/gui/Font$DisplayMode;II)V",
            "drawInBatch8xOutline(Lnet/minecraft/util/FormattedCharSequence;FFIILorg/joml/Matrix4fc;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
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
