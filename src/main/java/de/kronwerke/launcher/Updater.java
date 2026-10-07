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
 * Installs a new launcher from a release. The jar goes to kronwerke/launcher, named by its
 * checksum, and becomes "current"; the jar the panel starts is replaced too, so a fresh
 * container starts the same version. With reload the new version takes over at once and the
 * servers keep running.
 */
public final class Updater {
    static final String PREFIX = "https://github.com/kronwerke/launcher/releases/download/";
    static final int KEEP = 3;

    private Updater() {
    }

    public static Object install(Fleet fleet, String from, String sha256, boolean reload) throws Exception {
        if (!from.startsWith(PREFIX)) throw new IllegalArgumentException("updates only come from " + PREFIX);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 missing");
        Path own = Boot.ownJar();
        if (own == null) throw new IllegalStateException("not running from a jar");
        Path dir = fleet.root().resolve("kronwerke/launcher");
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
