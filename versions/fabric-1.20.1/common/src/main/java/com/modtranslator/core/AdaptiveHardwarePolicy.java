package com.modtranslator.core;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** The Normal edition chooses useful inference capacity from currently available hardware, keeping game headroom. */
public final class AdaptiveHardwarePolicy {
    public record Hardware(long totalMemoryMiB, long freeMemoryMiB, int logicalThreads,
                           long gpuFreeMiB, long gpuTotalMiB, int gpuDriverMajor) {}
    public record Plan(int memoryBudgetMiB, int threads, int contextTokens, int batchTokens,
                       int microBatchTokens, boolean gpuEligible, boolean worldLoaded) {}
    private AdaptiveHardwarePolicy() {}

    public static int memoryBudget(long totalMiB, long freeMiB) {
        if (totalMiB<=0 || freeMiB<0) return 2048;
        long available=Math.min(8192, Math.min(totalMiB/3, freeMiB-2048));
        return (int)Math.max(2048, (available/256)*256);
    }
    public static int threads(int logical, boolean worldLoaded) {
        int count=Math.max(1,logical);
        return Math.max(1,Math.min(32,worldLoaded?(count*3)/4:count-1));
    }
    public static Plan plan(Hardware hardware, boolean worldLoaded) {
        boolean cuda=hardware.gpuFreeMiB()>=4096 && hardware.gpuDriverMajor()>=580;
        int batch=cuda?(hardware.gpuFreeMiB()>=8192?1024:512):256;
        int micro=cuda?(hardware.gpuFreeMiB()>=8192?512:256):128;
        // Longer context does not accelerate short translations; keep the validated 4096-token window.
        return new Plan(Math.max(cuda?4096:2048,memoryBudget(hardware.totalMemoryMiB(),hardware.freeMemoryMiB())),
                threads(hardware.logicalThreads(),worldLoaded),4096,batch,micro,cuda,worldLoaded);
    }
    /** Normal can accelerate foreground text with a measured compact GPU profile. Lite stays on CPU. */
    public static Plan onDemandPlan(Hardware hardware,boolean worldLoaded) {
        boolean gpu=TranslationEdition.current()==TranslationEdition.NORMAL&&LocalResourcePolicy.MEMORY_LIMIT_MIB>=LocalResourcePolicy.ON_DEMAND_GPU_MEMORY_MIB
                &&hardware.gpuFreeMiB()>=LocalResourcePolicy.ON_DEMAND_GPU_FREE_MIB&&hardware.gpuDriverMajor()>=580&&!System.getProperty("traduzmods.local.acceleration","auto").equalsIgnoreCase("cpu");
        return new Plan(gpu?LocalResourcePolicy.ON_DEMAND_GPU_MEMORY_MIB:LocalResourcePolicy.ON_DEMAND_MEMORY_MIB,
                Math.max(1,Math.min(2,hardware.logicalThreads())),1024,64,32,gpu,worldLoaded);
    }
    public static Hardware capture() {
        long total=-1,free=-1;
        var bean=ManagementFactory.getOperatingSystemMXBean();
        if(bean instanceof com.sun.management.OperatingSystemMXBean memory) {
            total=memory.getTotalMemorySize()/1_048_576; free=memory.getFreeMemorySize()/1_048_576;
        }
        long gpuFree=-1,gpuTotal=-1;int driver=0;
        if(System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win")) {
            try {
                Process probe=new ProcessBuilder("nvidia-smi","--query-gpu=memory.free,memory.total,driver_version",
                        "--format=csv,noheader,nounits").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(!probe.waitFor(5,TimeUnit.SECONDS)) probe.destroyForcibly();
                else if(probe.exitValue()==0) {
                    String first=new String(probe.getInputStream().readNBytes(4096),StandardCharsets.UTF_8).lines().findFirst().orElse("");
                    String[] fields=first.split(",");
                    if(fields.length>=3) {
                        gpuFree=Long.parseLong(fields[0].strip());gpuTotal=Long.parseLong(fields[1].strip());
                        driver=Integer.parseInt(fields[2].strip().split("\\.")[0]);
                    }
                }
            } catch(InterruptedException interrupted) {Thread.currentThread().interrupt();}
            catch(Exception unavailable) {gpuFree=-1;gpuTotal=-1;driver=0;}
        }
        return new Hardware(total,free,Runtime.getRuntime().availableProcessors(),gpuFree,gpuTotal,driver);
    }
    public static boolean gpuUnderPressure(long freeMiB) { return freeMiB>=0 && freeMiB<1024; }
    public static boolean worldLoaded() {
        var game=net.minecraft.client.Minecraft.getInstance();
        return game!=null && game.level!=null;
    }
}
