package com.modtranslator.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Real production launch/watchdog/inference/restoration and bounded paragraph reuse. */
public final class NativeOptimizationSmoke {
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]),report=Path.of(args[1]);ModelComparisonFixtures.install();
        JsonArray rows=new JsonArray();
        try {
            boolean compact=args[2].equals("compact");
            long startup=System.nanoTime();
            if(!(compact?LocalTranslationClient.startOnDemand(directory):LocalTranslationClient.start(directory)).get(180,TimeUnit.SECONDS))throw new AssertionError(LocalTranslationClient.status());
            boolean effectiveCompact=LocalTranslationClient.isEconomyMode();
            System.out.println("READY backend="+LocalTranslationClient.backend()+" compact="+effectiveCompact+" startupMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-startup));
            for(String source:ModelComparisonFixtures.SOURCES) {
                long start=System.nanoTime();String output=LocalTranslationClient.translate(List.of(source)).get(120,TimeUnit.SECONDS).get(0);
                if(!TranslationService.tokensValid(source,output))throw new AssertionError("Invalid protected output for "+source);
                JsonObject row=new JsonObject();row.addProperty("source",source);row.addProperty("translation",output);row.addProperty("elapsedMs",(System.nanoTime()-start)/1_000_000D);
                long hits=LocalTranslationClient.paragraphReuseCount();start=System.nanoTime();
                String repeated=LocalTranslationClient.translate(List.of(source)).get(120,TimeUnit.SECONDS).get(0);
                if(!Objects.equals(output,repeated)||LocalTranslationClient.paragraphReuseCount()<=hits)throw new AssertionError("Identical paragraph sent for fresh inference");
                row.addProperty("reuseMs",(System.nanoTime()-start)/1_000_000D);rows.add(row);
                Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(rows));
            }
            System.out.println("PASS production native translation, name/format restoration and paragraph reuse; sources="+rows.size());
            if(effectiveCompact) {
                Thread.sleep(21_000);LocalTranslationClient.maintain(true);
                if(!LocalTranslationClient.available())throw new AssertionError("Open reading screen lost its warm translator");
                System.out.println("PASS foreground translator remains warm while reading beyond its idle window");
            }
            LocalTranslationClient.setUserPaused(true);
            if(LocalTranslationClient.available())throw new AssertionError("Pause retained an active translator");
            LocalTranslationClient.setUserPaused(false);
            if(!(compact?LocalTranslationClient.startOnDemand(directory):LocalTranslationClient.start(directory)).get(180,TimeUnit.SECONDS))throw new AssertionError("Resume failed: "+LocalTranslationClient.status());
            long reused=LocalTranslationClient.paragraphReuseCount();
            String resumed=LocalTranslationClient.translate(List.of(ModelComparisonFixtures.SOURCES.get(0))).get(120,TimeUnit.SECONDS).get(0);
            if(!TranslationService.tokensValid(ModelComparisonFixtures.SOURCES.get(0),resumed)||LocalTranslationClient.paragraphReuseCount()<=reused)throw new AssertionError("Resume lost reusable translation");
            System.out.println("PASS production native pause/resume reload and retained paragraph reuse");
        } finally {LocalTranslationClient.stop();}
    }
}
