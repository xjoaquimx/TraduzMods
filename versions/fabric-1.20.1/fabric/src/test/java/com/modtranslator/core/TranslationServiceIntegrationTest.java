package com.modtranslator.core;

import com.google.gson.*;
import com.modtranslator.client.StyledTextCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Isolated integration tests. Production service with an injected in-memory local provider and resource packs. */
class TranslationServiceIntegrationTest {
    static final class FakeGuideText {
        String text; FakeGuideText(String text){this.text=text;}
        public String getText(){return text;} public void setText(String text){this.text=text;}
    }
    static final class FakeGuideSpan {
        final Object style=new Object();final List<FakeGuideText> children;
        FakeGuideSpan(List<FakeGuideText> children){this.children=children;}
        public List<FakeGuideText> getChildren(){return children;}
    }
    static final class FakeGuideParagraph {
        final List<?> content;FakeGuideParagraph(List<?> content){this.content=content;}
        public Iterable<?> getContent(){return content;}
    }
    static final class FakeGuideDocument {
        final List<FakeGuideParagraph> blocks=new ArrayList<>();int invalidations;
        public List<FakeGuideParagraph> getChildren(){return blocks;}
        public void invalidateLayout(){invalidations++;}
    }
    static final class FakeGuideScreen {
        final FakeGuideDocument document=new FakeGuideDocument();
        boolean documentLayoutInvalid;int scrollY=130;
        protected FakeGuideDocument getDocument(){return document;}
    }
    static final class FakeAetherPages {
        private final Map<Integer,List<net.minecraft.network.chat.Component>> pages=new HashMap<>();
        private int currentPageNumber;
        String visible="";int color;
        private void createPages(net.minecraft.network.chat.Component source) {
            visible=source.getString();color=source.getStyle().getColor()==null?0:source.getStyle().getColor().getValue();
            String[] blocks=visible.split("\\n\\n");
            for(int i=0;i<blocks.length;i++)pages.put(i,List.of(net.minecraft.network.chat.Component.literal(blocks[i])));
        }
    }
    static final class FakeGuideRow {
        private final FakeGuideParagraph paragraph;
        FakeGuideRow(String text){paragraph=new FakeGuideParagraph(List.of(new FakeGuideText(text)));}
        String text(){return ((FakeGuideText)paragraph.content.get(0)).text;}
    }
    static final class FakeGuideNavbar {
        private final List<FakeGuideRow> rows=new ArrayList<>();int layouts;int scrollY=47;
        private void updateLayout(){layouts++;}
    }
    static final class FakeCompleteGuide {
        private final FakeGuideDocument document=new FakeGuideDocument();
        private final FakeGuideParagraph pageTitle=new FakeGuideParagraph(List.of(new FakeGuideText("The Charger")));
        private final FakeGuideNavbar navbar=new FakeGuideNavbar();
        boolean documentLayoutInvalid;int layouts;int scrollY=130;
        protected FakeGuideDocument getDocument(){return document;}
        private void updateScreenLayout(){layouts++;com.modtranslator.client.GuideMePageRefresh.refresh(this);}
        String title(){return ((FakeGuideText)pageTitle.content.get(0)).text;}
    }
    static final Gson GSON = new Gson();
    static final List<String> failures = new ArrayList<>();
    static Path root;
    static int passed;
    /** In-memory local inference substitute. Exercises the production service without sockets or external APIs. */
    static class Mock implements AutoCloseable, TranslationService.LocalProvider {
        final ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"fake-local-inference");t.setDaemon(true);return t;});
        final List<String> sent=new CopyOnWriteArrayList<>();
        final List<JsonObject> payloads=new CopyOnWriteArrayList<>();
        final List<Long> receivedAt=new CopyOnWriteArrayList<>();
        final AtomicInteger transientFailures=new AtomicInteger(),corrupt=new AtomicInteger(),starts=new AtomicInteger(),stops=new AtomicInteger();
        volatile boolean ready,economyMode,stopBackgroundWhenRequested;
        final AtomicInteger priorityRequests=new AtomicInteger(),backgroundRequests=new AtomicInteger();
        volatile String state="Local service unavailable";
        volatile long restartDelay;
        public long restartDelayMillis(){return restartDelay;}
        volatile CountDownLatch responseGate=new CountDownLatch(0);
        volatile java.util.function.UnaryOperator<String> translation=source->source.startsWith("<mt0>")?source.replaceFirst("<mt0>","<mt0>PT: "):"PT: "+source;
        volatile String corruptIfContains;
        public boolean available(){return ready;}
        public boolean isEconomyMode(){return economyMode;}
        public void maintain(){}
        public void stop(){stops.incrementAndGet();}
        public CompletableFuture<Boolean> start(Path directory){starts.incrementAndGet();return CompletableFuture.completedFuture(ready);}
        public String status(){return ready?"Local model ready":state;}
        public void requestInteractivePriority(){priorityRequests.incrementAndGet();}
        public CompletableFuture<List<String>> translate(List<String> sources){return translateSources(sources,false);}
        public CompletableFuture<List<String>> translateBackground(List<String> sources){backgroundRequests.incrementAndGet();return translateSources(sources,true);}
        private CompletableFuture<List<String>> translateSources(List<String> sources,boolean background){
            List<String> immutable=List.copyOf(sources);
            int priorityBaseline=priorityRequests.get();
            return CompletableFuture.supplyAsync(()->{
                List<TextProtection.ProtectedText> protectedTexts=immutable.stream().map(TextProtection::protect).toList();
                JsonObject payload=new JsonObject();JsonArray text=new JsonArray();
                protectedTexts.forEach(p->text.add(p.value()));payload.add("text",text);
                receivedAt.add(System.nanoTime());payloads.add(payload);
                try{responseGate.await(15,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new CompletionException(e);}
                List<String> result=new ArrayList<>();
                int slot=0;
                for(TextProtection.ProtectedText p:protectedTexts){
                    if(background&&slot++>0&&stopBackgroundWhenRequested&&priorityRequests.get()>priorityBaseline){result.add(null);continue;}
                    String source=p.value();sent.add(source);
                    String output=translation.apply(source);
                    if((corruptIfContains!=null&&source.contains(corruptIfContains))||corrupt.getAndUpdate(n->Math.max(0,n-1))>0)output=output.replaceAll("MODTRPHX*[0-9]+X","");
                    try{result.add(p.restore(output));}catch(IllegalArgumentException invalid){result.add(null);}
                }
                if(transientFailures.getAndUpdate(n->Math.max(0,n-1))>0)throw new CompletionException(new IOException("local_inference_failed"));
                return result;
            },worker);
        }
        public void close()throws Exception{responseGate.countDown();worker.shutdownNow();worker.awaitTermination(1,TimeUnit.SECONDS);}
    }
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static void await(BooleanSupplier ok,String message)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);
        while(System.nanoTime()<deadline){if(ok.getAsBoolean())return;Thread.sleep(30);}
        throw new AssertionError("timeout: "+message);
    }
    static Object service()throws Exception {return field(TranslationService.class,"instance").get(null);}
    static Field field(Class<?> type,String name)throws Exception {Field f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    static Object value(Object o,String name)throws Exception{return field(o.getClass(),name).get(o);}
    static void set(Object o,String name,Object v)throws Exception {field(o.getClass(),name).set(o,v);}
    static void invoke(Object o,String name)throws Exception {Method m=o.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(o);}
    static Map<?,?> jobs()throws Exception{return (Map<?,?>)value(service(),"jobs");}
    static void closeService()throws Exception {
        Object s=service();if(s==null)return;
        set(s,"closed",true); ScheduledExecutorService worker=(ScheduledExecutorService)value(s,"worker");
        worker.shutdown();if(!worker.awaitTermination(3,TimeUnit.SECONDS)){worker.shutdownNow();check(worker.awaitTermination(3,TimeUnit.SECONDS),"test worker failed to stop");}invoke(s,"persistSafely");
        field(TranslationService.class,"instance").set(null,null);
        field(TranslationService.class,"portugueseActive").setBoolean(null,false);
    }
    static void initializeLocal(Path configDirectory,Mock mock,boolean ready)throws Exception {
        TranslationService.initialize(configDirectory,List.of("testmod"));mock.ready=ready;set(service(),"provider",mock);
        // A headless suite has no game screen. Reading-screen tests inject their own probe.
        set(service(),"readingScreenProbe",(BooleanSupplier)()->false);
    }
    static Path init(String name,Mock mock,boolean ready)throws Exception {
        closeService();Path dir=root.resolve(name);Files.createDirectories(dir.resolve("modtranslator"));
        JsonObject c=new JsonObject();c.addProperty("targetLanguage","DE");
        Files.writeString(dir.resolve("modtranslator/config.json"),GSON.toJson(c));
        initializeLocal(dir,mock,ready);return dir.resolve("modtranslator");
    }
    static Resource resource(Map<String,String> strings){byte[] bytes=GSON.toJson(strings).getBytes(StandardCharsets.UTF_8);return new Resource(null,()->new ByteArrayInputStream(bytes));}
    static ResourceManager manager(Map<String,String> en,Map<String,String> pt,Map<String,String> override){
        Map<ResourceLocation,List<Resource>> data=new LinkedHashMap<>();
        data.put(new ResourceLocation("testmod","lang/en_us.json"),List.of(resource(en)));
        data.put(new ResourceLocation("testmod","lang/pt_br.json"),override.isEmpty()?List.of(resource(pt)):List.of(resource(pt),resource(override)));
        data.put(new ResourceLocation("minecraft","lang/en_us.json"),List.of(resource(Map.of("vanilla.test","Vanilla Storage"))));
        return (ResourceManager)java.lang.reflect.Proxy.newProxyInstance(ResourceManager.class.getClassLoader(),new Class<?>[]{ResourceManager.class},(p,m,args)->{
            if(m.getName().equals("listResourceStacks"))return data;
            if(m.getName().equals("toString"))return "integration-resource-manager";
            throw new UnsupportedOperationException(m.getName());
        });
    }
    static void scan(Map<String,String> en,Map<String,String> pt,Map<String,String> override)throws Exception {
        long rev=TranslationService.revision();
        TranslationService.languageLoaded(manager(en,pt,override),List.of("en_us","pt_br"));
        await(()->TranslationService.revision()>rev+1,"resource scan revision");
        // The revision is published before the scan finishes its checkpoint/HUD update.
        ((ScheduledExecutorService)value(service(),"worker")).submit(()->{}).get(12,TimeUnit.SECONDS);
    }
    static String translated(String key,String source){return TranslationService.translateIfCachedOrQueue(key,source);}
    static void test(String name,Checked run){String filter=System.getProperty("traduzmods.test.filter","");if(!filter.isBlank()&&!name.contains(filter))return;try{run.run();passed++;System.out.println("PASS "+name);}catch(Throwable e){failures.add(name+": "+e);System.out.println("FAIL "+name+": "+e);e.printStackTrace(System.out);}finally{try{closeService();}catch(Exception e){failures.add("cleanup: "+e);}}}
    interface Checked{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        // Initialize logging on the main thread, before teardown can interrupt a worker.
        com.mojang.logging.LogUtils.getLogger();
        // Inject the fake local provider before any resource scan can admit native inference.
        // Native inference has its own isolated suite.
        root=Path.of(args[0]);Files.createDirectories(root);
        test("indexed_queue_preserves_randomized_and_concurrent_priorities",OptimizationChecks::queue);
        test("compact_prompt_keeps_short_native_item_names_after_long_tool_names",()->{
            GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de madeira","Furnace","Fornalha","Cobblestone","Pedregulho","Stone","Pedra","Coal","Carvão","Stick","Graveto","Oak Wood","Madeira de carvalho"));
            String prompt=TranslationPrompt.build("With your Wooden Pickaxe, you can mine stone to obtain cobblestone. A furnace needs fuel: wood, coal and sticks work.",true);
            check(prompt.contains("Sticks translates to Gravetos."),"compact prompt dropped the native label for sticks");
        });
        test("periodic_checkpoint_defers_visible_work_without_losing_durable_results",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("visible-checkpoint",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String first="The first machine contains your tools.",visible="The next machine contains your tools.";
                scan(Map.of("option.testmod.first",first,"option.testmod.next",visible),Map.of(),Map.of());
                byte[] before=Files.readAllBytes(dir.resolve("translations.json"));
                byte[] pendingBefore=Files.readAllBytes(dir.resolve("pending.json"));
                Method commit=svc.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);
                commit.invoke(svc,List.of(jobs().get(first)),List.of("A primeira máquina contém suas ferramentas."),false);
                translated("option.testmod.next",visible);set(svc,"lastCheckpoint",0L);set(svc,"lastQueueSnapshot",0L);
                Method persist=svc.getClass().getDeclaredMethod("persist",boolean.class);persist.setAccessible(true);persist.invoke(svc,false);
                check(Arrays.equals(before,Files.readAllBytes(dir.resolve("translations.json"))),"foreground wait rewrote the full cache");
                check(Arrays.equals(pendingBefore,Files.readAllBytes(dir.resolve("pending.json"))),"foreground wait rewrote the full catalog queue");
                check(CacheJournal.recover(dir.resolve("translations.journal.jsonl")).size()==1,"deferred checkpoint lost its durable delta");
                commit.invoke(svc,List.of(jobs().get(visible)),List.of("A próxima máquina contém suas ferramentas."),false);persist.invoke(svc,false);
                check(CacheJournal.recover(dir.resolve("translations.journal.jsonl")).isEmpty(),"idle checkpoint failed to consolidate the journal");
                check(JsonParser.parseString(Files.readString(dir.resolve("translations.json"))).getAsJsonArray().size()==2,"idle checkpoint omitted committed translations");
                closeService();initializeLocal(dir.getParent(),mock,false);
                scan(Map.of("option.testmod.first",first,"option.testmod.next",visible),Map.of(),Map.of());
                check(translated("option.testmod.first",first).equals("A primeira máquina contém suas ferramentas.")&&translated("option.testmod.next",visible).equals("A próxima máquina contém suas ferramentas."),"compact checkpoint failed to reload");
            }
        });
        test("terminology_index_preserves_exhaustive_unicode_and_suffix_matches",OptimizationChecks::terminology);
        test("paragraph_memo_bounds_memory_preserves_context_and_evicts_LRU",()->{
            TranslationMemo memo=new TranslationMemo(600,2);
            memo.put("context A","first");memo.put("context B","second");
            check("first".equals(memo.get("context A")),"memo lookup lost result");
            memo.put("context C","third");
            check(memo.get("context B")==null&&memo.size()==2,"memo does not evict least-used entry");
            memo.put("context A","replaced");
            check("replaced".equals(memo.get("context A"))&&memo.bytes()<=600,"replacement breaks byte accounting");
            memo.put("huge","x".repeat(1000));check(memo.get("huge")==null&&memo.size()==2,"oversized paragraph evicted bounded useful entries");
            check(memo.get("new glossary context")==null,"memo reuses translation from different native terminology");
        });
        test("full_queue_promotes_existing_visible_work_without_admitting_extra_jobs",()->{
            try(Mock mock=new Mock()) {
                init("full-queue-visible",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                set(value(svc,"config"),"maxQueueEntries",1000);
                Method enqueue=svc.getClass().getDeclaredMethod("enqueue",String.class,String.class,boolean.class);enqueue.setAccessible(true);
                for(int i=0;i<1000;i++)enqueue.invoke(svc,"option.testmod."+i,"This pending tool number "+i,false);
                String existing="This pending tool number 999";
                enqueue.invoke(svc,"option.testmod.999",existing,true);
                enqueue.invoke(svc,"option.testmod.extra","This extra pending tool",true);
                check(jobs().size()==1000&&!jobs().containsKey("This extra pending tool"),"queue capacity was bypassed");
                check(((Deque<?>)value(svc,"visible")).contains(existing)&&!((Deque<?>)value(svc,"background")).contains(existing),"full queue refused existing visible priority");
            }
        });

















        test("ftb_literal_ampersands_are_safe_without_changing_text_or_unicode",()->{
            check(FtbTextSafety.escapeLiteralAmpersands(null)==null,"null FTB source changed");
            check(FtbTextSafety.escapeLiteralAmpersands("").isEmpty(),"empty FTB source changed");
            String plain="AE avançado: tradução rápida";
            check(FtbTextSafety.escapeLiteralAmpersands(plain)==plain,"unchanged FTB string unnecessarily copied");
            check(FtbTextSafety.escapeLiteralAmpersands("Extended & Advanced AE").equals("Extended \\& Advanced AE"),"literal title ampersand not escaped");
            check(FtbTextSafety.escapeLiteralAmpersands("Fim &").equals("Fim \\&"),"trailing ampersand remains invalid");
            for(char whitespace:new char[]{' ','\t','\n','\r','\u00a0','\u2003'}){
                String input="A &"+whitespace+"B";
                check(FtbTextSafety.escapeLiteralAmpersands(input).equals("A \\&"+whitespace+"B"),"whitespace after literal ampersand not safe: "+(int)whitespace);
            }
        });
        test("ftb_existing_escapes_match_parser_immediate_backslash_rule_and_are_idempotent",()->{
            for(int count=1;count<=6;count++){
                String input="Título "+"\\".repeat(count)+"& Avançado";
                check(FtbTextSafety.escapeLiteralAmpersands(input).equals(input),"existing FTB escape duplicated for "+count+" backslashes");
            }
            String safe=FtbTextSafety.escapeLiteralAmpersands("A & B & C &");
            check(safe.equals("A \\& B \\& C \\&"),"multiple literals incompletely escaped");
            check(FtbTextSafety.escapeLiteralAmpersands(safe).equals(safe),"repeated render adds escapes");
        });
        test("ftb_color_style_hex_and_substitution_codes_are_preserved",()->{
            String formatted="&aVerde &lNegrito&r &zArco-íris &#12AbEfCor §bAzul {item:minecraft:stone} A&B";
            check(FtbTextSafety.escapeLiteralAmpersands(formatted)==formatted,"valid FTB formatting/substitution altered");
            String title="&9&lExtended & Advanced AE&r";
            check(FtbTextSafety.escapeLiteralAmpersands(title).equals("&9&lExtended \\& Advanced AE&r"),"literal escape changed adjacent title formatting");
        });
        test("ftb_cached_title_is_escaped_at_presentation_only_without_retranslation_or_disk_mutation",()->{
            try(Mock mock=new Mock()){
                Path dir=init("ftb-cache-title-safety",mock,false);closeService();
                String source="Extended & Advanced AE",pt="AE estendido & avançado";
                JsonObject row=new JsonObject();row.addProperty("key","@quest");row.addProperty("source",source);row.addProperty("translated",pt);row.addProperty("qualityVersion",2);
                JsonArray rows=new JsonArray();rows.add(row);Files.writeString(dir.resolve("translations.json"),GSON.toJson(rows));
                initializeLocal(dir.getParent(),mock,false);scan(Map.of(),Map.of(),Map.of());TranslationService.setTranslationPaused(true);
                byte[] persisted=Files.readAllBytes(dir.resolve("translations.json"));
                check(TranslationService.translateQuest(source).equals(pt),"existing title cache not reused");
                check(FtbTextSafety.escapeLiteralAmpersands(TranslationService.translateQuest(source)).equals("AE estendido \\& avançado"),"cached title remains invalid for FTB parser");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                check(FtbTextSafety.escapeLiteralAmpersands(TranslationService.translateQuest(source)).equals("Extended \\& Advanced AE"),"source title invalid with display cache disabled");
                check(Arrays.equals(persisted,Files.readAllBytes(dir.resolve("translations.json")))&&mock.sent.isEmpty()&&jobs().isEmpty(),"presentation escaping changed cache bytes or invoked translation");
            }
        });
        test("local_restart_has_no_timed_block_and_RAM_guard_and_manual_pause_remain",()->{
            try(Mock mock=new Mock()){
                init("local-restart-no-cooldown",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String source="This visible tool stores your items.";scan(Map.of("option.testmod.restart",source),Map.of(),Map.of());translated("option.testmod.restart",source);
                mock.state="Local translation paused: low system RAM";((AtomicBoolean)value(svc,"busy")).set(false);invoke(svc,"updateProgress");
                await(()->{try{invoke(svc,"updateProgress");}catch(Exception e){return false;}return TranslationService.translationProgress().phase().equals("paused")&&TranslationService.translationProgress().reason().startsWith("RAM:");},"RAM safeguard remains after fixed cooldown removal");
                check(jobs().size()==1&&mock.sent.isEmpty(),"RAM wait dropped visible work or translated with unavailable provider");
                mock.restartDelay=Long.MAX_VALUE;mock.state="watchdog or process exit";invoke(svc,"updateProgress");
                check(LocalTranslationClient.restartDelayMillis()==0,"native provider still has a timed startup block");
                await(()->{try{invoke(svc,"updateProgress");}catch(Exception e){return false;}return TranslationService.translationProgress().phase().equals("waiting")&&TranslationService.translationProgress().reason().equals("Aguardando o tradutor local");},"watchdog state remains waiting without timed protection");
                TranslationService.setTranslationPaused(true);await(()->TranslationService.translationProgress().phase().equals("user_paused"),"manual pause must take precedence after watchdog exit");
                mock.ready=true;Thread.sleep(250);check(mock.sent.isEmpty()&&jobs().size()==1,"availability overrides explicit user pause");
                long requested=System.nanoTime();TranslationService.setTranslationPaused(false);
                await(()->translated("option.testmod.restart",source).equals("PT: "+source),"provider readmitted immediately without 60/120-second restart delay");
                check(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-requested)<4000,"ready provider remains blocked by previous resource wait");
                check(mock.sent.size()==1&&jobs().isEmpty(),"readmission duplicates or loses previously waiting text");
            }
        });

        test("edition_resource_selection_and_fail_safe",()->{
            check(TranslationEdition.from(" NORMAL ")==TranslationEdition.NORMAL,"normal edition parsing");
            check(TranslationEdition.from(null)==TranslationEdition.LITE,"missing edition fails open");
            check(TranslationEdition.from("unexpected")==TranslationEdition.LITE,"unknown edition fails open");
            String expected=System.getProperty("traduzmods.test.expectedEdition");
            if(expected!=null)check(TranslationEdition.current().id().equals(expected),"packaged resource did not select "+expected);
        });
        test("lite_and_normal_speed_resource_tradeoff",()->{
            var lite=TranslationEdition.LITE;var normal=TranslationEdition.NORMAL;
            check(lite.memoryBudgetMiB()==2048&&normal.memoryBudgetMiB()==8192,"edition RAM ceilings changed");
            check(lite.contextTokens()<normal.contextTokens(),"normal context not increased");
            check(lite.batchTokens()<normal.batchTokens()&&lite.microBatchTokens()<normal.microBatchTokens(),"normal batching not increased");
            check(lite.workerIntervalMillis()>normal.workerIntervalMillis(),"normal worker cadence not faster");
            check(lite.chunkCharacters()<normal.chunkCharacters(),"normal paragraph budget not increased");
            check(lite.threads()<=2&&normal.threads()<=6&&lite.threads()>=1,"CPU bounds invalid");
            check(!lite.allowsBackgroundWhilePlaying()&&normal.allowsBackgroundWhilePlaying(),"game performance policies identical");
        });
        test("edition_idle_admission_boundary_and_inflight_protection",()->{
            long idle=TranslationEdition.current().idleMillis();
            check(LocalResourcePolicy.IDLE_MILLIS==idle,"resource policy ignores edition idle");
            check(LocalResourcePolicy.MEMORY_LIMIT_MIB==(TranslationEdition.current()==TranslationEdition.NORMAL?8192:2048),"resource policy ignores edition RAM ceiling");
            check(!LocalResourcePolicy.shouldUnload(idle-1,0,false),"model unloaded before edition grace period");
            check(LocalResourcePolicy.shouldUnload(idle,0,false),"model retained after edition grace period");
            check(!LocalResourcePolicy.shouldUnload(idle+1,0,true),"active inference unloaded");
            check(!LocalResourcePolicy.canStart(4095)&&LocalResourcePolicy.canStart(4096),"free-RAM minimum startup boundary invalid");
            check(LocalResourcePolicy.budgetForStart(6000)+2048<=6000,"adaptive startup consumes game reserve");
            check(LocalResourcePolicy.underPressure(1535)&&!LocalResourcePolicy.underPressure(1536),"RAM pressure boundary invalid");
        });
        test("edition_status_uses_jar_profile_despite_legacy_configuration",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("edition-status",mock,true);
                set(value(service(),"config"),"pretranslateWhilePlaying",!TranslationEdition.current().allowsBackgroundWhilePlaying());
                invoke(service(),"persistSafely");
                JsonObject status=JsonParser.parseString(Files.readString(dir.resolve("status.json"))).getAsJsonObject();
                check(status.get("edition").getAsString().equals(TranslationEdition.current().id()),"status reports wrong JAR edition");
                check(status.get("pretranslateWhilePlaying").getAsBoolean()==TranslationEdition.current().allowsBackgroundWhilePlaying(),"legacy flag overrides packaged edition");
                check(status.get("localMemoryBudgetMiB").getAsInt()==LocalResourcePolicy.currentMemoryBudgetMiB(),"status RAM policy wrong");
                check(status.get("localIdleUnloadSeconds").getAsLong()==TranslationEdition.current().idleMillis()/1000,"status unload policy wrong");
            }
        });
        test("adaptive_RAM_reserves_game_headroom_and_caps_large_machines",()->{
            check(AdaptiveHardwarePolicy.memoryBudget(16384,12288)==5376,"16 GiB machine exceeds one-third budget");
            check(AdaptiveHardwarePolicy.memoryBudget(8192,6000)==2560,"8 GiB machine budget not reduced");
            check(AdaptiveHardwarePolicy.memoryBudget(65536,32768)==8192,"large-machine ceiling missing");
            check(AdaptiveHardwarePolicy.memoryBudget(16384,4096)==2048,"low-RAM model ceiling invalid");
            check(AdaptiveHardwarePolicy.memoryBudget(-1,-1)==2048,"unknown RAM must fall back conservatively");
            for(int free=4096;free<=32000;free+=256)check(AdaptiveHardwarePolicy.memoryBudget(65536,free)+2048<=free,"game reserve consumed at "+free);
        });
        test("adaptive_CPU_leaves_threads_for_the_running_world",()->{
            check(AdaptiveHardwarePolicy.threads(12,false)==11,"12-thread menu allocation wrong");
            check(AdaptiveHardwarePolicy.threads(12,true)==9,"running-world CPU reserve wrong");
            check(AdaptiveHardwarePolicy.threads(1,false)==1&&AdaptiveHardwarePolicy.threads(0,true)==1,"small/unknown CPU rejected");
            check(AdaptiveHardwarePolicy.threads(128,false)==32&&AdaptiveHardwarePolicy.threads(128,true)==32,"thread cap missing");
            for(int cpu=2;cpu<=128;cpu++)check(AdaptiveHardwarePolicy.threads(cpu,true)<=AdaptiveHardwarePolicy.threads(cpu,false),"world uses more CPU than menu");
        });
        test("adaptive_CUDA_requires_free_VRAM_and_supported_driver",()->{
            var low=AdaptiveHardwarePolicy.plan(new AdaptiveHardwarePolicy.Hardware(16384,8192,12,4095,8192,580),true);
            var oldDriver=AdaptiveHardwarePolicy.plan(new AdaptiveHardwarePolicy.Hardware(16384,8192,12,8192,8192,579),false);
            var safe=AdaptiveHardwarePolicy.plan(new AdaptiveHardwarePolicy.Hardware(16384,8192,12,4096,8192,580),true);
            check(!low.gpuEligible()&&!oldDriver.gpuEligible(),"unsafe CUDA admission");
            check(safe.gpuEligible()&&safe.batchTokens()==512&&safe.microBatchTokens()==256,"4 GiB free VRAM selection wrong");
            check(low.batchTokens()==256&&low.microBatchTokens()==128,"CPU inference buffers not reduced");
            check(safe.worldLoaded()&&safe.threads()==9,"world CPU settings not propagated");
        });
        test("adaptive_high_VRAM_increases_batch_without_inflating_context",()->{
            var plan=AdaptiveHardwarePolicy.plan(new AdaptiveHardwarePolicy.Hardware(65536,32768,32,8192,12288,580),false);
            check(plan.gpuEligible()&&plan.batchTokens()==1024&&plan.microBatchTokens()==512,"high VRAM batching unchanged");
            check(plan.contextTokens()==4096,"context needlessly inflated for short translations");
            check(plan.memoryBudgetMiB()==8192&&plan.threads()==31,"large-machine bounded plan wrong");
        });
        test("adaptive_VRAM_pressure_does_not_confuse_unknown_or_safe_probe",()->{
            check(AdaptiveHardwarePolicy.gpuUnderPressure(1023),"low GPU RAM not recognized");
            check(!AdaptiveHardwarePolicy.gpuUnderPressure(1024)&&!AdaptiveHardwarePolicy.gpuUnderPressure(-1),"safe/unknown GPU probe kills inference");
        });
        test("eight_local_results_commit_seven_durably_and_retry_only_failed_slot",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("eight-local-partial",mock,false);Map<String,String> en=new LinkedHashMap<>();
                for(int i=0;i<8;i++)en.put("option.testmod.batch"+i,"The storage contains "+i+" items.");
                scan(en,Map.of(),Map.of());check(jobs().size()==8,"startup did not queue every batch source");
                Object previous=service();((ScheduledExecutorService)value(previous,"worker")).shutdownNow();
                List<Object> batch=new ArrayList<>(jobs().values());List<String> outputs=new ArrayList<>();
                for(Object job:batch){set(job,"running",true);outputs.add("PT: "+value(job,"source"));}
                String failed=(String)value(batch.get(3),"source");outputs.set(3,null);
                Files.writeString(dir.resolve("pending.json"),GSON.toJson(batch));
                Method commit=previous.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);commit.invoke(previous,batch,outputs,false);
                check(jobs().size()==1&&jobs().containsKey(failed),"a bad local slot discarded valid results or was dropped");
                var progress=TranslationService.translationProgress();
                check(progress.pending()==1&&progress.completed()==(TranslationEdition.current()==TranslationEdition.NORMAL?7:0),"failed batch slot advanced HUD completion");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==7,"seven results were not durable before publication");
                set(previous,"closed",true);field(TranslationService.class,"instance").set(null,null);
                init("eight-local-partial",mock,false);scan(en,Map.of(),Map.of());
                for(var entry:en.entrySet())if(!entry.getValue().equals(failed))check(translated(entry.getKey(),entry.getValue()).equals("PT: "+entry.getValue()),"journal recovery lost "+entry.getKey());
                check(jobs().size()==1&&jobs().containsKey(failed),"restart retried already committed slots");
                check(mock.sent.isEmpty(),"local durability test called local provider");
            }
        });
        test("native_reference_prefilter_preserves_Unicode_boundaries_and_plurals",()->{
            GameTranslationContext.install(Map.of("Iron","Ferro","Σ","Sígma","Oak Log","Tronco de carvalho"));
            String unicode=GameTranslationContext.localReferences("Use İron and ς.");
            check(unicode.contains("Iron translates to Ferro.")&&unicode.contains("Σ translates to Sígma."),"Unicode regex case folding was suppressed by ASCII prefilter");
            String blocked=GameTranslationContext.localReferences("Ironwork Iron_1 xIron Iron2.");
            check(!blocked.contains("Iron translates to Ferro."),"native term matched inside another word/identifier");
            String plural=GameTranslationContext.localReferences("Use IRONS.");
            check(plural.contains("Irons translates to Ferros.")&&!plural.contains("Iron translates to Ferro."),"plural/singular word boundaries changed");
            String quest="Logs drop here.";GameTranslationContext.registerQuestSources(Set.of(quest));
            check(GameTranslationContext.localReferences(quest).contains("Logs translates to Troncos."),"quest native plural missing");
            check(!GameTranslationContext.localReferences("Logs are diagnostic messages.").contains("Logs translates to Troncos."),"ambiguous logs leaked into non-quest context");
            check(GameTranslationContext.normalizeTranslated(quest,"Registros vão soltar.").equals("Troncos cairão."),"native terminology normalization changed");
        });
        test("native_lookup_snapshot_preserves_exact_Unicode_and_install_replacement",()->{
            Map<String,String> labels=new LinkedHashMap<>();
            labels.put("IRON","Ferro A");labels.put("iron","Ferro B");labels.put("Σ","Sigma maiúsculo");labels.put("ς","Sigma final");
            labels.put("İ","I pontuado");labels.put("i","I minúsculo");labels.put("K","K ASCII");labels.put("K","K Kelvin");
            labels.put("Unique Tool","Ferramenta única");GameTranslationContext.install(labels);
            Object snapshot=field(GameTranslationContext.class,"nativeTerminology").get(null);
            @SuppressWarnings("unchecked") Map<String,String> exact=(Map<String,String>)value(snapshot,"exact");
            for(String query:List.of("IRON","iron","IrOn","Σ","σ","ς","İ","i","I","ı","K","k","K","unique tools","Unknown Term")) {
                String expected=exact.get(query);
                if(expected==null)for(var entry:exact.entrySet())if(entry.getKey().equalsIgnoreCase(query)){expected=entry.getValue();break;}
                check(Objects.equals(expected,GameTranslationContext.nativeLabel(query)),"native lookup changed equalsIgnoreCase/first-winner semantics for "+query);
            }
            check(GameTranslationContext.nativeLabel("IRON").equals("Ferro A")&&GameTranslationContext.nativeLabel("iron").equals("Ferro B"),"exact collision labels lost priority");
            labels.clear();labels.put("Unique Tool","Mutação indevida");
            check(GameTranslationContext.nativeLabel("Unique Tool").equals("Ferramenta única"),"caller mutated installed labels");
            check(GameTranslationContext.nativeLabel("New Tool")==null,"new label unexpectedly cached");
            GameTranslationContext.install(Map.of("New Tool","Ferramenta nova","Unique Tool","Ferramenta revisada"));
            check(GameTranslationContext.nativeLabel("new tool").equals("Ferramenta nova"),"previous miss survived replacement");
            check(GameTranslationContext.nativeLabel("UNIQUE TOOL").equals("Ferramenta revisada"),"old folded label survived replacement");
            check(GameTranslationContext.nativeLabel("IRON")==null&&GameTranslationContext.nativeLabel("σ")==null,"removed native labels survived replacement");
            check(exact.get("IRON").equals("Ferro A"),"previous published snapshot was modified during install");
        });
        test("protected_tokens_follow_format_pattern_and_replaced_mod_NPC_registration",()->{
            ModTextPolicy.registerModNames(List.of("Sodium"));ModTextPolicy.registerNpcNames(List.of("Durin"));
            var first=java.util.regex.Pattern.compile("%[0-9]+\\$s|§.");
            List<String> tokens=ModTextPolicy.protectedTokens(first).matcher("Sodium Durin %1$s §a").results().map(java.util.regex.MatchResult::group).toList();
            check(tokens.equals(List.of("Sodium","Durin","%1$s","§a")),"initial formatting/brands not protected");
            var replacement=java.util.regex.Pattern.compile("\\{[0-9]+}");
            List<String> changedFormat=ModTextPolicy.protectedTokens(replacement).matcher("Sodium Durin %1$s {0}").results().map(java.util.regex.MatchResult::group).toList();
            check(changedFormat.equals(List.of("Sodium","Durin","{0}")),"cached protection retained an obsolete formatting pattern");
            ModTextPolicy.registerModNames(List.of("Iris"));ModTextPolicy.registerNpcNames(List.of("S'kellak"));
            List<String> changedNames=ModTextPolicy.protectedTokens(replacement).matcher("Sodium Durin Iris S'kellak {0}").results().map(java.util.regex.MatchResult::group).toList();
            check(changedNames.equals(List.of("Iris","S'kellak","{0}")),"old mod/NPC cache survives registration replacement");
            check(ModTextPolicy.protectedTokens(first).matcher("%1$s §b Iris").results().count()==3,"cache failed to switch back to prior formatting pattern");
        });
        test("progress_snapshot_gates_optout_and_reports_start_wait_resource_pause_without_render_work",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("progress-gates",mock,false);Object svc=service();Object config=value(svc,"config");
                check(JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("showTranslationProgress").getAsBoolean(),"existing config did not receive default HUD setting");
                check(!TranslationService.translationProgress().enabled(),"HUD shown with non-Portuguese language");
                field(TranslationService.class,"portugueseActive").setBoolean(null,true);invoke(svc,"updateProgress");
                var starting=TranslationService.translationProgress();check(starting.enabled()&&starting.active()&&starting.phase().equals("starting")&&starting.total()==0,"catalog startup phase missing");
                ((AtomicBoolean)value(svc,"busy")).set(true);scan(Map.of("option.testmod.progress","The visible storage is waiting."),Map.of(),Map.of());
                translated("option.testmod.progress","The visible storage is waiting.");
                ((ScheduledExecutorService)value(svc,"worker")).shutdown();((ScheduledExecutorService)value(svc,"worker")).awaitTermination(2,TimeUnit.SECONDS);
                ((AtomicBoolean)value(svc,"busy")).set(false);
                String originalState=mock.state;
                try {
                    mock.state="Downloading local model";invoke(svc,"updateProgress");
                    check(TranslationService.translationProgress().phase().equals("starting"),"model download bootstrap not shown as starting");
                    mock.state="Local service unavailable";invoke(svc,"updateProgress");var waiting=TranslationService.translationProgress();
                    check(waiting.active()&&waiting.phase().equals("waiting")&&waiting.visiblePending()==1,"provider waiting phase misreported");
                    int queued=jobs().size();String pending=Files.readString(dir.resolve("pending.json"));
                    for(int i=0;i<2000;i++)check(TranslationService.translationProgress()==waiting,"getter allocates/rebuilds renderer snapshot");
                    check(jobs().size()==queued&&Files.readString(dir.resolve("pending.json")).equals(pending)&&mock.payloads.isEmpty(),"HUD getter touched queue/disk/provider");
                    mock.state="Stopped to preserve game RAM";invoke(svc,"updateProgress");
                    check(TranslationService.translationProgress().phase().equals("paused"),"RAM pause shown as regular translation");
                    set(config,"showTranslationProgress",false);check(!TranslationService.translationProgress().enabled(),"HUD opt-out waits for worker");
                    set(config,"showTranslationProgress",true);set(config,"enabled",false);check(!TranslationService.translationProgress().enabled(),"disabled mod retained HUD");
                    set(config,"enabled",true);field(TranslationService.class,"portugueseActive").setBoolean(null,false);check(!TranslationService.translationProgress().enabled(),"language switch retained HUD");
                    check(mock.payloads.isEmpty(),"phase test called a provider");
                } finally {mock.state=originalState;}
            }
        });
        test("progress_counts_unique_cached_sources_durable_commits_and_Lite_visible_work",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("progress-counts",mock,false);closeService();
                String cached="The cached storage is ready.",one="The first tool is ready.",two="The second tool is ready.";
                Files.writeString(dir.resolve("translations.json"),"[{\"key\":\"option.testmod.cached\",\"source\":\""+cached+"\",\"translated\":\"Armazenamento pronto.\",\"qualityVersion\":2}]");
                init("progress-counts",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                Map<String,String> english=Map.of("option.testmod.cached",cached,"option.testmod.alias",cached,"option.testmod.one",one,"option.testmod.two",two,"option.testmod.native","Native storage");
                Map<String,String> nativePt=Map.of("option.testmod.native","Armazenamento nativo");scan(english,nativePt,Map.of());
                boolean normal=TranslationEdition.current()==TranslationEdition.NORMAL;var catalog=TranslationService.translationProgress();
                check(catalog.completed()==(normal?1:0)&&catalog.pending()==2&&catalog.total()==(normal?3:0),"catalog cache/duplicate/native/Lite counters wrong");
                translated("option.testmod.one",one);((ScheduledExecutorService)value(svc,"worker")).shutdown();((ScheduledExecutorService)value(svc,"worker")).awaitTermination(2,TimeUnit.SECONDS);
                Object first=jobs().get(one),second=jobs().get(two);set(first,"running",true);invoke(svc,"updateProgress");var visible=TranslationService.translationProgress();
                check(visible.visiblePending()==1&&visible.pending()==2&&visible.total()==(normal?3:1)&&visible.phase().equals("translating"),"running visible text vanished or background counted in Lite HUD");
                Method commit=svc.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);
                commit.invoke(svc,List.of(first,second),List.of("Primeira ferramenta pronta.","Segunda ferramenta pronta."),false);
                var complete=TranslationService.translationProgress();check(!complete.active()&&complete.phase().equals("completed")&&complete.pending()==0&&complete.visiblePending()==0&&complete.completed()==(normal?3:1),"durable commits did not finish correct edition scope");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==2,"HUD completion published before durable journal");
                Method scanner=svc.getClass().getDeclaredMethod("scan",ResourceManager.class,long.class);scanner.setAccessible(true);long generation=((AtomicLong)value(svc,"reloadGeneration")).get();scanner.invoke(svc,manager(english,nativePt,Map.of()),generation);
                var rescanned=TranslationService.translationProgress();check(rescanned.completed()==(normal?3:1)&&rescanned.total()==(normal?3:1),"rescan double-counted successful cached sources");
                String stale="The old tool is waiting.",changed="The revised tool is waiting.";
                scanner.invoke(svc,manager(Map.of("option.testmod.changed",stale),Map.of(),Map.of()),generation);
                translated("option.testmod.changed",stale);invoke(svc,"updateProgress");
                check(TranslationService.translationProgress().visiblePending()==1,"new visible source not tracked after completed catalog");
                scanner.invoke(svc,manager(Map.of("option.testmod.changed",changed),Map.of(),Map.of()),generation);
                check(TranslationService.translationProgress().visiblePending()==0&&!jobs().containsKey(stale)&&jobs().containsKey(changed),"stale changed resource remains in visible progress");
                check(mock.payloads.isEmpty(),"progress-count tests used provider");
            }
        });
        test("future_visible_retry_preserves_priority_without_starving_ready_catalog",()->{
            try(Mock mock=new Mock()) {
                init("future-visible",mock,true);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String future="The blocked item will retry.",visibleReady="The visible item is ready.",catalogReady="The catalog item is ready.";
                scan(Map.of("option.testmod.future",future,"option.testmod.ready",visibleReady,"option.testmod.catalog",catalogReady),Map.of(),Map.of());

                Method enqueue=svc.getClass().getDeclaredMethod("enqueue",String.class,String.class,boolean.class);enqueue.setAccessible(true);
                if(TranslationEdition.current()==TranslationEdition.LITE)enqueue.invoke(svc,"option.testmod.catalog",catalogReady,true);
                enqueue.invoke(svc,"option.testmod.future",future,true);enqueue.invoke(svc,"option.testmod.ready",visibleReady,true);
                Object blocked=jobs().get(future);set(blocked,"retryAt",System.currentTimeMillis()+3_600_000);set(blocked,"lastPromoted",System.currentTimeMillis()-300);
                enqueue.invoke(svc,"option.testmod.future",future,true);
                @SuppressWarnings("unchecked") Deque<String> background=(Deque<String>)value(svc,"background");background.remove(future);background.remove(visibleReady);
                ((AtomicBoolean)value(svc,"busy")).set(false);invoke(svc,"requestTick");
                await(()->mock.sent.contains(visibleReady)&&mock.sent.contains(catalogReady),"ready visible/catalog translation behind future retry");
                await(()->{try{return jobs().size()==1;}catch(Exception e){throw new RuntimeException(e);}},"only delayed source remains pending");
                check(mock.sent.indexOf(visibleReady)<mock.sent.indexOf(catalogReady),"ready visible text lost its priority");
                check(!mock.sent.contains(future),"future retry was sent before its deadline");
                check((Boolean)value(blocked,"priority"),"delayed visible priority was dropped");
                @SuppressWarnings("unchecked") Deque<String> queue=(Deque<String>)value(svc,"visible");
                check(queue.stream().filter(future::equals).count()==1,"future retry was duplicated or dropped");
            }
        });
        test("unready_visible_provider_does_not_block_ready_canonical_UI",()->{
            try(Mock mock=new Mock()) {
                init("unready-visible",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String waiting="The unknown tool is waiting.";
                scan(Map.of("option.testmod.waiting",waiting,"option.testmod.armory","Armory"),Map.of(),Map.of());
                Method enqueue=svc.getClass().getDeclaredMethod("enqueue",String.class,String.class,boolean.class);enqueue.setAccessible(true);
                // Lite only advances visible work; Normal also advances this canonical catalog entry.
                if(TranslationEdition.current()==TranslationEdition.LITE)enqueue.invoke(svc,"option.testmod.armory","Armory",true);
                enqueue.invoke(svc,"option.testmod.waiting",waiting,true);
                @SuppressWarnings("unchecked") Deque<String> background=(Deque<String>)value(svc,"background");background.remove(waiting);
                if(TranslationEdition.current()==TranslationEdition.LITE)background.remove("Armory");
                Field bySource=field(svc.getClass(),"bySource");((AtomicBoolean)value(svc,"busy")).set(false);invoke(svc,"requestTick");
                await(()->{try{return "Arsenal".equals(((Map<?,?>)bySource.get(svc)).get("Armory"));}catch(Exception e){throw new RuntimeException(e);}},"canonical ready entry behind provider-blocked visible text");
                check(jobs().size()==1&&jobs().containsKey(waiting),"provider-blocked source was dropped");
                @SuppressWarnings("unchecked") Deque<String> queue=(Deque<String>)value(svc,"visible");
                // A concurrent tick briefly holds this job in its deferred map.
                // Observe its eventual requeue, then check uniqueness separately.
                await(()->queue.stream().filter(waiting::equals).count()==1,"provider-blocked source was not requeued");
                check(queue.stream().filter(waiting::equals).count()<=1,"provider-blocked source duplicated");
                check(mock.sent.isEmpty(),"canonical UI unexpectedly used local provider");
            }
        });

        test("FTB_Quests_JSON5_catalog_preserves_style_and_array_indices",()->{
            Path config=root.resolve("quest-json5-fixture"),lang=config.resolve("ftbquests/quests/lang");
            Files.createDirectories(lang.resolve("en_us/chapters"));Files.createDirectories(lang.resolve("pt_br/chapters"));
            String fixture="""
                    {
                      // comment containing a trailing-looking comma, }
                      "quest.STABLE.title": "Basic &bPower&f",
                      "quest.STABLE.quest_desc": [
                        "Use &bEnergy Storage&f, and retain [a,] {count}.",
                        "",
                        "The line\\ncontains %1$d energy.", /* trailing comment */
                      ],
                      "ignored_number": 12,
                      "nested": {caption: 'Click "Open", then continue.',},
                    }
                    """;
            Path englishFile=lang.resolve("en_us/chapters/test.json5");Files.writeString(englishFile,fixture);
            Files.writeString(lang.resolve("pt_br/chapters/test.json5"),"{\"quest.STABLE.title\":\"Energia &bbásica&f\",\"quest.STABLE.quest_desc\":[\"Use energia com {count}.\",],}");
            Files.writeString(lang.resolve("en_us/broken.json5"),"{broken");
            QuestCatalog.Catalog catalog=QuestCatalog.scan(config);
            check(catalog.english().size()==5,"JSON5 string/array catalog count incorrect: "+catalog.english());
            check(catalog.english().get("quest.STABLE.title").equals("Basic &bPower&f"),"FTB formatting codes changed");
            check(catalog.english().get("quest.STABLE.quest_desc[0]").equals("Use &bEnergy Storage&f, and retain [a,] {count}."),"prose commas or brackets modified");
            check(catalog.english().get("quest.STABLE.quest_desc[1]").isEmpty(),"empty description line lost index");
            check(catalog.english().get("quest.STABLE.quest_desc[2]").equals("The line\ncontains %1$d energy."),"description newlines/placeholders changed");
            check(catalog.english().get("nested.caption").equals("Click \"Open\", then continue."),"lenient singlequoted JSON5 lost prose");
            check(catalog.portuguese().get("quest.STABLE.title").equals("Energia &bbásica&f"),"native quest catalog not discovered");
            check(catalog.portuguese().containsKey("quest.STABLE.quest_desc[0]"),"native description index differs from English");
            check(Files.readString(englishFile).equals(fixture),"adapter modified quest file");
            check(QuestCatalog.scan(root.resolve("absent-quest-config")).english().isEmpty(),"missing FTB Quests must be harmless");
        });
        test("automatic_resource_discovery_native_preservation_dedup_revision",()->{
            try(Mock mock=new Mock()){
                // Hold provider completion until the resource scan revision baseline is observed.
                // Cache rows are published before the callback increments revision; await that
                // increment separately instead of assuming a lookup observes the whole callback.
                mock.responseGate=new CountDownLatch(1);
                Path dir=init("discovery",mock,true);
                Map<String,String> en=Map.of("item.testmod.native","Menril Berries","item.testmod.one","Energy Storage","item.testmod.two","Energy Storage","quest.testmod.button","Quests");
                scan(en,Map.of("item.testmod.native","Bagas de Menril","quest.testmod.button","Quests"),Map.of("item.testmod.native","Bagas personalizadas"));
                long before=TranslationService.revision();
                check(translated("item.testmod.native","Menril Berries").equals("Bagas personalizadas"),"native override lost");
                check(!translated("item.testmod.two","Energy Storage").equals("PT: Energy Storage"),"provider gate failed to isolate completion revision");
                mock.responseGate.countDown();
                await(()->translated("quest.testmod.button","Quests").equals("PT: Quests"),"English in pt_br translation");
                await(()->translated("item.testmod.two","Energy Storage").equals("PT: Energy Storage"),"async source cache reuse");
                await(()->TranslationService.revision()>before,"completion must invalidate rendered contents");
                check(Collections.frequency(mock.sent,"Energy Storage")==1,"same English source sent more than once");
                check(!mock.sent.contains("Menril Berries")&&!mock.sent.contains("Bagas personalizadas"),"native pt-BR transmitted");
                check(!mock.sent.contains("Vanilla Storage"),"vanilla namespace scanned");
                check(!mock.payloads.isEmpty(),"local inference never received missing source");
                check(Files.readString(dir.resolve("inventory.json")).contains("missingBrazilian"),"discovery inventory not persisted");
                int count=mock.sent.size();TranslationService.translateLiteral("Bagas personalizadas");TranslationService.translateLiteral("Pressione para abrir o inventário");Thread.sleep(750);
                check(mock.sent.size()==count,"Portuguese literal was retransmitted");
            }
        });
        test("quest_catalog_automatically_discovered_native_preserved_unknown_prose_translated",()->{
            try(Mock mock=new Mock()){
                Path dir=init("quest-integration",mock,true),lang=dir.getParent().resolve("ftbquests/quests/lang");
                Files.createDirectories(lang.resolve("en_us/chapters"));Files.createDirectories(lang.resolve("pt_br/chapters"));
                Files.writeString(lang.resolve("en_us/chapters/quests.json5"),"{\"quest.NATIVE.title\":\"Mine &bStone&f\",\"quest.MISSING.quest_desc\":[\"A mysterious contraption\",],}");
                Files.writeString(lang.resolve("pt_br/chapters/quests.json5"),"{\"quest.NATIVE.title\":\"Minere &bPedra&f\",}");
                check(!TranslationService.looksEnglish("A mysterious contraption"),"fixture must test prose outside English heuristic");
                scan(Map.of(),Map.of(),Map.of());
                check(TranslationService.translateQuest("Mine &bStone&f").equals("Minere &bPedra&f"),"native quest source lookup lost formatting or translation");
                await(()->TranslationService.translateQuest("A mysterious contraption").equals("PT: A mysterious contraption"),"automatically catalogued quest description completed");
                check(TranslationService.translateLiteral("A mysterious contraption").equals("PT: A mysterious contraption"),"known catalog source unavailable to literal rendering");
                check(mock.sent.size()==1,"native quest title charged to provider");
                JsonObject inventory=JsonParser.parseString(Files.readString(dir.resolve("inventory.json"))).getAsJsonObject();
                check(inventory.has("ftbquests_content"),"external quest namespace absent from inventory");
                check(inventory.getAsJsonObject("ftbquests_content").get("nativeBrazilian").getAsInt()==1,"quest native count incorrect");
                check(inventory.getAsJsonObject("ftbquests_content").get("missingBrazilian").getAsInt()==1,"quest missing count incorrect");
            }
        });
        test("styled_whole_phrase_XML_preserves_spans_and_cached_output_is_not_retranslated",()->{
            try(Mock mock=new Mock()){
                mock.translation=text->text.replace("Not ","Não ").replace("yet eaten","consumido ainda");
                Path dir=init("styled-whole-phrase",mock,true);
                Map<String,String> en=Map.of("item.testmod.nativecaption","Press to open"),pt=Map.of("item.testmod.nativecaption","Pressione para abrir");
                scan(en,pt,Map.of());
                String nativeStyled=StyledTextCodec.encode(List.of("Pressione ","para abrir"));
                check(TranslationService.translateStyled(nativeStyled).equals(nativeStyled),"native styled Portuguese changed");
                String source=StyledTextCodec.encode(List.of("Not ","yet eaten")),expected=StyledTextCodec.encode(List.of("Não ","consumido ainda"));
                await(()->TranslationService.translateStyled(source).equals(expected),"whole styled phrase translated preserving spans");
                check(mock.payloads.size()==1&&mock.payloads.get(0).getAsJsonArray("text").size()==1,"styled fragments were translated independently");
                JsonObject request=mock.payloads.get(0);String sent=request.getAsJsonArray("text").get(0).getAsString();
                check(sent.contains("<mt0>Not </mt0><mt1>yet eaten</mt1>"),"provider did not receive complete phrase with span tags");

                check(StyledTextCodec.decode(expected,2).size()==2&&StyledTextCodec.plainText(expected).equals("Não consumido ainda"),"translated span decoding incorrect");
                check(StyledTextCodec.decode("<mt0>a</mt0><mt0>b</mt0>",2)==null,"duplicate style IDs accepted");
                check(StyledTextCodec.decode("<mt0>a</mt0>extra<mt1>b</mt1>",2)==null,"text outside style spans accepted");
                check(StyledTextCodec.decode("<mt1>b</mt1><mt0>a</mt0>",2)!=null,"valid reordered styles rejected");
                check(TranslationService.translateStyled(expected).equals(expected),"translated styled output queued again");
                check(TranslationService.translateLiteral("Não consumido ainda").equals("Não consumido ainda"),"translated plain output queued again");
                check(jobs().isEmpty(),"translated output created new pending job");
                closeService();initializeLocal(dir.getParent(),mock,mock.ready);scan(en,pt,Map.of());
                check(TranslationService.translateStyled(source).equals(expected),"styled cache did not survive restart");
                TranslationService.translateStyled(expected);TranslationService.translateLiteral("Não consumido ainda");Thread.sleep(750);
                check(mock.payloads.size()==1&&jobs().isEmpty(),"styled cache reload retransmitted translated output");
            }
        });
        test("local_partial_commit_keeps_good_result_and_retries_only_bad_slot",()->{
            try(Mock mock=new Mock()){
                Path dir=init("partial-batch",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String good="Safe Storage %s",bad="Unsafe Storage %s";
                scan(Map.of("item.testmod.good",good,"item.testmod.bad",bad),Map.of(),Map.of());translated("item.testmod.good",good);translated("item.testmod.bad",bad);
                ((ScheduledExecutorService)value(svc,"worker")).shutdown();((ScheduledExecutorService)value(svc,"worker")).awaitTermination(2,TimeUnit.SECONDS);
                Object goodJob=jobs().get(good),badJob=jobs().get(bad);List<Object> batch=List.of(goodJob,badJob);set(goodJob,"running",true);set(badJob,"running",true);
                Method commit=svc.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);commit.invoke(svc,batch,List.of("Armazenamento seguro %s","Armazenamento inseguro"),false);
                check(translated("item.testmod.good",good).equals("Armazenamento seguro %s"),"valid neighboring slot not published");
                check(jobs().size()==1&&jobs().containsKey(bad)&&((Map<?,?>)value(svc,"bySource")).get(bad)==null,"invalid placeholder result completed or discarded pending source");
                check((int)value(badJob,"failures")==1&&(long)value(badJob,"retryAt")>System.currentTimeMillis(),"invalid local result lacks retry");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==1,"good slot not durable before publication");
                invoke(svc,"persistSafely");JsonArray pending=JsonParser.parseString(Files.readString(dir.resolve("pending.json"))).getAsJsonArray();
                check(pending.size()==1&&pending.get(0).getAsJsonObject().get("source").getAsString().equals(bad),"partial retry state not persisted");
                check(mock.sent.isEmpty(),"partial commit test unexpectedly called inference");
            }
        });
        test("source_change_uses_new_translation_without_stale_cache",()->{
            try(Mock mock=new Mock()){
                init("source-change",mock,true);scan(Map.of("item.testmod.power","Energy Storage"),Map.of(),Map.of());
                await(()->translated("item.testmod.power","Energy Storage").equals("PT: Energy Storage"),"initial source translated");
                scan(Map.of("item.testmod.power","New Storage"),Map.of(),Map.of());
                check(!translated("item.testmod.power","New Storage").equals("PT: Energy Storage"),"stale source cache displayed");
                await(()->translated("item.testmod.power","New Storage").equals("PT: New Storage"),"changed source translated");
            }
        });
        test("pending_queue_survives_restart_and_completes",()->{
            try(Mock mock=new Mock()){
                Path dir=init("restart",mock,false);scan(Map.of("item.testmod.save","Saved Storage"),Map.of(),Map.of());
                check(jobs().size()==1,"pending job missing before restart");closeService();
                check(JsonParser.parseString(Files.readString(dir.resolve("pending.json"))).getAsJsonArray().size()==1,"pending file missing job");
                mock.ready=true;
                initializeLocal(dir.getParent(),mock,mock.ready);
                check(jobs().size()==1,"pending job not loaded after restart");
                field(TranslationService.class,"portugueseActive").setBoolean(null,true);
                set(service(),"catalogReady",true);
                await(()->translated("item.testmod.save","Saved Storage").equals("PT: Saved Storage"),"restored job completed without rediscovery");
                closeService();initializeLocal(dir.getParent(),mock,mock.ready);field(TranslationService.class,"portugueseActive").setBoolean(null,true);set(service(),"catalogReady",true);
                check(translated("item.testmod.save","Saved Storage").equals("PT: Saved Storage"),"completed cache did not survive restart");
                check(mock.sent.size()==1,"restart sent cached source again");
            }
        });
        test("new_native_translation_cancels_obsolete_pending_source",()->{
            try(Mock mock=new Mock()){
                Path dir=init("native-update",mock,false);scan(Map.of("item.testmod.newnative","Native Storage"),Map.of(),Map.of());
                check(jobs().size()==1,"initial pending source missing");closeService();
                mock.ready=true;
                initializeLocal(dir.getParent(),mock,mock.ready);
                scan(Map.of("item.testmod.newnative","Native Storage"),Map.of("item.testmod.newnative","Armazenamento nativo"),Map.of());
                check(translated("item.testmod.newnative","Native Storage").equals("Armazenamento nativo"),"new native pt_br not used");
                Thread.sleep(1000);check(mock.sent.isEmpty(),"obsolete pending source sent although native pt_br now exists");
                check(jobs().isEmpty(),"obsolete native source retained in pending queue");
            }
        });
        test("transient_failure_preserves_job_and_retries",()->{
            try(Mock mock=new Mock()){
                mock.transientFailures.set(1);Path dir=init("retry",mock,true);scan(Map.of("item.testmod.retry","Retry Storage"),Map.of(),Map.of());translated("item.testmod.retry","Retry Storage");
                await(()->{try{return value(service(),"lastFailure").equals("IOException");}catch(Exception e){return false;}},"local inference error registered");
                check(jobs().size()==1,"failed job was dropped");check(JsonParser.parseString(Files.readString(dir.resolve("pending.json"))).getAsJsonArray().size()==1,"failed job not persisted");
                Object job=jobs().values().iterator().next();set(job,"retryAt",0L);set(service(),"retryGlobalUntil",0L);
                await(()->translated("item.testmod.retry","Retry Storage").equals("PT: Retry Storage"),"retry completed");
                check(mock.sent.size()==2,"expected failed request plus retry");
            }
        });
        test("legacy_API_configuration_is_removed_and_local_provider_is_exclusive",()->{
            try(Mock mock=new Mock()){
                Path dir=init("legacy-api",mock,false);closeService();
                JsonObject legacy=new JsonObject();legacy.addProperty("deeplApiKey","not-a-real-key");legacy.addProperty("apiEndpoint","http://example.invalid/translate");legacy.addProperty("localFallback",false);legacy.addProperty("batchSize",64);
                Files.writeString(dir.resolve("config.json"),GSON.toJson(legacy));
                initializeLocal(dir.getParent(),mock,true);
                JsonObject migrated=JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
                check(!migrated.has("deeplApiKey")&&!migrated.has("apiEndpoint")&&!migrated.has("localFallback")&&!migrated.has("batchSize"),"legacy API configuration remains active");
                String source="This tool stores your first items.";scan(Map.of("option.testmod.local",source),Map.of(),Map.of());
                await(()->translated("option.testmod.local",source).startsWith("PT: "),"local provider did not complete legacy config queue");
                check(mock.sent.size()==1,"legacy config bypassed local provider or repeated translation");
            }
        });
        test("formatting_placeholders_survive_local_roundtrip",()->{
            try(Mock mock=new Mock()){
                init("tokens",mock,true);String source="Storage %1$s has {count} items §a at testmod:item https://example.invalid/x";
                scan(Map.of("item.testmod.tokens",source),Map.of(),Map.of());
                await(()->translated("item.testmod.tokens",source).equals("PT: "+source),"safe token restoration");
                check(mock.sent.get(0).contains("MODTRPH")&&!mock.sent.get(0).contains("%1$s"),"placeholders not protected before request");
                check(TranslationService.tokensValid(source,translated("item.testmod.tokens",source)),"cached placeholders altered");
            }
        });
        test("raw_FTB_color_codes_become_fullphrase_XML_then_restore_exact_colors",()->{
            try(Mock mock=new Mock()){
                mock.translation=text->text.replace("Mine","Minere").replace("some","um pouco de").replace("Stone","Pedra");
                init("raw-color-wholephrase",mock,true);
                String source="&9&lMine &r&9some &bStone&f.",expected="&9&lMinere &r&9um pouco de &bPedra&f.";
                scan(Map.of("quest.testmod.rawcolors",source),Map.of(),Map.of());
                await(()->translated("quest.testmod.rawcolors",source).equals(expected),"raw formatted title translated and colors restored");
                check(TranslationService.tokensValid(source,expected),"raw title formatting multiset changed");
                check(mock.payloads.size()==1,"raw color source split into individual provider requests");
                JsonObject payload=mock.payloads.get(0);String sent=payload.getAsJsonArray("text").get(0).getAsString();
                check(sent.startsWith("<mt0>"),"raw colors were not protected as whole phrase");
                String xml=sent;
                check("Mine some Stone.".equals(StyledTextCodec.plainText(xml)),"formatting markers obscured lexical words or whitespace in complete phrase");
                check(!sent.contains("MODTRPH")&&!sent.contains("&9")&&!sent.contains("&l"),"raw color markers still concatenate translation words");
                check(TranslationService.translateQuest(source).equals(expected),"raw quest lookup did not reuse wholephrase cache");
                check(TranslationService.translateQuest(expected).equals(expected)&&jobs().isEmpty(),"translated raw quest output retransmitted");
            }
        });
        test("numeric_language_formats_match_Minecraft_string_placeholders",()->{
            try(Mock mock=new Mock()){
                Path dir=init("numeric-language-formats",mock,true);
                String nativeKey="item.testmod.nativeformat",missingKey="item.testmod.numericformat";
                String rawEnglish="Storage %2$.2f RF",normalizedEnglish="Storage %2$s RF";
                scan(Map.of(nativeKey,"Storage %1$d items",missingKey,rawEnglish),Map.of(nativeKey,"Armazenamento %1$d itens"),Map.of());
                String nativeResult=translated(nativeKey,"Storage %1$s items");
                check(nativeResult.equals("Armazenamento %1$s itens"),"native numeric format not normalized like Minecraft Language loader");
                check(!nativeResult.contains("%1$d"),"native output would use unsupported numeric placeholder");
                await(()->translated(missingKey,normalizedEnglish).equals("PT: "+normalizedEnglish),"numeric English source normalized and translated");
                String translatedResult=translated(missingKey,normalizedEnglish);
                check(TranslationService.tokensValid(normalizedEnglish,translatedResult),"translation lost normalized indexed placeholder");
                check(!translatedResult.contains("%2$.2f"),"English precision format restored instead of Minecraft %s");
                check(mock.sent.size()==1,"native formatted string unnecessarily translated");
                check(mock.sent.get(0).contains("MODTRPH")&&!mock.sent.get(0).contains("%2$.2f"),"numeric source formatting not protected");
                invoke(service(),"persistSafely");
                JsonArray cache=JsonParser.parseString(Files.readString(dir.resolve("translations.json"))).getAsJsonArray();
                check(cache.size()==1&&cache.get(0).getAsJsonObject().get("source").getAsString().equals(normalizedEnglish),"cache persisted raw numeric source incompatible with Minecraft lookup");
            }
        });
        test("lost_placeholder_rejected_then_retried",()->{
            try(Mock mock=new Mock()){
                mock.corrupt.set(1);init("bad-tokens",mock,true);String source="Storage %s has {count} items";
                scan(Map.of("item.testmod.badtoken",source),Map.of(),Map.of());translated("item.testmod.badtoken",source);
                await(()->mock.sent.size()==1,"first corrupt translation");Thread.sleep(250);
                check(jobs().size()==1,"corrupt output incorrectly completed source in English");
                check(((Map<?,?>)value(service(),"bySource")).get(source)==null,"corrupt output cached");
                Object job=jobs().values().iterator().next();set(job,"retryAt",0L);set(service(),"retryGlobalUntil",0L);
                await(()->translated("item.testmod.badtoken",source).equals("PT: "+source),"corrupt translation retried safely");
            }
        });
        test("hover_repromotes_existing_priority_job_ahead_of_startup_backlog",()->{
            try(Mock mock=new Mock()){
                mock.responseGate=new CountDownLatch(1);
                init("visible-repromotion",mock,true);
                scan(Map.of("item.testmod.initialrequest","Startup Storage"),Map.of(),Map.of());translated("item.testmod.initialrequest","Startup Storage");
                await(()->mock.payloads.size()==1,"initial request held in flight");
                String hovered="Not yet eaten tooltip";
                // Simulate startup resolving thousands of keys as visible translations. Place
                // the target beyond a whole batch for FIFO and add later rows to also defeat
                // a one-time LIFO promotion. Only a repeated hover can now move it to the front.
                for(int i=0;i<3500;i++) {
                    if(i==250) TranslationService.translateLiteral(hovered);
                    TranslationService.translateLiteral("Startup Storage item "+i);
                }
                check(!mock.payloads.get(0).getAsJsonArray("text").toString().contains(hovered),"hover source already in initial request");
                check(jobs().containsKey(hovered),"hover source not pending in startup backlog");
                Thread.sleep(300); // Above the production 250 ms repromotion throttle.
                TranslationService.translateLiteral(hovered);
                int queuedBefore=((java.util.Deque<?>)value(service(),"visible")).size();
                for(int i=0;i<100;i++) TranslationService.translateLiteral(hovered);
                int queuedAfter=((java.util.Deque<?>)value(service(),"visible")).size();
                check(queuedAfter<=queuedBefore+1,"repeated render calls bypassed promotion throttle");
                mock.responseGate.countDown();
                await(()->mock.payloads.size()>=2,"next provider batch after hover");
                JsonArray nextBatch=mock.payloads.get(1).getAsJsonArray("text");
                check(nextBatch.asList().stream().anyMatch(e->e.getAsString().equals(hovered)),"hovered existing priority source missing from immediately next batch behind 3500 startup rows");
                await(()->TranslationService.translateLiteral(hovered).equals("PT: "+hovered),"hover translation completed within one next batch");
                check(Collections.frequency(mock.sent,hovered)==1,"hover repromotion sent duplicate source requests");
            }
        });
        test("native_game_terminology_and_source_aware_semantics",()->{
            GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de Madeira","Furnace","Fornalha","Cobblestone","Pedregulho","Crafting Table","Bancada de Trabalho","Oak Log","Tronco de Carvalho","Log","Registrar","Oak Planks","Tábuas de Carvalho","Gem Dust","Pó de Gema","Gems","Gemas"));
            Set<String> source=Set.of("Logs", "Use Wooden Pickaxes, a Furnace and Crafting Tables.", "To get Gem Dust, smash Gems.", "Like all &2&lMinecraft&r starts, punch that &2Tree&r.");
            GameTranslationContext.registerQuestSources(source);
            check(GameTranslationContext.normalizeTranslated("Logs","Logs").equals("Troncos"),"diagnostic native Log displaced wood noun");
            GameTranslationContext.registerQuestSources(Set.of("Like all &2&lMinecraft&r starts, punch that &2Tree&r. \\n\\n&6Logs&r will drop."));
            check(GameTranslationContext.isQuestSource("Like all Minecraft starts, punch that Tree."),"escaped JSON5 paragraph lost quest context");
            check(GameTranslationContext.isQuestSource("Logs will drop."),"escaped JSON5 newline hid wood noun");
            check(GameTranslationContext.normalizeTranslated("Like all Minecraft starts, punch that Tree.","Como no início do Minecraft, dar um soco aquela Árvore.").endsWith("dê um soco naquela Árvore."),"quest imperative not natural");
            GameTranslationContext.registerQuestSources(Set.of("You Mine Blocks by Holding Left Click."));
            check(GameTranslationContext.normalizeTranslated("You Mine Blocks by Holding Left Click.","Você minerar blocos segurando o botão esquerdo do mouse.").startsWith("Você minera blocos"),"second-person mine stayed infinitive");
            String corrected=GameTranslationContext.normalizeTranslated("Use Wooden Pickaxes, a Furnace and Crafting Tables.","Use picões, um Furnace e Tabelas de Criação.");
            check(corrected.equals("Use Picaretas, uma Fornalha e Bancadas de Trabalho."),"native plurals/names mismatch: "+corrected);
            check(GameTranslationContext.normalizeTranslated("To get Gem Dust, smash Gems.","Para obter Poeira de Jade, esmague Jades.").equals("Para obter Pó de Gema, esmague Gemas."),"gem dust and gems not consistent with native items");
            String original="Like all &2&lMinecraft&r starts, punch that &2Tree&r.", translatedText="Assim como todos os &2&lMinecrafts&r começam, pressione aquele &2Árvore&r.";
            String natural=GameTranslationContext.normalizeTranslated(original,translatedText);
            check(natural.equals("Como em todo início no &2&lMinecraft&r, dê um soco naquela &2Árvore&r."),"quest action meaning not preserved: "+natural);
            check(TranslationService.tokensValid(original,natural),"semantic corrections changed styles");
            check(GameTranslationContext.normalizeTranslated("Read diagnostic logs","Logs").equals("Logs"),"ambiguous wood correction changed diagnostic text");
        });
        test("whole_phrase_translation_reapplies_item_styles_without_fragmenting_text",()->{
            GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de Madeira","Furnace","Fornalha"));
            String source=StyledTextCodec.encode(List.of("The first tool is a ","Wooden Pickaxe",". Make a ","Furnace"," next."));
            String target="A primeira ferramenta é uma Picareta de Madeira. Faça uma Fornalha depois.";
            String aligned=StyledTranslationAlignment.align(source,target,GameTranslationContext::nativeLabel);
            var parts=StyledTextCodec.decode(aligned,5);
            check(StyledTextCodec.plainText(aligned).equals(target),"alignment rewrote or fragmented complete translated sentence");
            check(parts.get(1).text().equals("Picareta de Madeira")&&parts.get(3).text().equals("Fornalha"),"native item styles applied to wrong words");
            String mine=StyledTranslationAlignment.align(StyledTextCodec.encode(List.of("Time to ","Mine")),"Hora de minerar",GameTranslationContext::nativeLabel);
            check(StyledTextCodec.decode(mine,2).get(1).text().equals("minerar"),"quest action color lost");
            String gem=StyledTextCodec.encode(List.of("Use an ","Apotheosis"," ","Gem","."));
            String reordered=StyledTranslationAlignment.align(gem,"Use uma Gema de Apotheosis.",term->term.equals("Gem")?"Gema":GameTranslationContext.nativeLabel(term));
            check(StyledTextCodec.plainText(reordered).equals("Use uma Gema de Apotheosis."),"native adjective order changed phrase");
            var gemParts=StyledTextCodec.decode(reordered,5);
            check(gemParts.stream().anyMatch(p->p.index()==3&&p.text().equals("Gema"))&&gemParts.stream().anyMatch(p->p.index()==1&&p.text().equals("Apotheosis")),"reordered gem lost item and brand styles");
            String fuel=StyledTextCodec.encode(List.of("Furnaces"," need Fuel: ","Wood",", ","Coal"," and carpets."));
            var fuelParts=StyledTextCodec.decode(StyledTranslationAlignment.align(fuel,"As Fornalhas precisam de combustível: Madeira, Carvão e tapetes.",term->switch(term){case "Furnaces"->"Fornalhas";case "Wood"->"Madeira";case "Coal"->"Carvão";default->null;}),6);
            check(fuelParts.stream().anyMatch(p->p.index()==0&&p.text().equals("As Fornalhas"))&&fuelParts.stream().anyMatch(p->p.index()==5&&p.text().equals(" e tapetes.")),"new article displaced reset and extended coal color");
        });
        test("old_quest_quality_cache_is_reprocessed_once_options_cache_preserved",()->{
            try(Mock mock=new Mock()) {
                Path base=root.resolve("quality-migration"),lang=base.resolve("ftbquests/quests/lang/en_us"),config=base.resolve("modtranslator");
                Files.createDirectories(lang);Files.createDirectories(config);
                Files.writeString(lang.resolve("quest.json5"),"{\"quest.QUALITY.title\":\"Use a Furnace\"}");
                Files.writeString(config.resolve("translations.json"),"[{\"key\":\"@quest\",\"source\":\"Use a Furnace\",\"translated\":\"Use um Furnace\"},{\"key\":\"option.testmod.safe\",\"source\":\"Save Data\",\"translated\":\"Salvar dados\"}]");
                Path dir=init("quality-migration",mock,true);
                scan(Map.of("item.testmod.furnace","Furnace","option.testmod.safe","Save Data"),Map.of("item.testmod.furnace","Fornalha"),Map.of());
                check(TranslationService.translateLiteral("Save Data").equals("Salvar dados"),"quality migration discarded unrelated valid option cache");
                await(()->TranslationService.translateQuest("Use a Furnace").equals("PT: Use a Fornalha"),"obsolete quest translation not reprocessed with native context");
                check(mock.sent.size()==1,"quality migration charged unrelated cache/native term");
                invoke(service(),"persistSafely");
                check(JsonParser.parseString(Files.readString(dir.resolve("translations.json"))).getAsJsonArray().asList().stream().allMatch(row->row.getAsJsonObject().get("qualityVersion").getAsInt()==2),"new cache quality version not saved");
                init("quality-migration",mock,true);scan(Map.of(),Map.of("item.testmod.furnace","Fornalha"),Map.of());
                check(TranslationService.translateQuest("Use a Furnace").equals("PT: Use a Fornalha"),"corrected quest not reused after restart");
                Thread.sleep(700);check(mock.sent.size()==1,"corrected quest unnecessarily translated again after restart");
            }
        });
        test("colored_quest_item_names_authoritative_native_labels_and_agreement",()->{
            GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de Madeira","Furnace","Fornalha","Gem Dust","Pó de Gema"));
            String original="Use your &6Wooden Pickaxe&r and a &7Furnace&r to get &9Gem Dust&r.";
            GameTranslationContext.registerQuestSources(Set.of(original));
            var protectedText=TextProtection.protect(original);
            String model="<mt0>Use seu </mt0><mt1>Picão de Madeira</mt1><mt2> e um </mt2><mt3>Furnance</mt3><mt4> para obter </mt4><mt5>Poeira de Jade</mt5><mt6>.</mt6>";
            String restored=protectedText.restore(model);
            check(restored.equals("Use sua &6Picareta de Madeira&r e uma &7Fornalha&r para obter &9Pó de Gema&r."),"native labels, artifacts or agreement inconsistent: "+restored);
            check(TranslationService.tokensValid(original,restored),"canonical labels changed colors");
        });
        test("durable_journal_recovers_without_shutdown_and_discards_only_partial_tail",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("journal-crash",mock,true);scan(Map.of("option.testmod.crash","Save Data"),Map.of(),Map.of());
                await(()->translated("option.testmod.crash","Save Data").equals("PT: Save Data"),"first durable result");
                Path journal=dir.resolve("translations.journal.jsonl");byte[] durable=Files.readAllBytes(journal);
                check(durable.length>0&&new String(durable,StandardCharsets.UTF_8).contains("Save Data"),"result displayed before durable append");
                check(JsonParser.parseString(Files.readString(dir.resolve("translations.json"))).getAsJsonArray().isEmpty(),"full cache rewritten for individual result");
                Object previous=service();set(previous,"closed",true);((ScheduledExecutorService)value(previous,"worker")).shutdownNow();field(TranslationService.class,"instance").set(null,null);
                Files.writeString(journal,"{\"source\":\"incomplete",StandardOpenOption.APPEND);
                init("journal-crash",mock,true);check(Arrays.equals(Files.readAllBytes(journal),durable),"partial tail damaged earlier committed rows");
                scan(Map.of("option.testmod.crash","Save Data"),Map.of(),Map.of());
                check(translated("option.testmod.crash","Save Data").equals("PT: Save Data"),"journal translation missing after crash restart");
                Thread.sleep(600);check(mock.sent.size()==1,"crash recovery charged a completed translation again");
                invoke(service(),"persistSafely");check(Files.size(journal)==0,"checkpoint did not compact journal");
                check(JsonParser.parseString(Files.readString(dir.resolve("translations.json"))).getAsJsonArray().size()==1,"checkpoint lost recovered result");
            }
        });
        test("cache_disk_failure_never_publishes_or_drops_translation",()->{
            try(Mock mock=new Mock()) {
                mock.responseGate=new CountDownLatch(1);Path dir=init("journal-disk-error",mock,true);
                scan(Map.of("option.testmod.disk","Save Data"),Map.of(),Map.of());translated("option.testmod.disk","Save Data");await(()->!mock.payloads.isEmpty(),"blocked request");
                Files.createDirectory(dir.resolve("translations.journal.jsonl"));mock.responseGate.countDown();
                Object current=service();Field failureField=field(current.getClass(),"lastFailure");
                await(()->{try{return !failureField.get(current).equals("");}catch(Exception e){throw new RuntimeException(e);}},"disk failure category");
                check(translated("option.testmod.disk","Save Data").equals("Save Data"),"undurable translation published");
                check(!jobs().isEmpty(),"disk failure lost pending job");
            }
        });
        test("completed_local_requests_continue_without_fixed_500ms_gap",()->{
            try(Mock mock=new Mock()){
                init("pipeline-latency",mock,true);scan(Map.of(),Map.of(),Map.of());
                for(String source:List.of("The first storage is ready.","The second storage is ready.","The third storage is ready.")){
                    TranslationService.translateLiteral(source);await(()->TranslationService.translateLiteral(source).startsWith("PT: "),"sequential local completion");
                }
                check(mock.payloads.size()==3,"latency fixture did not make three separate requests");
                long largest=0;for(int i=1;i<mock.receivedAt.size();i++)largest=Math.max(largest,TimeUnit.NANOSECONDS.toMillis(mock.receivedAt.get(i)-mock.receivedAt.get(i-1)));
                check(largest<450,"completed local requests wait for scheduler: "+largest+"ms");
            }
        });
        test("installed_mod_names_are_preserved_and_never_queued_as_prose",()->{
            ModTextPolicy.registerModNames(List.of("Sodium", "Sodium Shadowy Path Blocks", "Reese's Sodium Options"));
            try(Mock mock=new Mock()) {
                init("installed-mod-names",mock,true);
                scan(Map.of("option.testmod.page","Reese’s Sodium Options","option.testmod.description","Enable Reese's Sodium Options with Sodium"),Map.of(),Map.of());
                check(TranslationService.translateLiteral("Sodium Shadowy Path Blocks").equals("Sodium Shadowy Path Blocks"),"mod heading altered");
                check(translated("option.testmod.page","Reese’s Sodium Options").equals("Reese’s Sodium Options"),"curly apostrophe brand altered");
                String coloredBrand="<mt0>Reese's </mt0><mt1>Sodium Options</mt1>";
                check(TranslationService.translateStyled(coloredBrand).equals(coloredBrand),"brand split into style spans altered");
                await(()->translated("option.testmod.description","Enable Reese's Sodium Options with Sodium").equals("PT: Enable Reese's Sodium Options with Sodium"),"brand token local roundtrip");
                check(mock.sent.stream().noneMatch(s->s.contains("Sodium")),"provider received unprotected brand");
                check(mock.sent.size()==1,"exact mod names were sent to provider");
                check(!TranslationService.tokensValid("Enable Sodium","Ative Sódio"),"invalid brand accepted");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("unchanged_English_pt_br_UI_is_translated_and_explicit_Portuguese_wins",()->{
            try(Mock mock=new Mock()) {
                init("copied-English-UI",mock,true);
                scan(Map.of("option.testmod.steady","Steady Debug HUD","option.testmod.interval","Steady Debug HUD Refresh Interval","option.testmod.native","Debug HUD"),
                        Map.of("option.testmod.steady","Steady Debug HUD","option.testmod.interval","Steady Debug HUD Refresh Interval","option.testmod.native","Depuração personalizada"),Map.of());
                await(()->translated("option.testmod.steady","Steady Debug HUD").equals("HUD de depuração com atualização fixa"),"unchanged English treated as native");
                await(()->translated("option.testmod.interval","Steady Debug HUD Refresh Interval").equals("Intervalo de atualização do HUD de depuração"),"interval label untranslated");
                check(translated("option.testmod.native","Debug HUD").equals("Depuração personalizada"),"explicit native override lost");
                check(mock.sent.isEmpty(),"canonical UI terminology unnecessarily called local inference");
            }
        });
        test("obsolete_brand_and_UI_cache_reprocessed_unrelated_translations_reused",()->{
            ModTextPolicy.registerModNames(List.of("Sodium", "Reese's Sodium Options"));
            try(Mock mock=new Mock()) {
                Path dir=init("brand-cache-migration",mock,true);closeService();
                Files.writeString(dir.resolve("translations.json"),"[{\"key\":\"@literal\",\"source\":\"Reese's Sodium Options\",\"translated\":\"Opções de Sódio da Reese\",\"qualityVersion\":2},{\"key\":\"option.testmod.brand\",\"source\":\"Enable Sodium\",\"translated\":\"Ative Sódio\",\"qualityVersion\":2},{\"key\":\"option.testmod.panini\",\"source\":\"Panini Projection Strength\",\"translated\":\"Projeção Panini Força\",\"qualityVersion\":2},{\"key\":\"option.testmod.safe\",\"source\":\"Save Data\",\"translated\":\"Salvar dados\",\"qualityVersion\":2}]");
                init("brand-cache-migration",mock,true);scan(Map.of("option.testmod.brand","Enable Sodium","option.testmod.panini","Panini Projection Strength","option.testmod.safe","Save Data"),Map.of(),Map.of());
                check(TranslationService.translateLiteral("Reese's Sodium Options").equals("Reese's Sodium Options"),"bad heading cache retained");
                check(TranslationService.translateLiteral("Save Data").equals("Salvar dados"),"valid existing cache discarded");
                await(()->translated("option.testmod.brand","Enable Sodium").equals("PT: Enable Sodium"),"bad embedded brand cache retained");
                await(()->translated("option.testmod.panini","Panini Projection Strength").equals("Intensidade da projeção Panini"),"bad UI label cache retained");
                invoke(service(),"persistSafely");
                String saved=Files.readString(dir.resolve("translations.json"));
                check(!saved.contains("Sódio")&&!saved.contains("Projeção Panini Força"),"obsolete cache persisted again");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("brand_placeholders_preserve_rich_text_and_reject_missing_tokens",()->{
            ModTextPolicy.registerModNames(List.of("Sodium", "Reese's Sodium Options"));
            try {
                var p=TextProtection.protect("§aUse Reese's Sodium Options §rwith Sodium: %s");
                String translated=p.value().replace("Use ","Use ").replace("with ","com ");
                check(p.restore(translated).equals("§aUse Reese's Sodium Options §rcom Sodium: %s"),"brand/color/format roundtrip changed");
                boolean rejected=false;try{p.restore(translated.replace(p.markers().get(0),"Sódio"));}catch(IllegalArgumentException expected){rejected=true;}
                check(rejected,"missing brand marker accepted");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("canonical_UI_works_without_providers_and_native_brand_override_wins",()->{
            ModTextPolicy.registerModNames(List.of("Sodium"));
            try(Mock mock=new Mock()) {
                init("providerless-UI",mock,false);
                scan(Map.of("option.testmod.waiting","Open Storage","option.testmod.ready","Steady Debug HUD","option.testmod.brand","Sodium"),Map.of("option.testmod.brand","Sodium personalizado"),Map.of());
                await(()->translated("option.testmod.ready","Steady Debug HUD").equals("HUD de depuração com atualização fixa"),"canonical label incorrectly waits for local model");
                check(translated("option.testmod.brand","Sodium").equals("Sodium personalizado"),"native resource pack brand override lost");
                check(!jobs().isEmpty()&&mock.sent.isEmpty(),"unavailable provider job incorrectly sent or discarded");
                invoke(service(),"persistSafely");
                check(!Files.readString(root.resolve("providerless-UI/modtranslator/translations.json")).contains("Open Storage"),"untranslated prose cached as ready");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("Create_brand_is_preserved_but_creation_actions_and_old_cache_are_corrected",()->{
            ModTextPolicy.registerModNames(List.of("Create"));
            try(Mock mock=new Mock()) {
                check(ModTextPolicy.isModName("Create"),"standalone brand lost");
                check(ModTextPolicy.namesPreserved("Create Server","Criar servidor"),"creation verb treated as brand");
                check(!ModTextPolicy.namesPreserved("Use Create machines","Use máquinas"),"real brand protection lost");
                check(!TextProtection.protect("Create a new team").value().contains("MODTRPH"),"creation action protected as brand");
                Path dir=init("create-action",mock,false);closeService();
                Files.writeString(dir.resolve("translations.json"),"[{\"key\":\"@literal\",\"source\":\"Create Server\",\"translated\":\"Create Servidor\",\"qualityVersion\":2}]");
                init("create-action",mock,false);scan(Map.of("option.testmod.create","Create Server"),Map.of(),Map.of());
                await(()->TranslationService.translateLiteral("Create Server").equals("Criar servidor"),"stale creation-action cache retained");
                check(mock.sent.isEmpty(),"creation button unnecessarily called local inference");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("native_memory_budget_idle_and_pressure_policy",()->{
            check(!LocalResourcePolicy.canStart(3900),"model may consume game RAM reserve");
            check(LocalResourcePolicy.canStart(6000),"safe memory availability rejected");
            check(!LocalResourcePolicy.canStart(-1),"unknown memory amount allowed startup");
            check(LocalResourcePolicy.underPressure(1000),"low RAM must stop inference");
            check(!LocalResourcePolicy.underPressure(4000),"safe RAM treated as pressure");
            check(LocalResourcePolicy.shouldUnload(LocalResourcePolicy.IDLE_MILLIS+1,0,false),"idle model retained");
            check(!LocalResourcePolicy.shouldUnload(LocalResourcePolicy.IDLE_MILLIS+1,0,true),"active inference treated as idle");
        });
        test("mixed_native_item_names_keep_English_prose_eligible",()->{
            check(TranslationService.looksEnglish("You can use Picareta de Madeira with this tool."),"native term blocked whole English sentence");
            check(!TranslationService.looksEnglish("Você pode usar a picareta de madeira para minerar."),"native Portuguese requeued");
        });
        test("NPC_names_preserved_roles_translated_and_old_identity_cache_removed",()->{
            try(Mock mock=new Mock()) {
                init("npc-names",mock,true);
                scan(Map.of("npc.vaaz.trainer.name","Radin of the Side Rail","npc.vaaz.trainer.job","<Master Blacksmith>","dialogue.guard.loc.armory","Armory"),Map.of(),Map.of());
                await(()->!translated("npc.vaaz.trainer.job","<Master Blacksmith>").equals("<Master Blacksmith>"),"NPC role stayed English");
                check(mock.sent.contains("Master Blacksmith"),"role markup was submitted as an HTML tag");
                check(!mock.sent.contains("Radin of the Side Rail"),"NPC identity was sent for translation");
                check(translated("npc.vaaz.trainer.name","Radin of the Side Rail").equals("Radin of the Side Rail"),"NPC name changed");
                check(ModTextPolicy.canonicalUi("Armory").equals("Arsenal"),"location remains English");
            }
        });
        test("JourneyMap_identical_English_pt_br_is_missing_but_native_and_brands_win",()->{
            ModTextPolicy.registerModNames(List.of("JourneyMap"));
            try(Mock mock=new Mock()) {
                init("jm-English",mock,false);
                scan(Map.of("jm.server.admin","Server Admin","jm.cave","Cave Layers","jm.native","Open Map","jm.brand","JourneyMap"),Map.of("jm.server.admin","Server Admin","jm.cave","Cave Layers","jm.native","Abrir mapa","jm.brand","JourneyMap"),Map.of());
                await(()->translated("jm.server.admin","Server Admin").equals("Administração do servidor")&&translated("jm.cave","Cave Layers").equals("Camadas de cavernas"),"English JourneyMap locale treated as native");
                check(translated("jm.native","Open Map").equals("Abrir mapa"),"real native override changed");
                check(translated("jm.brand","JourneyMap").equals("JourneyMap"),"JourneyMap identity changed");
                check(mock.sent.isEmpty(),"canonical UI waited for a model");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("JourneyMap_dynamic_dimension_ID_and_vertical_index_stay_intact",()->{
            try(Mock mock=new Mock()) {
                init("jm-dynamic",mock,false);scan(Map.of(),Map.of(),Map.of());
                await(()->TranslationService.translateLiteral("Dimension: ae2:spatial_storage").equals("Dimensão: ae2:spatial_storage"),"dimension label or identifier changed");
                await(()->TranslationService.translateLiteral("Vertical Chunk: -4").equals("Seção vertical: -4"),"vertical index changed");
                check(mock.sent.isEmpty(),"dynamic known labels need inference");
                check(ModTextPolicy.canonicalUi("Dimension: invalid identifier")==null,"arbitrary user input was treated as an identifier");
            }
        });
        test("GuideMe_paragraph_layout_refresh_keeps_link_nodes_styles_and_originals",()->{
            try(Mock mock=new Mock()) {
                init("guideme-layout",mock,true);scan(Map.of(),Map.of(),Map.of());
                mock.translation=source->source.replace("You can find ","Você pode encontrar ").replace("Meteorites","Meteoritos").replace(" near your base."," perto da sua base.");
                FakeGuideScreen view=new FakeGuideScreen();
                FakeGuideText left=new FakeGuideText("You can find "),linked=new FakeGuideText("Meteorites"),right=new FakeGuideText(" near your base.");
                FakeGuideSpan link=new FakeGuideSpan(List.of(linked));Object linkStyle=link.style;
                view.document.blocks.add(new FakeGuideParagraph(List.of(left,link,right)));
                com.modtranslator.client.GuideMePageRefresh.refresh(view);
                await(()->{com.modtranslator.client.GuideMePageRefresh.refresh(view);return left.text.startsWith("Você")&&linked.text.equals("Meteoritos")&&right.text.equals(" perto da sua base.");},"guide flow text not updated before layout");
                check(view.documentLayoutInvalid&&view.document.invalidations>0,"translated paragraph did not invalidate layout");
                check(view.scrollY==130&&link.style==linkStyle&&link.children.get(0)==linked,"guide scroll/link identity/style lost");
                check(mock.sent.stream().anyMatch(s->s.contains("You can find ")&&s.contains("Meteorites")&&s.contains(" near your base.")),"guide prose translated as separate positioned fragments");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                await(()->{com.modtranslator.client.GuideMePageRefresh.refresh(view);return left.text.equals("You can find ")&&linked.text.equals("Meteorites");},"guide original text not restored");
            }
        });
        test("GuideMe_reordered_span_output_cannot_corrupt_fixed_link_structure",()->{
            try(Mock mock=new Mock()) {
                init("guideme-reorder",mock,true);scan(Map.of(),Map.of(),Map.of());
                mock.translation=source->source.replace("You can find ","Você encontra ");
                FakeGuideScreen view=new FakeGuideScreen();FakeGuideText left=new FakeGuideText("You can find "),right=new FakeGuideText("Meteorites");
                view.document.blocks.add(new FakeGuideParagraph(List.of(left,right)));
                com.modtranslator.client.GuideMePageRefresh.refresh(view);
                await(()->mock.sent.size()>0,"guide test job not submitted");
                Map<?,?> pendingJobs=jobs();
                await(()->pendingJobs.isEmpty(),"guide test job not cached");
                @SuppressWarnings("unchecked") Map<String,String> cache=(Map<String,String>)value(service(),"bySource");
                String encoded=com.modtranslator.client.StyledTextCodec.encode(List.of("You can find ","Meteorites"));
                cache.put(encoded,"<mt1>Meteoritos</mt1><mt0>Você encontra </mt0>");
                com.modtranslator.client.GuideMePageRefresh.refresh(view);
                check(left.text.equals("You can find ")&&right.text.equals("Meteorites"),"reordered spans changed fixed link topology");
            }
        });
        test("visible_document_preserves_breaks_and_admits_bare_headings",()->{
            List<String> visited=new ArrayList<>();
            String original="Ready for Testing\r\n\r\nFeatures are ready.\n \n\nBasic Farming";
            String resolved=VisibleDocument.resolve(original,block->{visited.add(block);return "PT: "+block;});
            check(visited.equals(List.of("Basic Farming","Features are ready.","Ready for Testing")),"heading admission order lost");
            check(resolved.equals("PT: Ready for Testing\r\n\r\nPT: Features are ready.\n \n\nPT: Basic Farming"),"paragraph separators changed");
            check(VisibleDocument.resolve(original,block->null).equals(original),"unavailable paragraph removed");
        });
        test("Aether_cached_pages_update_headings_live_keep_page_and_restore_original",()->{
            try(Mock mock=new Mock()) {
                init("aether-live",mock,true);scan(Map.of(),Map.of(),Map.of());
                mock.responseGate=new CountDownLatch(1);
                mock.translation=source->Map.of("Ready for Testing","Pronto para testes","Features are ready.","Os recursos estão prontos.","Basic Farming","Agricultura básica","You can grow plants.","Você pode cultivar plantas.").getOrDefault(source,"PT: "+source);
                FakeAetherPages view=new FakeAetherPages();
                var original=net.minecraft.network.chat.Component.literal("Ready for Testing\n\nFeatures are ready.\n\nBasic Farming\n\nYou can grow plants.").withStyle(net.minecraft.ChatFormatting.GOLD);
                var initial=com.modtranslator.client.AetherPageRefresh.capture(view,original);
                view.createPages(initial);view.currentPageNumber=1;
                check(view.visible.contains("Ready for Testing"),"pending heading disappeared");
                mock.responseGate.countDown();
                await(()->{com.modtranslator.client.AetherPageRefresh.refresh(view);return view.visible.contains("Pronto para testes")&&view.visible.contains("Agricultura básica")&&view.visible.contains("Você pode cultivar plantas.");},"open document did not refresh all headings/body");
                check(view.currentPageNumber==1,"reading page was reset");
                check(original.getString().startsWith("Ready for Testing"),"original document mutated");
                check(view.color==original.getStyle().getColor().getValue(),"document root style lost");
                check(mock.sent.contains("Ready for Testing")&&mock.sent.contains("Basic Farming"),"bare headings skipped prose heuristics");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                await(()->{com.modtranslator.client.AetherPageRefresh.refresh(view);return view.visible.equals(original.getString());},"cache toggle did not restore original open page");
            }
        });
        test("native_mod_document_override_wins_without_inference",()->{
            try(Mock mock=new Mock()) {
                init("aether-native",mock,true);
                scan(Map.of("aether_ii.guidebook.description.test","Basic Farming\n\nYou can grow plants."),Map.of("aether_ii.guidebook.description.test","Agricultura\n\nCultive plantas."),Map.of());
                check(TranslationService.translateModDocument("aether_ii.guidebook.description.test","Basic Farming\n\nYou can grow plants.").equals("Agricultura\n\nCultive plantas."),"native book translation replaced");
                check(mock.sent.isEmpty(),"native document was unnecessarily inferred");
            }
        });
        test("Logs_is_quest_item_caption_without_changing_unrelated_log_UI",()->{
            try(Mock mock=new Mock()) {
                init("quest-caption",mock,false);scan(Map.of(),Map.of("gui.test.console.logs","Logs"),Map.of());
                check(TranslationService.translateQuest("Logs").equals("Troncos"),"quest item label still English");
                check(TranslationService.translateLiteral("Logs").equals("Logs"),"unrelated console label changed");
                check(mock.sent.isEmpty(),"known quest label needs a model");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                check(TranslationService.translateQuest("Logs").equals("Logs"),"quest label ignored translation toggle");
            }
        });
        test("advancement_title_brand_collision_is_scoped_and_respects_native_override",()->{
            ModTextPolicy.registerModNames(List.of("Ice and Fire"));
            try(Mock mock=new Mock()) {
                init("advancement-title",mock,false);scan(Map.of("advancements.iceandfire.root.title","Ice and Fire","mod.test.brand","Ice and Fire"),Map.of(),Map.of());
                check(translated("advancements.iceandfire.root.title","Ice and Fire").equals("Gelo e Fogo"),"structured advancement treated as brand");
                check(translated("mod.test.brand","Ice and Fire").equals("Ice and Fire"),"mod identity translated");
                check(TranslationService.translateLiteral("Ice and Fire").equals("Ice and Fire"),"literal identity translated");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                check(translated("advancements.iceandfire.root.title","Ice and Fire").equals("Ice and Fire"),"advancement ignored saved-translation toggle");
            } finally {ModTextPolicy.registerModNames(List.of());}
        });
        test("structured_advancement_chat_refresh_never_matches_player_messages",()->{
            var advancement=net.minecraft.network.chat.Component.translatable("chat.type.advancement.task",net.minecraft.network.chat.Component.literal("Player"),net.minecraft.network.chat.Component.translatable("advancements.iceandfire.root.title"));
            check(com.modtranslator.client.NpcDialogueText.isRefreshableGameMessage(advancement),"structured advancement not refreshed");
            check(!com.modtranslator.client.NpcDialogueText.isDialogue(advancement),"advancement mistaken for NPC");
            check(!com.modtranslator.client.NpcDialogueText.isRefreshableGameMessage(net.minecraft.network.chat.Component.literal("Ice and Fire")),"player literal opted in");
            check(!com.modtranslator.client.NpcDialogueText.isRefreshableGameMessage(net.minecraft.network.chat.Component.translatable("chat.type.text",net.minecraft.network.chat.Component.literal("Player"),advancement)),"player message envelope bypassed");
        });
        test("NPC_chat_detection_does_not_match_player_written_NPC_labels",()->{
            check(com.modtranslator.client.NpcDialogueText.isDialogue(net.minecraft.network.chat.Component.translatable("dialogue.guard.finding")),"structured NPC dialogue not recognized");
            check(!com.modtranslator.client.NpcDialogueText.isDialogue(net.minecraft.network.chat.Component.literal("[NPC] What can I help with?")),"player literal mistaken for NPC dialogue");
        });
        test("whole_translatable_tree_preserves_colored_native_item_and_sends_complete_sentence",()->{
            try(Mock mock=new Mock()) {
                init("render-tree",mock,true);scan(Map.of(),Map.of(),Map.of());
                net.minecraft.network.chat.Component source=net.minecraft.network.chat.Component.translatableWithFallback("dialogue.test.begin","You can use ")
                        .append(net.minecraft.network.chat.Component.translatableWithFallback("item.test.pick","Picareta de Madeira").withStyle(net.minecraft.ChatFormatting.GOLD))
                        .append(net.minecraft.network.chat.Component.literal(" with this tool."));
                com.modtranslator.client.RenderTranslation.component(source);
                await(()->!mock.sent.isEmpty(),"whole component was not queued");
                String sent=mock.sent.get(0);
                check(sent.contains("You can use ")&&sent.contains("Picareta de Madeira")&&sent.contains(" with this tool."),"provider received a fragmented sentence");
                await(()->com.modtranslator.client.RenderTranslation.component(source).getString().startsWith("PT: "),"whole component cache not applied");
                var output=com.modtranslator.client.RenderTranslation.component(source);
                java.util.concurrent.atomic.AtomicBoolean gold=new java.util.concurrent.atomic.AtomicBoolean();
                output.visit((style,part)->{if(part.contains("Picareta de Madeira")&&style.getColor()!=null&&style.getColor().getValue()==0xFFAA00)gold.set(true);return java.util.Optional.empty();},net.minecraft.network.chat.Style.EMPTY);
                check(gold.get(),"native item color lost");
            }
        });
        test("legacy_SNBT_display_walker_keeps_formatting_and_ignores_identifiers",()->{
            var document=new net.minecraft.nbt.CompoundTag();document.putString("id","0123456789ABCDEF");document.putString("title","&aWelcome to the Tree");
            var description=new net.minecraft.nbt.ListTag();description.add(net.minecraft.nbt.StringTag.valueOf("Use this tool."));description.add(net.minecraft.nbt.StringTag.valueOf("&6Second line."));document.put("description",description);
            document.putString("icon","minecraft:oak_log");String before=document.toString();
            Map<String,String> output=new HashMap<>();
            Method walker=QuestCatalog.class.getDeclaredMethod("legacyText",net.minecraft.nbt.Tag.class,String.class,boolean.class,Map.class);walker.setAccessible(true);walker.invoke(null,document,"ftbquests.snbt.test",false,output);
            check(output.size()==3&&output.containsValue("&aWelcome to the Tree")&&output.containsValue("&6Second line."),"display text or formatting lost");
            check(!output.containsValue("0123456789ABCDEF")&&!output.containsValue("minecraft:oak_log"),"non-display quest data translated");check(before.equals(document.toString()),"quest data mutated");
        });
        test("FancyMenu_plain_paragraph_translates_whole_and_complex_markup_remains_intact",()->{
            String paragraph="You are a Flameborn, created by S'kellak, the Creator of All Things.\n\nYou are being sent to the unknown Overworld, with a quest to stop the Void's Invasion.";
            check(com.modtranslator.client.FancyMenuText.plainProse(paragraph),"welcome paragraph rejected");
            check(!com.modtranslator.client.FancyMenuText.plainProse("[Wiki](https://example.test) **link**"),"complex markup should require a parser");
            try(Mock mock=new Mock()) {
                init("fancymenu-whole",mock,true);scan(Map.of(),Map.of(),Map.of());
                com.modtranslator.client.FancyMenuText.translate(paragraph);
                await(()->mock.sent.contains(TextProtection.protect(paragraph).value()),"welcome paragraph fragmented before provider");
                await(()->com.modtranslator.client.FancyMenuText.translate(paragraph).startsWith("PT: "),"welcome paragraph not refreshed from cache");
            }
        });

        test("manual_pause_keeps_cache_and_queue_blocks_requests_and_resumes",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("manual-pause",mock,true);scan(Map.of(),Map.of(),Map.of());
                translated("option.testmod.armory","Armory");
                await(()->translated("option.testmod.armory","Armory").equals("Arsenal"),"canonical cache");
                TranslationService.setTranslationPaused(true);
                await(()->TranslationService.translationProgress().phase().equals("user_paused"),"manual pause HUD");
                check(TranslationService.translationProgress().active(),"manual pause must keep resume control visible");
                String source="This storage contains your first items.";
                scan(Map.of("option.testmod.pause",source),Map.of(),Map.of());translated("option.testmod.pause",source);
                Thread.sleep(650);
                check(mock.payloads.isEmpty(),"paused service dispatched local inference");check(jobs().containsKey(source),"paused queue lost pending job");
                check(translated("option.testmod.armory","Armory").equals("Arsenal"),"pause disabled existing cache");
                check(!LocalTranslationClient.start(dir).get(2,TimeUnit.SECONDS),"manual pause admitted model startup");
                await(()->{try{return JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("translationPaused").getAsBoolean();}catch(Exception e){return false;}},"pause preference persisted");
                TranslationService.toggleTranslationPaused();
                await(()->translated("option.testmod.pause",source).startsWith("PT: "),"resume did not process retained queue");
                check(mock.payloads.size()==1,"resume repeated already cached text");
            }
        });
        test("manual_pause_commits_inflight_result_without_dispatching_next_batch",()->{
            try(Mock mock=new Mock()) {
                init("pause-inflight",mock,true);
                mock.responseGate=new CountDownLatch(1);
                String one="This first tool is ready.",two="This second tool is ready.";
                scan(Map.of("option.testmod.one",one),Map.of(),Map.of());
                translated("option.testmod.one",one);
                await(()->mock.payloads.size()==1,"first batch dispatched");
                TranslationService.translateLiteral(two);
                TranslationService.setTranslationPaused(true);mock.responseGate.countDown();
                await(()->{try{return ((Map<?,?>)value(service(),"bySource")).size()==1;}catch(Exception e){return false;}},"inflight valid translation not saved");
                Thread.sleep(650);check(mock.payloads.size()==1&&jobs().size()==1,"pause dispatched next batch or lost queue");
                TranslationService.setTranslationPaused(false);
                await(()->{try{return jobs().isEmpty();}catch(Exception e){return false;}},"resume after inflight did not finish");
                check(mock.payloads.size()==2,"inflight success translated twice");
            }
        });
        test("manual_pause_persists_restart_and_native_generation_rejects_stale_startup",()->{
            try(Mock mock=new Mock()) {
                Path dir=init("pause-restart",mock,true);scan(Map.of(),Map.of(),Map.of());
                long generation=((AtomicLong)field(LocalTranslationClient.class,"cancellationGeneration").get(null)).get();
                TranslationService.setTranslationPaused(true);
                await(()->{try{return JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("translationPaused").getAsBoolean();}catch(Exception e){return false;}},"pause save");
                closeService();initializeLocal(dir.getParent(),mock,mock.ready);
                check(TranslationService.isTranslationPaused(),"restart lost pause preference");
                String source="You can store your items here.";scan(Map.of("option.testmod.restart",source),Map.of(),Map.of());translated("option.testmod.restart",source);
                Thread.sleep(250);check(mock.payloads.isEmpty(),"restart translated while paused");
                TranslationService.setTranslationPaused(false);
                Method gate=LocalTranslationClient.class.getDeclaredMethod("checkAdmission",long.class);gate.setAccessible(true);
                boolean rejected=false;try{gate.invoke(null,generation);}catch(InvocationTargetException e){rejected=e.getCause() instanceof IOException;}
                check(rejected,"rapid pause/resume allowed stale startup to spawn model");
                await(()->translated("option.testmod.restart",source).startsWith("PT: "),"restart resume stalled");
            }
        });
        test("pause_button_bounds_match_visible_screens_and_manual_pause_has_no_animation",()->{
            var button=com.modtranslator.client.TranslationProgressControl.bounds(854,480);
            double x=button.x()+button.width()/2.0,y=button.y()+button.height()/2.0;
            check(button.contains(x,y)&&!button.contains(button.x()-1,y),"button rectangle boundary incorrect");
            check(com.modtranslator.client.TranslationProgressControl.hit(true,false,true,854,480,x,y),"menu click rejected");
            check(!com.modtranslator.client.TranslationProgressControl.hit(false,false,true,854,480,x,y),"world mouse click stolen");
            check(!com.modtranslator.client.TranslationProgressControl.hit(true,true,true,854,480,x,y),"F1 hidden button stole input");
            check(!com.modtranslator.client.TranslationProgressControl.hit(true,false,false,854,480,x,y),"invisible widget stole input");
            check(!com.modtranslator.client.TranslationProgressControl.hit(true,false,true,89,480,x,y),"tiny viewport accepted invisible button");
            var progress=new TranslationService.TranslationProgress(true,true,"user_paused",0,0,0,0,"lite");
            var view=com.modtranslator.client.TranslationProgressLayout.view(progress,1);
            check(view!=null&&view.title().contains("por você")&&!view.indeterminate(),"manual paused zero-work widget missing/animated");
        });
        test("resource_pause_reason_and_session_count_are_live_immutable_snapshots",()->{
            try(Mock mock=new Mock()){
                Path dir=init("session-progress",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String source="This item contains your tools.";scan(Map.of("option.testmod.session",source),Map.of(),Map.of());translated("option.testmod.session",source);
                ((ScheduledExecutorService)value(svc,"worker")).shutdown();((ScheduledExecutorService)value(svc,"worker")).awaitTermination(2,TimeUnit.SECONDS);((AtomicBoolean)value(svc,"busy")).set(false);
                mock.state="Local translation paused to preserve game RAM";invoke(svc,"updateProgress");
                var waiting=TranslationService.translationProgress();check(waiting.reason().startsWith("RAM")&&waiting.sessionCompleted()==0,"resource wait reason hides memory restriction");
                var layout=com.modtranslator.client.TranslationProgressLayout.view(waiting,10);check(layout.title().equals("Aguardando memória")&&!layout.reason().contains("API"),"memory wait UI suggests external provider");
                Object job=jobs().get(source);Method commit=svc.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);commit.invoke(svc,List.of(job),List.of("Este item contém suas ferramentas."),false);
                var saved=TranslationService.translationProgress();check(saved.completed()>waiting.completed()&&saved.sessionCompleted()==1,"durable local completion failed to advance both counters");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==1,"progress advanced before journal save");
                closeService();initializeLocal(dir.getParent(),mock,false);scan(Map.of("option.testmod.session",source),Map.of(),Map.of());translated("option.testmod.session",source);invoke(service(),"updateProgress");
                check(TranslationService.translationProgress().sessionCompleted()==0,"restart reports old cache as new session work");
                check(translated("option.testmod.session",source).equals("Este item contém suas ferramentas.")&&mock.sent.isEmpty(),"restart retranslates disk cache");
            }
        });
        test("UTF8_Portuguese_filter_and_section_sign_formatting_are_preserved",()->{
            check(!TranslationService.looksEnglish("Tradução rápida está disponível"),"Portuguese prose marked English");
            check(!TranslationService.looksEnglish("Use a função disponível."),"UTF8 accents fail to exclude mixed Portuguese prose");
            check(TranslationService.looksEnglish("Use this tool."),"accent filter hides English source");
            check(!TranslationService.tokensValid("§aUse this tool.","Use esta ferramenta."),"section sign formatting disappeared without rejection");
            check(TranslationService.tokensValid("§aUse this tool.","§aUse esta ferramenta."),"unchanged section sign requires mojibake prefix");
            check(!TranslationService.tokensValid("§aUse this tool.","§bUse esta ferramenta."),"different color accepted");
        });
        test("pausing_partial_local_batch_saves_success_and_requeues_cancelled_slot_without_backoff",()->{
            try(Mock mock=new Mock()){
                Path dir=init("pause-partial-local",mock,false);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);
                String one="This first tool stores your items.",two="This second tool stores your items.";
                scan(Map.of("option.testmod.one",one,"option.testmod.two",two),Map.of(),Map.of());translated("option.testmod.one",one);translated("option.testmod.two",two);
                ((ScheduledExecutorService)value(svc,"worker")).shutdown();((ScheduledExecutorService)value(svc,"worker")).awaitTermination(2,TimeUnit.SECONDS);
                Object savedJob=jobs().get(one),cancelledJob=jobs().get(two);set(savedJob,"running",true);set(cancelledJob,"running",true);Object config=value(svc,"config");set(config,"translationPaused",true);
                Method commit=svc.getClass().getDeclaredMethod("commitResults",List.class,List.class,boolean.class);commit.setAccessible(true);commit.invoke(svc,List.of(savedJob,cancelledJob),Arrays.asList("Esta ferramenta guarda seus itens.",null),false);
                check(jobs().size()==1&&jobs().containsKey(two),"pause dropped cancelled source or successful neighbor");
                check((int)value(cancelledJob,"failures")==0&&(long)value(cancelledJob,"retryAt")==0&&!(boolean)value(cancelledJob,"running"),"pause incorrectly charges inference failure or retry cooldown");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==1&&translated("option.testmod.one",one).equals("Esta ferramenta guarda seus itens."),"success before pause not saved durably");
                set(config,"translationPaused",false);set(cancelledJob,"running",true);commit.invoke(svc,List.of(cancelledJob),List.of("A segunda ferramenta guarda seus itens."),false);
                check(jobs().isEmpty()&&TranslationService.translationProgress().sessionCompleted()==2,"resumed partial batch failed to finish counters");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==2&&mock.sent.isEmpty(),"resume duplicated success or bypassed controlled local commit");
            }
        });

        test("cache_display_toggle_is_independent_of_pause_preserves_native_and_disk_and_persists",()->{
            try(Mock mock=new Mock()){
                Path dir=init("display-cached-translations",mock,false);closeService();
                String keySource="This stored option is ready.",literalSource="You can store your first items.",questSource="You can open this quest.";
                String keyPt="Esta opção salva está pronta.",literalPt="Você pode guardar seus primeiros itens.",questPt="Você pode abrir esta missão.";
                String styledSource=StyledTextCodec.encode(List.of("Use this ","tool.")),styledPt=StyledTextCodec.encode(List.of("Use esta ","ferramenta."));
                JsonArray rows=new JsonArray();
                for(String[] row:new String[][]{{"option.testmod.saved",keySource,keyPt},{"@literal",literalSource,literalPt},{"@styled",styledSource,styledPt},{"@quest",questSource,questPt}}){
                    JsonObject entry=new JsonObject();entry.addProperty("key",row[0]);entry.addProperty("source",row[1]);entry.addProperty("translated",row[2]);entry.addProperty("qualityVersion",2);rows.add(entry);
                }
                Files.writeString(dir.resolve("translations.json"),GSON.toJson(rows));
                Files.writeString(dir.resolve("translations.journal.jsonl"),"");
                Path lang=dir.getParent().resolve("ftbquests/quests/lang");Files.createDirectories(lang.resolve("en_us/chapters"));Files.createDirectories(lang.resolve("pt_br/chapters"));
                Files.writeString(lang.resolve("en_us/chapters/native.json5"),"{\"quest.NATIVE.title\":\"Mine Stone\"}");Files.writeString(lang.resolve("pt_br/chapters/native.json5"),"{\"quest.NATIVE.title\":\"Minere pedra\"}");
                initializeLocal(dir.getParent(),mock,false);scan(Map.of("option.testmod.saved",keySource,"item.testmod.native","Native Storage"),Map.of("item.testmod.native","Armazenamento nativo"),Map.of());
                check(TranslationService.isCachedTranslationDisplayEnabled(),"new cache display setting defaults off");
                check(translated("option.testmod.saved",keySource).equals(keyPt)&&TranslationService.translateLiteral(literalSource).equals(literalPt)&&TranslationService.translateStyled(styledSource).equals(styledPt)&&TranslationService.translateQuest(questSource).equals(questPt),"fixture did not load all four cached render paths");
                TranslationService.setTranslationPaused(true);await(()->TranslationService.translationProgress().phase().equals("user_paused"),"manual pause before hiding cache");
                byte[] saved=Files.readAllBytes(dir.resolve("translations.json")),journal=Files.readAllBytes(dir.resolve("translations.journal.jsonl"));long before=TranslationService.revision();
                TranslationService.setCachedTranslationDisplayEnabled(false);
                check(!TranslationService.isCachedTranslationDisplayEnabled()&&TranslationService.isTranslationPaused()&&TranslationService.revision()>before,"display toggle did not change immediately or resumed translation");
                check(translated("option.testmod.saved",keySource).equals(keySource)&&TranslationService.translateLiteral(literalSource).equals(literalSource)&&TranslationService.translateStyled(styledSource).equals(styledSource)&&TranslationService.translateQuest(questSource).equals(questSource),"cache-off still substitutes a saved translation");
                check(translated("item.testmod.native","Native Storage").equals("Armazenamento nativo")&&TranslationService.translateQuest("Mine Stone").equals("Minere pedra"),"cache toggle hides official/native pt-BR");
                String miss="This unseen item stores your tools.";TranslationService.translateLiteral(miss);check(jobs().size()==1&&jobs().containsKey(miss),"cache-off either drops misses or requeues cache hits");
                await(()->{try{return !JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("displayCachedTranslations").getAsBoolean();}catch(Exception e){return false;}},"cache display preference persisted");
                check(Arrays.equals(saved,Files.readAllBytes(dir.resolve("translations.json")))&&Arrays.equals(journal,Files.readAllBytes(dir.resolve("translations.journal.jsonl"))),"hiding cached translations erased or rewrote disk data");
                long offRevision=TranslationService.revision();TranslationService.toggleCachedTranslationDisplay();
                check(TranslationService.isCachedTranslationDisplayEnabled()&&TranslationService.revision()>offRevision&&translated("option.testmod.saved",keySource).equals(keyPt)&&TranslationService.translateLiteral(literalSource).equals(literalPt)&&TranslationService.translateStyled(styledSource).equals(styledPt)&&TranslationService.translateQuest(questSource).equals(questPt),"reenabling cache requires model or reload");
                check(mock.sent.isEmpty()&&mock.starts.get()==0&&jobs().size()==1,"display toggle started inference or retried saved work");
                TranslationService.setCachedTranslationDisplayEnabled(false);await(()->{try{return !JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("displayCachedTranslations").getAsBoolean();}catch(Exception e){return false;}},"off flag persisted before restart");
                closeService();initializeLocal(dir.getParent(),mock,false);scan(Map.of("option.testmod.saved",keySource),Map.of(),Map.of());
                check(!TranslationService.isCachedTranslationDisplayEnabled()&&TranslationService.isTranslationPaused()&&translated("option.testmod.saved",keySource).equals(keySource),"restart lost independent display/pause preferences");
                TranslationService.setCachedTranslationDisplayEnabled(true);check(translated("option.testmod.saved",keySource).equals(keyPt)&&mock.sent.isEmpty(),"restart discarded hidden cached data");
            }
        });
        test("cache_display_off_keeps_local_inference_and_durable_commits_active",()->{
            try(Mock mock=new Mock()){
                Path dir=init("display-off-inflight",mock,true);mock.responseGate=new CountDownLatch(1);
                String first="This first storage contains your items.",second="This second storage contains your items.";
                scan(Map.of("option.testmod.first",first),Map.of(),Map.of());translated("option.testmod.first",first);await(()->mock.payloads.size()==1,"first local batch in flight");
                TranslationService.setCachedTranslationDisplayEnabled(false);check(!TranslationService.isTranslationPaused(),"hiding cache paused local translation");
                TranslationService.translateLiteral(second);mock.responseGate.countDown();
                await(()->{try{return jobs().isEmpty()&&((Map<?,?>)value(service(),"bySource")).containsKey(first)&&((Map<?,?>)value(service(),"bySource")).containsKey(second);}catch(Exception e){return false;}},"display-off local queue stopped or results not committed");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()==2&&TranslationService.translationProgress().sessionCompleted()==2,"hidden results not durably saved or counted");
                check(translated("option.testmod.first",first).equals(first)&&TranslationService.translateLiteral(second).equals(second)&&mock.sent.size()==2,"hidden new cache leaked or completed sources were retransmitted");
                TranslationService.setCachedTranslationDisplayEnabled(true);check(translated("option.testmod.first",first).equals("PT: "+first)&&TranslationService.translateLiteral(second).equals("PT: "+second),"new hidden translations not shown instantly on reenable");
                check(mock.sent.size()==2&&jobs().isEmpty(),"reenabling cache translated completed sources again");
            }
        });
        test("cache_display_configuration_migrates_to_on_without_altering_pause",()->{
            try(Mock mock=new Mock()){
                Path dir=init("display-default-migration",mock,false);closeService();
                JsonObject legacy=new JsonObject();legacy.addProperty("translationPaused",true);Files.writeString(dir.resolve("config.json"),GSON.toJson(legacy));
                initializeLocal(dir.getParent(),mock,false);JsonObject migrated=JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject();
                check(migrated.get("displayCachedTranslations").getAsBoolean()&&TranslationService.isCachedTranslationDisplayEnabled()&&TranslationService.isTranslationPaused(),"missing display setting did not default on or changed existing pause");
                long before=TranslationService.revision();TranslationService.setCachedTranslationDisplayEnabled(true);check(TranslationService.revision()==before,"idempotent display setter invalidates caches unnecessarily");
                check(mock.sent.isEmpty()&&mock.starts.get()==0,"display migration admitted local inference");
            }
        });

        test("cache_and_pause_buttons_have_separate_visible_GUI_hit_regions",()->{
            var cache=com.modtranslator.client.TranslationProgressControl.cacheBounds(854,480);var pause=com.modtranslator.client.TranslationProgressControl.bounds(854,480);
            double x=cache.x()+cache.width()/2.0,y=cache.y()+cache.height()/2.0;
            check(cache!=null&&pause!=null&&!pause.contains(x,y)&&!cache.contains(pause.x()+pause.width()/2.0,pause.y()+pause.height()/2.0),"cache/pause controls overlap");
            check(com.modtranslator.client.TranslationProgressControl.hitCache(true,false,true,854,480,x,y),"visible cache button cannot be clicked");
            check(!com.modtranslator.client.TranslationProgressControl.hit(true,false,true,854,480,x,y),"cache click toggles pause");
            check(!com.modtranslator.client.TranslationProgressControl.hitCache(false,false,true,854,480,x,y),"cache button steals world click");
            check(!com.modtranslator.client.TranslationProgressControl.hitCache(true,true,true,854,480,x,y),"F1 hidden cache button steals click");
            check(!com.modtranslator.client.TranslationProgressControl.hitCache(true,false,false,854,480,x,y),"invisible cache button steals click");
            check(com.modtranslator.client.TranslationProgressControl.cacheBounds(854,95)==null&&!com.modtranslator.client.TranslationProgressControl.hitCache(true,false,true,89,480,x,y),"tiny viewport exposes invisible cache button");
        });
        test("panel_minimization_persists_without_changing_pause_cache_or_translation_queue",()->{
            try(Mock mock=new Mock()){
                Path dir=init("panel-minimized-preference",mock,false);String source="This panel contains your tools.";
                ((AtomicBoolean)value(service(),"busy")).set(true);
                scan(Map.of("option.testmod.minimize",source),Map.of(),Map.of());translated("option.testmod.minimize",source);
                TranslationService.setTranslationPaused(true);TranslationService.setCachedTranslationDisplayEnabled(false);
                check(!TranslationService.isTranslationPanelMinimized(),"missing panel state defaults minimized");
                byte[] saved=Files.readAllBytes(dir.resolve("translations.json"));int queued=jobs().size();
                TranslationService.setTranslationPanelMinimized(true);check(TranslationService.isTranslationPanelMinimized()&&TranslationService.isTranslationPaused()&&!TranslationService.isCachedTranslationDisplayEnabled()&&jobs().size()==queued,"minimization changes translation behavior");
                await(()->{try{return JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("translationPanelMinimized").getAsBoolean();}catch(Exception e){return false;}},"minimized flag persisted");
                check(Arrays.equals(saved,Files.readAllBytes(dir.resolve("translations.json")))&&mock.sent.isEmpty()&&mock.starts.get()==0,"minimizing erases cache or starts inference");
                TranslationService.toggleTranslationPanelMinimized();check(!TranslationService.isTranslationPanelMinimized()&&TranslationService.isTranslationPaused()&&!TranslationService.isCachedTranslationDisplayEnabled(),"expanding panel toggles other controls");
                TranslationService.setTranslationPanelMinimized(true);await(()->{try{return JsonParser.parseString(Files.readString(dir.resolve("config.json"))).getAsJsonObject().get("translationPanelMinimized").getAsBoolean();}catch(Exception e){return false;}},"minimized preference saved before restart");
                closeService();initializeLocal(dir.getParent(),mock,false);
                check(TranslationService.isTranslationPanelMinimized()&&TranslationService.isTranslationPaused()&&!TranslationService.isCachedTranslationDisplayEnabled()&&jobs().size()==queued,"restart lost independent panel preferences or pending queue");
                TranslationService.setTranslationPanelMinimized(false);check(!TranslationService.isTranslationPanelMinimized()&&jobs().size()==queued&&mock.sent.isEmpty(),"expansion restarts/drops pending work");
            }
        });
        test("panel_motion_keeps_continuity_endpoints_and_visible_minimize_tab_input_guards",()->{
            com.modtranslator.client.TranslationPanelMotion.reset();
            var expanded=com.modtranslator.client.TranslationPanelMotion.frame(false,854,480,1000_000_000L);
            check(expanded.expandedInput()&&!expanded.collapsed(),"expanded panel starts moving/collapsed");
            var starting=com.modtranslator.client.TranslationPanelMotion.frame(true,854,480,1000_000_000L);
            check(!starting.expandedInput(),"collapse transition accepts invisible content clicks");
            var midway=com.modtranslator.client.TranslationPanelMotion.frame(true,854,480,1110_000_000L);
            check(midway.panelX()<expanded.panelX()&&!midway.expandedInput(),"collapse does not slide left or accepts content clicks");
            var reversing=com.modtranslator.client.TranslationPanelMotion.frame(false,854,480,1110_000_000L);
            check(Math.abs(reversing.panelX()-midway.panelX())<=1.0&&!reversing.expandedInput(),"reversing direction snaps panel or admits moving content");
            var reopened=com.modtranslator.client.TranslationPanelMotion.frame(false,854,480,1330_000_000L);
            check(reopened.expandedInput()&&!reopened.collapsed()&&Math.abs(reopened.panelX()-expanded.panelX())<=1.0,"expand endpoint does not restore panel");
            com.modtranslator.client.TranslationPanelMotion.frame(true,854,480,1400_000_000L);
            var collapsed=com.modtranslator.client.TranslationPanelMotion.frame(true,854,480,1620_000_000L);
            check(collapsed.collapsed()&&!collapsed.expandedInput()&&collapsed.panelX()<0,"collapsed endpoint leaves panel contents active");
            var tab=collapsed.tab();double x=tab.x()+tab.width()/2.0,y=tab.y()+tab.height()/2.0;
            check(tab.x()>=0&&x<854&&com.modtranslator.client.TranslationProgressControl.hitMinimize(true,false,true,collapsed,x,y),"collapsed panel has no reachable reopen tab");
            check(!com.modtranslator.client.TranslationProgressControl.hitMinimize(false,false,true,collapsed,x,y)&&!com.modtranslator.client.TranslationProgressControl.hitMinimize(true,true,true,collapsed,x,y)&&!com.modtranslator.client.TranslationProgressControl.hitMinimize(true,false,false,collapsed,x,y),"minimize tab steals world/F1/invisible clicks");
            com.modtranslator.client.TranslationPanelMotion.reset();
        });

        test("compact_on_demand_RAM_admission_requires_budget_and_game_reserve",()->{
            check(LocalResourcePolicy.ON_DEMAND_MEMORY_MIB==1792&&LocalResourcePolicy.ON_DEMAND_RESERVE_MIB==384,"compact local allocation changes protected game reserve");
            check(LocalResourcePolicy.minimumOnDemandFreeMemoryMiB()==2176,"compact admission omits model budget or reserve");
            var plan=AdaptiveHardwarePolicy.onDemandPlan(new AdaptiveHardwarePolicy.Hardware(16384,3328,24,8192,12288,580),true);
            boolean gpu=TranslationEdition.current()==TranslationEdition.NORMAL;
            check(plan.memoryBudgetMiB()==(gpu?2560:1792)&&plan.threads()==2&&plan.contextTokens()==1024&&plan.batchTokens()==64&&plan.microBatchTokens()==32,"compact local profile inflates allocation");
            check(plan.gpuEligible()==gpu&&plan.worldLoaded(),"compact on-demand backend or world state wrong");
            check(!LocalResourcePolicy.canStartOnDemand(-1)&&!LocalResourcePolicy.canStartOnDemand(2175)&&LocalResourcePolicy.canStartOnDemand(2176),"compact startup boundary or unknown-memory rejection wrong");
            check(!LocalResourcePolicy.underPressure(384,true)&&LocalResourcePolicy.underPressure(383,true),"compact inference reserve boundary wrong");
            check(LocalResourcePolicy.underPressure(1000,false)&&!LocalResourcePolicy.underPressure(1000,true),"foreground incorrectly shares the catalog RAM gate");
            check(!LocalResourcePolicy.canStartOnDemand(1535,true)&&LocalResourcePolicy.canStartOnDemand(1536,true),"measured GPU host admission boundary wrong");
            check(LocalResourcePolicy.underPressure(767,true,true)&&!LocalResourcePolicy.underPressure(768,true,true),"GPU reserve differs from watchdog");
            check(!AdaptiveHardwarePolicy.onDemandPlan(new AdaptiveHardwarePolicy.Hardware(16384,2048,12,2048,8192,580),true).gpuEligible(),"compact GPU ignores free VRAM headroom");
            check(!AdaptiveHardwarePolicy.onDemandPlan(new AdaptiveHardwarePolicy.Hardware(16384,2048,12,3071,8192,580),true).gpuEligible(),"compact GPU starts below measured headroom");
            check(AdaptiveHardwarePolicy.onDemandPlan(new AdaptiveHardwarePolicy.Hardware(16384,2048,12,3072,8192,580),true).gpuEligible()==gpu,"compact GPU rejects usable measured headroom");
        });
        test("compact_local_provider_prioritizes_visible_texts_and_Normal_finishes_catalog",()->{
            try(Mock mock=new Mock()){
                init("compact-local-visible",mock,true);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);mock.economyMode=true;
                String first="This first visible tool stores your items.",second="This second visible tool stores your items.",catalog="This background tool stores your items.";
                scan(Map.of("option.testmod.first",first,"option.testmod.second",second,"option.testmod.catalog",catalog),Map.of(),Map.of());
                translated("option.testmod.first",first);translated("option.testmod.second",second);((AtomicBoolean)value(svc,"busy")).set(false);invoke(svc,"requestTick");
                await(()->translated("option.testmod.first",first).startsWith("PT: ")&&translated("option.testmod.second",second).startsWith("PT: "),"compact visible queue failed to finish");
                if(TranslationEdition.current()==TranslationEdition.NORMAL) {
                    await(()->{try{return jobs().isEmpty();}catch(Exception e){return false;}},"Normal compact mode never finishes queued catalog");
                    check(mock.sent.size()==3&&mock.sent.subList(0,2).containsAll(List.of(first,second))&&mock.sent.get(2).equals(catalog),"compact catalog outranks visible work");
                } else {
                    Thread.sleep(350);check(mock.sent.size()==2&&!mock.sent.contains(catalog)&&jobs().size()==1&&jobs().containsKey(catalog),"Lite translates hidden catalog or drops unscheduled source");
                }
                check(mock.payloads.stream().allMatch(p->p.getAsJsonArray("text").size()==1),"compact inference inflates local batch size");
                await(()->mock.stops.get()>0,"compact model retained after eligible work completed");
            }
        });

        test("background_local_batch_yields_to_visible_text_without_failure_or_retranslation",()->{
            if(TranslationEdition.current()==TranslationEdition.LITE){check(!TranslationEdition.current().allowsBackgroundWhilePlaying(),"Lite must not create background translation batches");return;}
            try(Mock mock=new Mock()){
                Path dir=init("background-yield-visible",mock,true);mock.stopBackgroundWhenRequested=true;mock.responseGate=new CountDownLatch(1);
                Map<String,String> catalog=new LinkedHashMap<>();for(int i=0;i<8;i++)catalog.put("option.testmod.catalog"+i,"This catalog tool number "+i+" stores your items.");
                scan(catalog,Map.of(),Map.of());await(()->mock.backgroundRequests.get()==1&&mock.payloads.size()==1,"catalog local batch entered background provider");
                JsonArray initial=mock.payloads.get(0).getAsJsonArray("text");check(initial.size()==8,"fixture did not hold a full Normal background batch");
                List<Object> originalJobs=new ArrayList<>(jobs().values());String urgent="You can read this urgent tooltip now.";
                TranslationService.translateLiteral(urgent);await(()->mock.priorityRequests.get()>0,"visible text did not request cooperative priority");
                check(!TranslationService.isTranslationPaused(),"interactive priority pauses the whole translator");mock.responseGate.countDown();
                await(()->mock.payloads.size()>=2,"visible local request not dispatched after background yielded");
                check(mock.payloads.get(1).getAsJsonArray("text").get(0).getAsString().equals(urgent),"resumed catalog outranked urgent visible text");
                await(()->TranslationService.translateLiteral(urgent).startsWith("PT: "),"urgent visible translation did not complete");
                for(Object job:originalJobs)check((int)value(job,"failures")==0&&(long)value(job,"retryAt")==0,"cooperative yield counts as invalid translation or adds retry delay");
                check(value(service(),"lastFailure").equals(""),"yield reports a provider failure");
                String savedFirst=initial.get(0).getAsString();check(Collections.frequency(mock.sent,savedFirst)==1,"saved part of yielding batch was translated again");
                check(Files.readAllLines(dir.resolve("translations.journal.jsonl")).size()>=2,"background success/visible translation not saved durably");
            }
        });

        test("reading_screen_retains_aged_visible_work_and_suspends_catalog_inference",()->{
            try(Mock mock=new Mock()){
                init("reading-screen-priority",mock,true);Object svc=service();((AtomicBoolean)value(svc,"busy")).set(true);mock.economyMode=true;set(svc,"readingScreenProbe",(BooleanSupplier)()->true);
                String visible="This visible quest contains your first tools.",catalog="This catalog option contains your tools.";
                scan(Map.of("option.testmod.visible",visible,"option.testmod.catalog",catalog),Map.of(),Map.of());translated("option.testmod.visible",visible);
                Object visibleJob=jobs().get(visible);set(visibleJob,"lastPromoted",System.currentTimeMillis()-20_000);set(visibleJob,"retryAt",System.currentTimeMillis()+60_000);
                ((AtomicBoolean)value(svc,"busy")).set(false);invoke(svc,"requestTick");await(()->{try{return value(svc,"providerState").equals("idle");}catch(Exception e){return false;}},"reading-screen deferred pass");
                check((boolean)value(visibleJob,"priority")&&((Set<?>)value(svc,"visibleTracking")).contains(visible),"reading a quest demotes visible translation after ten seconds");
                check(mock.sent.isEmpty()&&jobs().size()==2,"reading screen schedules hidden catalog while visible text waits");
                set(visibleJob,"retryAt",0L);invoke(svc,"requestTick");await(()->translated("option.testmod.visible",visible).startsWith("PT: "),"aged visible reading-screen text not processed");
                Thread.sleep(250);check(mock.sent.size()==1&&mock.sent.get(0).equals(visible)&&jobs().size()==1&&jobs().containsKey(catalog),"quest reading competes with catalog translations");
                check(mock.stops.get()==0,"compact model unloads between visible quests while reading screen remains open");
                check(!TranslationService.isTranslationPaused(),"reading-screen priority changes user's pause preference");
                set(svc,"readingScreenProbe",(BooleanSupplier)()->false);invoke(svc,"requestTick");
                if(TranslationEdition.current()==TranslationEdition.NORMAL)await(()->{try{return jobs().isEmpty();}catch(Exception e){return false;}},"closing reading screen failed to resume Normal catalog");
                else check(jobs().containsKey(catalog),"Lite starts hidden catalog when reading screen closes");
                await(()->mock.stops.get()>0,"compact model retained after closing reading screen and finishing eligible work");
            }
        });

        test("Normal_completed_queue_keeps_model_warm_while_reading_then_unloads_on_close",()->{
            if(TranslationEdition.current()==TranslationEdition.LITE){check(TranslationEdition.current().idleMillis()==20_000,"Lite idle grace period changed");return;}
            try(Mock mock=new Mock()){
                init("reading-normal-warm-model",mock,true);Object svc=service();set(svc,"readingScreenProbe",(BooleanSupplier)()->true);
                String source="This reading screen explains your first tools.";
                scan(Map.of("option.testmod.reading",source),Map.of(),Map.of());translated("option.testmod.reading",source);
                await(()->translated("option.testmod.reading",source).startsWith("PT: "),"reading-screen translation completed");
                Thread.sleep(250);check(jobs().isEmpty()&&mock.stops.get()==0,"Normal drops loaded model between quests while screen stays open");
                set(svc,"readingScreenProbe",(BooleanSupplier)()->false);invoke(svc,"requestTick");await(()->mock.stops.get()>0,"Normal retained idle model after closing reading screen");
                check(mock.sent.size()==1&&TranslationService.translateLiteral(source).startsWith("PT: "),"model unload lost or retransmitted completed disk cache");
            }
        });

        test("GuideMe_detached_title_and_navigation_relayout_without_reopening",()->{
            try(Mock mock=new Mock()) {
                init("guide-complete",mock,true);scan(Map.of(),Map.of(),Map.of());
                mock.translation=source->source.replace("The Charger","O Carregador").replace("Getting Started","Primeiros passos").replace("Simple Automation","Automação simples");
                FakeCompleteGuide view=new FakeCompleteGuide();
                view.document.blocks.add(new FakeGuideParagraph(List.of(new FakeGuideText("Simple Automation"))));
                FakeGuideRow row=new FakeGuideRow("Getting Started");view.navbar.rows.add(row);
                com.modtranslator.client.GuideMePageRefresh.refresh(view);
                await(()->{com.modtranslator.client.GuideMePageRefresh.refresh(view);return view.title().equals("O Carregador")&&row.text().equals("Primeiros passos")&&view.layouts>0&&view.navbar.layouts>0;},"detached title or sidebar failed to refresh");
                check(view.scrollY==130&&view.navbar.scrollY==47,"guide navigation refresh reset scroll");
                TranslationService.setCachedTranslationDisplayEnabled(false);
                await(()->{com.modtranslator.client.GuideMePageRefresh.refresh(view);return view.title().equals("The Charger")&&row.text().equals("Getting Started");},"detached title/sidebar originals not restored");
            }
        });
        test("GuideMe_new_sidebar_rows_are_discovered_without_cache_revision_change",()->{
            try(Mock mock=new Mock()) {
                init("guide-new-rows",mock,true);scan(Map.of(),Map.of(),Map.of());
                TranslationService.setTranslationPaused(true);
                FakeCompleteGuide view=new FakeCompleteGuide();com.modtranslator.client.GuideMePageRefresh.refresh(view);
                long revision=TranslationService.revision();Thread.sleep(550);
                view.navbar.rows.add(new FakeGuideRow("Items, Blocks, and Machines"));
                com.modtranslator.client.GuideMePageRefresh.refresh(view);
                check(TranslationService.revision()==revision,"test unexpectedly changed cache revision");
                check(jobs().keySet().stream().anyMatch(k->k.toString().contains("Items, Blocks, and Machines")),"expanded sidebar row never queued");
            }
        });

        JsonObject summary=new JsonObject();summary.addProperty("passed",passed);summary.addProperty("failed",failures.size());summary.add("failures",GSON.toJsonTree(failures));
        Files.writeString(root.resolve("results.json"),new GsonBuilder().setPrettyPrinting().create().toJson(summary));
        System.out.println("RESULT "+summary);if(!failures.isEmpty())System.exit(1);
    }
}
