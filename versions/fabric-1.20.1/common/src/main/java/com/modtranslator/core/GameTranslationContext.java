package com.modtranslator.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resource-pack native terminology. Installed by the resource scanner, never by quest-specific translations. */
public final class GameTranslationContext {
    private static volatile NativeTerminology nativeTerminology=new NativeTerminology(Map.of(),Collections.emptyNavigableMap());
    private record RuleCatalog(List<Rule> rules,TerminologyIndex index) {}
    private static volatile RuleCatalog ruleCatalog=new RuleCatalog(List.of(),new TerminologyIndex(List.of()));
    private static final Set<String> QUEST_SOURCES=ConcurrentHashMap.newKeySet();
    private static final Pattern COLORS=Pattern.compile("&(?![A-Za-z][A-Za-z0-9]*;)[0-9a-fk-orA-FK-OR]|§x(?:§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]");
    private static final Pattern SKIP=Pattern.compile("</?[A-Za-z][^<>]*>|https?://[^\\s<>]+|%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])|%%|\\$\\{[^{}]+}|\\{[^{}]*}|\\$\\([^)]*\\)|[a-z0-9_]+:[a-z0-9_/.-]+|MODTRPHX*\\d+X|&(?![A-Za-z][A-Za-z0-9]*;)[0-9a-fk-orA-FK-OR]|§.");
    private record Rule(String english,String portuguese,Pattern pattern) {}
    private static final List<Rule> ACTIONS=List.of(
            action("Mine","minerar"),action("punch","dar um soco"),action("Left Click","botão esquerdo do mouse"),
            action("Drop","soltar"),action("Craft","criar"),action("Crafting","criação"),action("Smelt","fundir"),action("Smelting","fundição"),
            action("falling","caindo"),action("Warp","teleporte"),action("Early Birds","madrugadores"),action("Netherwart","Fungo do Nether"));
    private record NativeTerminology(Map<String,String> exact,NavigableMap<String,String> folded) {
        private static NativeTerminology index(Map<String,String> names) {
            Map<String,String> exact=Map.copyOf(names);
            TreeMap<String,String> folded=new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            // Match the old fallback's first winner in this exact map's iteration order.
            exact.forEach(folded::putIfAbsent);
            return new NativeTerminology(exact,Collections.unmodifiableNavigableMap(folded));
        }
        private String find(String key) {
            String direct=exact.get(key);
            return direct!=null?direct:folded.get(key);
        }
    }
    private GameTranslationContext() {}

    public static synchronized void install(Map<String,String> englishToPortuguese) {
        LinkedHashMap<String,String> names=new LinkedHashMap<>();
        englishToPortuguese.forEach((english,pt)-> {
            if(english!=null&&pt!=null&&!english.isBlank()&&!pt.isBlank()&&english.length()<=120&&!english.equals(pt))names.putIfAbsent(english,pt);
        });
        String pick=find(names,"Wooden Pickaxe");
        if(pick!=null) {
            names.putIfAbsent("Wooden Pick",pick);
            names.putIfAbsent("Pickaxe",head(pick));names.putIfAbsent("Pick",head(pick));
        }
        String log=find(names,"Oak Log");
        // Vanilla also has a diagnostic "Log" label (Registrar). A quest's log
        // denotes wood, so derive this ambiguous noun from the actual Oak Log item.
        if(log!=null)names.put("Log",head(log));
        String planks=find(names,"Oak Planks");
        if(planks!=null){names.put("Planks",head(planks));names.putIfAbsent("Plank",singular(head(planks)));}
        String ore=find(names,"Iron Ore");if(ore!=null)names.putIfAbsent("Ore",head(ore));
        String gems=find(names,"Gems");if(gems!=null)names.putIfAbsent("Gem",singular(gems));
        for(String tool:List.of("Axe","Sword","Shovel","Hoe")) {String wood=find(names,"Wooden "+tool);if(wood!=null)names.putIfAbsent(tool,head(wood));}
        String wood=find(names,"Oak Wood");if(wood!=null)names.putIfAbsent("Wood",head(wood));
        names.putIfAbsent("Tree","Árvore");
        String stone=find(names,"Stone");
        if(stone!=null){names.putIfAbsent("Stone Blocks","Blocos de "+stone);names.putIfAbsent("Stone Tools","Ferramentas de "+stone);}
        names.put("Red X","X vermelho");names.put("Green Checkmark","Marca de verificação verde");names.put("Checkmark","Marca de verificação");
        for(var entry:List.copyOf(names.entrySet())) {
            String plural=englishPlural(entry.getKey());
            if(plural!=null)names.putIfAbsent(plural,portuguesePlural(entry.getValue()));
        }
        nativeTerminology=NativeTerminology.index(names);
        List<Rule> rules=names.entrySet().stream().sorted(Comparator.<Map.Entry<String,String>>comparingInt(e->e.getKey().length()).reversed())
                .map(e->new Rule(e.getKey(),e.getValue(),word(e.getKey()))).toList();
        ruleCatalog=new RuleCatalog(rules,new TerminologyIndex(rules.stream().map(Rule::english).toList()));
    }

