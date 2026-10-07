package de.kronwerke.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Messages to a webhook when something needs a person: a crash, a server that gave up, a tick
 * time that stays high, backups. A Discord webhook gets Discord's shape, any other https URL a
 * JSON object with text, kind and server (Slack and most chat tools take that). The URL is a
 * secret and stays in console/alerts.json.
 */
public final class Alerts {
    public static final List<String> KINDS = List.of("crash", "down", "mspt", "backup", "backup-failed", "start", "stop");

    private final Fleet fleet;
    private final Path file;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Map<String, Long> last = new ConcurrentHashMap<>();
    private volatile Map<String, Object> cfg = Json.map();

    Alerts(Fleet fleet) {
        this.fleet = fleet;
        this.file = fleet.home().resolve("console").resolve("alerts.json");
        try {
            if (Files.exists(file)) cfg = Json.object(Files.readString(file));
        } catch (IOException | RuntimeException e) {
            fleet.note("alerts.json could not be read: " + e.getMessage());
        }
    }

    /** The settings without the URL itself, for the console. */
    public Map<String, Object> state() {
        String url = Json.str(cfg, "webhook", "");
        return Json.map("set", !url.isEmpty(), "host", url.isEmpty() ? "" : URI.create(url).getHost(),
                "events", cfg.getOrDefault("events", List.of("crash", "down", "mspt", "backup-failed")),
                "mspt", Json.num(cfg, "mspt", 50));
    }

    /** Saves the settings; an empty webhook keeps the one there, "-" removes it. */
    public synchronized void set(String webhook, List<String> events, long mspt) throws IOException {
        Map<String, Object> next = Json.map();
        String url = webhook == null || webhook.isEmpty() ? Json.str(cfg, "webhook", "") : webhook.equals("-") ? "" : webhook.trim();
        if (!url.isEmpty() && (!url.startsWith("https://") || url.length() > 500 || url.contains(" "))) throw new IllegalArgumentException("an https URL");
        next.put("webhook", url);
        List<Object> ev = new ArrayList<>();
        for (String e : events) {
            if (!KINDS.contains(e)) throw new IllegalArgumentException("unknown event " + e);
            ev.add(e);
        }
        next.put("events", ev);
        if (mspt < 20 || mspt > 1000) throw new IllegalArgumentException("tick time 20 to 1000 ms");
        next.put("mspt", mspt);
        Files.createDirectories(file.getParent());
        Files.writeString(file, Json.write(next));
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not POSIX
        }
        cfg = next;
    }

    public long mspt() {
        return Json.num(cfg, "mspt", 50);
    }

    /** Sends when this kind is switched on, at most once in five minutes per kind and server (30 for tick time). */
    public void send(String kind, String server, String text) {
        String url = Json.str(cfg, "webhook", "");
        if (url.isEmpty()) return;
        Object ev = cfg.getOrDefault("events", List.of("crash", "down", "mspt", "backup-failed"));
        if (!(ev instanceof List<?> l) || !l.contains(kind)) return;
        String key = kind + "|" + server;
        long now = System.currentTimeMillis(), gap = kind.equals("mspt") ? 30 * 60_000 : 5 * 60_000;
        Long before = last.get(key);
        if (before != null && now - before < gap && !kind.startsWith("backup")) return;
        last.put(key, now);
        fleet.submit(() -> post(url, kind, server, text));
    }

    /** A test message, whatever is switched on. */
    public String test() throws Exception {
        String url = Json.str(cfg, "webhook", "");
        if (url.isEmpty()) throw new IllegalArgumentException("no webhook set");
        return post(url, "test", null, fleet.config().get("console.language").equals("de") ? "Test aus der Console: Meldungen kommen hier an." : "Test from the console: alerts arrive here.");
    }

    private String post(String url, String kind, String server, String text) {
        String name = fleet.config().get("name").isEmpty() ? "Launcher" : fleet.config().get("name");
        String line = (server == null ? "" : "**" + server + "**: ") + text;
        Object body = url.matches("https://(canary\\.|ptb\\.)?discord(app)?\\.com/api/webhooks/.*")
                ? Json.map("username", name, "content", line.length() > 1900 ? line.substring(0, 1900) : line, "allowed_mentions", Json.map("parse", List.of()))
                : Json.map("text", name + ": " + line.replace("**", ""), "kind", kind, "server", server);
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                fleet.event("alert", server, "webhook answered " + r.statusCode());
                return "HTTP " + r.statusCode();
            }
            return "sent";
        } catch (IOException | InterruptedException | RuntimeException e) {
            fleet.event("alert", server, "webhook failed: " + e.getMessage());
            return e.getMessage();
        }
    }
}
