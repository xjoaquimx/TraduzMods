package com.modtranslator.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/** Reads FTB Quests' external language catalog without changing any quest files. */
public final class QuestCatalog {
    private static final System.Logger LOG=System.getLogger("ModTranslator");
    private QuestCatalog() {}

    public record Catalog(Map<String,String> english,Map<String,String> portuguese) {
        public Catalog { english=Map.copyOf(english);portuguese=Map.copyOf(portuguese); }
    }

    /** configDirectory is Minecraft's config directory, not config/modtranslator. */
    public static Catalog scan(Path configDirectory) {
        Path languageDirectory=configDirectory.resolve("ftbquests").resolve("quests").resolve("lang");
        Map<String,String> english = readLanguage(languageDirectory.resolve("en_us"));
        readLegacyQuests(configDirectory.resolve("ftbquests/quests/chapters"), english);
        return new Catalog(english,readLanguage(languageDirectory.resolve("pt_br")));
    }

    /** Older FTB files store display text directly in SNBT. Use its own parser, read-only. */
    private static void readLegacyQuests(Path directory, Map<String,String> output) {
        if (!Files.isDirectory(directory)) return;
        try {
            var read = Class.forName("dev.ftb.mods.ftblibrary.snbt.SNBT").getMethod("read",Path.class);
            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".snbt")).sorted().toList()) {
                    Object document = read.invoke(null,file);
                    if (document instanceof Tag tag) legacyText(tag,"ftbquests.snbt."+file.getFileName(),false,output);
                }
            }
        } catch (ClassNotFoundException absent) {
            // FTB is optional.
        } catch (Exception failure) {
            LOG.log(System.Logger.Level.WARNING,"Could not scan legacy FTB quest display text.",failure);
        }
    }

    private static void legacyText(Tag tag,String key,boolean display,Map<String,String> output) {
        if (tag instanceof CompoundTag compound) {
            for (String child : compound.getAllKeys()) {
                Tag value=compound.get(child);
                if(value!=null) legacyText(value,key+"."+child,child.equals("title")||child.equals("subtitle")||child.equals("description"),output);
            }
        } else if (tag instanceof ListTag list) {
            for(int i=0;i<list.size();i++) legacyText(list.get(i),key+"."+i,display,output);
        } else if(display && tag instanceof StringTag text && !text.getAsString().isBlank()) {
            output.put(key,text.getAsString());
        }
    }

    private static Map<String,String> readLanguage(Path directory) {
        Map<String,String> strings=new LinkedHashMap<>();
        if(!Files.isDirectory(directory)) return strings;
        try(Stream<Path> files=Files.walk(directory)) {
            for(Path file:files.filter(Files::isRegularFile).filter(QuestCatalog::isLanguageFile).sorted().toList()) {
                try(JsonReader reader=new JsonReader(new StringReader(removeTrailingCommas(Files.readString(file))))) {
                    reader.setLenient(true);
                    JsonElement document=JsonParser.parseReader(reader);
                    flatten(document,"",strings);
                } catch(Exception failure) {
                    LOG.log(System.Logger.Level.WARNING,"Skipped unreadable FTB Quests language file {0}",directory.relativize(file));
                }
            }
        } catch(Exception failure) {
            LOG.log(System.Logger.Level.WARNING,"Could not enumerate FTB Quests language catalog.");
        }
        return strings;
    }

    private static boolean isLanguageFile(Path file) {
        String name=file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".json5")||name.endsWith(".json");
    }

    private static void flatten(JsonElement value,String key,Map<String,String> output) {
        if(value.isJsonObject()) {
            value.getAsJsonObject().entrySet().forEach(entry -> flatten(entry.getValue(),key.isEmpty()?entry.getKey():key+"."+entry.getKey(),output));
        } else if(value.isJsonArray()) {
            for(int index=0;index<value.getAsJsonArray().size();index++) flatten(value.getAsJsonArray().get(index),key+"["+index+"]",output);
        } else if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&!key.isEmpty()) {
            // Keep empty lines and formatting exactly; the service decides what needs translation.
            output.put(key,value.getAsString());
        }
    }

    // Gson LENIENT accepts JSON5 comments/single quotes/unquoted names, but not a final
    // object comma. Remove only structural trailing commas; never touch quest prose.
    private static String removeTrailingCommas(String input) {
        StringBuilder output=new StringBuilder(input.length());
        char quote=0;boolean escaped=false,lineComment=false,blockComment=false;
        for(int index=0;index<input.length();index++) {
            char current=input.charAt(index),next=index+1<input.length()?input.charAt(index+1):0;
            if(lineComment) {output.append(current);if(current=='\r'||current=='\n')lineComment=false;continue;}
            if(blockComment) {output.append(current);if(current=='*'&&next=='/'){output.append(next);index++;blockComment=false;}continue;}
            if(quote!=0) {output.append(current);if(escaped)escaped=false;else if(current=='\\')escaped=true;else if(current==quote)quote=0;continue;}
            if(current=='\''||current=='"') {quote=current;output.append(current);continue;}
            if(current=='/'&&(next=='/'||next=='*')) {lineComment=next=='/';blockComment=next=='*';output.append(current).append(next);index++;continue;}
            if(current==',') {int following=skipTrivia(input,index+1);if(following<input.length()&&(input.charAt(following)==']'||input.charAt(following)=='}'))continue;}
            output.append(current);
        }
        return output.toString();
    }

    private static int skipTrivia(String input,int index) {
        while(index<input.length()) {
            if(Character.isWhitespace(input.charAt(index))) {index++;continue;}
            if(input.charAt(index)=='/'&&index+1<input.length()&&input.charAt(index+1)=='/') {
                index+=2;while(index<input.length()&&input.charAt(index)!='\r'&&input.charAt(index)!='\n')index++;continue;
            }
            if(input.charAt(index)=='/'&&index+1<input.length()&&input.charAt(index+1)=='*') {
                int end=input.indexOf("*/",index+2);if(end<0)return input.length();index=end+2;continue;
            }
            return index;
        }
        return index;
    }
}
