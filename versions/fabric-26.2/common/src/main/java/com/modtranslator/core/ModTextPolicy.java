package com.modtranslator.core;

import java.util.*;
import java.util.regex.*;

/** Installed mod brands are data, not prose; UI terminology is shared across providers. */
public final class ModTextPolicy {
    private static volatile Set<String> names = Set.of();
    private static volatile Set<String> npcNames = Set.of(), installedNames = Set.of();
    private static volatile Pattern brands = Pattern.compile("(?!)");
    private static volatile ProtectedTokens protectedTokens;
    private static final Pattern ROLE = Pattern.compile("<[A-Za-z ]{1,60}>");
    private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private record ProtectedTokens(Pattern formatting,Pattern brands,Pattern combined) {}
    private static final Map<String,String> UI = Map.ofEntries(
            Map.entry("Server Admin", "Administração do servidor"),
            Map.entry("Global Properties", "Propriedades globais"),
            Map.entry("Default Dimension Properties", "Propriedades padrão das dimensões"),
            Map.entry("Cave Layers", "Camadas de cavernas"),
            Map.entry("Vertical Chunk", "Seção vertical"),
            Map.entry("Max Zoom increased!", "Zoom máximo aumentado!"),
            Map.entry("Color Palette UI", "Interface da paleta de cores"),
            Map.entry("New Themes!", "Novos temas!"),
            Map.entry("Webmap is now it's own mod", "O mapa web agora é um mod separado"),
            Map.entry("New Work In Progress Waypoint Manager", "Novo gerenciador de pontos de referência em desenvolvimento"),
            Map.entry("Plus Many more updates!", "E muitas outras melhorias!"),
            Map.entry("Steady Debug HUD Refresh Interval", "Intervalo de atualização do HUD de depuração"),
            Map.entry("Steady Debug HUD", "HUD de depuração com atualização fixa"),
            Map.entry("Refresh Interval", "Intervalo de atualização"),
            Map.entry("Debug HUD", "HUD de depuração"),
            Map.entry("Panini Projection Strength", "Intensidade da projeção Panini"),
            Map.entry("Cloud Height Override", "Substituir altura das nuvens"),
            Map.entry("Create Server", "Criar servidor"),
            Map.entry("Create World", "Criar mundo"),
            Map.entry("Create New World", "Criar novo mundo"),
            Map.entry("Tavern", "Taverna"), Map.entry("Armory", "Arsenal"),
            Map.entry("Guard", "Guarda"), Map.entry("Tavern Keeper", "Taverneiro"));
    private static final List<String> UI_TERMS = UI.keySet().stream()
            .sorted(Comparator.comparingInt(String::length).reversed()).toList();

    private ModTextPolicy() {}

    public static synchronized void registerModNames(Collection<String> installedNames) {
        ModTextPolicy.installedNames=Set.copyOf(installedNames);
        rebuildNames();
    }
    public static boolean isNpcNameKey(String key) { return key!=null&&key.startsWith("npc.")&&key.endsWith(".name"); }
    public static synchronized void registerNpcNames(Collection<String> values) {
        npcNames=Set.copyOf(values); rebuildNames();
    }
    private static void rebuildNames() {
        Set<String> variants = new HashSet<>();
        List<String> combined=new ArrayList<>(installedNames); combined.addAll(npcNames);
        for(String name:combined) {
            if(name == null || name.isBlank()) continue;
            name = name.strip();
            variants.add(name);
            variants.add(name.replace('\u2019', '\''));
            variants.add(name.replace('\'', '\u2019'));
        }
        variants.add("Minecraft");
        names = Set.copyOf(variants);
        String alternatives = variants.stream().sorted(Comparator.comparingInt(String::length).reversed())
                .map(name -> name.equals("Create")
                        ? Pattern.quote(name)+"(?!\\s+(?:(?:a|an|the|new|New)\\s+)*(?:[Ss]erver|[Ww]orld|[Bb]ackup|[Pp]rofile|[Tt]eam|[Pp]arty|[Cc]haracter|[Pp]reset|[Ww]aypoint)\\b)"
                        : Pattern.quote(name)).collect(java.util.stream.Collectors.joining("|"));
        brands = Pattern.compile("(?<![\\p{L}\\p{N}_])(?:"+alternatives+")(?![\\p{L}\\p{N}_])");
    }

    public static boolean isModName(String source) { return source != null && names.contains(source.strip()); }
    public static String canonicalQuest(String source) { return "Logs".equals(source) ? "Troncos" : null; }
    public static boolean isUntranslatedJourneyMapFallback(String key,String english,String nativeText) {
        return key != null && key.startsWith("jm.") && english != null && english.equals(nativeText) && !isModName(english);
    }
    public static String canonicalLanguage(String key, String source) {
        // The same words identify a brand elsewhere; only a structured advancement title is prose here.
        return key != null && key.startsWith("advancements.") && key.endsWith(".title")
                && "Ice and Fire".equals(source) ? "Gelo e Fogo" : null;
    }
    public static String canonicalUi(String source) {
        String result=UI.get(source);
        if(result==null && source!=null && source.startsWith("Dimension: ") && DIMENSION_ID.matcher(source.substring(11)).matches())return "Dimensão: "+source.substring(11);
        if(result==null && source!=null && source.startsWith("Vertical Chunk: ") && INTEGER.matcher(source.substring(16)).matches())return "Seção vertical: "+source.substring(16);
        if(result==null && source!=null && source.startsWith("<") && ROLE.matcher(source).matches()) {
            String role=UI.get(source.substring(1,source.length()-1)); if(role!=null)return "<"+role+">";
        }
        return result;
    }
    public static boolean hasEnglishUi(String source) {
        return source != null && (canonicalUi(source)!=null || UI_TERMS.stream().anyMatch(term -> source.contains(term)));
    }
    public static Pattern protectedTokens(Pattern formatting) {
        Pattern currentBrands=brands;
        ProtectedTokens cached=protectedTokens;
        if(cached!=null&&cached.formatting==formatting&&cached.brands==currentBrands)return cached.combined;
        Pattern combined=Pattern.compile("(?:"+formatting.pattern()+")|(?:"+currentBrands.pattern()+")");
        // A single immutable entry: registrations invalidate by pattern identity;
        // distinct caller patterns replace it instead of growing an unbounded cache.
        protectedTokens=new ProtectedTokens(formatting,currentBrands,combined);
        return combined;
    }
    public static boolean namesPreserved(String source,String translated) {
        if(source == null || translated == null) return false;
        Map<String,Integer> required = new HashMap<>(), actual = new HashMap<>();
        Matcher before = brands.matcher(source), after = brands.matcher(translated);
        while(before.find()) required.merge(before.group(),1,Integer::sum);
        while(after.find()) actual.merge(after.group(),1,Integer::sum);
        return required.entrySet().stream().allMatch(e -> Objects.equals(e.getValue(),actual.get(e.getKey())));
    }
    public static String references(String source) {
        StringBuilder result = new StringBuilder();
        for(String term:UI_TERMS) if(source.contains(term)) result.append(term).append(" translates to ").append(UI.get(term)).append(".\n");
        if(source.toLowerCase(Locale.ROOT).contains("tooltip")) result.append("Tooltip means dica de item or descrição contextual, never ferramenta de sugestão.\n");
        return result.toString();
    }
    public static String normalizeUi(String source,String translated) {
        String exact = canonicalUi(source);
        if(exact != null) return exact;
        for(String term:UI_TERMS) if(source.contains(term)) translated = translated.replace(term,UI.get(term));
        return translated;
    }
}
