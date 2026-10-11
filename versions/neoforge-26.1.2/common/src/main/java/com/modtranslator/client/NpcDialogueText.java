package com.modtranslator.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Recognize structured game dialogue, never infer NPC provenance from player-written text. */
public final class NpcDialogueText {
    private NpcDialogueText() {}
    public static boolean isDialogue(Component component) { return inspect(component,0); }
    private static boolean inspect(Component component,int depth) {
        if(depth>64)return false;
        if(component.getContents() instanceof TranslatableContents contents) {
            if(contents.getKey().startsWith("dialogue."))return true;
            for(Object argument:contents.getArgs())if(argument instanceof Component nested&&inspect(nested,depth+1))return true;
        }
        for(Component sibling:component.getSiblings())if(inspect(sibling,depth+1))return true;
        return false;
    }
}
