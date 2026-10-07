package de.kronwerke.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who played when and how long: every join and leave the network sees (from the consoles or
 * from mods on the bus), kept in console/sessions.json. A server that stops or crashes ends
 * its open sessions.
 */
public final class Sessions {
    private static final int KEEP = 40;

    private final Fleet fleet;
    private final Path file;
    /** name -> total, last, sessions */
    private final Map<String, Map<String, Object>> players = new LinkedHashMap<>();
    /** name -> server, from */
    private final Map<String, Map<String, Object>> open = new LinkedHashMap<>();
    private long savedAt;
    private boolean dirty;

    Sessions(Fleet fleet) {
        this.fleet = fleet;
        this.file = fleet.home().resolve("console").resolve("sessions.json");
        try {
            if (Files.exists(file)) {
                Map<String, Object> d = Json.object(Files.readString(file));
                copy(d.get("players"), players);
                copy(d.get("open"), open);
            }
        } catch (IOException | RuntimeException e) {
            fleet.note("sessions.json could not be read: " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private static void copy(Object from, Map<String, Map<String, Object>> to) {
        if (from instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) if (e.getValue() instanceof Map<?, ?> v) to.put(String.valueOf(e.getKey()), new LinkedHashMap<>((Map<String, Object>) v));
        }
    }

    /** From the network's feed. */
    synchronized void on(Map<String, Object> e) {
        String kind = Json.str(e, "kind", ""), name = Json.str(e, "player", ""), server = Json.str(e, "server", "");
        if (name.isEmpty() || !name.matches("[A-Za-z0-9_]{2,16}")) return;
        long now = System.currentTimeMillis();
        if (kind.equals("join")) {
            close(name, now);
            open.put(name, Json.map("server", server, "from", now));
            Map<String, Object> p = players.computeIfAbsent(name, k -> Json.map("total", 0L));
            p.put("last", now);
            p.put("server", server);
            changed(false);
        } else if (kind.equals("leave")) {
            Map<String, Object> o = open.get(name);
            if (o != null && Json.str(o, "server", "").equals(server)) {
                close(name, now);
                changed(true);
            }
        }
    }

    /** A server stopped: whoever was on it left. */
    synchronized void serverDown(String server) {
        long now = System.currentTimeMillis();
        boolean any = false;
        for (String name : new ArrayList<>(open.keySet())) {
            if (Json.str(open.get(name), "server", "").equals(server)) {
                close(name, now);
                any = true;
            }
        }
        if (any) changed(true);
    }

    private void close(String name, long now) {
        Map<String, Object> o = open.remove(name);
        if (o == null) return;
        long from = Json.num(o, "from", now);
        long ms = Math.max(0, now - from);
        Map<String, Object> p = players.computeIfAbsent(name, k -> Json.map("total", 0L));
        p.put("total", Json.num(p, "total", 0) + ms);
        p.put("last", now);
        @SuppressWarnings("unchecked")
        List<Object> list = p.get("sessions") instanceof List<?> l ? (List<Object>) l : new ArrayList<>();
        list.add(Json.map("server", o.get("server"), "from", from, "to", now));
        while (list.size() > KEEP) list.remove(0);
        p.put("sessions", list);
    }

    private void changed(boolean now) {
        dirty = true;
        if (now || System.currentTimeMillis() - savedAt > 30_000) save();
    }

    synchronized void save() {
        if (!dirty) return;
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("sessions.json.tmp");
            Files.writeString(tmp, Json.write(Json.map("players", players, "open", open)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            savedAt = System.currentTimeMillis();
            dirty = false;
        } catch (IOException e) {
            fleet.note("sessions.json could not be written: " + e.getMessage());
        }
    }

    /** One player: total (with an open session counted up to now), last seen, sessions. */
    public synchronized Map<String, Object> player(String name) {
        Map<String, Object> p = players.get(name);
        Map<String, Object> o = open.get(name);
        long now = System.currentTimeMillis();
        Map<String, Object> out = Json.map("name", name, "total", p == null ? 0 : Json.num(p, "total", 0)
                + (o == null ? 0 : now - Json.num(o, "from", now)), "last", p == null ? null : p.get("last"),
                "online", o == null ? null : o.get("server"), "since", o == null ? null : o.get("from"),
                "sessions", p == null ? List.of() : p.getOrDefault("sessions", List.of()));
        return out;
    }

    /** Everyone, most played first: name, total, last, online. */
    public synchronized List<Object> all() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (var e : players.entrySet()) {
            Map<String, Object> o = open.get(e.getKey());
            out.add(Json.map("name", e.getKey(), "total", Json.num(e.getValue(), "total", 0) + (o == null ? 0 : now - Json.num(o, "from", now)),
                    "last", e.getValue().get("last"), "online", o == null ? null : o.get("server")));
        }
        out.sort((a, b) -> Long.compare(Json.num(b, "total", 0), Json.num(a, "total", 0)));
        return new ArrayList<>(out);
    }

    static String iso(long ms) {
        return Instant.ofEpochMilli(ms).toString();
    }
}
