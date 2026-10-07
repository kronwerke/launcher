package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Fleet;
import de.kronwerke.launcher.Json;
import de.kronwerke.launcher.Main;
import de.kronwerke.launcher.Metrics;
import de.kronwerke.launcher.Pack;
import de.kronwerke.launcher.Proc;
import de.kronwerke.launcher.Server;
import de.kronwerke.launcher.ServerFiles;
import de.kronwerke.launcher.Updater;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * The console's JSON API. Every answer is {"ok": true, "data": ...} or {"ok": false,
 * "error": "..."}. Reading needs the scope "read"; every change names its scope and lands in
 * the audit log.
 */
final class Api {
    /** Commands someone with only "players" may run. */
    static final List<String> PLAYER_COMMANDS = List.of("list", "kick ", "say ", "msg ", "tell ", "w ", "whitelist add ", "whitelist remove ",
            "ban ", "pardon ");
    static final Set<String> SERVER_KEYS = Set.of("memory", "cpu.share", "autostart", "restart.on.crash", "jvm.args", "color",
            "network", "sync.chat", "chat.radius", "sync.joins", "sync.tablist", "sync.lists", "sync.players", "label");

    private final Web web;
    private final Fleet fleet;
    private final Access access;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final List<Runnable> closers = new ArrayList<>();
    private volatile Map<String, Object> remotePack;
    private volatile long remoteAt;
    private final long started = Instant.now().getEpochSecond();
    private final Mods mods;
    private final Software software = new Software();

    Api(Web web) {
        this.web = web;
        this.fleet = web.fleet;
        this.access = web.access;
        String curse = "";
        try {
            Path f = web.dir.resolve("curseforge.key");
            if (Files.exists(f)) curse = Files.readString(f).trim();
        } catch (IOException ignored) {
            // no key
        }
        this.mods = new Mods(curse, web.cfg.get("curseforge.proxy"));
    }

    void close() {
        List<Runnable> cs;
        synchronized (closers) {
            cs = new ArrayList<>(closers);
        }
        for (Runnable c : cs) c.run();
    }

    // ---- routing ----

    void handle(Web.Req r, String path) throws Exception {
        String m = r.method();
        String ip = r.ip();
        if (web.blocked(ip) && (path.startsWith("/auth/") || r.headerIn("Authorization").startsWith("Bearer "))) {
            throw new Web.Http(429, "too many failed attempts, wait ten minutes");
        }
        identify(r, ip);

        // open to everyone: who am I, and signing in
        switch (path) {
            case "/session" -> {
                r.ok(session(r));
                return;
            }
            case "/auth/options" -> {
                post(m);
                r.ok(access.loginOptions());
                return;
            }
            case "/auth/login" -> {
                post(m);
                login(r, ip);
                return;
            }
            case "/auth/register/options" -> {
                post(m);
                registerOptions(r, ip);
                return;
            }
            case "/auth/register" -> {
                post(m);
                register(r, ip);
                return;
            }
            default -> {
                // the rest needs someone
            }
        }
        if (r.who == null) throw new Web.Http(401, "sign in first");
        if (!m.equals("GET")) checkCsrf(r);

        String[] p = path.substring(1).split("/");
        switch (p[0]) {
            case "auth" -> {
                if (path.equals("/auth/logout")) {
                    post(m);
                    if (r.who.session() != null) access.endSession(r.who.session());
                    r.header("Set-Cookie", Web.COOKIE + "=; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=0");
                    r.ok("signed out");
                    return;
                }
            }
            case "overview" -> {
                need(r, "read");
                r.ok(overview());
                return;
            }
            case "stream" -> {
                need(r, "read");
                stream(r);
                return;
            }
            case "servers" -> {
                if (p.length >= 3) {
                    server(r, fleet.server(p[1]), p[2], p.length > 3 ? p[3] : "");
                    return;
                }
                if (p.length == 1 && m.equals("POST")) {
                    createServer(r);
                    return;
                }
                if (p.length == 2 && m.equals("DELETE")) {
                    removeServer(r, fleet.server(p[1]));
                    return;
                }
            }
            case "software" -> {
                need(r, "read");
                if (p.length == 1) r.ok(Software.kinds());
                else r.ok(software.versions(p[1], r.q("all", "").equals("1")));
                return;
            }
            case "container" -> {
                need(r, "read");
                r.ok(samples(fleet.container()));
                return;
            }
            case "pack" -> {
                pack(r, p.length > 1 ? p[1] : "");
                return;
            }
            case "players" -> {
                need(r, "read");
                r.ok(players());
                return;
            }
            case "people" -> {
                need(r, "read");
                r.ok(people());
                return;
            }
            case "season" -> {
                need(r, "read");
                if (!web.season()) throw new Web.Http(404, "no season here");
                r.ok(season());
                return;
            }
            case "launcher" -> {
                launcher(r, p.length > 1 ? p[1] : "");
                return;
            }
            case "audit" -> {
                need(r, "read");
                r.ok(web.audit.last((int) Math.min(1000, Long.parseLong(r.q("n", "200")))));
                return;
            }
            case "access" -> {
                accessRoute(r, p);
                return;
            }
            case "schedule" -> {
                scheduleRoute(r, p);
                return;
            }
            case "alerts" -> {
                alertsRoute(r, p);
                return;
            }
            case "sessions" -> {
                need(r, "read");
                r.ok(p.length > 1 ? fleet.sessions().player(java.net.URLDecoder.decode(p[1], StandardCharsets.UTF_8)) : fleet.sessions().all());
                return;
            }
            case "network" -> {
                if (p.length == 1) {
                    need(r, "read");
                    r.ok(Json.map("servers", fleet.network().state(), "bus", fleet.network().busOpen(),
                            "busPort", web.cfg.get("bus.port"), "feed", fleet.network().feed((int) Math.min(500, Long.parseLong(r.q("n", "200"))))));
                    return;
                }
                if (p[1].equals("say")) {
                    post(m);
                    Map<String, Object> b = r.body();
                    String text = Json.str(b, "text", "").trim();
                    List<String> to = new ArrayList<>();
                    if (b.get("servers") instanceof List<?> l) for (Object o : l) to.add(String.valueOf(o));
                    act(r, "players", "say", to.isEmpty() ? null : String.join(",", to), text, () -> {
                        fleet.network().say(to, r.who.name(), text);
                        return "sent";
                    });
                    return;
                }
            }
            default -> {
                // falls through to 404
            }
        }
        throw new Web.Http(404, "no such endpoint");
    }

