package com.modtranslator.core;

import com.google.gson.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual checkpoint/journal timings in an isolated directory, never a player's cache. */
public final class PersistenceBenchmark {
    @SuppressWarnings("unchecked")
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);Files.createDirectories(directory);
        TranslationServiceIntegrationTest.root=directory;
        try(var mock=new TranslationServiceIntegrationTest.Mock()) {
            Path cacheDirectory=TranslationServiceIntegrationTest.init("persistence-benchmark",mock,false);
            Object service=TranslationServiceIntegrationTest.service();
            ScheduledExecutorService worker=(ScheduledExecutorService)TranslationServiceIntegrationTest.value(service,"worker");
            worker.shutdown();if(!worker.awaitTermination(3,TimeUnit.SECONDS))throw new AssertionError("Worker did not stop");
            var cache=(ConcurrentHashMap<String,ConcurrentHashMap<String,String>>)TranslationServiceIntegrationTest.value(service,"cache");
            for(int i=0;i<30_000;i++)cache.put("quest.benchmark."+i,new ConcurrentHashMap<>(Map.of("This quest explains how to craft machine component "+i+" and obtain your first tools.","Esta missão explica como fabricar o componente de máquina "+i+" e obter suas primeiras ferramentas.")));
            List<Double> checkpoints=new ArrayList<>(),deltas=new ArrayList<>();
            for(int round=0;round<7;round++) {
                long start=System.nanoTime();TranslationServiceIntegrationTest.invoke(service,"persistSafely");checkpoints.add((System.nanoTime()-start)/1_000_000D);
            }
            Path journal=cacheDirectory.resolve("translations.journal.jsonl");Gson compact=new Gson();
            for(int round=0;round<50;round++) {
                List<String> rows=new ArrayList<>();
                for(int i=0;i<8;i++) {JsonObject row=new JsonObject();row.addProperty("key","quest.benchmark.delta."+(round*8+i));row.addProperty("source","The new quest describes this tool.");row.addProperty("translated","A nova missão descreve esta ferramenta.");row.addProperty("qualityVersion",2);rows.add(compact.toJson(row));}
                long start=System.nanoTime();CacheJournal.append(journal,rows);deltas.add((System.nanoTime()-start)/1_000_000D);
            }
            if(CacheJournal.recover(journal).size()!=400)throw new AssertionError("Durable journal lost records");
            checkpoints.sort(Double::compare);deltas.sort(Double::compare);
            JsonObject result=new JsonObject();result.addProperty("cachedRecords",30_000);result.addProperty("checkpointBytes",Files.size(cacheDirectory.resolve("translations.json")));result.addProperty("medianFullCheckpointMs",checkpoints.get(3));result.addProperty("medianEightRecordJournalMs",deltas.get(25));result.addProperty("journalRecoveredRecords",400);
            Files.writeString(directory.resolve("persistence-metrics.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));System.out.println("PERSISTENCE "+result);
        } finally {TranslationServiceIntegrationTest.closeService();}
    }
}
