package com.modtranslator.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Owns an authenticated, loopback-only Hy-MT2 llama.cpp server; never uses a foreign server. */
public final class LocalTranslationClient {
    private static final int PORT = Compat.clamp(Integer.getInteger("traduzmods.local.port",18765),1024,65535);
    private static final String BASE = "http://127.0.0.1:" + PORT;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "traduzmods-local");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object LIFECYCLE=new Object();
    private static final java.util.concurrent.atomic.AtomicLong cancellationGeneration=new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean userPaused;
    private static final ExecutorService CONTROL=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"traduzmods-pause-control");t.setDaemon(true);return t;});
    /** No IO or process waits on the caller (Minecraft's render thread). */
    public static void setUserPaused(boolean paused) {
        userPaused=paused;
        if(paused){cancellationGeneration.incrementAndGet();CONTROL.execute(()->{synchronized(LIFECYCLE){if(userPaused){stop();state="Local translation paused by user";}}});}
    }
    private static boolean admitted(long generation){return !userPaused&&generation==cancellationGeneration.get();}
    private static void checkAdmission(long generation)throws IOException {if(!admitted(generation))throw new IOException("Translation startup cancelled by user pause");}
    private static volatile ProcessHandle ownedProcess;
    private static volatile Instant ownedStart;
    private static volatile boolean ready;
    private static volatile String state = "Local model not started";
    private static volatile String token;
    private static volatile String backend="cpu";
    private static volatile AdaptiveHardwarePolicy.Hardware detectedHardware;
    private static volatile AdaptiveHardwarePolicy.Plan activePlan;
    private static volatile long lastHardwareReconfigure;
    private static final ExecutorService HARDWARE_MONITOR=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"traduzmods-hardware");t.setDaemon(true);return t;});
    private static final java.util.concurrent.atomic.AtomicBoolean gpuProbeRunning=new java.util.concurrent.atomic.AtomicBoolean();
    private static volatile long lastGpuProbeTime,currentGpuFreeMiB=-1,gpuProbeOwnerPid=-1;
    private static volatile Instant gpuProbeOwnerStart;
    public static long currentGpuFreeMiB() { return currentGpuFreeMiB; }
    private static void probeGpuPressure() {
        if(TranslationEdition.current()!=TranslationEdition.NORMAL || !ready || !"cuda".equals(backend)
                || System.currentTimeMillis()-lastGpuProbeTime<30_000 || !gpuProbeRunning.compareAndSet(false,true))return;
        lastGpuProbeTime=System.currentTimeMillis();
        var process=ownedProcess;var start=ownedStart;
        if(process==null) {gpuProbeRunning.set(false);return;}
        HARDWARE_MONITOR.execute(()->{
            try {
                var hardware=AdaptiveHardwarePolicy.capture();
                var active=ownedProcess;
                if(active!=null && active.pid()==process.pid() && java.util.Objects.equals(ownedStart,start)) {
                    gpuProbeOwnerPid=process.pid();gpuProbeOwnerStart=start;currentGpuFreeMiB=hardware.gpuFreeMiB();
                }
            } finally {gpuProbeRunning.set(false);}
        });
    }
    public static AdaptiveHardwarePolicy.Hardware hardware() {
        var hardware=detectedHardware;
        return hardware!=null?hardware:new AdaptiveHardwarePolicy.Hardware(LocalResourcePolicy.totalMemoryMiB(),
                LocalResourcePolicy.freeMemoryMiB(),Runtime.getRuntime().availableProcessors(),-1,-1,0);
    }
    public static AdaptiveHardwarePolicy.Plan activePlan() {
        var plan=activePlan;
        if(plan!=null)return plan;
        if(TranslationEdition.current()==TranslationEdition.NORMAL)return AdaptiveHardwarePolicy.plan(hardware(),false);
        var edition=TranslationEdition.LITE;
        return new AdaptiveHardwarePolicy.Plan(LocalResourcePolicy.currentMemoryBudgetMiB(),edition.threads(),
                edition.contextTokens(),edition.batchTokens(),edition.microBatchTokens(),false,false);
    }
    public static long foregroundMinimumFreeMemoryMiB() {
        return AdaptiveHardwarePolicy.onDemandPlan(hardware(),false).gpuEligible()?LocalResourcePolicy.GPU_START_FREE_MIB:LocalResourcePolicy.minimumOnDemandFreeMemoryMiB();
    }
    private static CompletableFuture<Boolean> startup;
    private static volatile long lastUse;
    private static volatile boolean inferenceRunning;
    private static volatile boolean economyMode;
    private static final java.util.concurrent.atomic.AtomicLong interactivePriorityGeneration=new java.util.concurrent.atomic.AtomicLong();
    // These two fields are confined to the serial native worker; never interrupt a valid active HTTP completion.
    private static boolean translatingBackground;
    private static final TranslationMemo PARAGRAPHS=new TranslationMemo(TranslationEdition.current()==TranslationEdition.LITE?128*1024:512*1024,256);
    private static volatile long paragraphReuse;
    public static long paragraphReuseCount(){return paragraphReuse;}
    private static long backgroundPriorityGeneration;
    private static volatile boolean lastBackgroundYielded;
    public static void requestInteractivePriority() { interactivePriorityGeneration.incrementAndGet(); }
    public static boolean wasBackgroundYielded() { return lastBackgroundYielded; }
    private static boolean backgroundMustYield() {
        return translatingBackground && backgroundPriorityGeneration!=interactivePriorityGeneration.get();
    }
    private static final class BackgroundYieldException extends IOException {
        BackgroundYieldException() { super("Background translation yielded to newly visible text"); }
    }
    private static void checkInteractivePriority() throws BackgroundYieldException {
        if(backgroundMustYield()){lastBackgroundYielded=true;throw new BackgroundYieldException();}
    }
    public static boolean isEconomyMode() { return economyMode; }
    public static long restartDelayMillis() { return 0; }
    private static java.nio.channels.FileChannel leaseChannel;
    private static java.nio.channels.FileLock lease;
    // Independent of Minecraft's JVM: native crashes and Runtime.halt skip Java hooks.
    private static final String WATCHDOG = "$ErrorActionPreference='Stop'; "
            + "$record=Get-Content -LiteralPath $env:TRADUZMODS_SERVER_OWNERSHIP -Raw | ConvertFrom-Json; "
            + "function Matches($process,$start,$path) { "
            + "if ($null -eq $process) { return $false }; "
            + "try { $time=([DateTimeOffset]$process.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); "
            + "return ($time -eq [long]$start -and ($null -eq $path -or $process.Path -eq $path)) } catch { return $false } }; "
            + "while ($true) { "
            + "$server=Get-Process -Id ([int]$record.serverPid) -ErrorAction SilentlyContinue; "
            + "if (-not (Matches $server $record.serverStartMillis $record.executable)) { break }; "
            + "try { $server.Refresh(); $free=[long](Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1024 } "
            + "catch { if (Matches $server $record.serverStartMillis $record.executable) { $server.Kill() }; break }; "
            + "if ($server.WorkingSet64 -gt ([long]$env:TRADUZMODS_MEMORY_LIMIT * 1048576) -or "
            + "$server.PrivateMemorySize64 -gt ([long]$env:TRADUZMODS_MEMORY_LIMIT * 1048576) -or $free -lt [long]$env:TRADUZMODS_RAM_RESERVE) { "
            + "try { [IO.File]::WriteAllText($env:TRADUZMODS_RESOURCE_GUARD, ($record | ConvertTo-Json -Compress)) } catch {}; "
            + "if (Matches $server $record.serverStartMillis $record.executable) { $server.Kill() }; break }; "
            + "$parent=Get-Process -Id ([int]$record.parentPid) -ErrorAction SilentlyContinue; "
            + "if (-not (Matches $parent $record.parentStartMillis $null)) { "
            + "$server=Get-Process -Id ([int]$record.serverPid) -ErrorAction SilentlyContinue; "
            + "if (Matches $server $record.serverStartMillis $record.executable) { $server.Kill() }; break }; "
            + "Start-Sleep -Seconds 2 }; "
            + "try { $latest=Get-Content -LiteralPath $env:TRADUZMODS_SERVER_OWNERSHIP -Raw | ConvertFrom-Json; "
            + "if ($latest.serverPid -eq $record.serverPid -and $latest.serverStartMillis -eq $record.serverStartMillis) { "
            + "Remove-Item -LiteralPath $env:TRADUZMODS_SERVER_OWNERSHIP -Force } } catch {}";

    static { Runtime.getRuntime().addShutdownHook(new Thread(LocalTranslationClient::stop, "traduzmods-local-stop")); }
    private LocalTranslationClient() {}

    /** configDirectory is the mod's config/modtranslator directory. Never blocks the game thread. */
    public static synchronized CompletableFuture<Boolean> start(Path configDirectory) {
        return startProfile(configDirectory,TranslationEdition.current()==TranslationEdition.NORMAL&&!LocalResourcePolicy.canStart(LocalResourcePolicy.freeMemoryMiB()));
    }
    /** Compact foreground inference with the same local model/cache; Normal can use CUDA. */
    public static synchronized CompletableFuture<Boolean> startOnDemand(Path configDirectory) {
        return startProfile(configDirectory,true);
    }
    private static CompletableFuture<Boolean> startProfile(Path configDirectory,boolean compact) {
        if(userPaused)return CompletableFuture.completedFuture(false);
        if (available()) return CompletableFuture.completedFuture(true);
        if (startup != null && !startup.isDone()) return startup;
        if (!(compact?LocalResourcePolicy.canAttemptOnDemand(LocalResourcePolicy.freeMemoryMiB()):canStartProfile(LocalResourcePolicy.freeMemoryMiB(),false))) {
            state = "Local translation paused to preserve game RAM";
            return CompletableFuture.completedFuture(false);
        }
        lastUse = System.currentTimeMillis();
        long generation=cancellationGeneration.get();
        startup = CompletableFuture.supplyAsync(() -> startServer(configDirectory.toAbsolutePath().normalize(),!compact||TranslationEdition.current()==TranslationEdition.NORMAL,generation,compact), WORKER);
        return startup;
    }
    private static boolean canStartProfile(long freeMiB,boolean compact) {
        return canStartProfile(freeMiB,compact,false);
    }
    private static boolean canStartProfile(long freeMiB,boolean compact,boolean gpu) {
        return compact?LocalResourcePolicy.canStartOnDemand(freeMiB,gpu):LocalResourcePolicy.canStart(freeMiB);
    }

    public static boolean available() { return !userPaused && ready && isOwnedAlive(); }
    public static String status() { return ready && !isOwnedAlive() ? "Local model process exited" : state; }
    public static String backend() {return backend;}
    /** Nonblocking maintenance on the service worker; native watchdog also runs during inference/JVM stalls. */
    public static void maintain() {
        maintain(false);
    }
    public static void maintain(boolean readingScreen) {
        if (ownedProcess == null) return;
        // Reading a quest is active use. Avoid unloading between pages and paying
        // another model startup; the same live pressure/watchdog checks still run.
        if(readingScreen)lastUse=System.currentTimeMillis();
        if (!isOwnedAlive()) { boolean pressure=LocalResourcePolicy.underPressure(LocalResourcePolicy.freeMemoryMiB(),economyMode,"cuda".equals(backend));stop(); state=pressure?"Local translation paused: low system RAM":"Local translator stopped; checking available resources"; return; }
        probeGpuPressure();
        if(TranslationEdition.current()==TranslationEdition.NORMAL && ownedProcess!=null
                && ownedProcess.pid()==gpuProbeOwnerPid && java.util.Objects.equals(ownedStart,gpuProbeOwnerStart)
                && AdaptiveHardwarePolicy.gpuUnderPressure(currentGpuFreeMiB)) {
            stop();state="Local translation paused: low free GPU memory";
        } else if (LocalResourcePolicy.underPressure(LocalResourcePolicy.freeMemoryMiB(),economyMode,"cuda".equals(backend))) {
            stop(); state="Local translation paused: low system RAM";
        } else if (!economyMode && TranslationEdition.current()==TranslationEdition.NORMAL && ready && !inferenceRunning
                && (startup==null||startup.isDone()) && activePlan!=null
                && activePlan.worldLoaded()!=AdaptiveHardwarePolicy.worldLoaded()
                && System.currentTimeMillis()-lastHardwareReconfigure>=30_000) {
            stop();lastHardwareReconfigure=System.currentTimeMillis();
            state="Reallocating translation hardware between menu and world; disk translations retained";
        } else if (!(inferenceRunning || (startup!=null&&!startup.isDone()))
                && System.currentTimeMillis()-lastUse >= (economyMode?20_000:LocalResourcePolicy.IDLE_MILLIS)) {
            long idleMillis=economyMode?20_000:LocalResourcePolicy.IDLE_MILLIS;
            stop(); state="Local model unloaded after "+(idleMillis/1000)+" seconds idle; disk translations retained";
        }
    }

    public static CompletableFuture<List<String>> translate(List<String> sources) {
        return translate(sources,false,interactivePriorityGeneration.get());
    }
    public static CompletableFuture<List<String>> translateBackground(List<String> sources) {
        return translate(sources,true,interactivePriorityGeneration.get());
    }
    private static CompletableFuture<List<String>> translate(List<String> sources,boolean background,long priorityGeneration) {
        List<String> immutableSources = List.copyOf(sources);
        long generation=cancellationGeneration.get();
        return CompletableFuture.supplyAsync(() -> {
            if (!admitted(generation)) throw new java.util.concurrent.CancellationException("Translation paused");
            if (!available()) throw new CompletionException(new IOException(status()));
            inferenceRunning=true; lastUse=System.currentTimeMillis();
            translatingBackground=background; backgroundPriorityGeneration=priorityGeneration;
            if(background)lastBackgroundYielded=false;
            List<String> output = new ArrayList<>(immutableSources.size());
            try {
            for (String source : immutableSources) {
                if(backgroundMustYield()){lastBackgroundYielded=true;output.add(null);continue;}
                if(!admitted(generation)||!available()) {output.add(null);continue;}
                try { output.add(translateOne(source)); }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new CompletionException(exception);
                } catch (IOException | RuntimeException exception) {
                    // One bad source must not discard seven successful translations in a Normal batch.
                    output.add(null);
                }
            }
            return output;
            } finally { translatingBackground=false;backgroundPriorityGeneration=0;inferenceRunning=false; lastUse=System.currentTimeMillis(); }
        }, WORKER);
    }

    private static boolean startServer(Path directory) {
        return startServer(directory,true,cancellationGeneration.get(),false);
    }
    private static boolean startServer(Path directory,boolean acceleration,long generation) {
        return startServer(directory,acceleration,generation,false);
    }
    private static boolean startServer(Path directory,boolean acceleration,long generation,boolean compact) {
        stop();
        economyMode=compact;
        Path local = directory.resolve("local");
        Path runtime = local.resolve("runtime");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path executable = runtime.resolve(windows ? "llama-server.exe" : "llama-server");
        Path model = local.resolve("model.gguf");
        boolean gpuAttempt=false;
        try {
            checkAdmission(generation);
            detectedHardware=(!compact||acceleration&&TranslationEdition.current()==TranslationEdition.NORMAL)?AdaptiveHardwarePolicy.capture():new AdaptiveHardwarePolicy.Hardware(LocalResourcePolicy.totalMemoryMiB(),LocalResourcePolicy.freeMemoryMiB(),Runtime.getRuntime().availableProcessors(),-1,-1,0);
            // A small CUDA window is faster than falling back to CPU just because
            // the larger catalog window would leave too little VRAM for the game.
            if(!compact&&acceleration&&TranslationEdition.current()==TranslationEdition.NORMAL
                    &&AdaptiveHardwarePolicy.onDemandPlan(detectedHardware,false).gpuEligible()
                    &&!LocalGpuRuntime.hasCapacity(detectedHardware,4096))return startServer(directory,acceleration,generation,true);
            boolean candidateGpu=compact&&acceleration&&AdaptiveHardwarePolicy.onDemandPlan(detectedHardware,false).gpuEligible();
            if(!canStartProfile(LocalResourcePolicy.freeMemoryMiB(),compact,candidateGpu)) {state="Local translation paused to preserve game RAM";return false;}
            // One active local model per OS user across all instances and ports.
            leaseChannel=java.nio.channels.FileChannel.open(Path.of(System.getProperty("java.io.tmpdir"),"traduzmods-local-model.lock"),java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.WRITE);
            lease=leaseChannel.tryLock();
            if(lease==null) {leaseChannel.close();leaseChannel=null;state="Local translation waiting for the other modpack's model";return false;}
            LocalBootstrap.ensure(directory, progress -> state = progress);
            checkAdmission(generation);
            if(!canStartProfile(LocalResourcePolicy.freeMemoryMiB(),compact,candidateGpu))throw new IOException("Insufficient free RAM after preparing runtime");
            boolean allowGpu=acceleration&&(compact?candidateGpu:LocalResourcePolicy.MEMORY_LIMIT_MIB>=4096);
            long minimumGpuFree=compact?LocalResourcePolicy.ON_DEMAND_GPU_FREE_MIB:4096;
            var choice=LocalGpuRuntime.choose(directory,allowGpu,progress -> state=progress,detectedHardware,minimumGpuFree);
            checkAdmission(generation);
            runtime=choice.runtime();backend=choice.backend();gpuAttempt=choice.layers()>0;
            executable=runtime.resolve(windows ? "llama-server.exe" : "llama-server");
            reclaimStaleServer(local.resolve("owned-server.json"), executable);
            // Refuse to connect to any preexisting listener, even a compatible llama.cpp process.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
                state = "Local model unavailable: port " + PORT + " is already in use";
                stop();return false;
            } catch (IOException freePort) { /* Server itself verifies the bind. */ }
            Files.createDirectories(local);
            if(!canStartProfile(LocalResourcePolicy.freeMemoryMiB(),compact,gpuAttempt))throw new IOException("Insufficient free RAM after preparing runtime");
            // Recalculate RAM after any downloads. Launch watchdog and status use this exact budget.
            if(TranslationEdition.current()==TranslationEdition.NORMAL) {
                detectedHardware=AdaptiveHardwarePolicy.capture();
                if(choice.layers()>0 && !LocalGpuRuntime.hasCapacity(detectedHardware,minimumGpuFree)) {
                    choice=new LocalGpuRuntime.Choice(local.resolve("runtime"),"cpu",0);
                    runtime=choice.runtime();backend="cpu";gpuAttempt=false;
                    executable=runtime.resolve(windows?"llama-server.exe":"llama-server");
                    reclaimStaleServer(local.resolve("owned-server.json"),executable);
                }
            }
            long freeNow=LocalResourcePolicy.freeMemoryMiB();
            if(!canStartProfile(freeNow,compact,gpuAttempt))throw new IOException("Insufficient free RAM before native launch");
            detectedHardware=new AdaptiveHardwarePolicy.Hardware(detectedHardware.totalMemoryMiB(),freeNow,
                    detectedHardware.logicalThreads(),detectedHardware.gpuFreeMiB(),detectedHardware.gpuTotalMiB(),detectedHardware.gpuDriverMajor());
            currentGpuFreeMiB=detectedHardware.gpuFreeMiB();
            AdaptiveHardwarePolicy.Plan selected;
            if(compact) {
                var inferenceHardware=choice.layers()>0?detectedHardware:new AdaptiveHardwarePolicy.Hardware(detectedHardware.totalMemoryMiB(),freeNow,detectedHardware.logicalThreads(),-1,detectedHardware.gpuTotalMiB(),detectedHardware.gpuDriverMajor());
                selected=AdaptiveHardwarePolicy.onDemandPlan(inferenceHardware,AdaptiveHardwarePolicy.worldLoaded());
            } else if(TranslationEdition.current()==TranslationEdition.NORMAL) {
                var inferenceHardware=choice.layers()>0?detectedHardware:new AdaptiveHardwarePolicy.Hardware(
                        detectedHardware.totalMemoryMiB(),freeNow,detectedHardware.logicalThreads(),-1,
                        detectedHardware.gpuTotalMiB(),detectedHardware.gpuDriverMajor());
                selected=AdaptiveHardwarePolicy.plan(inferenceHardware,AdaptiveHardwarePolicy.worldLoaded());
            } else {
                var edition=TranslationEdition.LITE;
                selected=new AdaptiveHardwarePolicy.Plan(2048,edition.threads(),edition.contextTokens(),edition.batchTokens(),
                        edition.microBatchTokens(),choice.layers()>0,AdaptiveHardwarePolicy.worldLoaded());
            }
            activePlan=new AdaptiveHardwarePolicy.Plan(Math.min(LocalResourcePolicy.MEMORY_LIMIT_MIB,selected.memoryBudgetMiB()),
                    selected.threads(),selected.contextTokens(),selected.batchTokens(),selected.microBatchTokens(),
                    selected.gpuEligible(),selected.worldLoaded());
            LocalResourcePolicy.configureLaunchBudget(activePlan.memoryBudgetMiB());
            lastHardwareReconfigure=System.currentTimeMillis();
            Files.deleteIfExists(local.resolve("resource-guard.txt"));
            token = UUID.randomUUID().toString();
            List<String> arguments = new ArrayList<>(List.of("-m", model.toString(), "--host", "127.0.0.1", "--port", Integer.toString(PORT),
                    "--api-key", token, "--alias", "traduzmods-hy-mt2", "-c", Integer.toString(activePlan.contextTokens()), "-np", "1", "-ngl", Integer.toString(choice.layers()),"--split-mode","none","--main-gpu","0",
                    "-b", Integer.toString(activePlan.batchTokens()), "-ub", Integer.toString(activePlan.microBatchTokens()), "--cache-ram", "0", "--ctx-checkpoints", "0", "--no-cache-idle-slots",
                    "-t", Integer.toString(activePlan.threads()),
                    "--poll", "0", "--prio", "-1", "--no-webui"));
            // On full GPU offload, release file-backed host pages after uploading
            // weights. Measured with the pinned runtime; CPU retains mmap.
            if(choice.layers()>0) { arguments.add("--load-mode");arguments.add("none"); }
            state = "Loading local Hy-MT2 model";
            synchronized(LIFECYCLE){
            checkAdmission(generation);
            if(!canStartProfile(LocalResourcePolicy.freeMemoryMiB(),compact,gpuAttempt))throw new IOException("Insufficient free RAM at native launch");
            if (windows) {
                // Script is constant; paths and argv arrive as data through environment variables.
                // Start-Process Hidden avoids a console window when launched by Minecraft's javaw.
                String script = "$ErrorActionPreference='Stop'; "
                        + "$a=ConvertFrom-Json $env:TRADUZMODS_SERVER_ARGS; "
                        + "$quoted=$a | ForEach-Object { '\"' + $_.Replace('\"','\\\"') + '\"' }; "
                        + "$p=Start-Process -FilePath $env:TRADUZMODS_SERVER_EXE -ArgumentList ($quoted -join ' ') "
                        + "-WorkingDirectory $env:TRADUZMODS_SERVER_DIR -WindowStyle Hidden -PassThru "
                        + "-RedirectStandardOutput $env:TRADUZMODS_SERVER_OUT -RedirectStandardError $env:TRADUZMODS_SERVER_ERR; "
                        + "$record=[ordered]@{ serverPid=$p.Id; "
                        + "serverStartMillis=([DateTimeOffset]$p.StartTime.ToUniversalTime()).ToUnixTimeMilliseconds(); "
                        + "parentPid=[long]$env:TRADUZMODS_PARENT_PID; parentStartMillis=[long]$env:TRADUZMODS_PARENT_START; executable=$p.Path }; "
                        + "[IO.File]::WriteAllText($env:TRADUZMODS_SERVER_OWNERSHIP, ($record | ConvertTo-Json -Compress)); "
                        + "try { $watcher=Start-Process -FilePath 'powershell.exe' -WindowStyle Hidden -PassThru "
                        + "-ArgumentList @('-NoProfile','-NonInteractive','-EncodedCommand',$env:TRADUZMODS_WATCHDOG) "
                        + "-RedirectStandardOutput $env:TRADUZMODS_WATCHDOG_OUT -RedirectStandardError $env:TRADUZMODS_WATCHDOG_ERR; "
                        + "[IO.File]::WriteAllText($env:TRADUZMODS_SERVER_PID, [string]$p.Id) } "
                        + "catch { $p.Kill(); throw }";
                String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
                ProcessBuilder launcher = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded);
                launcher.environment().put("TRADUZMODS_SERVER_EXE", executable.toString());
                launcher.environment().put("TRADUZMODS_SERVER_DIR", runtime.toString());
                launcher.environment().put("TRADUZMODS_SERVER_OUT", local.resolve("server.stdout.log").toString());
                launcher.environment().put("TRADUZMODS_SERVER_ERR", local.resolve("server.stderr.log").toString());
                launcher.environment().put("TRADUZMODS_SERVER_OWNERSHIP", local.resolve("owned-server.json").toString());
                launcher.environment().put("TRADUZMODS_PARENT_PID", Long.toString(ProcessHandle.current().pid()));
                launcher.environment().put("TRADUZMODS_PARENT_START", Long.toString(ProcessHandle.current().info().startInstant()
                        .orElseThrow(() -> new IOException("Cannot prove Minecraft process ownership")).toEpochMilli()));
                launcher.environment().put("TRADUZMODS_WATCHDOG", Base64.getEncoder().encodeToString(WATCHDOG.getBytes(StandardCharsets.UTF_16LE)));
                launcher.environment().put("TRADUZMODS_MEMORY_LIMIT", Integer.toString(activePlan.memoryBudgetMiB()));
                launcher.environment().put("TRADUZMODS_RAM_RESERVE",Integer.toString(LocalResourcePolicy.runningReserveMiB(compact,gpuAttempt)));
                launcher.environment().put("TRADUZMODS_RESOURCE_GUARD", local.resolve("resource-guard.txt").toString());
                launcher.environment().remove("GGML_CUDA_DISABLE_GRAPHS");
                launcher.environment().put("TRADUZMODS_WATCHDOG_OUT", local.resolve("watchdog.stdout.log").toString());
                launcher.environment().put("TRADUZMODS_WATCHDOG_ERR", local.resolve("watchdog.stderr.log").toString());
                Path pidFile = local.resolve("server.pid");
                Files.deleteIfExists(pidFile);
                launcher.environment().put("TRADUZMODS_SERVER_PID", pidFile.toString());
                JsonArray array = new JsonArray();
                arguments.forEach(array::add);
                launcher.environment().put("TRADUZMODS_SERVER_ARGS", array.toString());
                launcher.redirectError(local.resolve("launcher.log").toFile());
                launcher.redirectOutput(ProcessBuilder.Redirect.DISCARD);
                Process shell = launcher.start();
                if (!shell.waitFor(20, TimeUnit.SECONDS)) {
                    shell.destroyForcibly();
                    throw new IOException("Hidden local server launcher timed out");
                }
                if (shell.exitValue() != 0) throw new IOException("Hidden local server launcher failed; see local/launcher.log");
                long pid = Long.parseLong(Files.readString(pidFile).trim());
                ownedProcess = ProcessHandle.of(pid).orElseThrow(() -> new IOException("Local server exited during launch"));
            } else {
                List<String> command = new ArrayList<>();
                command.add(executable.toString());
                command.addAll(arguments);
                ProcessBuilder nativeBuilder = new ProcessBuilder(command).directory(runtime.toFile());
                nativeBuilder.environment().remove("GGML_CUDA_DISABLE_GRAPHS");
                ownedProcess = nativeBuilder.redirectOutput(local.resolve("server.stdout.log").toFile())
                        .redirectError(local.resolve("server.stderr.log").toFile()).start().toHandle();
            }
            ownedStart = ownedProcess.info().startInstant().orElse(null);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            while (System.nanoTime() < deadline) {
                checkAdmission(generation);
                if (!isOwnedAlive()) throw new IOException("Local server exited; see local/server.stderr.log");
                try {
                    Response response = send("/v1/models", null, 2000);
                    if (response.statusCode() == 200 && response.body().contains("traduzmods-hy-mt2")) {
                        synchronized(LIFECYCLE){checkAdmission(generation);ready = true;}
                        lastUse=System.currentTimeMillis();
                        state = compact?"Local Hy-MT2 model ready ("+backend+" on-demand)":"Local Hy-MT2 model ready ("+backend+")";
                        return true;
                    }
                    state = "Loading local model; readiness HTTP " + response.statusCode();
                } catch (IOException loading) { state = "Loading local model; " + loading.getMessage(); }
                Thread.sleep(500);
            }
            throw new IOException("Local model did not become ready within 120 seconds");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            state = "Local model startup interrupted";
        } catch (Exception exception) {
            state = "Local model unavailable: " + exception.getMessage();
        }
        boolean resourceCut=wasStoppedByResourceGuard(local);
        stop();
        if(resourceCut) {state="Local model unloaded by RAM guard; checking available resources";return false;}
        if(admitted(generation)&&gpuAttempt&&!Thread.currentThread().isInterrupted()&&canStartProfile(LocalResourcePolicy.freeMemoryMiB(),compact))return startServer(directory,false,generation,compact);
        return false;
    }

    private static boolean wasStoppedByResourceGuard(Path local) {
        try {
            if(ownedProcess==null||!Files.isRegularFile(local.resolve("resource-guard.txt")))return false;
            JsonObject record=JsonParser.parseString(Files.readString(local.resolve("resource-guard.txt"))).getAsJsonObject();
            return record.get("serverPid").getAsLong()==ownedProcess.pid()
                    &&ownedStart!=null&&record.get("serverStartMillis").getAsLong()==ownedStart.toEpochMilli();
        } catch(Exception invalid) {return false;}
    }

    private record Response(int statusCode, String body) {}

    private static void reclaimStaleServer(Path ownership, Path executable) throws IOException, InterruptedException {
        if (!Files.isRegularFile(ownership) || Files.size(ownership) > 16_384) return;
        try {
            JsonObject record = JsonParser.parseString(Files.readString(ownership)).getAsJsonObject();
            long parentPid = record.get("parentPid").getAsLong();
            long parentStart = record.get("parentStartMillis").getAsLong();
            long serverPid = record.get("serverPid").getAsLong();
            long serverStart = record.get("serverStartMillis").getAsLong();
            Path recordedExecutable = Path.of(record.get("executable").getAsString());
            ProcessHandle parent = ProcessHandle.of(parentPid).orElse(null);
            if (parent != null && parent.isAlive() && parent.info().startInstant().map(time -> time.toEpochMilli() == parentStart).orElse(true)) return;
            ProcessHandle server = ProcessHandle.of(serverPid).orElse(null);
            if (server == null || !server.isAlive()) { Files.deleteIfExists(ownership); return; }
            if (!server.info().startInstant().map(time -> time.toEpochMilli() == serverStart).orElse(false)) return;
            String command = server.info().command().orElse(null);
            if (command == null || !Files.isSameFile(recordedExecutable, executable) || !Files.isSameFile(Path.of(command), executable)) return;
            server.destroy();
            try { server.onExit().get(3, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) {
                if (server.info().startInstant().map(time -> time.toEpochMilli() == serverStart).orElse(false)) server.destroyForcibly();
            } catch (java.util.concurrent.ExecutionException failure) { throw new IOException("Cannot reclaim owned stale server", failure); }
            Files.deleteIfExists(ownership);
        } catch (RuntimeException malformedRecord) { /* Unproven ownership is never a reason to kill a process. */ }
    }

    private static Response send(String path, String payload, int timeout) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(BASE + path).toURL().openConnection(java.net.Proxy.NO_PROXY);
        try {
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Content-Type", "application/json");
            if (payload != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (var output = connection.getOutputStream()) { output.write(bytes); }
            }
            int statusCode = connection.getResponseCode();
            var stream = statusCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return new Response(statusCode, "");
            try (stream) { return new Response(statusCode, new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        } finally { connection.disconnect(); }
    }

    private static String translateOne(String source) throws IOException, InterruptedException {
        checkInteractivePriority();
        if (source.isBlank()) return source;
        if (!available()) throw new IOException(status());
        if (source.startsWith("<mt0>")) return translateStyled(source);
        // FTB stores escaped line separators as literal backslash+n. Keep them
        // intact, but translate each complete paragraph with all its styles.
        var lines = java.util.regex.Pattern.compile("\\r\\n|\\r|\\n|\\\\r\\\\n|\\\\n|\\\\r").matcher(source);
        if (!lines.find()) {
            TextProtection.ProtectedText formatted = TextProtection.protect(source);
            return formatted.formatting()!=null?formatted.restore(translateStyled(formatted.value())):translateSegment(source);
        }
        lines.reset();
        StringBuilder result = new StringBuilder();
        java.util.Map<String, String> repeated = new java.util.HashMap<>();
        int offset = 0;
        while (lines.find()) {
            String line = source.substring(offset, lines.start());
            String translated = repeated.get(line);
            if (translated == null) {
                translated = translateOne(line);
                repeated.put(line, translated);
            }
            result.append(translated).append(lines.group());
            offset = lines.end();
        }
        result.append(translateOne(source.substring(offset)));
        return result.toString();
    }

    private static String translateStyled(String source) throws IOException, InterruptedException {
        // The small CPU model can retain valid XML while translating each tag
        // independently. Translate prose without style tags, then align its styles.
        String plain=StyledTranslationAlignment.plain(source);
        String translated=translateSegment(plain);
        return StyledTranslationAlignment.align(source,translated,GameTranslationContext::nativeLabel);
    }

    private static String translateSegment(String source) throws IOException, InterruptedException {
        checkInteractivePriority();
        if (source.isBlank()) return source;
        TextProtection.ProtectedText protectedText = TextProtection.protect(source);
        StringBuilder translation = new StringBuilder();
        String value = protectedText.value();
        int offset = 0;
        var markerPattern = java.util.regex.Pattern.compile("MODTRPHX*\\d+X");
        while (offset < value.length()) {
            checkInteractivePriority();
            // Keep original separators between chunks and never split a protected marker.
            int chunkLimit=economyMode?300:translatingBackground?400:TranslationEdition.current().chunkCharacters();
            int end = Math.min(value.length(), offset + chunkLimit);
            if (end < value.length()) {
                int space = value.lastIndexOf(' ', end);
                if (space > offset + 250) end = space;
                var matcher = markerPattern.matcher(value);
                while (matcher.find()) {
                    if (matcher.start() < end && matcher.end() > end) { end = matcher.start(); break; }
                }
                if (end <= offset) throw new IOException("Cannot split oversized protected token");
            }
            String chunk = value.substring(offset, end);
            int lead = 0;
            int tail = chunk.length();
            while (lead < tail && Character.isWhitespace(chunk.charAt(lead))) lead++;
            while (tail > lead && Character.isWhitespace(chunk.charAt(tail - 1))) tail--;
            translation.append(chunk, 0, lead);
            if (tail > lead) translation.append(translateChunk(chunk.substring(lead, tail)));
            translation.append(chunk, tail, chunk.length());
            offset = end;
        }
        try {
            String restored = protectedText.restore(translation.toString());
            if (restored.equals(source) && !protectedText.markers().isEmpty()) {
                throw new IllegalArgumentException("Formatted text was left unchanged; try literal spans");
            }
            return restored;
        }
        catch (IllegalArgumentException unsafe) {
            if (protectedText.markers().isEmpty()) throw unsafe;
            // A small model can drop markers in dense UI text. Translate only the literal
            // spans in a second pass, inserting original tokens ourselves instead.
            StringBuilder safe = new StringBuilder();
            java.util.Map<String, String> repeated = new java.util.HashMap<>();
            var matcher = markerPattern.matcher(value);
            int previous = 0;
            while (matcher.find()) {
                appendLiteral(safe, value.substring(previous, matcher.start()), repeated);
                int index = protectedText.markers().indexOf(matcher.group());
                if (index < 0) throw new IOException("Unexpected protected marker");
                safe.append(protectedText.originals().get(index));
                previous = matcher.end();
            }
            appendLiteral(safe, value.substring(previous), repeated);
            // Re-protect and roundtrip to validate no formatting was invented in spans.
            TextProtection.ProtectedText result = TextProtection.protect(safe.toString());
            if (!new java.util.HashMap<>(result.originals().stream().collect(java.util.stream.Collectors.groupingBy(
                    java.util.function.Function.identity(), java.util.stream.Collectors.counting())))
                    .equals(protectedText.originals().stream().collect(java.util.stream.Collectors.groupingBy(
                            java.util.function.Function.identity(), java.util.stream.Collectors.counting())))) {
                throw new IOException("Local translation changed formatting in literal span");
            }
            return safe.toString();
        }
    }

    private static void appendLiteral(StringBuilder output, String literal, java.util.Map<String, String> repeated)
            throws IOException, InterruptedException {
        String value = repeated.get(literal);
        if (value == null) {
            int lead = 0;
            int tail = literal.length();
            while (lead < tail && Character.isWhitespace(literal.charAt(lead))) lead++;
            while (tail > lead && Character.isWhitespace(literal.charAt(tail - 1))) tail--;
            String body = literal.substring(lead, tail);
            String translated = body.isBlank() ? "" : translateSegment(body);
            value = literal.substring(0, lead) + translated + literal.substring(tail);
            repeated.put(literal, value);
        }
        output.append(value);
    }

    private static String translateChunk(String chunk) throws IOException, InterruptedException {
        checkInteractivePriority();
        String prompt=TranslationPrompt.build(chunk,economyMode);
        String reused=PARAGRAPHS.get(prompt);
        if(reused!=null){paragraphReuse++;return reused;}
        JsonObject message = new JsonObject();
        message.addProperty("role", "user");
        message.addProperty("content", prompt);
        JsonArray messages = new JsonArray();
        messages.add(message);
        JsonObject payload = new JsonObject();
        payload.addProperty("model", "traduzmods-hy-mt2");
        payload.add("messages", messages);
        payload.addProperty("temperature", 0.7);
        payload.addProperty("top_p", 0.6);
        payload.addProperty("top_k", 20);
        payload.addProperty("repeat_penalty", 1.05);
        payload.addProperty("max_tokens", Math.min(economyMode?384:TranslationEdition.current().maxOutputTokens(), Math.max(96, chunk.length() * 2)));
        payload.addProperty("stream", false);
        payload.addProperty("cache_prompt",true);
        checkInteractivePriority();
        Response response = send("/v1/chat/completions", payload.toString(), 180000);
        if (response.statusCode() != 200) throw new IOException("Local translation returned HTTP " + response.statusCode());
        JsonObject choice = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("choices").get(0).getAsJsonObject();
        if (!choice.has("finish_reason") || !"stop".equals(choice.get("finish_reason").getAsString())) {
            throw new IOException("Local translation was truncated; source retained for retry");
        }
        String result = choice.getAsJsonObject("message").get("content").getAsString().strip();
        // Do not memoize unsafe markers: restoration must still be able to use
        // its literal-span fallback when the model omits a protected token.
        if(!result.isBlank()&&markers(chunk).equals(markers(result)))PARAGRAPHS.put(prompt,result);
        return result;
    }

    private static java.util.Map<String,Long> markers(String text) {
        var matcher=java.util.regex.Pattern.compile("MODTRPHX*\\d+X").matcher(text);
        java.util.Map<String,Long> counts=new java.util.HashMap<>();
        while(matcher.find())counts.merge(matcher.group(),1L,Long::sum);
        return counts;
    }

    private static boolean isOwnedAlive() {
        ProcessHandle process = ownedProcess;
        return process != null && process.isAlive()
                && (ownedStart == null || process.info().startInstant().map(ownedStart::equals).orElse(false));
    }

    /** Stops only the process started by this JVM, never a process found by name or port. */
    public static void stop() {synchronized(LIFECYCLE){stopOwned();}}
    private static void stopOwned() {
        ready = false;
        ProcessHandle process = ownedProcess;
        if (process != null && isOwnedAlive()) {
            process.destroy();
            try { process.onExit().get(3, TimeUnit.SECONDS); }
            catch (Exception ignored) { if (isOwnedAlive()) process.destroyForcibly(); }
        }
        ownedProcess = null;
        ownedStart = null;
        try {if(lease!=null)lease.close();}catch(IOException ignored){}
        try {if(leaseChannel!=null)leaseChannel.close();}catch(IOException ignored){}
        lease=null;leaseChannel=null;
    }
}