    private static List<Rule> candidateRules(String text) {
        RuleCatalog catalog=ruleCatalog;
        BitSet matches=catalog.index.candidates(text);
        List<Rule> result=new ArrayList<>(matches.cardinality());
        for(int at=matches.nextSetBit(0);at>=0;at=matches.nextSetBit(at+1))result.add(catalog.rules.get(at));
        return result;
    }

    public static void registerQuestSources(Set<String> sources) { sources.forEach(source->{if(source!=null){String visible=plain(source);QUEST_SOURCES.add(visible);for(String paragraph:visible.split("[\\r\\n]+"))if(!paragraph.isBlank())QUEST_SOURCES.add(paragraph.strip());}}); }
    public static boolean isQuestSource(String source) { return source!=null&&QUEST_SOURCES.contains(plain(source)); }
    public static boolean isNativeNameKey(String key) {
        return key.startsWith("item.")||key.startsWith("block.")||key.startsWith("tag.")||key.startsWith("entity.")||key.startsWith("biome.")||key.startsWith("enchantment.")||key.startsWith("effect.")||key.startsWith("attribute.")||key.startsWith("rarity.")||(key.startsWith("painting.")&&key.endsWith(".title"));
    }
    public static int nativeTermCount() {return nativeTerminology.exact.size();}
    public static String nativeLabel(String english) {
        if(english.equalsIgnoreCase("Mine"))return "Minerar";
        if(english.equalsIgnoreCase("Minecraft"))return "Minecraft";
        if(english.equalsIgnoreCase("Apotheosis"))return "Apotheosis";
        return nativeTerminology.find(english.strip());
    }
    public static String canonicalStyledLabel(String sourceBody,String translatedBody) {
        String nativeValue=nativeLabel(sourceBody);
        if(nativeValue==null)return translatedBody;
        String body=translatedBody.strip();
        if(body.isEmpty())return translatedBody;
        if(SKIP.matcher(body).find()||body.split("\\s+").length>Math.max(nativeValue.split("\\s+").length,sourceBody.strip().split("\\s+").length)+2)return translatedBody;
        var article=Pattern.compile("(?iu)^(uma|um|a|o|as|os|umas|uns)\\s+").matcher(body);
        String prefix=article.find()?article.group():"";
        var punctuation=Pattern.compile("[,.;:!?]+$").matcher(body);
        String suffix=punctuation.find()?punctuation.group():"";
        return prefix+nativeValue+suffix;
    }

