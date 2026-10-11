package com.modtranslator.client;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Recognize structured game dialogue, never infer NPC provenance from player-written text. */
public final class NpcDialogueText {
    private NpcDialogueText() {}
    public static boolean isDialogue(Component component) { return inspect(component,0,false); }
    public static boolean isRefreshableGameMessage(Component component) { return inspect(component,0,true); }
    private static boolean inspect(Component component,int depth,boolean advancements) {
        if(depth>64)return false;
        if(component.getContents() instanceof TranslatableContents contents) {
            if(contents.getKey().startsWith("dialogue."))return true;
            if(advancements && (contents.getKey().equals("chat.type.advancement.task")
                    || contents.getKey().equals("chat.type.advancement.goal")
                    || contents.getKey().equals("chat.type.advancement.challenge")))return true;
            // A player message can itself contain translatable arguments; its envelope is a hard boundary.
            if(contents.getKey().equals("chat.type.text") || contents.getKey().equals("chat.type.team.text"))return false;
            for(Object argument:contents.getArgs())if(argument instanceof Component nested&&inspect(nested,depth+1,advancements))return true;
        }
        for(Component sibling:component.getSiblings())if(inspect(sibling,depth+1,advancements))return true;
        return false;
    }
}
