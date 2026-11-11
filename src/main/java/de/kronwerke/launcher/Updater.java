package de.kronwerke.launcher;

import de.kronwerke.boot.Boot;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

/**
 * Installs a new launcher from a release. The jar goes to the launcher folder, named by its
 * checksum, and becomes "current"; the jar the panel starts is replaced too, so a fresh
 * container starts the same version. With reload the new version takes over at once and the
 * servers keep running.
 */
public final class Updater {
    public static final String REPO = "kronwerke/launcher";
    /**
     * The Ed25519 public key the release workflow signs SHA256SUMS with (SHA256SUMS.sig). A
     * release without a valid signature is never installed, whoever asks for it: the console,
     * the bot over the link, or someone who could publish a release on the repository.
     */
    public static final String RELEASE_KEY = "ZEQjgaExXr3UULiDXHjYaMVAEt2tD3vupI37zW/934k=";

    /** Where releases come from: update.repo (owner/name on GitHub), this project by default. */
    public static String prefix(Config cfg) {
        String repo = cfg.get("update.repo").isEmpty() ? REPO : cfg.get("update.repo");
        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("update.repo is owner/name");
        return "https://github.com/" + repo + "/releases/download/";
    }
    static final int KEEP = 3;

    private Updater() {
    }

    public static Object install(Fleet fleet, String from, String sha256, boolean reload) throws Exception {
        String prefix = prefix(fleet.config());
        if (!from.startsWith(prefix)) throw new IllegalArgumentException("updates only come from " + prefix);
        // the checksum that counts is the one in the signed SHA256SUMS of the same release
        String base = from.substring(0, from.lastIndexOf('/') + 1);
        String name = from.substring(from.lastIndexOf('/') + 1);
        String signed = signedSum(base, name);
        if (!sha256.isEmpty() && !sha256.equals(signed)) throw new IOException("the checksum asked for is not the one the release signed");
        sha256 = signed;
        Path own = Boot.ownJar();
        if (own == null) throw new IllegalStateException("not running from a jar");
        Path dir = jars(fleet.home());
        Files.createDirectories(dir);
        Path jar = dir.resolve("launcher-" + sha256.substring(0, 12) + ".jar");
        Path tmp = dir.resolve(jar.getFileName() + ".part");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(from)).timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("HTTP " + r.statusCode());
        }
        String got = sha256(tmp);
        if (!got.equals(sha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("checksum mismatch: " + got);
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        // the panel's jar: a new file under the old name, the running JVM keeps the old one open
        Path ownTmp = own.resolveSibling(own.getFileName() + ".new");
        Files.copy(jar, ownTmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(ownTmp, own, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.writeString(dir.resolve("current"), jar.getFileName() + "\n");
        prune(dir, jar);
        fleet.event("launcher", null, "launcher " + jar.getFileName() + " installed");
        if (!reload) return "installed, active from the next reload or start of the container";
        fleet.submit(() -> {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException ignored) {
                // reload anyway
            }
            fleet.reload();
        });
        return "installed, reloading now; the servers keep running";
    }

    /** The checksum of a file of a release, from its SHA256SUMS after the signature over it checked out. */
    static String signedSum(String base, String file) throws Exception {
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        byte[] sums = fetch(http, base + "SHA256SUMS", 1 << 16);
        byte[] sig = fetch(http, base + "SHA256SUMS.sig", 1024);
        if (!verify(sums, sig, RELEASE_KEY)) throw new IOException("the release is not signed with the Kronwerke release key, not installing");
        for (String l : new String(sums, java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            String[] f = l.trim().split("\\s+");
            if (f.length == 2 && f[1].replace("*", "").equals(file) && f[0].matches("[0-9a-f]{64}")) return f[0];
        }
        throw new IOException("the signed SHA256SUMS has no line for " + file);
    }

    /** Ed25519 over the bytes, with a raw 32 byte public key in base64. */
    static boolean verify(byte[] data, byte[] sig, String rawKey) {
        try {
            byte[] raw = java.util.Base64.getDecoder().decode(rawKey);
            byte[] der = new byte[12 + raw.length];
            System.arraycopy(HexFormat.of().parseHex("302a300506032b6570032100"), 0, der, 0, 12);
            System.arraycopy(raw, 0, der, 12, raw.length);
            java.security.PublicKey key = java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(der));
            java.security.Signature v = java.security.Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(data);
            return v.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] fetch(HttpClient http, String url, int limit) throws Exception {
        HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(1)).build(), HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IOException(url.substring(url.lastIndexOf('/') + 1) + ": HTTP " + r.statusCode());
        if (r.body().length > limit) throw new IOException(url.substring(url.lastIndexOf('/') + 1) + " is too large");
        return r.body();
    }

    /** Where installed launcher jars live; Boot looks in the same places. */
    static Path jars(Path home) {
        return Files.exists(home.resolve("launcher/current")) ? home.resolve("launcher") : home.resolve("jars");
    }

    static String sha256(Path file) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
    }

    /** Keeps the newest few jars, so a reload can still fall back. */
    static void prune(Path dir, Path keep) throws IOException {
        List<Path> jars;
        try (Stream<Path> s = Files.list(dir)) {
            jars = s.filter(p -> p.getFileName().toString().matches("launcher-[0-9a-f]{12}\\.jar") && !p.equals(keep))
                    .sorted(Comparator.comparingLong(Updater::modified).reversed()).toList();
        }
        for (int i = KEEP - 1; i < jars.size(); i++) Files.deleteIfExists(jars.get(i));
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }
}