    public static String contextFor(List<String> sources) {
        StringBuilder context=new StringBuilder("Minecraft game quest and interface text. Translate each complete sentence naturally into Brazilian Portuguese, with correct noun gender, number and articles. Preserve all placeholders and style tags, keeping their original item styling. Minecraft and mod names are brands and must stay invariant. In quests, punch means strike with the fist, socar or bater, never press a key. Mine means minerar. Log means a tree trunk in a gathering quest. Use the exact native game labels below, not dictionary alternatives. Early Birds means madrugadores. Netherwart means Fungo do Nether.\nNative game terminology:\n");
        LinkedHashSet<Rule> selected=new LinkedHashSet<>();
        for(String source:sources) {
            String visible=plain(source);
            for(Rule rule:candidateRules(visible)) {
                if((rule.english.equalsIgnoreCase("Log")||rule.english.equalsIgnoreCase("Logs"))&&!isQuestSource(source))continue;
                if(rule.pattern.matcher(visible).find())selected.add(rule);
            }
        }
        for(Rule rule:selected) {
            String line=rule.english+" translates to "+rule.portuguese+".\n";
            if(context.length()+line.length()>3200)break;
            context.append(line);
        }
        for(String source:sources) context.append(ModTextPolicy.references(source));
        return context.toString();
    }

    /** Hy-MT's reference template: keep directives short and terminology source-specific. */
    public static String localReferences(String source) {
        String visible=plain(source);StringBuilder reference=new StringBuilder("Reference the following translations:\n").append(ModTextPolicy.references(visible));
        for(Rule rule:candidateRules(visible))if(rule.pattern.matcher(visible).find()) {
            if((rule.english.equalsIgnoreCase("Log")||rule.english.equalsIgnoreCase("Logs"))&&!isQuestSource(source))continue;
            String line=rule.english+" translates to "+rule.portuguese+".\n";
            if(reference.length()+line.length()>TranslationEdition.current().referenceCharacters())break;reference.append(line);
        }
        for(Rule action:ACTIONS)if(action.pattern.matcher(visible).find())reference.append(action.english).append(" translates to ").append(action.portuguese).append(".\n");
        return reference.toString();
    }

    /** Only quest catalog sources get ambiguous noun corrections such as logs -> tree trunks. */
    public static String normalizeTranslated(String source,String output) {
        if(!isQuestSource(source)||output==null)return output;
        String visibleSource=plain(source);
        List<Rule> relevant=candidateRules(visibleSource).stream().filter(rule->rule.pattern.matcher(visibleSource).find()).toList();
        Matcher tokens=SKIP.matcher(output);StringBuilder result=new StringBuilder();int offset=0;
        while(tokens.find()) {
            result.append(normalizeVisible(visibleSource,output.substring(offset,tokens.start()),relevant)).append(tokens.group());offset=tokens.end();
        }
        String normalized=result.append(normalizeVisible(visibleSource,output.substring(offset),relevant)).toString();
        String pick=nativeTerminology.find("Wooden Pickaxe");
        if(pick!=null&&word("Wooden Pickaxe").matcher(visibleSource).find()) {
            String codes="(?:[&§][0-9a-fk-orA-FK-OR])*";
            normalized=normalized.replaceAll("(?iu)\\b(é|será) o(\\s+"+codes+")("+Pattern.quote(pick)+")", "$1 a$2$3");
        }
        if(visibleSource.startsWith("Like all Minecraft starts")) {
            String color="[&§][0-9a-fk-orA-FK-OR]";
            normalized=normalized.replaceAll("(?iu)Assim como todos os(\\s+(?:"+color+")*)Minecraft((?:"+color+")*)\\s+começam,", "Como em todo início no$1Minecraft$2,");
        }
        Set<String> genderChecked=new HashSet<>();
        for(Rule rule:relevant)if(genderChecked.add(rule.portuguese)) {
            String noun=head(rule.portuguese).toLowerCase(Locale.ROOT);
            boolean female=(noun.endsWith("a")||noun.endsWith("as")||noun.equals("árvore")||noun.equals("árvores"))&&!Set.of("mapa","sistema","programa","problema","tema","dia").contains(noun);
            if(!female)continue;
            boolean plural=noun.endsWith("s");
            var agreement=Pattern.compile("(?iu)\\b(o|os|um|uns|nosso|nossos|seu|seus|aquele|aqueles|este|estes|esse|esses)(\\s+(?:[&§][0-9a-fk-orA-FK-OR])*)"+Pattern.quote(rule.portuguese)).matcher(normalized);
            StringBuilder revised=new StringBuilder();
            while(agreement.find()) {
                String masculine=agreement.group(1).toLowerCase(Locale.ROOT),femaleWord=switch(masculine){case "o","os"->"a";case "um","uns"->"uma";case "nosso","nossos"->"nossa";case "seu","seus"->"sua";case "aquele","aqueles"->"aquela";case "este","estes"->"esta";default->"essa";};
                if(plural)femaleWord+="s";
                if(Character.isUpperCase(agreement.group(1).charAt(0)))femaleWord=Character.toUpperCase(femaleWord.charAt(0))+femaleWord.substring(1);
                agreement.appendReplacement(revised,Matcher.quoteReplacement(femaleWord+agreement.group(2)+rule.portuguese));
            }
            agreement.appendTail(revised);normalized=revised.toString();
        }
        return normalized;
    }

