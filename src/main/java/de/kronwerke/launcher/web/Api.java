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
    static final List<String> PLAYER_COMMANDS = List.of("list", "kick ", "say ", "msg ", "tell ", "w ");
    static final Set<String> SERVER_KEYS = Set.of("memory", "cpu.share", "autostart", "restart.on.crash", "jvm.args");

    private final Web web;
    private final Fleet fleet;
    private final Access access;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final List<Runnable> closers = new ArrayList<>();
    private volatile Map<String, Object> remotePack;
    private volatile long remoteAt;
    private final long started = Instant.now().getEpochSecond();

    Api(Web web) {
        this.web = web;
        this.fleet = web.fleet;
        this.access = web.access;
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
        if (!Access.constantEquals(r.headerIn("X-Kw-Csrf"), token)) throw new SecurityException("missing or wrong request token");
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
                "accent", accent.matches("#[0-9a-fA-F]{6}") ? accent : "#e5b451", "season", web.season());
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
        if (r.who != null && "session".equals(r.who.kind())) {
            web.audit.add(r.who, ip, "add passkey", null, Json.str(b, "label", ""), true);
            r.ok(session(r));
            return;
        }
        signIn(r, user, ip, "first passkey");
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
        long[] disk = Proc.disk(fleet.root());
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
                "dir", c.get("dir"), "type", s.type(), "color", c.get("color").matches("#[0-9a-fA-F]{6}") ? c.get("color") : "", "last", last == null ? null : sampleJson(last), "players", s.metrics().players(),
                "dimensions", s.metrics().dimensions());
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
                act(r, scope, "command", s.name(), c, () -> s.command(c));
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
                        case "autostart", "restart.on.crash" -> {
                            if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("true or false");
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
                    for (String l : sums.split("\n")) {
                        String[] f = l.trim().split("\\s+");
                        if (f.length == 2 && f[1].replace("*", "").equals("kronwerke-launcher.jar")) sha = f[0];
                    }
                    if (sha.isEmpty()) throw new IOException("the release has no checksum for kronwerke-launcher.jar");
                    return Updater.install(fleet, base + "kronwerke-launcher.jar", sha, true);
                });
            }
            case "config" -> {
                String key = Json.str(b, "key", ""), value = Json.str(b, "value", "");
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
