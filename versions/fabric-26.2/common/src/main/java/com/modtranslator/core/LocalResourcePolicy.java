package com.modtranslator.core;

import java.lang.management.ManagementFactory;

/** Separate native inference budget, never confused with Minecraft's -Xmx or the disk translation cache. */
public final class LocalResourcePolicy {
    public static final int ON_DEMAND_MEMORY_MIB = 1792;
    public static final int ON_DEMAND_RESERVE_MIB = 384;
    public static final int ON_DEMAND_GPU_MEMORY_MIB = 2560;
    public static final int ON_DEMAND_GPU_FREE_MIB = 3072;
    public static final int GPU_START_FREE_MIB = 1536;
    private static final int EDITION_CAP_MIB = TranslationEdition.current()==TranslationEdition.LITE?2048:8192;
    public static final int MEMORY_LIMIT_MIB = Math.max(768, Math.min(EDITION_CAP_MIB,
            Integer.getInteger("traduzmods.local.maxMemoryMiB", EDITION_CAP_MIB)));
    public static final long IDLE_MILLIS = TranslationEdition.current().idleMillis();
    private static volatile int activeBudget=budgetForStart(freeMemoryMiB());
    private LocalResourcePolicy() {}
    public static long freeMemoryMiB() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean memory ? memory.getFreeMemorySize() / 1_048_576 : -1;
    }
    public static long totalMemoryMiB() {
        var bean = ManagementFactory.getOperatingSystemMXBean();
        return bean instanceof com.sun.management.OperatingSystemMXBean memory ? memory.getTotalMemorySize()/1_048_576 : -1;
    }
    public static int budgetForStart(long freeMiB) {
        return Math.min(MEMORY_LIMIT_MIB,TranslationEdition.current()==TranslationEdition.LITE?2048:
                AdaptiveHardwarePolicy.memoryBudget(totalMemoryMiB(),freeMiB));
    }
    public static int currentMemoryBudgetMiB() { return activeBudget; }
    static void configureLaunchBudget(int budget) { activeBudget=Math.max(768,Math.min(MEMORY_LIMIT_MIB,budget)); }
    public static long minimumFreeMemoryMiB(long freeMiB) { return budgetForStart(freeMiB)+2048L; }
    public static long minimumOnDemandFreeMemoryMiB() { return ON_DEMAND_MEMORY_MIB+ON_DEMAND_RESERVE_MIB; }
    static boolean canStart(long freeMiB) { return freeMiB >= minimumFreeMemoryMiB(freeMiB); }
    public static boolean canStartOnDemand(long freeMiB) { return freeMiB >= minimumOnDemandFreeMemoryMiB(); }
    public static boolean canAttemptOnDemand(long freeMiB) {
        return canStartOnDemand(freeMiB)||(TranslationEdition.current()==TranslationEdition.NORMAL&&freeMiB>=GPU_START_FREE_MIB);
    }
    public static boolean canStartOnDemand(long freeMiB,boolean gpu) {
        return freeMiB>=(gpu?GPU_START_FREE_MIB:minimumOnDemandFreeMemoryMiB());
    }
    static boolean underPressure(long freeMiB) { return freeMiB >= 0 && freeMiB < 1536; }
    /** Foreground inference uses a measured small context. Catalog work keeps its larger reserve. */
    public static int runningReserveMiB(boolean compact) { return compact?ON_DEMAND_RESERVE_MIB:1536; }
    public static int runningReserveMiB(boolean compact,boolean gpu) { return gpu?768:runningReserveMiB(compact); }
    static boolean underPressure(long freeMiB,boolean compact) {
        return freeMiB>=0 && freeMiB<runningReserveMiB(compact);
    }
    static boolean underPressure(long freeMiB,boolean compact,boolean gpu) {
        return freeMiB>=0 && freeMiB<runningReserveMiB(compact,gpu);
    }
    static boolean shouldUnload(long now, long lastUse, boolean running) { return !running && now-lastUse >= IDLE_MILLIS; }
}
