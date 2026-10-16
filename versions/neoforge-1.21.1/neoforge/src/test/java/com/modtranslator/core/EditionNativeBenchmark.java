package com.modtranslator.core;
import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
/** Isolated native workload plus pause/resume ownership checks. Excludes game data and cache hits. */
public class EditionNativeBenchmark {
 public static void main(String[] args)throws Exception {
  Path directory=Path.of(args[0]),report=Path.of(args[1]);JsonObject result=new JsonObject();JsonArray rows=new JsonArray();
  boolean lifecycleOnly=args.length>3&&args[3].equals("lifecycleOnly");
  boolean onDemand=Boolean.getBoolean("traduzmods.test.onDemand");
  int workload=lifecycleOnly?1:60;
  result.addProperty("edition",TranslationEdition.current().id());result.addProperty("freeBeforeMiB",LocalResourcePolicy.freeMemoryMiB());
  result.addProperty("onDemandProfile",onDemand);
  GameTranslationContext.install(Map.of("Wooden Pickaxe","Picareta de madeira","Oak Log","Tronco de carvalho"));
  ModTextPolicy.registerModNames(List.of("Minecraft","Prominence II"));ModTextPolicy.registerNpcNames(List.of("Radin of the Side Rail","Durin"));
  try {
   LocalTranslationClient.setUserPaused(true);
   if(startModel(directory,onDemand).get(10,TimeUnit.SECONDS))throw new AssertionError("Paused native model was admitted");
   LocalTranslationClient.setUserPaused(false);
   long start=System.nanoTime();
   if(!startModel(directory,onDemand).get(180,TimeUnit.SECONDS))throw new AssertionError(LocalTranslationClient.status());
   result.addProperty("startupMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start));result.addProperty("backend",LocalTranslationClient.backend());
   result.addProperty("activeMemoryBudgetMiB",LocalResourcePolicy.currentMemoryBudgetMiB());
   var plan=LocalTranslationClient.activePlan();JsonObject selected=new JsonObject();
   if(onDemand&&(!LocalTranslationClient.isEconomyMode()||plan.threads()>2||plan.contextTokens()!=1024||plan.batchTokens()!=64||plan.microBatchTokens()!=32||plan.gpuEligible()||LocalResourcePolicy.currentMemoryBudgetMiB()!=1792))throw new AssertionError("On-demand profile does not match bounded CPU plan");
   selected.addProperty("threads",plan.threads());selected.addProperty("contextTokens",plan.contextTokens());selected.addProperty("batchTokens",plan.batchTokens());selected.addProperty("microBatchTokens",plan.microBatchTokens());selected.addProperty("gpuEligible",plan.gpuEligible());selected.addProperty("worldLoaded",plan.worldLoaded());result.add("activePlan",selected);
   var hardware=LocalTranslationClient.hardware();JsonObject detected=new JsonObject();
   detected.addProperty("totalMemoryMiB",hardware.totalMemoryMiB());detected.addProperty("freeMemoryMiB",hardware.freeMemoryMiB());detected.addProperty("logicalThreads",hardware.logicalThreads());detected.addProperty("gpuFreeMiB",hardware.gpuFreeMiB());detected.addProperty("gpuTotalMiB",hardware.gpuTotalMiB());detected.addProperty("gpuDriverMajor",hardware.gpuDriverMajor());result.add("hardware",detected);
   Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(result));
   if(onDemand){
    String quest="With your Wooden Pickaxe, you can mine stone to obtain cobblestone. This lets you craft stone tools and mine more blocks. You can also craft a furnace to smelt items. Furnaces need fuel: wood, coal and even sticks work. Remember to gather enough cobblestone to make a furnace, a sword, a pickaxe and an axe. Hold the left mouse button to mine blocks. Better tools let you mine stronger materials.";
    long questStart=System.nanoTime();var questResults=LocalTranslationClient.translate(List.of(quest)).get(180,TimeUnit.SECONDS);
    if(questResults.size()!=1||questResults.get(0)==null||!TranslationService.tokensValid(quest,questResults.get(0)))throw new AssertionError("Compact long quest failed");
    result.addProperty("longQuestElapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-questStart));result.addProperty("longQuestTranslation",questResults.get(0));
    var background=LocalTranslationClient.translateBackground(List.of(quest.repeat(5),quest,quest));
    var running=LocalTranslationClient.class.getDeclaredField("inferenceRunning");running.setAccessible(true);
    long runningDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
    while(!running.getBoolean(null)&&!background.isDone()&&System.nanoTime()<runningDeadline)Thread.sleep(5);
    if(!running.getBoolean(null))throw new AssertionError("Background inference did not start");
    Thread.sleep(150);
    long priorityStart=System.nanoTime();LocalTranslationClient.requestInteractivePriority();
    var interrupted=background.get(120,TimeUnit.SECONDS);
    long yieldedMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-priorityStart);
    if(interrupted.size()!=3||interrupted.stream().noneMatch(Objects::isNull)||!LocalTranslationClient.wasBackgroundYielded()||!LocalTranslationClient.available())throw new AssertionError("Background did not yield while preserving the loaded model");
    var foreground=LocalTranslationClient.translate(List.of("What can I help with?")).get(120,TimeUnit.SECONDS);
    if(foreground.size()!=1||foreground.get(0)==null)throw new AssertionError("Foreground failed after background yield");
    result.addProperty("backgroundYieldPassed",true);result.addProperty("backgroundYieldMs",yieldedMs);result.addProperty("visibleTextAfterYieldMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-priorityStart));result.addProperty("visibleTextAfterYieldTranslation",foreground.get(0));
   }
   long totalStart=System.nanoTime();
   for(int offset=0;offset<workload;offset+=TranslationEdition.current().localBatchSize()) {
    List<String> sources=new ArrayList<>();int count=Math.min(TranslationEdition.current().localBatchSize(),workload-offset);
    for(int item=0;item<count;item++){int i=offset+item;String source=switch(i%6) {
     case 0->"What can I help with?";
     case 1->"Of course. What are you looking for?";
     case 2->"Radin of the Side Rail will guide you. You can use Picareta de madeira to mine blocks.";
     case 3->"You have "+i+" items in your inventory. Hold the left mouse button to mine stone.";
     case 4->"Level up your strengths and unlock new abilities. Roll out of danger and avoid attacks.";
     default->"The Eternal Flame has called for an emergency! Travel outside Vaaz and investigate the undead demons.";
    };sources.add(source);}
    start=System.nanoTime();List<String> outputs=LocalTranslationClient.translate(sources).get(120,TimeUnit.SECONDS);long elapsed=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start);
    if(outputs.size()!=sources.size())throw new AssertionError("Batch result count differs");
    for(int item=0;item<count;item++) {
     String source=sources.get(item),translated=outputs.get(item);if(translated==null||!TranslationService.tokensValid(source,translated))throw new AssertionError("Formatting/name invalid at "+(offset+item));
     JsonObject row=new JsonObject();row.addProperty("source",source);row.addProperty("translated",translated);row.addProperty("batchElapsedMs",elapsed);row.addProperty("batchSize",count);rows.add(row);
    }
    System.out.println("BENCH "+TranslationEdition.current().id()+" "+(offset+count));
   }
   result.addProperty("translationTotalMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-totalStart));result.add("translations",rows);
   Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(result));
   if(lifecycleOnly){result.addProperty("idleRetested",false);}
   else if(args.length>2&&args[2].equals("skipIdle")) {
    Thread.sleep(35_000);LocalTranslationClient.maintain();Thread.sleep(2000);LocalTranslationClient.maintain();
    if(!LocalTranslationClient.available())throw new AssertionError("Native process stopped during safe GPU pressure observation");
    result.addProperty("gpuFreeAfterPressureProbeMiB",LocalTranslationClient.currentGpuFreeMiB());result.addProperty("safePressureObservationPassed",true);
    result.addProperty("idleRetested",false);
   } else {
    long wait=LocalResourcePolicy.IDLE_MILLIS+1500;System.out.println("IDLE_TEST_WAIT_MS "+wait);Thread.sleep(wait);LocalTranslationClient.maintain();
    if(LocalTranslationClient.available())throw new AssertionError("Edition idle unload failed");
    result.addProperty("idleUnloaded",true);
   }
   LocalTranslationClient.setUserPaused(true);
   var owned=LocalTranslationClient.class.getDeclaredField("ownedProcess");owned.setAccessible(true);
   long pauseDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
   while(owned.get(null)!=null&&System.nanoTime()<pauseDeadline)Thread.sleep(25);
   if(owned.get(null)!=null)throw new AssertionError("Pause retained the owned native process");
   if(startModel(directory,onDemand).get(10,TimeUnit.SECONDS))throw new AssertionError("Paused restart was admitted");
   LocalTranslationClient.setUserPaused(false);
   // Windows may reclaim the model's pages shortly after ProcessHandle.onExit completes.
   // Preserve the real admission guard and retry only while waiting for reclaimed memory.
   long resumeDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
   boolean resumedReady=false;
   do {resumedReady=startModel(directory,onDemand).get(180,TimeUnit.SECONDS);if(!resumedReady)Thread.sleep(500);}
   while(!resumedReady&&System.nanoTime()<resumeDeadline);
   if(!resumedReady)throw new AssertionError("Resume still waiting for resources: "+LocalTranslationClient.status());
   String resumedSource="You can mine stone with a Wooden Pickaxe.";
   var resumed=LocalTranslationClient.translate(List.of(resumedSource)).get(120,TimeUnit.SECONDS);
   if(resumed.size()!=1||resumed.get(0)==null||!TranslationService.tokensValid(resumedSource,resumed.get(0)))throw new AssertionError("Resumed inference failed");
   result.addProperty("pausedStartupRejected",true);
   result.addProperty("pauseReleasedOwnedProcess",true);
   result.addProperty("resumeNativeInferencePassed",true);
   result.addProperty("resumedTranslation",resumed.get(0));
   result.addProperty("passed",true);
  } catch(Throwable failure){result.addProperty("passed",false);result.addProperty("failure",failure.toString());throw failure;}
  finally {LocalTranslationClient.stop();Files.writeString(report,new GsonBuilder().setPrettyPrinting().create().toJson(result));}
 }
 private static CompletableFuture<Boolean> startModel(Path directory,boolean onDemand){return onDemand?LocalTranslationClient.startOnDemand(directory):LocalTranslationClient.start(directory);}
}