    private static String normalizeVisible(String source,String text,List<Rule> relevant) {
        for(Rule rule:relevant)text=replace(text,rule.pattern,rule.portuguese);
        String pick=matchingTarget(source,"Wooden Picks","Wooden Pickaxes","Wooden Pick","Wooden Pickaxe","Pickaxes","Picks","Pickaxe","Pick");
        // Incorrect Portuguese synonyms observed from machine providers; replacements come from installed native labels.
        if(pick!=null) {
            String single=head(nativeTerminology.find("Pickaxe"));
            text=replace(text,Pattern.compile("\\b(?:picões|picãos|piões)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),portuguesePlural(single));
            text=replace(text,Pattern.compile("\\b(?:picão|pião)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),single);
        }
        String table=matchingTarget(source,"Crafting Tables","Crafting Table");
        if(table!=null) {
            String single=nativeTerminology.find("Crafting Table");
            text=replace(text,Pattern.compile("\\b(?:tabelas de criação|mesas de artesanato|mesas de fabricação)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),portuguesePlural(single));
            text=replace(text,Pattern.compile("\\b(?:tabela de criação|mesa de artesanato|mesa de fabricação)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),single);
        }
        String furnace=matchingTarget(source,"Furnaces","Furnace");
        if(furnace!=null) {
            String single=nativeTerminology.find("Furnace");
            text=replace(text,word("fornalhas"),portuguesePlural(single));text=replace(text,word("fornalha"),single);
        }
        String logs=matchingTarget(source,"Logs","Log");
        if(logs!=null)text=replace(text,Pattern.compile("\\b(?:registros?|diários?)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),logs);
        String dust=matchingTarget(source,"Gem Dust");
        if(dust!=null)text=replace(text,Pattern.compile("\\b(?:poeira|pó) de (?:jade|gemas?)\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),dust);
        String gem=matchingTarget(source,"Gems","Gem");
        if(gem!=null) {
            String single=nativeTerminology.find("Gem");
            if(single!=null){text=replace(text,word("Jades"),portuguesePlural(single));text=replace(text,word("Jade"),single);}
        }
        if(word("Minecraft").matcher(source).find())text=replace(text,word("Minecrafts"),"Minecraft");
        if(word("Apotheosis").matcher(source).find())text=replace(text,word("Apoteose"),"Apotheosis");
        if(word("Red X").matcher(source).find())text=replace(text,Pattern.compile("\\bX vermelh[oa]\\b",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE),"X vermelho");
        if(word("punch").matcher(source).find()&&word("Tree").matcher(source).find()) {
            text=text.replaceAll("(?iu)\\b(?:dar|dê) um soco (?:aquela|aquele)\\b", "dê um soco naquela");
            text=text.replaceAll("(?iu)\\bdar um soco (?:na|no)\\b", "dê um soco na");
            text=text.replaceAll("(?iu)\\bfaça o impacto\\b", "dê um soco");
            text=text.replaceAll("(?iu)\\bpressione\\s+aquele\\b", "dê um soco naquela");
            text=text.replaceAll("(?iu)\\bpressione\\s+aquela\\b", "dê um soco naquela");
            text=text.replaceAll("(?iu)\\bpressione\\s+(?:a|o)\\b", "dê um soco na");
        }
        if(word("Mine").matcher(source).find()) {
            text=text.replaceAll("(?iu)\\bVocê minerar\\b","Você minera");
            text=text.replaceAll("(?iu)\\bmineriza\\b","minera");
        }
        if(word("Smelting").matcher(source).find())text=replace(text,word("fundação"),"fundição");
        if(word("Logs").matcher(source).find()&&word("Drop").matcher(source).find())text=text.replaceAll("(?iu)\\bvão soltar\\b","cairão");
        if(word("Tool").matcher(source).find())text=text.replaceAll("(?iu)\\bo primeiro ferramenta\\b", "A primeira ferramenta");
        return text;
    }

    private static String matchingTarget(String source,String... aliases) {
        for(String alias:aliases)if(word(alias).matcher(source).find()) {String pt=nativeTerminology.find(alias);if(pt!=null)return pt;}
        return null;
    }
    private static Rule action(String english,String portuguese) { return new Rule(english,portuguese,word(english)); }
    private static Pattern word(String text) { return Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(text)+"(?![\\p{L}\\p{N}_])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE); }
    private static String replace(String text,Pattern pattern,String replacement) { return pattern.matcher(text).replaceAll(Matcher.quoteReplacement(replacement)); }
    private static String find(Map<String,String> map,String key) {String direct=map.get(key);if(direct!=null)return direct;for(var entry:map.entrySet())if(entry.getKey().equalsIgnoreCase(key))return entry.getValue();return null;}
    private static String head(String label) {int space=label.indexOf(' ');return space<0?label:label.substring(0,space);}
    private static String singular(String label) {String first=head(label);return (first.endsWith("s")?first.substring(0,first.length()-1):first)+label.substring(first.length());}
    private static String englishPlural(String label) {
        if(label.endsWith("s"))return null;
        if(label.matches(".*[^aeiou]y$"))return label.substring(0,label.length()-1)+"ies";
        if(label.endsWith("ch")||label.endsWith("sh")||label.endsWith("x")||label.endsWith("z"))return label+"es";
        return label+"s";
    }
    private static String portuguesePlural(String label) {
        String head=head(label),plural=head;
        if(head.endsWith("ão"))plural=head.substring(0,head.length()-2)+"ões";
        else if(head.endsWith("m"))plural=head.substring(0,head.length()-1)+"ns";
        else if(head.endsWith("al"))plural=head.substring(0,head.length()-2)+"ais";
        else if(head.endsWith("el"))plural=head.substring(0,head.length()-2)+"eis";
        else if(head.endsWith("ol"))plural=head.substring(0,head.length()-2)+"ois";
        else if(head.endsWith("il"))plural=head.substring(0,head.length()-2)+"is";
        else if(head.endsWith("r")||head.endsWith("z"))plural=head+"es";
        else if(!head.endsWith("s"))plural=head+"s";
        return plural+label.substring(head.length());
    }
    private static String plain(String source) {
        return COLORS.matcher(source.replaceAll("</?mt\\d+>","")).replaceAll("").replace("&lt;","<").replace("&gt;",">").replace("&amp;","&").replace("\\n","\n").replace("\\r","\r").replace("\\t","\t").strip();
    }
}
