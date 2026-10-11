package com.modtranslator.core;

import java.nio.file.*;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Optional automatic CUDA installation. The independent CPU runtime stays usable. */
final class LocalGpuRuntime {
    static final String CUDA_HASH="76ddc6eff2389570789ed608881efc6977751a722015d8e8c94f302224ff1a3a";
    static final String CUDA_DLL_HASH="738f8c251ac22b70c3ae6f83a10cf222725df0395246a2cf58f32bdb85fbe668";
    private static final String RELEASE="https://github.com/ggml-org/llama.cpp/releases/download/"+LocalBootstrap.RUNTIME_TAG+"/";
    record Choice(Path runtime,String backend,int layers) {}
    private LocalGpuRuntime() {}
    static Choice choose(Path directory,boolean acceleration,Consumer<String> status) {
        return choose(directory,acceleration,status,AdaptiveHardwarePolicy.capture());
    }
    static Choice choose(Path directory,boolean acceleration,Consumer<String> status,AdaptiveHardwarePolicy.Hardware hardware) {
        Choice cpu=new Choice(directory.resolve("local/runtime"),"cpu",0);
        if(!acceleration||System.getProperty("traduzmods.local.acceleration","auto").equalsIgnoreCase("cpu")||!System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win"))return cpu;
        try {
            if(hardware.gpuFreeMiB()<4096||hardware.gpuDriverMajor()<580)return cpu;
            Path local=directory.resolve("local"),runtime=local.resolve("runtime-cuda"),stamp=runtime.resolve("verified-runtime.txt");
            String expected=LocalBootstrap.RUNTIME_TAG+"\n"+CUDA_HASH+"\n"+CUDA_DLL_HASH+"\n";
            if(!Files.isRegularFile(stamp)||!Files.readString(stamp).equals(expected)||!Files.isRegularFile(runtime.resolve("llama-server.exe"))||!Files.isRegularFile(runtime.resolve("ggml-cuda.dll"))) {
                Path dlls=local.resolve("cudart-13.4.zip"),binaries=local.resolve("llama-"+LocalBootstrap.RUNTIME_TAG+"-cuda.zip");
                LocalBootstrap.download(RELEASE+"cudart-llama-bin-win-cuda-13.4-x64.zip",dlls,423_535_356L,CUDA_DLL_HASH,status,"CUDA libraries");
                LocalBootstrap.download(RELEASE+"llama-"+LocalBootstrap.RUNTIME_TAG+"-bin-win-cuda-13.4-x64.zip",binaries,153_089_864L,CUDA_HASH,status,"CUDA runtime");
                LocalBootstrap.extractRuntime(dlls,runtime,1_500 * 1_048_576L);
                LocalBootstrap.extractRuntime(binaries,runtime,1_500 * 1_048_576L);
                if(!Files.isRegularFile(runtime.resolve("llama-server.exe"))||!Files.isRegularFile(runtime.resolve("ggml-cuda.dll")))return cpu;
                Files.writeString(stamp,expected);
            }
            return new Choice(runtime,"cuda",99);
        } catch(Exception unavailable) {status.accept("CUDA unavailable; using CPU runtime");return cpu;}
    }
}
