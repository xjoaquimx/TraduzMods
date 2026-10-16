package com.modtranslator.core;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Properties;

/** Immutable build edition. Swapping the JAR changes the policy without changing user settings/cache. */
public enum TranslationEdition {
    LITE("lite", 2048, 2048, 64, 32, 2, 20_000, 700, 900, 768, 500, false),
    NORMAL("normal", 8192, 4096, 512, 128, 6, 180_000, 1400, 1600, 1536, 100, true);

    private static final TranslationEdition CURRENT = load();
    private final String id;
    private final int memoryBudgetMiB, contextTokens, batchTokens, microBatchTokens, maxThreads;
    private final long idleMillis;
    private final int chunkCharacters, referenceCharacters, maxOutputTokens, workerIntervalMillis;
    private final boolean backgroundWhilePlaying;

    TranslationEdition(String id, int memoryBudgetMiB, int contextTokens, int batchTokens, int microBatchTokens,
                       int maxThreads, long idleMillis, int chunkCharacters, int referenceCharacters,
                       int maxOutputTokens, int workerIntervalMillis, boolean backgroundWhilePlaying) {
        this.id=id; this.memoryBudgetMiB=memoryBudgetMiB; this.contextTokens=contextTokens;
        this.batchTokens=batchTokens; this.microBatchTokens=microBatchTokens; this.maxThreads=maxThreads;
        this.idleMillis=idleMillis; this.chunkCharacters=chunkCharacters; this.referenceCharacters=referenceCharacters;
        this.maxOutputTokens=maxOutputTokens; this.workerIntervalMillis=workerIntervalMillis;
        this.backgroundWhilePlaying=backgroundWhilePlaying;
    }
    public static TranslationEdition current() { return CURRENT; }
    public static TranslationEdition from(String value) {
        return value != null && "normal".equals(value.trim().toLowerCase(Locale.ROOT)) ? NORMAL : LITE;
    }
    private static TranslationEdition load() {
        try (InputStream input=TranslationEdition.class.getResourceAsStream("/traduzmods-edition.properties")) {
            if (input==null) return LITE;
            Properties properties=new Properties(); properties.load(input);
            return from(properties.getProperty("edition"));
        } catch (IOException | IllegalArgumentException failure) { return LITE; }
    }
    public String id() { return id; }
    public int memoryBudgetMiB() { return memoryBudgetMiB; }
    public int contextTokens() { return contextTokens; }
    public int batchTokens() { return batchTokens; }
    public int microBatchTokens() { return microBatchTokens; }
    public int threads() { return Math.max(1, Math.min(maxThreads, Runtime.getRuntime().availableProcessors()/2)); }
    public long idleMillis() { return idleMillis; }
    public int chunkCharacters() { return chunkCharacters; }
    public int referenceCharacters() { return referenceCharacters; }
    public int maxOutputTokens() { return maxOutputTokens; }
    public int workerIntervalMillis() { return workerIntervalMillis; }
    public int localBatchSize() { return this==NORMAL?8:1; }
    public boolean allowsBackgroundWhilePlaying() { return backgroundWhilePlaying; }
}
