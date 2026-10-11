package com.modtranslator.core;

import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import com.modtranslator.client.StyledTextCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.regex.Pattern;

/** The worker belongs to the client: discovery, retries, inference and persistence run off the render thread. */
public final class TranslationService {
    private static final System.Logger LOG = System.getLogger("ModTranslator");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Gson CACHE_JSON = new Gson();
    private static final int TRANSLATION_QUALITY_VERSION = 2;
    private static final Pattern LANGUAGE_FORMAT = Pattern.compile("%(\\d+\\$)?[\\d.]*[df]");
    static final Pattern FORMAT_TOKEN = Pattern.compile("%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z]|%%|\\$\\{[^{}]+}|\\{(?:\\d+|[A-Za-z_][\\w.-]*)(?:,[^{}]*)?}|§.|&(?![A-Za-z][A-Za-z0-9]*;)[0-9a-fk-orA-FK-OR]|\\$\\([^)]*\\)|https?://[^\\s]+|[a-z0-9_]+:[a-z0-9_/.-]+");
    private static final Pattern ENGLISH_PROSE_WORDS=Pattern.compile("\\b(?:the|your|you|with|without|when|from|into|can|cannot|created|by|found|they|their|what|looking|help|join|welcome|abilities|strengths|unlock)\\b");
    private static final Pattern PORTUGUESE_ACCENTS=Pattern.compile(".*[ãõçáéíóúâêô].*");
    private static final Pattern PORTUGUESE_WORDS=Pattern.compile(".*\\b(?:você|voce|para|com|sem|uma|não|nao|itens|ativado|desativado|clique|pressione|configuração|tradução)\\b.*");
    private static final Pattern RESOURCE_IDENTIFIER=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    private static final Pattern DOTTED_IDENTIFIER=Pattern.compile("[a-z0-9_]+(?:\\.[a-z0-9_]+){2,}");
    private static final Pattern ENGLISH_WORDS=Pattern.compile("(?s).*\\b(?:the|this|that|these|those|is|are|your|you|with|without|only|when|while|from|into|using|used|use|can|cannot|not|enable|enabled|disable|disabled|click|press|hold|show|hide|open|close|add|remove|quest|quests|blocking|strength|damage|speed|power|energy|storage|inventory|requires|required|crafting|recipe|recipes|map|waypoint|waypoints|settings|options|description|color|space|animate|textures|visible|carrot|health|armor|attack|level|range|duration|amount|capacity|empty|full|mode|filter|search|accept|cancel|back|next|previous|item|items|block|blocks|warning|error|missing|load|save|create|delete|progress|reward|rewards|complete|completed|task|tasks|tree|trees|wood|stone|iron|gold|diamond|food|water|left|right|top|bottom)\\b.*");
    private static final Set<String> NON_MOD_IDS = Set.of("minecraft", "modtranslator", "fabricloader", "neoforge", "fml");
    private static volatile TranslationService instance;
    private static volatile boolean portugueseActive;
    public record TranslationProgress(boolean enabled,boolean active,String phase,long completed,long total,long pending,long visiblePending,String edition,String reason,long sessionCompleted) {
        public TranslationProgress(boolean enabled,boolean active,String phase,long completed,long total,long pending,long visiblePending,String edition) {
            this(enabled,active,phase,completed,total,pending,visiblePending,edition,"",0);
        }
    }
    private static final TranslationProgress DISABLED_PROGRESS=new TranslationProgress(false,false,"disabled",0,0,0,0,TranslationEdition.current().id());
    /** A local inference boundary; production never selects an external translation service. */
    interface LocalProvider {
        boolean available();
        void maintain();
        default void maintain(boolean readingScreen){maintain();}
        CompletableFuture<Boolean> start(Path directory);
        CompletableFuture<List<String>> translate(List<String> sources);
        String status();
        void stop();
        default CompletableFuture<Boolean> startOnDemand(Path directory){return start(directory);}
        default boolean isEconomyMode(){return false;}
        default CompletableFuture<List<String>> translateBackground(List<String> sources){return translate(sources);}
        default void requestInteractivePriority(){}
        default long restartDelayMillis(){return 0;}
    }
    private volatile LocalProvider provider=new LocalProvider() {
        public boolean available(){return LocalTranslationClient.available();}
        public void maintain(){LocalTranslationClient.maintain();}
        public void maintain(boolean readingScreen){LocalTranslationClient.maintain(readingScreen);}
        public CompletableFuture<Boolean> start(Path directory){return LocalTranslationClient.start(directory);}
        public CompletableFuture<List<String>> translate(List<String> sources){return LocalTranslationClient.translate(sources);}
        public String status(){return LocalTranslationClient.status();}
        public void stop(){LocalTranslationClient.stop();}
        public CompletableFuture<Boolean> startOnDemand(Path directory){return LocalTranslationClient.startOnDemand(directory);}
        public boolean isEconomyMode(){return LocalTranslationClient.isEconomyMode();}
        public CompletableFuture<List<String>> translateBackground(List<String> sources){return LocalTranslationClient.translateBackground(sources);}
        public void requestInteractivePriority(){LocalTranslationClient.requestInteractivePriority();}
        public long restartDelayMillis(){return LocalTranslationClient.restartDelayMillis();}
    };
    private final Config config;
    private final AtomicLong pauseGeneration=new AtomicLong();
    private final Path directory;
    private final Set<String> modIds;
    private volatile Map<String,String> english = Map.of(), nativePortuguese = Map.of();
    private volatile Set<String> nativeTexts = Set.of(), knownEnglishTexts = Set.of();
    private volatile Map<String,String> questNativeBySource = Map.of();
    private volatile Set<String> questSources = Set.of();
    private final Set<String> translatedTexts = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String,ConcurrentHashMap<String,String>> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,String> bySource = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,Job> jobs = new ConcurrentHashMap<>();
    private final IndexedWorkDeque visible = new IndexedWorkDeque(), background = new IndexedWorkDeque();
    // Known interface labels need no model and must not wait for RAM admission.
    private final IndexedWorkDeque canonicalReady = new IndexedWorkDeque();
    // At most one entry per admitted job; the renderer never traverses this set or the queue.
    private final Set<String> visibleTracking=ConcurrentHashMap.newKeySet();
    private volatile TranslationProgress progressSnapshot=DISABLED_PROGRESS;
    private long catalogCachedAtScan,progressCommitsSinceScan,liteVisibleCompleted;
    private volatile boolean activeBatchBackground;
    private final AtomicBoolean backgroundYieldRequested=new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicLong revision = new AtomicLong(), reloadGeneration = new AtomicLong();
    private final AtomicBoolean tickRequested=new AtomicBoolean();
    private final AtomicLong queueVersion=new AtomicLong();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> { Thread t=new Thread(r,"traduzmods-worker"); t.setDaemon(true); if(TranslationEdition.current()==TranslationEdition.LITE)t.setPriority(Thread.MIN_PRIORITY); return t; });
    private volatile long retryGlobalUntil, localRestartAfter;
    private volatile String providerState = "starting", lastFailure = "";
    private volatile boolean closed, catalogReady;
    private volatile int discovered;
    private int commits;
    private boolean cacheDirty=true;
    private long lastCheckpoint,lastQueueSnapshot,savedQueueVersion=-1,lastTranslationMs;
    private long lastPrioritySweep;
    private java.util.function.BooleanSupplier readingScreenProbe=com.modtranslator.client.InteractiveTranslationContext::isReadingScreen;

    private TranslationService(Config config,Path directory,Collection<String> ids) {
        this.config=config; this.directory=directory; this.modIds=Set.copyOf(ids);
        questSources=Set.copyOf(QuestCatalog.scan(directory.getParent()).english().values());
        GameTranslationContext.registerQuestSources(questSources);
        loadCache();loadJournal();loadQueue();
        worker.scheduleWithFixedDelay(this::tickSafely,TranslationEdition.current().workerIntervalMillis(),TranslationEdition.current().workerIntervalMillis(),TimeUnit.MILLISECONDS);
        worker.scheduleWithFixedDelay(() -> persist(false),10,10,TimeUnit.SECONDS);
        // Load native inference only when work actually needs it; cache/native strings need no model.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { closed=true; worker.shutdownNow(); persistSafely(); },"traduzmods-save"));
    }
    public static synchronized void initialize(Path configDirectory,Collection<String> ids) {
        try {
            Path directory=configDirectory.resolve("modtranslator"); Files.createDirectories(directory);
            Path file=directory.resolve("config.json");
            Config config=Files.isRegularFile(file)?GSON.fromJson(Files.readString(file),Config.class):new Config();
            if(config==null) config=new Config(); config.defaults();
            // Migrate legacy configuration: obsolete external-provider fields are ignored and removed.
            atomicWrite(file,GSON.toJson(config));
            LocalTranslationClient.setUserPaused(config.translationPaused);
            instance=new TranslationService(config,directory,ids);
            LOG.log(System.Logger.Level.INFO,"TraduzMods 2.0.0-alpha.3 {0} automatic worker initialized; {1} cached keys, {2} installed mods.",TranslationEdition.current().id(),instance.cache.size(),ids.size());
        } catch(Exception e) { LOG.log(System.Logger.Level.ERROR,"Translation initialization failed.",e); }
    }
    public static boolean isPortugueseActive() { return portugueseActive; }
    public static TranslationProgress translationProgress() {
        TranslationService s=instance;
        return s==null||!portugueseActive||!s.config.enabled||!s.config.showTranslationProgress?DISABLED_PROGRESS:s.progressSnapshot;
    }
    /** UI calls only change flags and enqueue work; never perform IO on the render thread. */
    public static boolean isTranslationPaused() {TranslationService s=instance;return s!=null&&s.config.translationPaused;}
    public static void toggleTranslationPaused() {setTranslationPaused(!isTranslationPaused());}
    public static void setTranslationPaused(boolean paused) {
        TranslationService s=instance;
        if(s==null||s.closed||s.config.translationPaused==paused)return;
        s.config.translationPaused=paused;
        s.pauseGeneration.incrementAndGet();
        LocalTranslationClient.setUserPaused(paused);
        s.worker.execute(()->{
            s.localRestartAfter=0;
            s.updateProgress();
            try {atomicWrite(s.directory.resolve("config.json"),GSON.toJson(s.config));}
            catch(IOException failure){LOG.log(System.Logger.Level.WARNING,"Could not save translation pause preference.",failure);}
            s.persist(false);
            s.requestTick();
        });
    }
    /** Toggle only the display of generated translations. Queue, native pt-BR and disk cache are retained. */
    public static boolean isCachedTranslationDisplayEnabled() {TranslationService s=instance;return s==null||s.config.displayCachedTranslations;}
    public static void toggleCachedTranslationDisplay() {setCachedTranslationDisplayEnabled(!isCachedTranslationDisplayEnabled());}
    public static void setCachedTranslationDisplayEnabled(boolean enabled) {
        TranslationService s=instance;
        if(s==null||s.closed||s.config.displayCachedTranslations==enabled)return;
        s.config.displayCachedTranslations=enabled;
        // Invalidate Minecraft's decomposed components, rendered sequences and open quest widgets.
        s.revision.incrementAndGet();
        s.worker.execute(()->{
            s.updateProgress();
            try{atomicWrite(s.directory.resolve("config.json"),GSON.toJson(s.config));}
            catch(IOException failure){LOG.log(System.Logger.Level.WARNING,"Could not save cached translation display preference.",failure);}
            s.persist(false);
        });
    }
    public static boolean isTranslationPanelMinimized(){TranslationService s=instance;return s!=null&&s.config.translationPanelMinimized;}
    public static void toggleTranslationPanelMinimized(){setTranslationPanelMinimized(!isTranslationPanelMinimized());}
    public static void setTranslationPanelMinimized(boolean minimized){
        TranslationService s=instance;
        if(s==null||s.closed||s.config.translationPanelMinimized==minimized)return;
        s.config.translationPanelMinimized=minimized;
        s.worker.execute(()->{
            try{atomicWrite(s.directory.resolve("config.json"),GSON.toJson(s.config));}
            catch(IOException failure){LOG.log(System.Logger.Level.WARNING,"Could not save panel visibility preference.",failure);}
            s.persist(false);
        });
    }
    public static long revision() { return instance==null?0:instance.revision.get(); }
    public static void languageLoaded(ResourceManager manager,List<String> stack) {
        portugueseActive=stack.stream().anyMatch(s -> s.replace('-','_').equalsIgnoreCase("pt_br"));
        TranslationService s=instance;
        if(s==null) return;
        s.catalogReady=false; s.revision.incrementAndGet(); long generation=s.reloadGeneration.incrementAndGet();
        if(portugueseActive && s.config.enabled) s.worker.execute(() -> {s.updateProgress();s.scan(manager,generation);});
        else s.worker.execute(s::updateProgress);
    }
    public static void discoverMissingTranslations(ResourceManager manager) { if(instance!=null) instance.worker.execute(() -> instance.scan(manager,instance.reloadGeneration.get())); }
    public static String translateIfCachedOrQueue(String key,String source) { return instance==null?source:instance.translate(key,source); }
    public static String translateLiteral(String source) { return instance==null?source:instance.literal(source); }
    public static String translateStyled(String source) { return instance==null?source:instance.styled(source); }
    public static String translateQuest(String source) { return instance==null?source:instance.quest(source); }
    /** Only trusted mod documentation calls this; never pass player chat, writable books or user input. */
    public static String translateModDocument(String key,String source) { return instance==null?source:instance.document(key,source); }

    private String document(String key,String source) {
        if(!config.enabled||!portugueseActive||!catalogReady||source==null||source.isBlank()||!config.displayCachedTranslations)return source;
        String nativeValue=key==null?null:nativePortuguese.get(key);
        if(nativeValue!=null)return nativeValue;
        String original=key==null?source:english.getOrDefault(key,source);
        String hit=bySource.get(original); if(hit!=null)return hit;
        return VisibleDocument.resolve(original,block->{
            String translated=bySource.get(block); if(translated!=null)return translated;
            // Headings lack the English prose heuristics. Their document origin supplies that provenance.
            enqueue("@document",block,true); return block;
        });
    }

    private String quest(String source) {
        if(!config.enabled||!portugueseActive||!catalogReady||source==null||source.isBlank()) return source;
        String nativeValue=questNativeBySource.get(source); if(nativeValue!=null) return nativeValue;
        String canonical=ModTextPolicy.canonicalQuest(source); if(canonical!=null)return config.displayCachedTranslations?canonical:source;
        String hit=bySource.get(source); if(hit!=null) return config.displayCachedTranslations?hit:source;
        if(nativeTexts.contains(source)||translatedTexts.contains(source)) return source;
        enqueue("@quest",source,true); return source;
    }
    private String styled(String source) {
        String plain=StyledTextCodec.plainText(source);
        if(plain==null||!config.enabled||!portugueseActive||!catalogReady||nativeTexts.contains(plain)||translatedTexts.contains(plain)) return source;
        if(ModTextPolicy.isModName(plain)) return source;
        String hit=bySource.get(source); if(hit!=null) return config.displayCachedTranslations?hit:source;
        if(!config.translateLiterals||(!knownEnglishTexts.contains(plain)&&!looksEnglish(plain))) return source;
        enqueue("@styled",source,true); return source;
    }

    private String translate(String key,String source) {
        if(!config.enabled || !portugueseActive || !catalogReady || key==null || !valid(key,source)) return source;
        String nativeValue=nativePortuguese.get(key);
        // Explicit native Brazilian strings win, including resource-pack overrides.
        if(nativeValue!=null) return nativeValue;
        String contextual=ModTextPolicy.canonicalLanguage(key,source); if(contextual!=null)return config.displayCachedTranslations?contextual:source;
        if(ModTextPolicy.isNpcNameKey(key)) return english.getOrDefault(key,source);
        if(ModTextPolicy.isModName(source)) return source;
        String original=english.get(key);
        if(original==null) {
            if(!isModKey(key) || !looksEnglish(source)) return source;
            original=source;
        }
        String hit=getCached(key,original);
        if(hit!=null) return config.displayCachedTranslations?hit:source;
        enqueue(key,original,true); return source;
    }
    private String literal(String source) {
        if(!config.enabled || !portugueseActive || !catalogReady || source==null || source.isBlank() || nativeTexts.contains(source)||translatedTexts.contains(source)) return source;
        if(ModTextPolicy.isModName(source)) return source;
        String hit=bySource.get(source); if(hit!=null) return config.displayCachedTranslations?hit:source;
        if(!config.translateLiterals || (!knownEnglishTexts.contains(source)&&!looksEnglish(source))) return source;
        enqueue("@literal",source,true); return source;
    }
    private String getCached(String key,String source) {
        Map<String,String> entries=cache.get(key); String value=entries==null?null:entries.get(source);
        return value==null?bySource.get(source):value;
    }
    private boolean isModKey(String key) { for(String part:key.split("[.:]")) if(modIds.contains(part)&&!NON_MOD_IDS.contains(part)) return true; return false; }
    private static boolean valid(String key,String source) { return source!=null&&!source.isBlank()&&!source.equals(key)&&source.chars().anyMatch(Character::isLetter); }

    // Deliberately conservative for arbitrary rendered text; known en_us resources do not need this heuristic.
    static boolean looksEnglish(String text) {
        if(text==null || text.length()>24_000 || !text.chars().anyMatch(Character::isLetter)) return false;
        if(ModTextPolicy.isModName(text)) return false;
        if(ModTextPolicy.hasEnglishUi(text)) return true;
        String low=text.toLowerCase(Locale.ROOT);
        // A native item name inside English prose does not make the whole sentence Portuguese.
        if(ENGLISH_PROSE_WORDS.matcher(low).results().limit(2).count()>=2) return true;
        if(PORTUGUESE_ACCENTS.matcher(low).matches() || PORTUGUESE_WORDS.matcher(low).matches()) return false;
        if(RESOURCE_IDENTIFIER.matcher(low).matches() || low.startsWith("http") || DOTTED_IDENTIFIER.matcher(low).matches()) return false;
        return ENGLISH_WORDS.matcher(low).matches();
    }

    private void scan(ResourceManager manager,long generation) {
        try {
            Map<String,Map<String,String>> en=new TreeMap<>(),pt=new TreeMap<>();
            Map<Identifier,List<Resource>> resources=manager.listResourceStacks("lang",id -> id.getPath().equals("lang/en_us.json")||id.getPath().equals("lang/pt_br.json"));
            for(var entry:resources.entrySet()) {
                String ns=entry.getKey().getNamespace(); if(NON_MOD_IDS.contains(ns)&&!ns.equals("minecraft")) continue;
                Map<String,String> values=(entry.getKey().getPath().contains("en_us")?en:pt).computeIfAbsent(ns,k->new LinkedHashMap<>());
                for(Resource resource:entry.getValue()) {
                    try(Reader reader=resource.openAsReader()) {
                        JsonObject object=JsonParser.parseReader(reader).getAsJsonObject();
                        // Match Minecraft's own loader: TranslatableContents accepts %s,
                        // while language JSON authors may use numeric %d/%f formats.
                        object.entrySet().forEach(e -> { if(e.getValue().isJsonPrimitive()&&e.getValue().getAsJsonPrimitive().isString()) values.put(e.getKey(),LANGUAGE_FORMAT.matcher(e.getValue().getAsString()).replaceAll("%$1s")); });
                    } catch(Exception e) { LOG.log(System.Logger.Level.WARNING,"Skipped unreadable language resource {0}",entry.getKey()); }
                }
            }
            QuestCatalog.Catalog quests=QuestCatalog.scan(directory.getParent());
            questSources=Set.copyOf(quests.english().values());GameTranslationContext.registerQuestSources(questSources);
            if(!quests.english().isEmpty()) { en.put("ftbquests_content",quests.english()); pt.put("ftbquests_content",quests.portuguese()); }
            if(generation!=reloadGeneration.get()) return;
            Map<String,String> glossary=new LinkedHashMap<>();
            List<String> namespaces=new ArrayList<>(en.keySet());namespaces.remove("ftbquests_content");namespaces.remove("minecraft");namespaces.add(0,"minecraft");
            for(String namespace:namespaces) for(var term:en.getOrDefault(namespace,Map.of()).entrySet()) {
                String original=term.getValue(),nativeValue=pt.getOrDefault(namespace,Map.of()).get(term.getKey());
                if(GameTranslationContext.isNativeNameKey(term.getKey())&&nativeValue!=null&&!nativeValue.isBlank()&&!nativeValue.equals(original)&&original.length()<=80&&!original.contains("\n")&&!FORMAT_TOKEN.matcher(original).find()&&valid(term.getKey(),original))glossary.putIfAbsent(original,nativeValue);
            }
            GameTranslationContext.install(glossary);
            Map<String,String> allEnglish=new HashMap<>(),allNative=new HashMap<>(); JsonObject inventory=new JsonObject(); int missing=0;
            for(var entry:en.entrySet()) {
                if(NON_MOD_IDS.contains(entry.getKey()))continue;
                Map<String,String> nativeMap=pt.getOrDefault(entry.getKey(),Map.of()); int nativeCount=0,pendingCount=0;
                for(var e:entry.getValue().entrySet()) {
                    allEnglish.put(e.getKey(),e.getValue()); String nativeValue=nativeMap.get(e.getKey());
                    if(ModTextPolicy.isUntranslatedJourneyMapFallback(e.getKey(),e.getValue(),nativeValue))nativeValue=null;
                    // Some mods ship unchanged English inside pt_br.json (e.g. Quests).
                    if(nativeValue!=null&&!nativeValue.isBlank()&&(!nativeValue.equals(e.getValue())||!looksEnglish(nativeValue))) { allNative.put(e.getKey(),nativeValue); nativeCount++; }
                    else if(valid(e.getKey(),e.getValue())) { missing++; pendingCount++; }
                }
                JsonObject row=new JsonObject(); row.addProperty("english",entry.getValue().size()); row.addProperty("nativeBrazilian",nativeCount); row.addProperty("missingBrazilian",pendingCount); inventory.add(entry.getKey(),row);
            }
            pt.forEach((namespace,map)->{if(!NON_MOD_IDS.contains(namespace))map.forEach(allNative::putIfAbsent);});
            ModTextPolicy.registerNpcNames(allEnglish.entrySet().stream().filter(e->ModTextPolicy.isNpcNameKey(e.getKey())).map(Map.Entry::getValue).toList());
            // Old machine translations of NPC identities are never reused as display names or prose.
            cache.entrySet().removeIf(e->ModTextPolicy.isNpcNameKey(e.getKey()));
            for(String name:allEnglish.entrySet().stream().filter(e->ModTextPolicy.isNpcNameKey(e.getKey())).map(Map.Entry::getValue).toList()) {
                String old=bySource.remove(name); if(old!=null)translatedTexts.remove(old); jobs.remove(name);visibleTracking.remove(name);
            }
            // Remove unchanged English native entries that were identified as missing above.
            allNative.entrySet().removeIf(e -> e.getValue().equals(allEnglish.get(e.getKey()))&&(looksEnglish(e.getValue())||ModTextPolicy.isUntranslatedJourneyMapFallback(e.getKey(),allEnglish.get(e.getKey()),e.getValue())));
            english=Map.copyOf(allEnglish); nativePortuguese=Map.copyOf(allNative); nativeTexts=Set.copyOf(allNative.values()); discovered=missing; catalogReady=true;
            Set<String> known=new HashSet<>(); allEnglish.values().forEach(text -> known.add(text.replaceAll("[&Â§][0-9a-fk-orA-FK-OR]",""))); knownEnglishTexts=Set.copyOf(known);
            Map<String,String> questNative=new HashMap<>(); quests.english().forEach((key,value)->{String ptValue=allNative.get(key); if(ptValue!=null)questNative.put(value,ptValue);});questNativeBySource=Map.copyOf(questNative);
            Set<String> missingSources=new HashSet<>(); for(var e:allEnglish.entrySet()) if(!allNative.containsKey(e.getKey())) missingSources.add(e.getValue());
            jobs.entrySet().removeIf(e -> !e.getValue().running&&allEnglish.containsKey(e.getValue().key)&&(!allEnglish.get(e.getValue().key).equals(e.getValue().source)||!missingSources.contains(e.getValue().source)));
            visibleTracking.removeIf(source->!jobs.containsKey(source));
            catalogCachedAtScan=missingSources.stream().filter(bySource::containsKey).count();progressCommitsSinceScan=0;
            int added=0; for(var e:allEnglish.entrySet()) if(!allNative.containsKey(e.getKey())&&valid(e.getKey(),e.getValue())&&getCached(e.getKey(),e.getValue())==null) { enqueue(e.getKey(),e.getValue(),false); added++; }
            revision.incrementAndGet(); atomicWrite(directory.resolve("inventory.json"),GSON.toJson(inventory)); persistSafely();
            LOG.log(System.Logger.Level.INFO,"Automatic scan: {0} namespaces, {1} missing pt_br, {2} waiting; native pt_br preserved.",en.size(),missing,added);
        } catch(Exception e) { LOG.log(System.Logger.Level.WARNING,"Automatic resource scan failed.",e); }
        finally {updateProgress();}
    }
    private void enqueue(String key,String source,boolean priority) {
        if(ModTextPolicy.isModName(source)) return;
        if(closed||source.length()>config.maxTextCharacters) return;
        if(jobs.size()>=config.maxQueueEntries&&!jobs.containsKey(source))return;
        Job fresh=new Job(key,source,priority); Job existing=jobs.putIfAbsent(source,fresh);
        if(existing==null) {queueVersion.incrementAndGet();if(priority) {fresh.lastPromoted=System.currentTimeMillis();visible.addFirst(source);} else background.addLast(source);if(ModTextPolicy.canonicalUi(source)!=null)canonicalReady.addLast(source);}
        else if(priority&&!existing.running) {
            // Language lookups during registries/building screens also promote thousands
            // of jobs. Repeated render/hover requests must outrank that startup backlog.
            long now=System.currentTimeMillis();
            if(!existing.priority||now-existing.lastPromoted>=250) {
                if(!existing.priority)queueVersion.incrementAndGet();existing.priority=true;existing.lastPromoted=now;
                background.remove(source);visible.addFirst(source);
            }
        }
        if(priority) {
            visibleTracking.add(source);if(!jobs.containsKey(source))visibleTracking.remove(source);
            if(activeBatchBackground&&backgroundYieldRequested.compareAndSet(false,true))provider.requestInteractivePriority();
            requestTick();
        }
    }
    private void requestTick() {
        if(!closed&&tickRequested.compareAndSet(false,true))try{worker.execute(()->{tickRequested.set(false);tickSafely();});}catch(RejectedExecutionException shutdown){tickRequested.set(false);}
    }
    private void tickSafely() { try {tick();}catch(Exception e){busy.set(false);LOG.log(System.Logger.Level.WARNING,"Worker recovered from failure.",e);}finally{updateProgress();} }
    /** Called only by the service worker; rendering reads one immutable volatile snapshot. */
    private void updateProgress() {
        boolean enabled=config.enabled&&config.showTranslationProgress&&portugueseActive;
        if(!enabled){progressSnapshot=DISABLED_PROGRESS;return;}
        boolean normal=TranslationEdition.current()==TranslationEdition.NORMAL;
        long visiblePending=visibleTracking.size();
        long pending=jobs.size(),wantedPending=normal?pending:visiblePending;
        long completed=normal?catalogCachedAtScan+progressCommitsSinceScan:liteVisibleCompleted;
        String phase;
        if(config.translationPaused)phase="user_paused";
        else if(!catalogReady)phase="starting";
        else if(wantedPending==0)phase=completed>0?"completed":"idle";
        else if(busy.get())phase="translating";
        else {
            String local=provider.status();
            boolean resourcePause=local.contains("preserve game RAM")||local.contains("low free GPU memory")||local.contains("low system RAM")||local.contains("RAM guard")||local.contains("Insufficient free RAM");
            boolean preparing=local.startsWith("Validating local Hy-MT2 model")||local.startsWith("Downloading ")||local.startsWith("Loading local Hy-MT2 model");
            phase=preparing?"starting":resourcePause?"paused":"waiting";
        }
        String reason="";
        if(config.translationPaused)reason="Novas traduções pausadas.";
        else if("paused".equals(phase)) {
            if(provider.status().contains("GPU"))reason="VRAM livre insuficiente";
            else {
                long free=LocalResourcePolicy.freeMemoryMiB();
                long required=visiblePending>0||TranslationEdition.current()==TranslationEdition.NORMAL?LocalTranslationClient.foregroundMinimumFreeMemoryMiB():LocalResourcePolicy.minimumFreeMemoryMiB(free);
                reason=String.format(Locale.forLanguageTag("pt-BR"),"RAM: %.1f GB livres / mín. %.1f GB",Math.max(0,free)/1024D,required/1024D);
            }
        } else if("waiting".equals(phase))reason="Aguardando o tradutor local";
        else if("starting".equals(phase))reason=catalogReady?"Preparando o tradutor local":"Descobrindo textos do modpack";
        else if("translating".equals(phase))reason=activeBatchBackground?"Preparando o catálogo":"Prioridade: texto aberto";
        progressSnapshot=new TranslationProgress(true,config.translationPaused||!catalogReady||wantedPending>0,phase,completed,completed+wantedPending,pending,visiblePending,TranslationEdition.current().id(),reason,commits);
    }
    private void tick() {
        if(config.translationPaused){providerState="paused_by_user";return;}
        boolean readingScreen=readingScreenProbe.getAsBoolean();
        provider.maintain(readingScreen);
        if(readingScreen&&activeBatchBackground&&backgroundYieldRequested.compareAndSet(false,true))provider.requestInteractivePriority();
        if(!readingScreen&&provider.isEconomyMode()&&visibleTracking.isEmpty()&&!busy.get()&&provider.available()
                &&(TranslationEdition.current()==TranslationEdition.LITE||jobs.isEmpty()))provider.stop();
        if(!readingScreen&&TranslationEdition.current()==TranslationEdition.NORMAL && catalogReady && jobs.isEmpty()
                && !busy.get() && provider.available())provider.stop();
        if(closed||!portugueseActive||!catalogReady||!config.enabled||System.currentTimeMillis()<retryGlobalUntil||!busy.compareAndSet(false,true)) return;
        // Lite never loads native inference for catalog bulk work, including the main menu.
        // Visible texts promote themselves; Normal advances the catalog while sufficient RAM is available.
        long freeMemory=LocalResourcePolicy.freeMemoryMiB();
        boolean economyRequested=!provider.available()&&!visible.isEmpty()
                &&!LocalResourcePolicy.canStart(freeMemory)&&LocalResourcePolicy.canAttemptOnDemand(freeMemory);
        boolean backgroundAllowed=TranslationEdition.current().allowsBackgroundWhilePlaying()&&!readingScreen;
        if(!backgroundAllowed&&!readingScreen&&System.currentTimeMillis()-lastPrioritySweep>=1000) {
            lastPrioritySweep=System.currentTimeMillis();
            int queued=visible.size();
            while(queued-->0) {String source=visible.pollFirst();if(source==null)break;Job job=jobs.get(source);if(job==null||job.running)continue;
                if(lastPrioritySweep-job.lastPromoted>10_000) {job.priority=false;visibleTracking.remove(source);queueVersion.incrementAndGet();background.addLast(source);} else visible.addLast(source);}
        }
        boolean localWork=!visible.isEmpty() || (backgroundAllowed&&!background.isEmpty());
        if(!provider.available()&&provider.status().contains("preserve game RAM")&&(economyRequested||LocalResourcePolicy.canStart(freeMemory)))localRestartAfter=0;
        if(localWork&&!provider.available()&&System.currentTimeMillis()>localRestartAfter) {
            localRestartAfter=System.currentTimeMillis()+1_000;
            (economyRequested?provider.startOnDemand(directory):provider.start(directory)).thenAccept(ok->{if(ok)localRestartAfter=0;requestTick();});
        }
        boolean providerReady=provider.available();
        List<Job> batch=new ArrayList<>(); int limit=provider.isEconomyMode()||!visible.isEmpty()?1:TranslationEdition.current().localBatchSize(); int characters=0;
        // Bound queue scans even when the provider is ready: retried catalogs must not monopolize the worker.
        int attempts=Math.min(providerReady?512:128,Math.max(visible.size()+background.size()+canonicalReady.size(),1));
        Map<String,Job> deferred=new LinkedHashMap<>();
        try {
          while(batch.size()<limit&&attempts-->0) {
            String id=visible.pollFirst();
            if(id==null)id=canonicalReady.pollFirst();
            if(id==null&&backgroundAllowed)id=background.pollFirst();
            if(id==null)break;
            if(canonicalReady.remove(id)||ModTextPolicy.canonicalUi(id)!=null) {visible.remove(id);background.remove(id);}
            Job job=jobs.get(id); if(job==null||job.running) continue;
            if(deferred.containsKey(id))continue;
            // Reinsert ineligible jobs only after this pass. A visible retry must not hide the entire catalog.
            if(!providerReady&&ModTextPolicy.canonicalUi(job.source)==null) { deferred.put(id,job); continue; }
            if(job.retryAt>System.currentTimeMillis()) { deferred.put(id,job); continue; }
            if(!batch.isEmpty()&&characters+job.source.length()>config.maxRequestCharacters) { (job.priority?visible:background).addFirst(id); break; }
            if(getCached(job.key,job.source)!=null) { jobs.remove(id,job);if(visibleTracking.remove(id))liteVisibleCompleted++; continue; }
            job.running=true; batch.add(job); characters+=job.source.length();
          }
        } finally {
            deferred.forEach((id,job)->{if(jobs.get(id)==job&&!job.running)(job.priority?visible:background).addLast(id);});
        }
        if(batch.isEmpty()) { busy.set(false); providerState=providerReady?"idle":"waiting_for_local: "+provider.status(); return; }
        final boolean backgroundBatch=batch.stream().noneMatch(job->job.priority);
        backgroundYieldRequested.set(false);activeBatchBackground=backgroundBatch;
        final long admissionGeneration=pauseGeneration.get();
        providerState=provider.isEconomyMode()?"local-hy-mt2-on-demand":"local-hy-mt2";
        List<String> sources=batch.stream().map(j->j.source).toList();
        List<Boolean> roles=batch.stream().map(j->j.key.startsWith("npc.")&&j.key.endsWith(".job")&&j.source.matches("<[A-Za-z ]{1,60}>")).toList();
        List<String> providerSources=new ArrayList<>(); for(int i=0;i<sources.size();i++)providerSources.add(roles.get(i)?sources.get(i).substring(1,sources.get(i).length()-1):sources.get(i));
        long requestedAt=System.nanoTime();
        boolean terminologyOnly=sources.stream().allMatch(source -> ModTextPolicy.canonicalUi(source)!=null);
        if(terminologyOnly) providerState="interface-terminology";
        CompletableFuture<List<String>> request=terminologyOnly?CompletableFuture.completedFuture(sources.stream().map(ModTextPolicy::canonicalUi).toList()):(!config.translationPaused&&pauseGeneration.get()==admissionGeneration?(backgroundBatch?provider.translateBackground(providerSources):provider.translate(providerSources)):CompletableFuture.<List<String>>failedFuture(new CancellationException("Translation paused"))).thenApply(values->{List<String> wrapped=new ArrayList<>();for(int i=0;i<values.size();i++)wrapped.add(values.get(i)==null?null:(roles.get(i)?"<"+values.get(i)+">":values.get(i)));return wrapped;});
        if(backgroundBatch&&(!visible.isEmpty()||readingScreen)&&backgroundYieldRequested.compareAndSet(false,true))provider.requestInteractivePriority();
        request.whenComplete((results,failure) -> {
            if(closed) return;
            worker.execute(() -> {
                try {
                    if(failure!=null) fail(batch,failure);
                    else if(results==null||results.size()!=batch.size()) fail(batch,new IOException("Local result count mismatch"));
                    else {
                        lastTranslationMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-requestedAt);
                        try{commitResults(batch,results,backgroundBatch&&backgroundYieldRequested.get());}catch(IOException diskFailure){fail(batch,diskFailure);}
                    }
                } finally {activeBatchBackground=false;busy.set(false);updateProgress();requestTick();}
            });
        });
    }
    private synchronized void commitResults(List<Job> batch,List<String> results,boolean yielded) throws IOException {
        if(closed)return;
        List<Job> accepted=new ArrayList<>();List<CacheRecord> rows=new ArrayList<>();
        for(int i=0;i<batch.size();i++) {
            Job job=batch.get(i);String output=results.get(i);
            if(output==null&&(config.translationPaused||yielded)){job.running=false;job.retryAt=0;queueVersion.incrementAndGet();(job.priority?visible:background).addFirst(job.source);continue;}
            if(output==null||output.isBlank()||!tokensValid(job.source,output)){retry(job);lastFailure="invalid_translation_tokens";continue;}
            accepted.add(job);rows.add(new CacheRecord(job.key,job.source,output,TRANSLATION_QUALITY_VERSION));
        }
        CacheJournal.append(directory.resolve("translations.journal.jsonl"),rows.stream().map(CACHE_JSON::toJson).toList());
        for(int i=0;i<rows.size();i++) {
            CacheRecord row=rows.get(i);Job job=accepted.get(i);
            cache.computeIfAbsent(row.key,k->new ConcurrentHashMap<>()).put(row.source,row.translated);bySource.put(row.source,row.translated);translatedTexts.add(row.translated);
            String plain=StyledTextCodec.plainText(row.translated);if(plain!=null)translatedTexts.add(plain);jobs.remove(job.source,job);commits++;progressCommitsSinceScan++;
            if(visibleTracking.remove(job.source))liteVisibleCompleted++;
        }
        if(!rows.isEmpty()){cacheDirty=true;revision.incrementAndGet();}
        updateProgress();
    }
    static boolean tokensValid(String source,String result) {
        String plain=StyledTextCodec.plainText(source);
        if(plain!=null) {int count=0;for(int at=0;(at=source.indexOf("<mt",at))>=0;at+=3)count++;if(StyledTextCodec.decode(result,count)==null)return false;}
        Map<String,Integer> before=new HashMap<>(),after=new HashMap<>();
        var m=FORMAT_TOKEN.matcher(source); while(m.find()) before.merge(m.group(),1,Integer::sum);
        m=FORMAT_TOKEN.matcher(result); while(m.find()) after.merge(m.group(),1,Integer::sum);
        return before.equals(after)&&!result.contains("MODTRPH")&&ModTextPolicy.namesPreserved(source,result);
    }
    private void fail(List<Job> batch,Throwable failure) {
        Throwable cause=failure; while(cause.getCause()!=null&&(cause instanceof CompletionException||cause instanceof ExecutionException)) cause=cause.getCause();
        if(cause instanceof CancellationException) {
            // A pause is not a failed translation: resume the same unsaved work without backoff.
            for(Job job:batch){job.running=false;job.retryAt=0;(job.priority?visible:background).addFirst(job.source);}
            queueVersion.incrementAndGet();
        } else {
            lastFailure=cause.getClass().getSimpleName();
            for(Job job:batch)retry(job);
            retryGlobalUntil=System.currentTimeMillis()+2_000;
        }
        persist(false);
    }
    private void retry(Job job) {
        queueVersion.incrementAndGet();
        job.running=false; job.failures++;
        job.retryAt=System.currentTimeMillis()+Math.min(300_000L,2_000L*(1L<<Math.min(job.failures,7)));
        (job.priority?visible:background).addLast(job.source);
    }
    private void loadCache() {
        Path file=directory.resolve("translations.json"); if(!Files.isRegularFile(file)) return;
        try {
            List<CacheRecord> rows=GSON.fromJson(Files.readString(file),new TypeToken<List<CacheRecord>>(){}.getType());
            if(rows!=null)for(CacheRecord row:rows) {
                loadRecord(row);
            }
        }
        catch(Exception e) {LOG.log(System.Logger.Level.WARNING,"Invalid cache retained on disk; no translations loaded.");}
    }
    private void loadJournal() {
        try{for(String line:CacheJournal.recover(directory.resolve("translations.journal.jsonl")))try{loadRecord(GSON.fromJson(line,CacheRecord.class));}catch(RuntimeException invalidRow){LOG.log(System.Logger.Level.WARNING,"Skipped invalid cache journal row.");}}
        catch(IOException failure){LOG.log(System.Logger.Level.WARNING,"Could not recover cache journal.");}
    }
    private void loadRecord(CacheRecord row) {
        if(row==null||row.key==null||!valid(row.key,row.source))return;
        if(ModTextPolicy.isNpcNameKey(row.key))return;
        if(row.source.equals(row.translated)&&row.key.startsWith("npc.")&&row.key.endsWith(".job")) {enqueue(row.key,row.source,false);return;}
        if(ModTextPolicy.isModName(row.source))return;
        String canonicalUi=ModTextPolicy.canonicalUi(row.source);
        if(!ModTextPolicy.namesPreserved(row.source,row.translated)||(canonicalUi!=null&&!canonicalUi.equals(row.translated))) {
            enqueue(row.key,row.source,false);return;
        }
        boolean quest=row.key.equals("@quest")||row.key.startsWith("quest.")||row.key.startsWith("chapter.")||row.key.startsWith("task.")||questSources.contains(row.source);
        if(quest&&row.qualityVersion<TRANSLATION_QUALITY_VERSION){if(getCached(row.key,row.source)==null)enqueue(row.key,row.source,false);return;}
        if(row.translated==null||row.translated.isBlank()||!tokensValid(row.source,row.translated))return;
        cache.computeIfAbsent(row.key,k->new ConcurrentHashMap<>()).put(row.source,row.translated);bySource.put(row.source,row.translated);translatedTexts.add(row.translated);jobs.remove(row.source);
        String plain=StyledTextCodec.plainText(row.translated);if(plain!=null)translatedTexts.add(plain);
    }
    private void loadQueue() {
        Path file=directory.resolve("pending.json"); if(!Files.isRegularFile(file)) return;
        try { List<Job> rows=GSON.fromJson(Files.readString(file),new TypeToken<List<Job>>(){}.getType()); if(rows!=null) for(Job job:rows) if(valid(job.key,job.source)&&getCached(job.key,job.source)==null) {enqueue(job.key,job.source,false);Job loaded=jobs.get(job.source);if(loaded!=null){loaded.forceLocal=job.forceLocal;loaded.failures=job.failures;}} }
        catch(Exception e) {LOG.log(System.Logger.Level.WARNING,"Pending queue will be rebuilt from active resources.");}
    }
    private void persistSafely(){persist(true);}
    private synchronized void persist(boolean force) {
        try {
            // Every result is already durable in the journal. Consolidation can wait
            // while the player is waiting for a visible text; shutdown always flushes.
            long now=System.currentTimeMillis();boolean checkpoint=force||(cacheDirty&&now-lastCheckpoint>=60_000&&visibleTracking.isEmpty());
            if(checkpoint) {
                List<CacheRecord> rows=new ArrayList<>();cache.forEach((key,values)->values.forEach((source,value)->rows.add(new CacheRecord(key,source,value,TRANSLATION_QUALITY_VERSION))));rows.sort(Comparator.comparing(CacheRecord::key).thenComparing(CacheRecord::source));
                atomicWrite(directory.resolve("translations.json"),CACHE_JSON.toJson(rows));CacheJournal.clear(directory.resolve("translations.journal.jsonl"));cacheDirty=false;lastCheckpoint=now;
            }
            long queued=queueVersion.get();
            if(force||checkpoint||(queued!=savedQueueVersion&&now-lastQueueSnapshot>=10_000&&visibleTracking.isEmpty())){atomicWrite(directory.resolve("pending.json"),CACHE_JSON.toJson(jobs.values()));savedQueueVersion=queued;lastQueueSnapshot=now;}
            JsonObject status=new JsonObject(); status.addProperty("version","2.0.0-alpha.3");status.addProperty("paragraphReuseHits",LocalTranslationClient.paragraphReuseCount());status.addProperty("translationEngine","local-hy-mt2");status.addProperty("economyOnDemand",provider.isEconomyMode());status.addProperty("ptBrActive",portugueseActive);status.addProperty("installedMods",modIds.size());status.addProperty("discoveredMissing",discovered);status.addProperty("cachedUniqueSources",bySource.size());status.addProperty("pending",jobs.size());status.addProperty("translatedThisRun",commits);status.addProperty("provider",providerState);status.addProperty("local",provider.status());status.addProperty("lastFailure",lastFailure);status.addProperty("updatedAt",java.time.Instant.now().toString());
            status.addProperty("translationQualityVersion",TRANSLATION_QUALITY_VERSION);status.addProperty("nativeTerminologyEntries",GameTranslationContext.nativeTermCount());
            status.addProperty("storageMode","journal");status.addProperty("localBackend",LocalTranslationClient.backend());status.addProperty("lastTranslationMs",lastTranslationMs);status.addProperty("lastCheckpointMillis",lastCheckpoint);
            status.addProperty("localMemoryBudgetMiB",LocalResourcePolicy.currentMemoryBudgetMiB());status.addProperty("localIdleUnloadSeconds",LocalResourcePolicy.IDLE_MILLIS/1000);status.addProperty("pretranslateWhilePlaying",TranslationEdition.current().allowsBackgroundWhilePlaying());
            var hardware=LocalTranslationClient.hardware();var allocation=LocalTranslationClient.activePlan();
            status.addProperty("translationPaused",config.translationPaused);status.addProperty("displayCachedTranslations",config.displayCachedTranslations);status.addProperty("translationPanelMinimized",config.translationPanelMinimized);status.addProperty("edition",TranslationEdition.current().id());status.addProperty("adaptiveHardware",TranslationEdition.current()==TranslationEdition.NORMAL);
            status.addProperty("localContextTokens",allocation.contextTokens());status.addProperty("localBatchTokens",allocation.batchTokens());
            status.addProperty("localMicroBatchTokens",allocation.microBatchTokens());status.addProperty("localTextsPerBatch",TranslationEdition.current().localBatchSize());status.addProperty("localThreads",allocation.threads());
            status.addProperty("hardwareTotalMemoryMiB",hardware.totalMemoryMiB());status.addProperty("hardwareFreeMemoryMiB",LocalResourcePolicy.freeMemoryMiB());
            status.addProperty("hardwareLogicalThreads",hardware.logicalThreads());status.addProperty("hardwareGpuTotalMiB",hardware.gpuTotalMiB());
            status.addProperty("hardwareGpuFreeMiBAtLaunch",hardware.gpuFreeMiB());status.addProperty("hardwareGpuFreeMiB",LocalTranslationClient.currentGpuFreeMiB());status.addProperty("allocationWorldLoaded",allocation.worldLoaded());
            Path journal=directory.resolve("translations.journal.jsonl");status.addProperty("cacheJournalBytes",Files.isRegularFile(journal)?Files.size(journal):0);
            atomicWrite(directory.resolve("status.json"),GSON.toJson(status));
        }catch(Exception e){LOG.log(System.Logger.Level.WARNING,"Could not persist worker state.");}
    }
    private static void atomicWrite(Path path,String value)throws IOException {
        Path temp=path.resolveSibling(path.getFileName()+".tmp");
        try(var channel=java.nio.channels.FileChannel.open(temp,StandardOpenOption.CREATE,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
            var buffer=java.nio.ByteBuffer.wrap(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
        }
        try{Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
    }
    private record CacheRecord(String key,String source,String translated,int qualityVersion){}
    private static final class Job {String key,source;volatile boolean priority;boolean forceLocal;transient volatile boolean running;transient volatile long lastPromoted;long retryAt;int failures;Job(String key,String source,boolean priority){this.key=key;this.source=source;this.priority=priority;}}
    private static final class Config {
        volatile boolean translationPaused=false;
        volatile boolean displayCachedTranslations=true;
        volatile boolean translationPanelMinimized=false;
        boolean enabled=true,showTranslationProgress=true;
        String targetLanguage="PT-BR";
        boolean translateLiterals=true,pretranslateWhilePlaying=false;
        int maxRequestCharacters=8_000,maxTextCharacters=24_000,maxQueueEntries=200_000;
        void defaults(){maxRequestCharacters=Compat.clamp(maxRequestCharacters,1000,30000);maxTextCharacters=Compat.clamp(maxTextCharacters,1000,100000);maxQueueEntries=Compat.clamp(maxQueueEntries,1000,500000);targetLanguage="PT-BR";}

    }
}
