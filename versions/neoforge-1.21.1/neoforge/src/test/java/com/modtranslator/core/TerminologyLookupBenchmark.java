package com.modtranslator.core;

import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.util.*;

/** Exercise a large installed glossary without loading a model or a Minecraft world. */
public final class TerminologyLookupBenchmark {
    public static void main(String[] args) throws Exception {
        Map<String,String> terms=new LinkedHashMap<>();
        for(int i=0;i<10_000;i++)terms.put("Synthetic Item "+i,"Item sintético "+i);
        terms.put("Iron Gear","Engrenagem de ferro");terms.put("Copper Widget","Peça de cobre");
        terms.put("Wooden Pickaxe","Picareta de madeira");terms.put("Oak Log","Tronco de carvalho");
        terms.put("İron Relic","Relíquia de ferro");terms.put("Rune Σ","Runa Sigma");
        GameTranslationContext.install(terms);
        List<String> cases=List.of(
            "Use an IRON GEAR with Copper Widget. Hold Left Click to Craft it.",
            "Your Picareta de madeira can mine Iron Gear blocks.",
            "Use an iron relic next to Rune ς.",
            "Iron Gears are required. Iron Gearbox is a separate name.",
            "Mine Oak Log with a Wooden Pickaxe.");
        GameTranslationContext.registerQuestSources(Set.of(cases.get(4)));
        List<Map<String,String>> outputs=new ArrayList<>();
        for(String source:cases){Map<String,String> row=new LinkedHashMap<>();row.put("source",source);
            row.put("local",GameTranslationContext.localReferences(source));
            row.put("remote",GameTranslationContext.contextFor(List.of(source)));
            row.put("normalized",GameTranslationContext.normalizeTranslated(source,"Use Iron Gear e Oak Log."));outputs.add(row);}
        String source=cases.get(0);for(int i=0;i<8;i++){GameTranslationContext.localReferences(source);GameTranslationContext.contextFor(List.of(source));}
        long start=System.nanoTime();int length=0;for(int i=0;i<80;i++)length+=GameTranslationContext.localReferences(source).length();
        double localMs=(System.nanoTime()-start)/1_000_000.0;
        start=System.nanoTime();for(int i=0;i<80;i++)length+=GameTranslationContext.contextFor(List.of(source)).length();
        double remoteMs=(System.nanoTime()-start)/1_000_000.0;
        Map<String,Object> result=new LinkedHashMap<>();result.put("nativeTerms",GameTranslationContext.nativeTermCount());result.put("iterations",80);
        result.put("localTotalMs",localMs);result.put("remoteTotalMs",remoteMs);result.put("lengthChecksum",length);result.put("outputs",outputs);
        Files.writeString(Path.of(args[0]),new GsonBuilder().setPrettyPrinting().create().toJson(result));
        System.out.println("TERMINOLOGY_BENCH "+result.get("nativeTerms")+" terms; local="+localMs+"ms; remote="+remoteMs+"ms");
    }
}
