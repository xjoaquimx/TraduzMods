package com.modtranslator.client;
import net.minecraft.client.Minecraft;
/** Reading a menu in a loaded world gives visible text exclusive inference priority. */
public final class InteractiveTranslationContext {
    private InteractiveTranslationContext() {}
    public static boolean isReadingScreen(){var game=Minecraft.getInstance();return game!=null&&game.level!=null&&game.gui.screen()!=null;}
}
