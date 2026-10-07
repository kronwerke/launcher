package de.kronwerke.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Things that happen by the clock: restarts with warnings, commands, messages to the players,
 * backups, starting and stopping. Kept in console/schedule.json, checked every minute.
 * <p>
 * A task runs "daily" at a time on chosen weekdays, or "every" so many hours from midnight.
 * Restarts and stops warn the players at the given minutes before.
 */
public final class Schedule {
    public static final List<String> ACTIONS = List.of("restart", "stop", "start", "command", "say", "backup");

    private final Fleet fleet;
    private final Path file;
    private final List<Map<String, Object>> tasks = new ArrayList<>();
    private volatile Thread thread;

    Schedule(Fleet fleet) {
        this.fleet = fleet;
        this.file = fleet.home().resolve("console").resolve("schedule.json");
        load();
    }

    public ZoneId zone() {
        String z = fleet.config().get("timezone");
        try {
            return z.isEmpty() ? ZoneId.systemDefault() : ZoneId.of(z);
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }

    private void load() {
        try {
            if (!Files.exists(file)) return;
            Object o = Json.parse(Files.readString(file));
            if (o instanceof List<?> l) {
                for (Object t : l) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) t;
                    tasks.add(m);
                }
            }
        } catch (IOException | RuntimeException e) {
            fleet.note("schedule.json could not be read: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("schedule.json.tmp");
        Files.writeString(tmp, Json.write(tasks));
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Every task with its next run. */
    public synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        ZonedDateTime now = ZonedDateTime.now(zone());
        for (Map<String, Object> t : tasks) {
            Map<String, Object> m = new java.util.LinkedHashMap<>(t);
            ZonedDateTime next = next(t, now);
            m.put("next", next == null ? null : next.toInstant().toString());
            out.add(m);
        }
        return out;
    }

    /** Adds or replaces a task (by id), after checking it. */
    public synchronized Map<String, Object> put(Map<String, Object> in) throws IOException {
        Map<String, Object> t = new java.util.LinkedHashMap<>();
        String id = Json.str(in, "id", "");
        t.put("id", id.matches("t_[a-z0-9]{8}") ? id : "t_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
        String name = Json.str(in, "name", "").trim();
        if (name.isEmpty() || name.length() > 60) throw new IllegalArgumentException("a name of 1 to 60 characters");
        t.put("name", name);
        String action = Json.str(in, "action", "");
        if (!ACTIONS.contains(action)) throw new IllegalArgumentException("action: " + String.join(", ", ACTIONS));
        t.put("action", action);
        String server = Json.str(in, "server", "*");
        if (!server.equals("*")) fleet.server(server);
        t.put("server", server);
        String text = Json.str(in, "text", "").trim();
        if ((action.equals("command") || action.equals("say")) && text.isEmpty()) throw new IllegalArgumentException("this needs a text");
        if (text.length() > 256 || text.contains("\n")) throw new IllegalArgumentException("text: one line, at most 256 characters");
        t.put("text", text);
        String kind = Json.str(in, "kind", "daily");
        if (kind.equals("daily")) {
            String time = Json.str(in, "time", "");
            if (!time.matches("([01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("time like 05:00");
            t.put("time", time);
            List<Object> days = new ArrayList<>();
            if (in.get("days") instanceof List<?> l) for (Object d : l) {
                int n = ((Number) d).intValue();
                if (n < 1 || n > 7) throw new IllegalArgumentException("days 1 (Monday) to 7");
                if (!days.contains((long) n)) days.add((long) n);
            }
            if (days.isEmpty()) for (long d = 1; d <= 7; d++) days.add(d);
            t.put("days", days);
        } else if (kind.equals("every")) {
            long hours = Json.num(in, "hours", 0);
            if (hours < 1 || hours > 24 || 24 % hours != 0) throw new IllegalArgumentException("every 1, 2, 3, 4, 6, 8, 12 or 24 hours");
            t.put("hours", hours);
        } else {
            throw new IllegalArgumentException("kind: daily or every");
        }
        t.put("kind", kind);
        List<Object> warn = new ArrayList<>();
        if (in.get("warn") instanceof List<?> l) for (Object w : l) {
            long n = ((Number) w).longValue();
            if (n < 1 || n > 60) throw new IllegalArgumentException("warnings 1 to 60 minutes before");
            warn.add(n);
        }
        warn.sort((a, b) -> Long.compare((Long) b, (Long) a));
        t.put("warn", warn);
        t.put("enabled", Json.bool(in, "enabled", true));
        tasks.removeIf(x -> x.get("id").equals(t.get("id")));
        tasks.add(t);
        save();
        return t;
    }

    public synchronized void remove(String id) throws IOException {
        if (!tasks.removeIf(x -> id.equals(x.get("id")))) throw new IllegalArgumentException("no task " + id);
        save();
    }

    /** The next time after now, or null when it never runs. */
    static ZonedDateTime next(Map<String, Object> t, ZonedDateTime now) {
        if (!Json.bool(t, "enabled", true)) return null;
        if ("every".equals(t.get("kind"))) {
            long h = Json.num(t, "hours", 24);
            ZonedDateTime at = now.toLocalDate().atStartOfDay(now.getZone());
            while (!at.isAfter(now)) at = at.plusHours(h);
            return at;
        }
        LocalTime time = LocalTime.parse(Json.str(t, "time", "05:00"));
        List<?> days = t.get("days") instanceof List<?> l ? l : List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        for (int k = 0; k <= 7; k++) {
            LocalDate d = now.toLocalDate().plusDays(k);
            ZonedDateTime at = d.atTime(time).atZone(now.getZone());
            if (at.isAfter(now) && days.contains((long) d.getDayOfWeek().getValue())) return at;
        }
        return null;
    }

    // ---- running ----

    void start() {
        Thread t = new Thread(this::loop, "schedule");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    void stop() {
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
    }

    private void loop() {
        ZonedDateTime last = ZonedDateTime.now(zone()).withSecond(0).withNano(0);
        while (thread == Thread.currentThread()) {
            try {
                Thread.sleep(Math.max(1000, 60_000 - (System.currentTimeMillis() % 60_000) + 500));
            } catch (InterruptedException e) {
                return;
            }
            ZonedDateTime now = ZonedDateTime.now(zone()).withSecond(0).withNano(0);
            if (!now.isAfter(last)) continue;
            List<Map<String, Object>> due;
            synchronized (this) {
                due = new ArrayList<>(tasks);
            }
            for (Map<String, Object> t : due) {
                // the window is (last, now], so a slow minute misses nothing and nothing runs twice
                ZonedDateTime n = next(t, last);
                if (n == null) continue;
                for (Object w : t.get("warn") instanceof List<?> l ? l : List.of()) {
                    long min = ((Number) w).longValue();
                    ZonedDateTime warnAt = n.minusMinutes(min);
                    if (warnAt.isAfter(last) && !warnAt.isAfter(now)) warn(t, min);
                }
                if (!n.isAfter(now)) fleet.submit(() -> run(t));
            }
            last = now;
        }
    }

    private List<Server> targets(Map<String, Object> t) {
        String s = Json.str(t, "server", "*");
        if (s.equals("*")) return fleet.servers();
        try {
            return List.of(fleet.server(s));
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private void warn(Map<String, Object> t, long minutes) {
        String action = Json.str(t, "action", "");
        if (!action.equals("restart") && !action.equals("stop")) return;
        boolean de = fleet.config().get("console.language").equals("de");
        String text = de
                ? (action.equals("restart") ? "Neustart" : "Der Server stoppt") + " in " + minutes + (minutes == 1 ? " Minute" : " Minuten")
                : (action.equals("restart") ? "Restart" : "The server stops") + " in " + minutes + (minutes == 1 ? " minute" : " minutes");
        List<String> names = new ArrayList<>();
        for (Server s : targets(t)) if (s.minecraft() && s.state() == Server.State.RUNNING) names.add(s.name());
        if (names.isEmpty()) return;
        try {
            fleet.network().say(names, fleet.config().get("name").isEmpty() ? "Server" : fleet.config().get("name"), text);
        } catch (RuntimeException ignored) {
            // nobody to tell
        }
    }

    /** Runs a task now, from the clock or the console. */
    public void run(Map<String, Object> t) {
        String action = Json.str(t, "action", ""), text = Json.str(t, "text", "");
        fleet.event("schedule", null, Json.str(t, "name", "") + ": " + action + (text.isEmpty() ? "" : " " + text));
        List<Server> targets = targets(t);
        switch (action) {
            case "restart" -> targets.forEach(s -> {
                if (s.wanted()) fleet.submit(s::restart);
            });
            case "stop" -> targets.forEach(s -> fleet.submit(s::stop));
            case "start" -> targets.forEach(Server::start);
            case "command" -> targets.forEach(s -> {
                try {
                    if (s.state() == Server.State.RUNNING) s.command(text);
                } catch (IOException | RuntimeException e) {
                    fleet.event("schedule", s.name(), "command failed: " + e.getMessage());
                }
            });
            case "say" -> {
                List<String> names = new ArrayList<>();
                for (Server s : targets) if (s.minecraft() && s.state() == Server.State.RUNNING) names.add(s.name());
                if (!names.isEmpty()) fleet.network().say(names, fleet.config().get("name").isEmpty() ? "Server" : fleet.config().get("name"), text);
            }
            case "backup" -> targets.forEach(s -> {
                if (s.minecraft()) fleet.submit(() -> fleet.backups().run(s, Json.str(t, "name", "schedule")));
            });
            default -> {
                // checked on put
            }
        }
        synchronized (this) {
            for (Map<String, Object> x : tasks) if (x.get("id").equals(t.get("id"))) x.put("last", Instant.now().toString());
            try {
                save();
            } catch (IOException ignored) {
                // last run is only a note
            }
        }
    }

    public synchronized Map<String, Object> get(String id) {
        for (Map<String, Object> t : tasks) if (id.equals(t.get("id"))) return t;
        throw new IllegalArgumentException("no task " + id);
    }
}
