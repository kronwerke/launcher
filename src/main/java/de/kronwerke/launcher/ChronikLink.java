package de.kronwerke.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * The console's view of Chronik, the block and container log in every Minecraft server.
 * Chronik answers lookups on 127.0.0.1 only, with a token; the launcher starts every server
 * with the port (its RCON port plus 1000) and the launcher's own token in the environment. A lookup in the console asks every running server at once
 * and merges the rows by time, with the server as a column (review R3-08: following a
 * griefer into the Nether meant joining every server and typing /co l there).
 */
public final class ChronikLink {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private ChronikLink() {
    }

    /** The launcher's token for Chronik, made once and kept next to launcher.properties. */
    static synchronized String token(Path home) throws IOException {
        Path f = home.resolve("chronik.token");
        if (Files.exists(f)) {
            String t = Files.readString(f, StandardCharsets.UTF_8).trim();
            if (t.length() >= 32) return t;
        }
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        String t = HexFormat.of().formatHex(b);
        Files.writeString(f, t + "\n", StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        return t;
    }

    /** The port Chronik listens on for this server: its RCON port plus 1000, or 0 without one. */
    static int port(Server s) {
        try {
            String rcon = Rcon.property(s.properties(), "rcon.port");
            int p = (rcon.isEmpty() ? 25575 : Integer.parseInt(rcon)) + 1000;
            return p > 0 && p < 65536 ? p : 0;
        } catch (IOException | NumberFormatException e) {
            return 0;
        }
    }

    /** Whether the server has Chronik in its mods folder. */
    static boolean installed(Path dir) {
        try (Stream<Path> s = Files.list(dir.resolve("mods"))) {
            return s.anyMatch(p -> p.getFileName().toString().toLowerCase().startsWith("chronik") && p.toString().endsWith(".jar"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Before a start: Chronik's port and token for this server, as CHRONIK_WEB_PORT and CHRONIK_WEB_TOKEN. */
    static void environment(Server s, Map<String, String> env, Path home) {
        if (!installed(s.dir())) return;
        int port = port(s);
        if (port == 0) return;
        try {
            env.put("CHRONIK_WEB_PORT", Integer.toString(port));
            env.put("CHRONIK_WEB_TOKEN", token(home));
        } catch (IOException e) {
            s.note("Chronik's console view not set up: " + e.getMessage());
        }
    }

    /**
     * Asks every running Minecraft server with Chronik for the query and merges the rows,
     * newest first. Servers that do not answer are named in "missing".
     */
    public static Map<String, Object> lookup(Fleet fleet, String q, String only, int limit) throws IOException {
        String token = token(fleet.home());
        Map<String, CompletableFuture<HttpResponse<String>>> asks = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (Server s : fleet.servers()) {
            if (!s.minecraft() || (!only.isEmpty() && !only.equals(s.name()))) continue;
            if (s.state() != Server.State.RUNNING || !installed(s.dir())) continue;
            int port = port(s);
            if (port == 0) continue;
            URI uri = URI.create("http://127.0.0.1:" + port + "/lookup?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8) + "&limit=" + limit);
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(35)).header("Authorization", "Bearer " + token).GET().build();
            asks.put(s.name(), HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        String error = null;
        for (var e : asks.entrySet()) {
            try {
                HttpResponse<String> res = e.getValue().join();
                Map<String, Object> body = Json.object(res.body());
                if (res.statusCode() == 400) {
                    error = Json.str(body, "error", "invalid query"); // the same query is wrong everywhere
                    continue;
                }
                if (res.statusCode() != 200) {
                    missing.add(e.getKey());
                    continue;
                }
                if (body.get("rows") instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> m) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> row = new LinkedHashMap<>((Map<String, Object>) m);
                            row.put("server", e.getKey());
                            rows.add(row);
                        }
                    }
                }
            } catch (RuntimeException ex) {
                missing.add(e.getKey()); // not listening yet (Chronik before the first restart with a port)
            }
        }
        if (error != null && rows.isEmpty()) throw new IllegalArgumentException(error);
        rows.sort((a, b) -> Long.compare(num(b.get("time")), num(a.get("time"))));
        if (rows.size() > limit) rows = new ArrayList<>(rows.subList(0, limit));
        return Json.map("rows", rows, "missing", missing, "asked", new ArrayList<>(asks.keySet()));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
