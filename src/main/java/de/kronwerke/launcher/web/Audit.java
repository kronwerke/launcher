package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every change made through the console, by anyone: who, from where, what, on which server,
 * and whether it worked. One JSON object per line in kronwerke/console/audit.jsonl.
 */
final class Audit {
    private final Path file;

    Audit(Path file) {
        this.file = file;
    }

    synchronized void add(Access.Who who, String ip, String action, String server, String detail, boolean ok) {
        Map<String, Object> e = Json.map("t", Instant.now().toString(), "who", who == null ? "" : who.name(),
                "via", who == null ? "" : who.kind(), "ip", ip, "action", action, "server", server, "detail",
                detail == null ? "" : detail.length() > 300 ? detail.substring(0, 300) + "..." : detail, "ok", ok);
        try {
            Files.writeString(file, Json.write(e) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // the disk is full; the action itself already happened
        }
    }

    /** The newest n entries, newest first. */
    synchronized List<Map<String, Object>> last(int n) throws IOException {
        if (!Files.exists(file)) return List.of();
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0 && out.size() < n; i--) {
            try {
                out.add(Json.object(lines.get(i)));
            } catch (IllegalArgumentException ignored) {
                // a torn line
            }
        }
        return out;
    }
}
