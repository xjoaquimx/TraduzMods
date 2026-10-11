package com.modtranslator.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;

/** Uses production protection, resource terminology, restoration and cache validation. */
public final class ModelComparisonFixtures {
    static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    static final List<String> SOURCES=List.of(
            "What can I help with?",
            "Hold the left mouse button to mine stone.",
            "With your Wooden Pickaxe, you can mine stone to obtain cobblestone. A furnace needs fuel: wood, coal and sticks work.",
            "Nether Fortresses are structures found in the Nether. They contain chests and Nether Wart.",
            "Radin of the Side Rail will guide you. The Eternal Flame has called for an emergency!",
            "You have %s items. Press {key} to open your inventory.",
            "Extended and Advanced AE are addons for Applied Energistics 2. They improve existing devices and add more features.",
            "Disable shaders before changing the Panini projection. This option updates the debug HUD every 3 ticks.");
    static void install() {
        GameTranslationContext.install(Map.ofEntries(Map.entry("Wooden Pickaxe","Picareta de madeira"),Map.entry("Furnace","Fornalha"),Map.entry("Cobblestone","Pedregulho"),Map.entry("Stone","Pedra"),Map.entry("Coal","Carvão"),Map.entry("Stick","Graveto"),Map.entry("Oak Wood","Madeira de carvalho"),Map.entry("Nether Wart","Fungo do Nether")));
        GameTranslationContext.registerQuestSources(new HashSet<>(SOURCES));
        ModTextPolicy.registerModNames(List.of("Minecraft","Applied Energistics 2","Extended AE","Advanced AE"));
        ModTextPolicy.registerNpcNames(List.of("Radin of the Side Rail"));
    }
    public static void main(String[] args)throws Exception {
        install();Path file=Path.of(args[1]);
        if(args[0].equals("prepare")) {
            JsonArray fixtures=new JsonArray();
            for(String source:SOURCES) {
                var protectedText=TextProtection.protect(source);String chunk=protectedText.value();
                String prompt=TranslationPrompt.build(chunk,false);
                JsonObject row=new JsonObject();row.addProperty("source",source);row.addProperty("prompt",prompt);fixtures.add(row);
            }
            Files.writeString(file,GSON.toJson(fixtures));return;
        }
        JsonObject report=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        for(JsonElement result:report.getAsJsonArray("results"))for(JsonElement element:result.getAsJsonObject().getAsJsonArray("samples")) {
            JsonObject row=element.getAsJsonObject();String source=row.get("source").getAsString(),output=row.get("translation").getAsString();
            try {
                String restored=TextProtection.protect(source).restore(output);
                restored=GameTranslationContext.normalizeTranslated(source,restored);
                row.addProperty("restored",restored);
                row.addProperty("formatAndNamesValid",TranslationService.tokensValid(source,restored));
            } catch(RuntimeException invalid) {row.addProperty("formatAndNamesValid",false);row.addProperty("validationError",invalid.getClass().getSimpleName());}
        }
        Files.writeString(file,GSON.toJson(report));
        System.out.println("Validated native outputs with production protection and terminology; linguistic review remains separate.");
    }
}