    private void identify(Web.Req r, String ip) throws IOException {
        String auth = r.headerIn("Authorization");
        if (auth.startsWith("Bearer ")) {
            r.who = access.key(auth.substring(7).trim());
            if (r.who == null) {
                web.failed(ip);
                throw new Web.Http(401, "unknown or expired key");
            }
            return;
        }
        r.who = access.session(r.cookie(Web.COOKIE));
    }

    /** Sessions must send their token and come from the console's own origin; keys need neither. */
    private void checkCsrf(Web.Req r) {
        if (!"session".equals(r.who.kind())) return;
        String token = Json.str(r.who.session(), "csrf", "");
        if (!Access.constantEquals(r.headerIn("X-Csrf-Token"), token)) throw new SecurityException("missing or wrong request token");
        String origin = r.headerIn("Origin");
        if (!origin.isEmpty() && !web.origins.contains(origin)) throw new SecurityException("wrong origin");
    }

    private static void post(String m) {
        if (!m.equals("POST")) throw new Web.Http(405, "POST only");
    }

    private static void need(Web.Req r, String scope) {
        if (!r.who.can(scope)) throw new SecurityException("this needs " + scope);
    }

    /** Runs a change, writes it to the audit log and the timeline, answers with its result. */
    private void act(Web.Req r, String scope, String action, String server, String detail, Change c) throws Exception {
        need(r, scope);
        Object result;
        try {
            result = c.run();
        } catch (Exception e) {
            web.audit.add(r.who, r.ip(), action, server, detail + " -> " + e.getMessage(), false);
            throw e;
        }
        web.audit.add(r.who, r.ip(), action, server, detail, true);
        fleet.event("action", server, r.who.name() + ": " + action + (detail == null || detail.isEmpty() ? "" : " " + detail));
        r.ok(result);
    }

    interface Change {
        Object run() throws Exception;
    }

    // ---- signing in ----

    private Map<String, Object> session(Web.Req r) {
        String accent = web.cfg.get("console.accent");
        Map<String, Object> s = Json.map("host", web.host, "setup", access.setupCode() != null, "launcher", Main.VERSION,
                "title", web.title(), "language", web.cfg.get("console.language").equals("de") ? "de" : "en",
                "accent", accent.matches("#[0-9a-fA-F]{6}") ? accent : "#e5b451", "season", web.season(),
                "named", !web.cfg.get("name").isEmpty(), "panelPort", System.getenv("SERVER_PORT") == null ? "" : System.getenv("SERVER_PORT"));
        if (r.who != null) {
            s.put("user", Json.map("id", r.who.id(), "name", r.who.name(), "role", r.who.role(), "kind", r.who.kind()));
            s.put("scopes", new ArrayList<>(r.who.scopes()));
            if (r.who.session() != null) s.put("csrf", r.who.session().get("csrf"));
        }
        return s;
    }

    private void login(Web.Req r, String ip) throws Exception {
        Map<String, Object> b = r.body();
        Map<String, Object> user;
        try {
            user = access.login(Json.str(b, "id", ""), obj(b, "credential"));
        } catch (SecurityException e) {
            web.failed(ip);
            web.audit.add(null, ip, "sign in", null, e.getMessage(), false);
            throw e;
        }
        signIn(r, user, ip, "sign in");
    }

    private void registerOptions(Web.Req r, String ip) throws IOException {
        Map<String, Object> b = r.body();
        try {
            r.ok(access.registerOptions(Json.str(b, "purpose", ""), Json.str(b, "secret", ""), r.who, Json.str(b, "name", "")));
        } catch (SecurityException e) {
            web.failed(ip);
            throw e;
        }
    }

    private void register(Web.Req r, String ip) throws Exception {
        Map<String, Object> b = r.body();
        Map<String, Object> user;
        try {
            user = access.register(Json.str(b, "id", ""), obj(b, "credential"), Json.str(b, "label", ""));
        } catch (SecurityException e) {
            web.failed(ip);
            web.audit.add(r.who, ip, "add passkey", null, e.getMessage(), false);
            throw e;
        }
        if (r.who != null && "session".equals(r.who.kind()) && r.who.id().equals(user.get("id"))) {
            web.audit.add(r.who, ip, "add passkey", null, Json.str(b, "label", ""), true);
            r.ok(session(r));
            return;
        }
        signIn(r, user, ip, "new passkey");
    }

    private void signIn(Web.Req r, Map<String, Object> user, String ip, String what) throws IOException {
        String token = access.newSession(Json.str(user, "id", ""), ip, r.headerIn("User-Agent"));
        r.header("Set-Cookie", Web.COOKIE + "=" + token + "; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=" + Access.SESSION_SECONDS);
        r.who = access.session(token);
        web.audit.add(r.who, ip, what, null, "", true);
        fleet.event("access", null, r.who.name() + " signed in");
        r.ok(session(r));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Map<String, Object> b, String key) {
        Object o = b.get(key);
        if (!(o instanceof Map)) throw new IllegalArgumentException(key + " missing");
        return (Map<String, Object>) o;
    }

    // ---- reading ----

    private Map<String, Object> overview() throws IOException {
        Pack.Info info = fleet.pack().local();
        long[] mem = Proc.containerMemory();
        long[] disk = {fleet.diskUsed(), fleet.diskLimit()};
        Metrics.Sample c = fleet.container().last();
        List<Object> servers = new ArrayList<>();
        for (Server s : fleet.servers()) servers.add(server(s));
        return Json.map(
                "launcher", Json.map("version", Main.VERSION, "java", System.getProperty("java.version"), "started", started,
                        "pid", ProcessHandle.current().pid()),
                "container", Json.map("cpuLimit", Metrics.round(Proc.cpuLimit()), "cpus", Proc.allowedCpus().size(),
                        "cpu", c == null ? -1 : c.cpu(), "memory", mem[0], "memoryMax", mem[1], "memoryLimitGb", fleet.memoryLimitGb(),
                        "disk", disk[0], "diskMax", disk[1], "pin", fleet.config().flag("cpu.pin"),
                        "balance", fleet.config().flag("cpu.balance"), "pinned", fleet.pinned()),
                "pack", Json.map("version", info == null ? "" : info.version(), "neoforge", info == null ? "" : info.neoforge(),
                        "minecraft", info == null ? "" : info.minecraft(), "updating", fleet.updating()),
                "servers", servers,
                "events", fleet.events(80));
    }

