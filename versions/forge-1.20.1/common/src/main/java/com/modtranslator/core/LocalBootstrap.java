package com.modtranslator.core;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.ZipInputStream;

/** Automatic, pinned, SHA-256 checked installation of the free local translation backend. */
public final class LocalBootstrap {
    public static final String RUNTIME_TAG = "b11429";
    public static final String RUNTIME_SHA256 = "1283323272b04cd07905816a597a0da810918102de958f4ff6f7bbaa70ed2efe";
    public static final String MODEL_REVISION = "a0c709d9fac510f2c807aa3af52872340dc37a4a";
    public static final String MODEL_SHA256 = "dc5f44fcf1fa496ee7ad725982c0c8c553a4de00259b53af84c4b89fb0c06699";
    public static final long MODEL_BYTES = 1_133_080_448L;
    private static final long RUNTIME_BYTES = 19_398_918L;
    private static final String RUNTIME_URL = "https://github.com/ggml-org/llama.cpp/releases/download/" + RUNTIME_TAG
            + "/llama-" + RUNTIME_TAG + "-bin-win-cpu-x64.zip";
    private static final String MODEL_URL = "https://huggingface.co/tencent/Hy-MT2-1.8B-GGUF/resolve/"
            + MODEL_REVISION + "/Hy-MT2-1.8B-Q4_K_M.gguf";

    private LocalBootstrap() {}

    /** Called only by the background local-provider worker; the game thread never downloads. */
    public static void ensure(Path directory, Consumer<String> status) throws IOException {
        Path local = directory.toAbsolutePath().normalize().resolve("local");
        Path runtime = local.resolve("runtime");
        Path model = local.resolve("model.gguf");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path executable = runtime.resolve(windows ? "llama-server.exe" : "llama-server");
        Files.createDirectories(local);
        if (!Files.isRegularFile(executable)) {
            if (!windows || !(System.getProperty("os.arch", "").equals("amd64") || System.getProperty("os.arch", "").equals("x86_64"))) {
                throw new IOException("Automatic local runtime currently supports Windows x64; no compatible runtime installed");
            }
            Path zip = local.resolve("llama-" + RUNTIME_TAG + "-cpu.zip");
            download(RUNTIME_URL, zip, RUNTIME_BYTES, RUNTIME_SHA256, status, "local runtime");
            extractRuntime(zip, runtime);
            if (!Files.isRegularFile(executable)) throw new IOException("Runtime archive has no llama-server.exe");
            Files.writeString(local.resolve("runtime-version.txt"), RUNTIME_TAG + "\nsha256:" + RUNTIME_SHA256 + "\n");
        }
        if (Files.isRegularFile(model)) {
            status.accept("Validating local Hy-MT2 model");
            if (Files.size(model) == MODEL_BYTES && sha256(model).equals(MODEL_SHA256)) return;
            throw new IOException("Existing local/model.gguf does not match the pinned Hy-MT2 model; preserving existing file");
        }
        download(MODEL_URL, model, MODEL_BYTES, MODEL_SHA256, status, "Hy-MT2 model");
        Files.writeString(local.resolve("model-version.txt"), "tencent/Hy-MT2-1.8B-GGUF\nrevision:" + MODEL_REVISION
                + "\nsha256:" + MODEL_SHA256 + "\nApache-2.0\n");
    }

    static void download(String url, Path target, long expectedBytes, String expectedHash,
                                 Consumer<String> status, String label) throws IOException {
        if (Files.isRegularFile(target) && Files.size(target) == expectedBytes && sha256(target).equals(expectedHash)) return;
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        status.accept("Downloading " + label + " automatically (" + (expectedBytes / 1_048_576) + " MiB)");
        HttpURLConnection connection = connect(URI.create(url));
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Backend download returned HTTP " + connection.getResponseCode());
            long contentLength = connection.getContentLengthLong();
            if (contentLength > 0 && contentLength != expectedBytes) throw new IOException("Backend download size differs from pinned manifest");
            MessageDigest digest = digest();
            long total = 0;
            long progressAt = 0;
            try (InputStream input = connection.getInputStream(); var output = Files.newOutputStream(partial)) {
                byte[] buffer = new byte[256 * 1024];
                for (int count; (count = input.read(buffer)) >= 0;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Backend download interrupted");
                    total += count;
                    if (total > expectedBytes) throw new IOException("Backend download exceeded pinned size");
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    if (total - progressAt >= 8 * 1_048_576L) {
                        status.accept("Downloading " + label + ": " + (total * 100 / expectedBytes) + "%");
                        progressAt = total;
                    }
                }
            }
            if (total != expectedBytes || !HexFormat.of().formatHex(digest.digest()).equals(expectedHash)) {
                throw new IOException("Backend download failed SHA-256 or size validation");
            }
            move(partial, target);
        } finally {
            connection.disconnect();
            Files.deleteIfExists(partial);
        }
    }

    private static HttpURLConnection connect(URI initial) throws IOException {
        URI uri = initial;
        for (int redirects = 0; redirects < 8; redirects++) {
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || !allowedHost(host.toLowerCase(Locale.ROOT))) {
                throw new IOException("Backend download redirected outside trusted HTTPS hosts");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection(Proxy.NO_PROXY);
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "TraduzMods/0.3.0");
            int status = connection.getResponseCode();
            if (status >= 300 && status <= 399) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Backend download redirect has no location");
                uri = uri.resolve(location);
            } else return connection;
        }
        throw new IOException("Backend download exceeded redirect limit");
    }

    private static boolean allowedHost(String host) {
        return host.equals("github.com") || host.equals("objects.githubusercontent.com")
                || host.equals("release-assets.githubusercontent.com") || host.equals("huggingface.co")
                || host.endsWith(".huggingface.co") || host.endsWith(".hf.co") || host.equals("hf.co")
                || host.endsWith(".xethub.hf.co");
    }

    private static void extractRuntime(Path archive, Path runtime) throws IOException {
        extractRuntime(archive,runtime,160 * 1_048_576L);
    }
    static void extractRuntime(Path archive, Path runtime,long maximumBytes) throws IOException {
        Files.createDirectories(runtime);
        Path root = runtime.toAbsolutePath().normalize();
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 400) throw new IOException("Runtime ZIP contains too many entries");
                Path target = root.resolve(entry.getName()).normalize();
                if (!target.startsWith(root) || target.equals(root)) throw new IOException("Runtime ZIP path escapes target directory");
                if (entry.isDirectory()) { Files.createDirectories(target); continue; }
                Files.createDirectories(target.getParent());
                Path partial = target.resolveSibling(target.getFileName() + ".part");
                try (var output = Files.newOutputStream(partial)) {
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = zip.read(buffer)) >= 0;) {
                        total += count;
                        if (total > maximumBytes) throw new IOException("Runtime ZIP exceeds extraction size limit");
                        output.write(buffer, 0, count);
                    }
                }
                move(partial, target);
                zip.closeEntry();
            }
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = Files.newInputStream(path)) {
            byte[] bytes = new byte[256 * 1024];
            for (int count; (count = input.read(bytes)) >= 0;) digest.update(bytes, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
