package com.modtranslator.core;
import java.nio.file.*;
import java.util.concurrent.*;
/** Deliberately tiny isolated budget verifies watchdog rejection and absence of an immediate CPU relaunch. */
public class LocalGuardSmoke {
    public static void main(String[] args)throws Exception {
        Path directory=Path.of(args[0]);
        try {
            boolean ready=LocalTranslationClient.start(directory).get(180,TimeUnit.SECONDS);
            if(ready) {Thread.sleep(4000);LocalTranslationClient.maintain();}
            if(LocalTranslationClient.available())throw new AssertionError("Watchdog failed to stop over-budget inference");
            if(!Files.exists(directory.resolve("local/resource-guard.txt")))throw new AssertionError("No proven resource guard event; test environment may not support CUDA");
            String before=Files.readString(directory.resolve("local/server.pid"));
            if(LocalTranslationClient.start(directory).get(5,TimeUnit.SECONDS))throw new AssertionError("Unsafe immediate restart");
            String after=Files.readString(directory.resolve("local/server.pid"));
            if(!before.equals(after))throw new AssertionError("Watchdog cut launched a replacement process");
            System.out.println("PASS_RESOURCE_WATCHDOG_AND_RESTART_COOLDOWN");
        } finally {LocalTranslationClient.stop();}
    }
}