    static Map<String, Object> server(Server s) {
        Metrics.Sample last = s.metrics().last();
        var c = s.config();
        return Json.map("name", s.name(), "state", s.state().name().toLowerCase(), "detail", s.detail(),
                "since", s.since().toString(), "pid", s.pid(), "starts", s.starts(), "wanted", s.wanted(),
                "role", c.get("role"), "port", c.get("port"), "memory", c.get("memory"), "share", c.number("cpu.share", 1),
                "autostart", c.flag("autostart"), "restartOnCrash", c.flag("restart.on.crash"), "jvmArgs", c.get("jvm.args"),
                "dir", c.get("dir"), "type", s.type(), "jar", c.get("jar"), "neoforge", c.get("neoforge"), "minecraft", c.get("minecraft"), "color", c.get("color").matches("#[0-9a-fA-F]{6}") ? c.get("color") : "", "last", last == null ? null : sampleJson(last), "players", s.metrics().players(),
                "dimensions", s.metrics().dimensions(), "maintenance", c.flag("maintenance"), "tools", tools(s));
    }

    /** Tools the console offers buttons for, found by their jar in mods or plugins. */
    static List<String> tools(Server s) {
        List<String> out = new ArrayList<>();
        for (String d : List.of("mods", "plugins")) {
            Path dir = s.dir().resolve(d);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> st = Files.list(dir)) {
                for (Path f : st.toList()) {
                    String n = f.getFileName().toString().toLowerCase();
                    if (!n.endsWith(".jar")) continue;
                    if (n.startsWith("spark") && !out.contains("spark")) out.add("spark");
                    if (n.startsWith("chunky") && !out.contains("chunky")) out.add("chunky");
                }
            } catch (IOException ignored) {
                // unreadable folder
            }
        }
        return out;
    }

    // ---- schedule, backups, alerts ----

    private void scheduleRoute(Web.Req r, String[] p) throws Exception {
        String m = r.method();
        var sch = fleet.schedule();
        if (p.length == 1) {
            if (m.equals("GET")) {
                need(r, "read");
                r.ok(Json.map("tasks", sch.list(), "zone", sch.zone().getId(), "now", java.time.ZonedDateTime.now(sch.zone()).toString()));
                return;
            }
            post(m);
            Map<String, Object> b = r.body();
            act(r, "config", "schedule", null, Json.str(b, "name", ""), () -> sch.put(b));
            return;
        }
        String id = p[1];
        if (p.length > 2 && p[2].equals("run")) {
            post(m);
            Map<String, Object> t = sch.get(id);
            act(r, "power", "run task", null, Json.str(t, "name", ""), () -> {
                fleet.submit(() -> sch.run(t));
                return "running";
            });
            return;
        }
        if (m.equals("DELETE")) {
            act(r, "config", "remove task", null, id, () -> {
                sch.remove(id);
                return "removed";
            });
            return;
        }
        throw new Web.Http(405, "GET, POST or DELETE");
    }

    private void alertsRoute(Web.Req r, String[] p) throws Exception {
        String m = r.method();
        if (p.length > 1 && p[1].equals("test")) {
            post(m);
            act(r, "config", "test alert", null, "", () -> fleet.alerts().test());
            return;
        }
        if (m.equals("GET")) {
            need(r, "read");
            r.ok(fleet.alerts().state());
            return;
        }
        post(m);
        Map<String, Object> b = r.body();
        List<String> ev = new ArrayList<>();
        if (b.get("events") instanceof List<?> l) for (Object o : l) ev.add(String.valueOf(o));
        act(r, "config", "alerts", null, String.join(",", ev), () -> {
            fleet.alerts().set(Json.str(b, "webhook", ""), ev, Json.num(b, "mspt", 50));
            return fleet.alerts().state();
        });
    }

    private void backupsRoute(Web.Req r, Server s, String file) throws Exception {
        String m = r.method();
        var bk = fleet.backups();
        if (file.isEmpty()) {
            if (m.equals("GET")) {
                need(r, "read");
                r.ok(Json.map("backups", bk.list(s), "busy", bk.busy(s), "keep", s.config().number("backup.keep", 5)));
                return;
            }
            post(m);
            act(r, "power", "backup", s.name(), "", () -> {
                if (bk.busy(s)) throw new IllegalStateException("a backup is running");
                fleet.submit(() -> {
                    try {
                        bk.run(s, r.who.name());
                    } catch (RuntimeException ignored) {
                        // in the timeline
                    }
                });
                return "started";
            });
            return;
        }
        String[] f = file.split(":", 2);
        String name = f[0];
        if (m.equals("GET")) {
            need(r, "files");
            web.audit.add(r.who, r.ip(), "download backup", s.name(), name, true);
            r.file(bk.file(s, name), "application/zip", name);
            return;
        }
        if (m.equals("DELETE")) {
            act(r, "power", "delete backup", s.name(), name, () -> {
                bk.delete(s, name);
                return "deleted";
            });
            return;
        }
        if (m.equals("POST") && f.length > 1 && f[1].equals("restore")) {
            act(r, "power", "restore backup", s.name(), name, () -> Json.map("aside", bk.restore(s, name)));
            return;
        }
        throw new Web.Http(405, "GET, POST or DELETE");
    }

    /** Keys the launcher sets itself; the form shows them but does not change them. */
    static final Set<String> MANAGED = Set.of("server-port", "rcon.port", "rcon.password", "enable-rcon", "accepts-transfers");

    private static List<Object> properties(Server s) throws IOException {
        List<Object> out = new ArrayList<>();
        if (!Files.exists(s.properties())) return out;
        for (String l : Files.readAllLines(s.properties(), StandardCharsets.ISO_8859_1)) {
            int eq = l.indexOf('=');
            if (l.startsWith("#") || eq <= 0) continue;
            String k = l.substring(0, eq).trim();
            out.add(Json.map("key", k, "value", k.equals("rcon.password") ? "" : l.substring(eq + 1), "managed", MANAGED.contains(k)));
        }
        return out;
    }

    private static void setProperty(Server s, String key, String value) throws IOException {
        if (!key.matches("[a-z0-9.-]{1,64}")) throw new IllegalArgumentException("not a key");
        if (MANAGED.contains(key)) throw new IllegalArgumentException(key + " is set by the launcher (its server file)");
        if (value.contains("\n") || value.length() > 500) throw new IllegalArgumentException("one line, at most 500 characters");
        boolean known = false;
        for (Object o : properties(s)) if (key.equals(((Map<?, ?>) o).get("key"))) known = true;
        if (!known) throw new IllegalArgumentException("no key " + key + " in server.properties");
        Server.Properties.set(s.properties(), Map.of(key, value));
    }

    /** Lines with the text in the newest logs, old ones (gzipped) included, newest first. */
    private static Map<String, Object> logSearch(Server s, String q, int max) throws IOException {
        if (q.trim().length() < 2) throw new IllegalArgumentException("at least two characters");
        String needle = q.toLowerCase();
        Path dir = s.dir().resolve("logs");
        List<Object> hits = new ArrayList<>();
        int files = 0;
        if (Files.isDirectory(dir)) {
            List<Path> logs;
            try (Stream<Path> st = Files.list(dir)) {
                logs = st.filter(f -> f.getFileName().toString().matches(".*\\.log(\\.gz)?")).sorted(Comparator.comparing(Api::modified).reversed()).toList();
            }
            for (Path f : logs) {
                if (hits.size() >= max || files >= 60) break;
                files++;
                List<Object> mine = new ArrayList<>();
                try (var in = f.toString().endsWith(".gz") ? new java.util.zip.GZIPInputStream(Files.newInputStream(f)) : Files.newInputStream(f);
                     var rd = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
                    int n = 0;
                    for (String l; (l = rd.readLine()) != null; ) {
                        n++;
                        if (l.toLowerCase().contains(needle)) mine.add(Json.map("file", f.getFileName().toString(), "line", n, "text", l.length() > 400 ? l.substring(0, 400) : l));
                    }
                } catch (IOException e) {
                    // a broken archive: skip it
                }
                // newest lines first within a file too
                java.util.Collections.reverse(mine);
                for (Object o : mine) {
                    if (hits.size() >= max) break;
                    hits.add(o);
                }
            }
        }
        return Json.map("hits", hits, "files", files, "more", hits.size() >= max);
    }

    static Map<String, Object> sampleJson(Metrics.Sample s) {
        return Json.map("t", s.time(), "cpu", Metrics.round(s.cpu()), "rss", s.rss(), "tps", Metrics.round(s.tps()),
                "mspt", Metrics.round(s.mspt()), "players", s.players());
    }

    private static List<Object> samples(Metrics m) {
        List<Object> out = new ArrayList<>();
        for (Metrics.Sample s : m.all()) out.add(sampleJson(s));
        return out;
    }

    private List<Object> players() {
        List<Object> out = new ArrayList<>();
        for (Server s : fleet.servers()) {
            for (String n : s.metrics().players()) out.add(Json.map("name", n, "server", s.name()));
        }
        return out;
    }

    /**
     * Everyone the first Minecraft server knows: online, whitelisted, operators, banned, and
     * whoever logged in (usercache.json keeps a month). With Kronwerke Core also its roster of
     * streamers and slots.
     */
    private Map<String, Object> people() {
        Server main = fleet.servers().stream().filter(Server::minecraft).findFirst().orElse(fleet.main());
        Map<String, Map<String, Object>> byName = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        java.util.function.Function<String, Map<String, Object>> get = n -> byName.computeIfAbsent(n, k -> Json.map("name", k));
        for (Object o : jsonList(main.dir().resolve("usercache.json"))) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Map<String, Object> p = get.apply(String.valueOf(m.get("name")));
            p.put("uuid", m.get("uuid"));
            p.put("seen", lastSeen(String.valueOf(m.get("expiresOn"))));
        }
        for (Object o : jsonList(main.dir().resolve("whitelist.json"))) {
            if (o instanceof Map<?, ?> m) get.apply(String.valueOf(m.get("name"))).put("whitelisted", true);
        }
        for (Object o : jsonList(main.dir().resolve("ops.json"))) {
            if (o instanceof Map<?, ?> m) get.apply(String.valueOf(m.get("name"))).put("op", m.get("level"));
        }
        for (Object o : jsonList(main.dir().resolve("banned-players.json"))) {
            if (o instanceof Map<?, ?> m) get.apply(String.valueOf(m.get("name"))).put("banned", String.valueOf(m.get("reason")));
        }
        for (Server s : fleet.servers()) {
            for (String n : s.metrics().players()) get.apply(n).put("online", s.name());
        }
        // members who linked their name on Discord (pushed by the Kronwerke bot)
        List<Object> linked = jsonList(fleet.home().resolve("discord-links.json"));
        for (Object o : linked) {
            if (!(o instanceof Map<?, ?> m) || m.get("player") == null) continue;
            Map<String, Object> p = get.apply(String.valueOf(m.get("player")));
            p.put("discord", Json.map("id", m.get("discord_id"), "name", m.get("discord_name"), "kind", m.get("kind"), "at", m.get("at")));
        }
        Map<String, Object> out = Json.map("server", main.name(), "players", new ArrayList<>(byName.values()), "linked", linked,
                "whitelist", Proc.whitelistOn(main.properties()));
        if (web.season() && main.state() == Server.State.RUNNING) out.put("roster", core(main, "kw admin roster json"));
        return out;
    }

    private static List<Object> jsonList(Path file) {
        try {
            if (!Files.exists(file)) return List.of();
            Object v = Json.parse(Files.readString(file));
            return v instanceof List<?> l ? new ArrayList<>(l) : List.of();
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    /** usercache.json keeps an entry a month after the last login. */
    static long lastSeen(String expiresOn) {
        try {
            var f = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");
            return java.time.OffsetDateTime.parse(expiresOn, f).minusMonths(1).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Core's own answers about the season and its goals, read on the first server. */
    private Map<String, Object> season() {
        Server main = fleet.main();
        Map<String, Object> out = Json.map("server", main.name(), "state", main.state().name().toLowerCase());
        if (main.state() != Server.State.RUNNING) return out;
        out.put("season", core(main, "kw admin season json"));
        out.put("goals", core(main, "kw admin goals json"));
        return out;
    }

    /** Runs a Core command that answers "OK <json>" and returns the JSON, or an error text. */
    private static Object core(Server s, String cmd) {
        try {
            String a = s.command(cmd).trim();
            if (a.startsWith("OK ")) return Json.parse(a.substring(3).trim());
            return Json.map("error", a);
        } catch (IOException | RuntimeException e) {
            return Json.map("error", e.getMessage());
        }
    }

    // ---- one server ----

    private void server(Web.Req r, Server s, String what, String sub) throws Exception {
        String m = r.method();
        ServerFiles files = fleet.files(s);
        switch (what) {
            case "console" -> {
                need(r, "read");
                r.ok(s.console((int) Math.min(Server.CONSOLE_LINES, Long.parseLong(r.q("n", "500")))));
            }
            case "metrics" -> {
                need(r, "read");
                r.ok(samples(s.metrics()));
            }
            case "command" -> {
                post(m);
                String cmd = Json.str(r.body(), "cmd", "").trim();
                if (cmd.startsWith("/")) cmd = cmd.substring(1);
                if (cmd.isEmpty()) throw new IllegalArgumentException("the command is empty");
                String c = cmd;
                String scope = r.who.can("command") || PLAYER_COMMANDS.stream().noneMatch(c::startsWith) ? "command" : "players";
                act(r, scope, "command", s.name(), c, () -> {
                    String answer = s.command(c);
                    fleet.network().mirror(s, c);
                    return answer;
                });
            }
            case "power" -> {
                post(m);
                String action = Json.str(r.body(), "action", "");
                switch (action) {
                    case "start" -> act(r, "power", "start", s.name(), "", () -> {
                        s.start();
                        return "starting";
                    });
                    case "stop" -> act(r, "power", "stop", s.name(), "", () -> {
                        fleet.submit(s::stop);
                        return "stopping";
                    });
                    case "restart" -> act(r, "power", "restart", s.name(), "", () -> {
                        fleet.submit(s::restart);
                        return "restarting";
                    });
                    case "kill" -> act(r, "power", "kill", s.name(), "", () -> {
                        long pid = s.pid();
                        if (pid == 0) throw new IllegalStateException("not running");
                        ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
                        return "killed";
                    });
                    default -> throw new IllegalArgumentException("start, stop, restart or kill");
                }
            }
            case "config" -> {
                post(m);
                String key = Json.str(r.body(), "key", ""), value = Json.str(r.body(), "value", "").trim();
                if (!SERVER_KEYS.contains(key)) throw new IllegalArgumentException("not a key the console changes: " + key);
                act(r, "config", "config", s.name(), key + "=" + value, () -> {
                    switch (key) {
                        case "memory" -> {
                            if (!value.matches("[1-9][0-9]{0,2}G|[1-9][0-9]{2,5}M")) throw new IllegalArgumentException("like 20G or 8192M");
                        }
                        case "autostart", "restart.on.crash", "sync.joins", "sync.tablist", "sync.lists", "sync.players" -> {
                            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("true or false");
                        }
                        case "network" -> {
                            if (!value.matches("[a-z0-9_-]{0,24}")) throw new IllegalArgumentException("lower case letters, digits, - and _, at most 24, or empty");
                        }
                        case "label" -> {
                            if (!value.matches("[\\p{L}\\p{N} _.-]{0,24}")) throw new IllegalArgumentException("letters, digits, spaces, at most 24");
                        }
                        case "sync.chat" -> {
                            if (!List.of("network", "server", "radius").contains(value)) throw new IllegalArgumentException("network, server or radius");
                        }
                        case "chat.radius" -> {
                            if (!value.matches("[1-9][0-9]{0,3}")) throw new IllegalArgumentException("1 to 9999 blocks");
                        }
                        case "color" -> {
                            if (!value.isEmpty() && !value.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("like #e5b451, or empty");
                        }
                        case "cpu.share" -> {
                            fleet.share(s, Integer.parseInt(value));
                            return "share " + value;
                        }
                        default -> {
                            if (value.contains("@") || value.length() > 500) throw new IllegalArgumentException("not like that");
                        }
                    }
                    s.config().set(key, value);
                    if (de.kronwerke.launcher.Network.KEYS.contains(key) || key.equals("color")) fleet.network().changed();
                    if (key.equals("network") || key.equals("sync.lists")) fleet.submit(() -> {
                        try {
                            fleet.linkLists(s);
                        } catch (IOException ignored) {
                            // links on the next start
                        }
                    });
                    return key.equals("memory") || key.equals("jvm.args") ? "saved, applies on the next start" : "saved";
                });
            }
            case "files" -> {
                String path = r.q("path", "");
                switch (m) {
                    case "GET" -> {
                        need(r, "files");
                        Path at = s.dir().resolve(path).normalize();
                        if (Files.isDirectory(at)) {
                            r.ok(Json.map("dir", true, "path", path, "entries", files.list(path)));
                        } else {
                            Map<String, Object> f = files.read(path);
                            f.put("dir", false);
                            f.put("writable", files.writableRel(path));
                            r.ok(f);
                        }
                    }
                    case "PUT" -> {
                        Map<String, Object> b = r.body();
                        String p = Json.str(b, "path", path);
                        act(r, "files", "write", s.name(), p, () -> files.write(p, Json.str(b, "data", "")));
                    }
                    case "DELETE" -> act(r, "files", "delete", s.name(), path, () -> files.delete(path));
                    default -> throw new Web.Http(405, "GET, PUT or DELETE");
                }
            }
            case "logs" -> {
                need(r, "read");
                String path = r.q("path", "logs/latest.log");
                if (!path.startsWith("logs/") && !path.startsWith("crash-reports/")) throw new IllegalArgumentException("logs and crash reports only");
                r.ok(files.tail(path, (int) Math.min(20000, Long.parseLong(r.q("lines", "500")))));
            }
            case "crashes" -> {
                need(r, "read");
                r.ok(crashes(s));
            }
            case "software" -> {
                post(m);
                Map<String, Object> b = r.body();
                String kind = Json.str(b, "kind", ""), version = Json.str(b, "version", "");
                act(r, "config", "software", s.name(), kind + " " + version, () -> {
                    need(r, "power");
                    boolean was = s.wanted();
                    s.stop();
                    Map<String, String> set = software.install(kind, version, s.dir());
                    for (var e : set.entrySet()) s.config().set(e.getKey(), e.getValue());
                    if (was) s.start();
                    return Json.map("installed", kind + " " + version, "started", was);
                });
            }
            case "jar" -> {
                if (!m.equals("PUT")) throw new Web.Http(405, "PUT");
                String name = r.q("name", "server.jar");
                if (!name.matches("[A-Za-z0-9_.+-]{1,80}\\.jar")) throw new IllegalArgumentException("a jar file name");
                act(r, "config", "upload jar", s.name(), name, () -> {
                    need(r, "files");
                    Path tmp = s.dir().resolve(name + ".part");
                    Files.createDirectories(s.dir());
                    long size;
                    try (var in = r.raw(); var out = Files.newOutputStream(tmp)) {
                        size = in.transferTo(out);
                    }
                    if (size > 512L << 20) {
                        Files.deleteIfExists(tmp);
                        throw new IllegalArgumentException("larger than 512 MB");
                    }
                    try (var z = new java.util.zip.ZipFile(tmp.toFile())) {
                        if (z.getEntry("META-INF/MANIFEST.MF") == null) throw new IllegalArgumentException("not a runnable jar");
                    } catch (IOException e) {
                        Files.deleteIfExists(tmp);
                        throw new IllegalArgumentException("not a jar");
                    }
                    Files.move(tmp, s.dir().resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    s.config().set("type", "jar");
                    s.config().set("jar", name);
                    return Json.map("jar", name, "size", size);
                });
            }
            case "backups" -> backupsRoute(r, s, sub);
            case "maintenance" -> {
                post(m);
                Map<String, Object> b = r.body();
                boolean on = Json.bool(b, "on", false);
                String msg = b.containsKey("message") ? Json.str(b, "message", "") : null;
                act(r, "power", on ? "maintenance on" : "maintenance off", s.name(), msg == null ? "" : msg,
                        () -> Json.map("sent", fleet.network().maintenance(s, on, msg)));
            }
            case "properties" -> {
                if (!s.minecraft()) throw new IllegalArgumentException("only Minecraft servers have server.properties");
                if (m.equals("GET")) {
                    need(r, "read");
                    r.ok(properties(s));
                } else {
                    post(m);
                    String key = Json.str(r.body(), "key", ""), value = Json.str(r.body(), "value", "");
                    act(r, "config", "server.properties", s.name(), key + "=" + value, () -> {
                        setProperty(s, key, value);
                        return s.state() == Server.State.RUNNING ? "saved, applies on the next start" : "saved";
                    });
                }
            }
            case "logsearch" -> {
                need(r, "read");
                r.ok(logSearch(s, r.q("q", ""), (int) Math.min(1000, Long.parseLong(r.q("max", "300")))));
            }
            case "profile" -> {
                post(m);
                long secs = Math.max(10, Math.min(300, Json.num(r.body(), "seconds", 30)));
                act(r, "command", "profile", s.name(), secs + " s", () -> s.command("spark profiler start --timeout " + secs));
            }
            case "mods" -> {
                Mods.Target target = Mods.target(s, fleet.pack().local());
                if (sub.equals("search")) {
                    need(r, "read");
                    r.ok(mods.search(target, r.q("q", ""), (int) Math.min(1000, Long.parseLong(r.q("offset", "0")))));
                    return;
                }
                switch (m) {
                    case "GET" -> {
                        need(r, "read");
                        Map<String, Object> list = mods.list(s, target, r.q("fresh", "").equals("1"));
                        list.put("managed", s.type().equals("neoforge") && !fleet.config().get("pack.url").isEmpty());
                        r.ok(list);
                    }
                    case "POST" -> {
                        Map<String, Object> b = r.body();
                        String project = Json.str(b, "project", ""), replace = Json.str(b, "replace", "");
                        act(r, "pack", replace.isEmpty() ? "install mod" : "update mod", s.name(), project + (replace.isEmpty() ? "" : " for " + replace), () -> {
                            List<String> present = new ArrayList<>();
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> now = (List<Map<String, Object>>) mods.list(s, target, false).get("mods");
                            for (Map<String, Object> x : now) if (x.get("project") != null) present.add(String.valueOf(x.get("project")));
                            return Json.map("files", mods.install(target, project, replace, present), "restart", s.state() == Server.State.RUNNING);
                        });
                    }
                    case "DELETE" -> {
                        String file = r.q("file", "");
                        act(r, "pack", "remove mod", s.name(), file, () -> {
                            Mods.remove(target, file);
                            return Json.map("removed", file, "restart", s.state() == Server.State.RUNNING);
                        });
                    }
                    default -> throw new Web.Http(405, "GET, POST or DELETE");
                }
            }
            default -> throw new Web.Http(404, "no such endpoint");
        }
    }

    private static List<Object> crashes(Server s) throws IOException {
        Path d = s.dir().resolve("crash-reports");
        List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.filter(Files::isRegularFile).sorted(Comparator.comparing(Api::modified).reversed()).limit(30).toList()) {
                out.add(Json.map("name", p.getFileName().toString(), "size", Files.size(p), "modified", modified(p), "headline", headline(p)));
            }
        }
        return out;
    }

    private static long modified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** The line of a crash report that says what happened. */
    static String headline(Path p) {
        try {
            List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
            String description = "";
            for (int i = 0; i < Math.min(lines.size(), 60); i++) {
                String l = lines.get(i);
                if (l.startsWith("Description:")) description = l.substring(12).trim();
                if (!description.isEmpty() && i + 2 < lines.size() && lines.get(i).isBlank()) {
                    String ex = lines.get(i + 1).trim();
                    if (!ex.isEmpty()) return description + ": " + ex;
                }
            }
            return description;
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    // ---- new servers ----

    /** Ports the servers and the console already use. */
    private java.util.Set<String> usedPorts() {
        java.util.Set<String> used = new java.util.HashSet<>();
        used.add(web.cfg.get("console.port"));
        for (Server s : fleet.servers()) {
            for (String k : List.of("port", "rcon.port", "voice.port")) used.add(s.config().get(k));
            if (s.config().get("port").isEmpty() && s.minecraft()) {
                try {
                    for (String l : Files.readAllLines(s.properties(), StandardCharsets.ISO_8859_1)) {
                        if (l.startsWith("server-port=")) used.add(l.substring(12).trim());
                        if (l.startsWith("rcon.port=")) used.add(l.substring(10).trim());
                    }
                } catch (IOException ignored) {
                    // no file yet
                }
            }
        }
        used.remove("");
        return used;
    }

    private void createServer(Web.Req r) throws Exception {
        Map<String, Object> b = r.body();
        String name = Json.str(b, "name", "").trim().toLowerCase();
        String kind = Json.str(b, "kind", ""), version = Json.str(b, "version", "");
        String port = Json.str(b, "port", "").trim();
        Software.kind(kind);
        if (!port.matches("[0-9]{2,5}")) throw new IllegalArgumentException("a port number");
        if (usedPorts().contains(port)) throw new IllegalArgumentException("port " + port + " is taken");
        act(r, "config", "new server", name, kind + " " + version + " on " + port, () -> {
            need(r, "power");
            int rcon = 25575;
            while (usedPorts().contains(Integer.toString(rcon))) rcon++;
            int order = 10;
            for (Server s : fleet.servers()) order = Math.max(order, s.config().number("order", 50) + 10);
            Map<String, String> v = new java.util.LinkedHashMap<>();
            v.put("port", port);
            v.put("rcon.port", Integer.toString(rcon));
            v.put("memory", Json.str(b, "memory", "4G"));
            v.put("cpu.share", Long.toString(Math.max(1, Json.num(b, "share", 3))));
            v.put("order", Integer.toString(order));
            boolean samePack = Json.bool(b, "samePack", false) && kind.equals("neoforge");
            if (!samePack) v.put("share", "-");
            String net = Json.str(b, "network", "").trim();
            if (!net.matches("[a-z0-9_-]{0,24}")) throw new IllegalArgumentException("network: lower case letters, digits, - and _");
            if (!net.isEmpty()) v.put("network", net);
            if (Json.bool(b, "transfers", false)) v.put("transfers", "true");
            String voice = Json.str(b, "voice", "").trim();
            if (!voice.isEmpty()) {
                if (!voice.matches("[0-9]{2,5}") || usedPorts().contains(voice) || voice.equals(port)) throw new IllegalArgumentException("voice port taken or wrong");
                v.put("voice.port", voice);
            }
            String role = Json.str(b, "role", "").trim();
            if (!role.isEmpty()) {
                if (!role.matches("[a-z0-9_-]{1,24}")) throw new IllegalArgumentException("role: lower case letters and digits");
                v.put("role", role);
            }
            Path dir = fleet.root().resolve("servers").resolve(name);
            var cfg = de.kronwerke.launcher.Config.createServer(fleet.root(), name, v);
            if (samePack) {
                // NeoForge and every mod from the first server's pack, nothing to download here
                cfg.set("type", "neoforge");
                cfg.set("loader", "neoforge");
            } else if (!kind.equals("custom")) {
                for (var e : software.install(kind, version, dir).entrySet()) cfg.set(e.getKey(), e.getValue());
            }
            if (Json.bool(b, "eula", false)) {
                Files.createDirectories(dir);
                Files.writeString(dir.resolve("eula.txt"), "# accepted in the console\neula=true\n");
            }
            cfg.set("autostart", Boolean.toString(Json.bool(b, "start", true) && !kind.equals("custom")));
            fleet.submit(() -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                    // reload anyway
                }
                fleet.reload();
            });
            return Json.map("name", name, "reload", true);
        });
    }

    private void removeServer(Web.Req r, Server s) throws Exception {
        if (s == fleet.main()) throw new IllegalArgumentException("the first server stays");
        act(r, "config", "remove server", s.name(), "", () -> {
            need(r, "power");
            s.stop();
            Path f = fleet.home().resolve("servers").resolve(s.name() + ".properties");
            Files.move(f, f.resolveSibling(s.name() + ".properties.removed"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            fleet.submit(fleet::reload);
            return "removed; its folder stays";
        });
    }

    // ---- pack ----

    private void pack(Web.Req r, String what) throws Exception {
        switch (what) {
            case "" -> {
                need(r, "read");
                Pack.Info info = fleet.pack().local();
                Map<String, Object> out = Json.map("local", info == null ? null : Json.map("version", info.version(),
                        "neoforge", info.neoforge(), "minecraft", info.minecraft()), "updating", fleet.updating(),
                        "url", fleet.config().get("pack.url"), "mods", mods());
                out.put("remote", remote(r.q("fresh", "").equals("1")));
                r.ok(out);
            }
            case "update" -> {
                post(r.method());
                act(r, "pack", "pack update", null, "", () -> {
                    if (fleet.updating()) throw new IllegalStateException("an update is already running");
                    fleet.submit(() -> {
                        try {
                            fleet.update();
                        } catch (Exception e) {
                            fleet.event("update", null, "pack update failed: " + e.getMessage());
                        }
                    });
                    return "updating: every server stops, the pack is updated, then they start again";
                });
            }
            default -> throw new Web.Http(404, "no such endpoint");
        }
    }

    private List<Object> mods() throws IOException {
        Path d = fleet.root().resolve("mods");
        List<Object> out = new ArrayList<>();
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> s = Files.list(d)) {
            for (Path p : s.filter(x -> x.toString().endsWith(".jar")).sorted().toList()) {
                out.add(Json.map("file", p.getFileName().toString(), "size", Files.size(p)));
            }
        }
        return out;
    }

    /** pack.toml and the changelog from the repository, kept for a minute. */
    private Map<String, Object> remote(boolean fresh) {
        String url = fleet.config().get("pack.url");
        if (url.isEmpty()) return null;
        if (!fresh && remotePack != null && System.currentTimeMillis() - remoteAt < 60_000) return remotePack;
        Map<String, Object> out = Json.map();
        try {
            String toml = get(url + "?t=" + System.currentTimeMillis());
            var m = java.util.regex.Pattern.compile("(?m)^version\\s*=\\s*\"([^\"]*)\"").matcher(toml);
            out.put("version", m.find() ? m.group(1) : "");
            String base = url.substring(0, url.lastIndexOf('/') + 1);
            try {
                String log = get(base + "CHANGELOG.md");
                out.put("changelog", log.length() > 60_000 ? log.substring(log.length() - 60_000) : log);
            } catch (IOException e) {
                out.put("changelog", "");
            }
        } catch (IOException | InterruptedException e) {
            out.put("error", e.getMessage());
        }
        remotePack = out;
        remoteAt = System.currentTimeMillis();
        return out;
    }

    private String get(String url) throws IOException, InterruptedException {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) throw new IOException("HTTP " + res.statusCode());
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }
    }

    // ---- launcher ----

    private void launcher(Web.Req r, String what) throws Exception {
        post(r.method());
        Map<String, Object> b = r.body();
        switch (what) {
            case "reload" -> act(r, "power", "launcher reload", null, "", () -> {
                fleet.submit(() -> {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException ignored) {
                        // reload anyway
                    }
                    fleet.reload();
                });
                return "reloading; the servers keep running";
            });
            case "update" -> {
                String version = Json.str(b, "version", "");
                if (!version.matches("v\\d+\\.\\d+\\.\\d+")) throw new IllegalArgumentException("a version like v0.2.1");
                act(r, "power", "launcher update", null, version, () -> {
                    String base = Updater.prefix(fleet.config()) + version + "/";
                    String sums = get(base + "SHA256SUMS");
                    String sha = "";
                    String jar = "";
                    for (String l : sums.split("\n")) {
                        String[] f = l.trim().split("\\s+");
                        String n = f.length == 2 ? f[1].replace("*", "") : "";
                        // launcher.jar since 0.3, kronwerke-launcher.jar before
                        if (n.equals("launcher.jar") || (jar.isEmpty() && n.equals("kronwerke-launcher.jar"))) {
                            sha = f[0];
                            jar = n;
                        }
                    }
                    if (sha.isEmpty()) throw new IOException("the release has no checksum for launcher.jar");
                    return Updater.install(fleet, base + jar, sha, true);
                });
            }
            case "settings" -> {
                act(r, "config", "settings", null, "", () -> {
                    String name = Json.str(b, "name", null), lang = Json.str(b, "language", null), accent = Json.str(b, "accent", null);
                    if (name != null) {
                        if (name.length() > 40 || name.contains("\n")) throw new IllegalArgumentException("a name of up to 40 characters");
                        web.cfg.set("name", name.trim());
                    }
                    if (lang != null) {
                        if (!lang.equals("en") && !lang.equals("de")) throw new IllegalArgumentException("en or de");
                        web.cfg.set("console.language", lang);
                    }
                    if (accent != null) {
                        if (!accent.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("a colour like #e5b451");
                        web.cfg.set("console.accent", accent);
                    }
                    return "saved";
                });
            }
            case "config" -> {
                String key = Json.str(b, "key", ""), value = Json.str(b, "value", "");
                if (key.equals("bus.port")) {
                    if (!value.isEmpty() && (!value.matches("[0-9]{4,5}") || usedPorts().contains(value))) throw new IllegalArgumentException("a free port, or empty");
                    act(r, "config", "config", null, key + "=" + value, () -> {
                        fleet.config().set(key, value);
                        fleet.network().reopen();
                        return "saved; servers connect after their next start";
                    });
                    return;
                }
                if (!Set.of("cpu.pin", "cpu.balance").contains(key)) throw new IllegalArgumentException("not a key the console changes: " + key);
                if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("true or false");
                act(r, "config", "config", null, key + "=" + value, () -> {
                    fleet.config().set(key, value);
                    fleet.submit(() -> fleet.applyCpuNow());
                    return "saved";
                });
            }
            default -> throw new Web.Http(404, "no such endpoint");
        }
    }

    // ---- people and keys ----

    private void accessRoute(Web.Req r, String[] p) throws Exception {
        String m = r.method();
        String what = p.length > 1 ? p[1] : "";
        if (what.equals("pair")) {
            post(m);
            if (!"session".equals(r.who.kind())) throw new SecurityException("only people pair devices");
            Map<String, Object> pr = access.newPairing(r.who.id());
            web.audit.add(r.who, r.ip(), "pairing code", null, "", true);
            r.ok(pr);
            return;
        }
        if (what.isEmpty()) {
            if (!r.who.can("access")) {
                // everyone sees themselves
                r.ok(Json.map("me", Json.map("id", r.who.id(), "name", r.who.name(), "role", r.who.role())));
                return;
            }
            r.ok(access.overview(null));
            return;
        }
        Map<String, Object> b = m.equals("GET") ? Map.of() : r.body();
        String id = p.length > 2 ? p[2] : "";
        switch (what) {
            case "invites" -> {
                if (m.equals("POST")) {
                    String role = Json.str(b, "role", "mod");
                    act(r, "access", "invite", null, role, () -> {
                        String token = access.newInvite(role, Json.str(b, "name", ""), r.who.id());
                        return Json.map("link", "https://" + web.host + "/invite#" + token);
                    });
                } else if (m.equals("DELETE")) {
                    act(r, "access", "drop invite", null, id, () -> access.dropInvite(id));
                } else {
                    throw new Web.Http(405, "POST or DELETE");
                }
            }
            case "keys" -> {
                if (m.equals("POST")) {
                    List<String> scopes = new ArrayList<>();
                    if (b.get("scopes") instanceof List<?> l) for (Object o : l) scopes.add(String.valueOf(o));
                    act(r, "access", "new key", null, Json.str(b, "name", "") + " " + scopes,
                            () -> access.newKey(Json.str(b, "name", ""), scopes, Json.num(b, "days", 0), r.who.id()));
                } else if (m.equals("DELETE")) {
                    act(r, "access", "drop key", null, id, () -> access.dropKey(id));
                } else {
                    throw new Web.Http(405, "POST or DELETE");
                }
            }
            case "users" -> {
                if (m.equals("DELETE")) {
                    act(r, "access", "remove person", null, id, () -> access.dropUser(id, r.who.id()));
                } else if (m.equals("POST")) {
                    String role = Json.str(b, "role", "");
                    act(r, "access", "role", null, id + " " + role, () -> {
                        access.setRole(id, role, r.who.id());
                        return role;
                    });
                } else {
                    throw new Web.Http(405, "POST or DELETE");
                }
            }
            case "passkeys" -> {
                if (!m.equals("DELETE")) throw new Web.Http(405, "DELETE");
                // anyone may remove their own; owners anyone's
                String user = Json.str(b, "user", r.who.id());
                if (!user.equals(r.who.id())) need(r, "access");
                act(r, user.equals(r.who.id()) ? "read" : "access", "drop passkey", null, id, () -> access.dropPasskey(user, id));
            }
            default -> throw new Web.Http(404, "no such endpoint");
        }
    }

    // ---- live ----

    /**
     * Server sent events: console lines of the asked servers, state changes, the timeline,
     * and every ten seconds the overview. A comment every 15 seconds keeps Cloudflare's 100
     * second timeout away.
     */
    private void stream(Web.Req r) throws Exception {
        BlockingQueue<String> q = new LinkedBlockingQueue<>(20_000);
        Set<String> want = Set.of(r.q("servers", "").split(","));
        List<Runnable> undo = new ArrayList<>();
        for (Server s : fleet.servers()) {
            if (!want.contains(s.name()) && !want.contains("*")) continue;
            Consumer<String> c = line -> q.offer(frame("line", Json.map("server", s.name(), "text", line)));
            s.onConsole(c);
            undo.add(() -> s.removeConsole(c));
        }
        Consumer<Map<String, Object>> ev = e -> q.offer(frame("event", e));
        fleet.onEvent(ev);
        undo.add(() -> fleet.removeEvent(ev));
        Consumer<Map<String, Object>> chat = e -> q.offer(frame("chat", e));
        fleet.network().onFeed(chat);
        undo.add(() -> fleet.network().removeFeed(chat));
        Runnable closer = () -> q.offer("");
        synchronized (closers) {
            closers.add(closer);
        }
        try (OutputStream out = r.stream()) {
            out.write(frame("hello", Json.map("t", Instant.now().toString())).getBytes(StandardCharsets.UTF_8));
            out.flush();
            long nextOverview = System.currentTimeMillis() + 10_000, lastWrite = System.currentTimeMillis();
            while (true) {
                long now = System.currentTimeMillis();
                long wait = Math.max(50, Math.min(nextOverview - now, lastWrite + 15_000 - now));
                String f = q.poll(wait, TimeUnit.MILLISECONDS);
                if (f != null && f.isEmpty()) break;
                StringBuilder b = new StringBuilder(f == null ? "" : f);
                for (String more; b.length() < 256_000 && (more = q.poll()) != null; ) {
                    if (more.isEmpty()) return;
                    b.append(more);
                }
                now = System.currentTimeMillis();
                if (now >= nextOverview) {
                    b.append(frame("overview", overview()));
                    nextOverview = now + 10_000;
                }
                if (b.length() == 0) {
                    if (now - lastWrite < 15_000) continue;
                    b.append(": ping\n\n");
                }
                out.write(b.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
                lastWrite = now;
            }
        } catch (IOException e) {
            // the browser went away
        } finally {
            for (Runnable u : undo) u.run();
            synchronized (closers) {
                closers.remove(closer);
            }
        }
    }

    static String frame(String event, Object data) {
        return "event: " + event + "\ndata: " + Json.write(data) + "\n\n";
    }

    static String base64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }
}
