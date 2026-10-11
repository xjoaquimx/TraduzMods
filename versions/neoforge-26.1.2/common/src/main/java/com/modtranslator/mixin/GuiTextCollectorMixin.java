package com.modtranslator.mixin;

import com.modtranslator.client.RenderTranslation;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Minecraft 26 widgets submit through ActiveTextCollector, bypassing the legacy text methods. */
@Mixin(targets = "net.minecraft.client.gui.GuiGraphicsExtractor$RenderingTextCollector")
public abstract class GuiTextCollectorMixin {
    @ModifyVariable(method = "accept(Lnet/minecraft/client/gui/TextAlignment;IILnet/minecraft/client/gui/ActiveTextCollector$Parameters;Lnet/minecraft/util/FormattedCharSequence;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private FormattedCharSequence modtranslator$sequence(FormattedCharSequence source) { return RenderTranslation.sequence(source); }

    @ModifyVariable(method = "acceptScrolling(Lnet/minecraft/network/chat/Component;IIIIILnet/minecraft/client/gui/ActiveTextCollector$Parameters;)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private Component modtranslator$scrolling(Component source) { return RenderTranslation.component(source); }
}
