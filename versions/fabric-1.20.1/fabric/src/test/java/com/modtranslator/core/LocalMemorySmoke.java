package com.modtranslator.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real native inference with varied prompts; isolated local runtime, no API keys or player data. */
public class LocalMemorySmoke {
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]),report=Path.of(args[1]);
        GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de madeira","Oak Log","Tronco de carvalho"));
        ModTextPolicy.registerModNames(List.of("Minecraft","Prominence II"));
        ModTextPolicy.registerNpcNames(List.of("Radin of the Side Rail","Durin"));
        JsonArray rows=new JsonArray();
        try {
            if(!LocalTranslationClient.start(directory).get(180,TimeUnit.SECONDS))throw new AssertionError(LocalTranslationClient.status());
            System.out.println("READY "+LocalTranslationClient.backend());
            for(int i=0;i<60;i++) {
                String source=switch(i%6) {
                    case 0->"What can I help with?";
                    case 1->"Of course. What are you looking for?";
                    case 2->"Radin of the Side Rail will guide you. You can use Picareta de madeira to mine blocks.";
                    case 3->"You have "+i+" items in your inventory. Hold the left mouse button to mine stone.";
                    case 4->"Level up your strengths and unlock new abilities. Roll out of danger and avoid attacks.";
                    default->"The Eternal Flame has called for an emergency! Travel outside Vaaz and investigate the undead demons.";
                };
                long start=System.nanoTime();
                String output=LocalTranslationClient.translate(List.of(source)).get(120,TimeUnit.SECONDS).get(0);
                if(!TranslationService.tokensValid(source,output))throw new AssertionError("Invalid formatting/name output: "+i);
                JsonObject row=new JsonObject();row.addProperty("source",source);row.addProperty("translated",output);row.addProperty("elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));rows.add(row);
                Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(rows));
                if(i%10==9)System.out.println("TRANSLATED "+(i+1));
            }
            System.out.println("WAITING_FOR_IDLE_UNLOAD");
            Thread.sleep(46_000);LocalTranslationClient.maintain();
            if(LocalTranslationClient.available())throw new AssertionError("Idle model still alive");
            System.out.println("PASS_IDLE_UNLOADED; translated="+rows.size());
        } finally {LocalTranslationClient.stop();}
    }
}
