package de.kronwerke.launcher.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import de.kronwerke.launcher.Config;
import de.kronwerke.launcher.Fleet;
import de.kronwerke.launcher.Json;
import de.kronwerke.launcher.Main;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * The web console, served by the launcher itself: the page, its assets and the JSON
 * API under /api that the page and Elchi Ops use alike. Behind Cloudflare, with Cloudflare's
 * client certificate required, so a request straight to the container's address fails in the
 * TLS handshake.
 */
public final class Web {
    static final long MAX_BODY = 16L << 20;
    static final String COOKIE = "__Host-kw";

    final Fleet fleet;
    final Config cfg;
    final Path dir;
    final Access access;
    final Audit audit;
    final Api api;
    final String host;
    final List<String> origins;
    final boolean behindCloudflare;
    private final String assetVersion;
    private final Map<String, byte[]> assets = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> failures = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "console-http");
        t.setDaemon(true);
        return t;
    });
    private HttpServer server;

    public Web(Fleet fleet, Config cfg) throws IOException {
        this.fleet = fleet;
        this.cfg = cfg;
        this.dir = fleet.home().resolve("console");
        Files.createDirectories(dir);
        this.host = cfg.get("console.host");
        if (host.isEmpty()) throw new IOException("console.host is empty: the name the console is reached by");
        List<String> o = new ArrayList<>();
        o.add("https://" + host);
        for (String extra : cfg.get("console.origins").split(",")) if (!extra.isBlank()) o.add(extra.trim());
        this.origins = List.copyOf(o);
        String rp = cfg.get("console.rpid").isEmpty() ? host : cfg.get("console.rpid");
        this.access = new Access(dir, rp, origins, title());
        this.audit = new Audit(dir.resolve("audit.jsonl"));
        this.behindCloudflare = !"off".equals(clientCa());
        this.assetVersion = Main.VERSION.replaceAll("[^A-Za-z0-9.]", "") + "-" + Long.toString(Instant.now().getEpochSecond(), 36);
        this.api = new Api(this);
    }

    /** The console's title: the launcher's name and "Console". */
    String title() {
        return (cfg.get("name").isEmpty() ? "Launcher" : cfg.get("name")) + " Console";
    }

    /** Whether the season page is there: Kronwerke Core in the first server's mods, or forced. */
    boolean season() {
        String s = cfg.get("console.season");
        if (s.equals("on")) return true;
        if (s.equals("off")) return false;
        try (var files = Files.list(fleet.main().dir().resolve("mods"))) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith("kronwerke-core"));
        } catch (IOException e) {
            return false;
        }
    }

    private String clientCa() {
        return cfg.get("console.client.ca").isEmpty() ? "cloudflare" : cfg.get("console.client.ca");
    }

    /** Opens the port. Prints the setup code while nobody has a passkey yet. */
    public void start() throws Exception {
        int port = Integer.parseInt(cfg.get("console.port"));
        String bind = cfg.get("console.bind").isEmpty() ? "0.0.0.0" : cfg.get("console.bind");
        InetSocketAddress addr = new InetSocketAddress(bind, port);
        if ("off".equals(cfg.get("console.tls"))) {
            if (!bind.startsWith("127.")) throw new IOException("console.tls=off only on a loopback address");
            server = HttpServer.create(addr, 64);
        } else {
            SSLContext ctx = Tls.context(dir, host, cfg.get("console.cert"), cfg.get("console.key"), clientCa());
            HttpsServer s = HttpsServer.create(addr, 64);
            boolean needClient = behindCloudflare;
            s.setHttpsConfigurator(new HttpsConfigurator(ctx) {
                @Override
                public void configure(HttpsParameters params) {
                    SSLParameters p = ctx.getDefaultSSLParameters();
                    p.setNeedClientAuth(needClient);
                    p.setProtocols(new String[] {"TLSv1.3", "TLSv1.2"});
                    params.setSSLParameters(p);
                }
            });
            server = s;
        }
        server.createContext("/", this::handle);
        server.setExecutor(pool);
        server.start();
        String setup = access.setupCode();
        fleet.event("console", null, "console on port " + port + (behindCloudflare ? ", Cloudflare only" : ""));
        if (setup != null) {
            fleet.main().notice("Console: nobody has a passkey yet. Open https://" + host + "/setup and enter " + setup);
        }
    }

    public void stop() {
        if (server != null) server.stop(0);
        api.close();
        pool.shutdownNow();
    }

    // ---- requests ----

    private void handle(HttpExchange ex) {
        Req r = new Req(this, ex);
        try {
            String path = ex.getRequestURI().getPath();
            if (path.startsWith("/api/")) {
                r.header("Cache-Control", "no-store");
                api.handle(r, path.substring(4));
            } else if (path.startsWith("/assets/")) {
                asset(r, path.substring(8));
            } else if (path.equals("/favicon.svg")) {
                asset(r, "favicon.svg");
            } else if (ex.getRequestMethod().equals("GET")) {
                page(r);
            } else {
                r.fail(405, "method not allowed");
            }
        } catch (Http e) {
            r.fail(e.status, e.getMessage());
        } catch (SecurityException e) {
            r.fail(403, e.getMessage());
        } catch (IllegalArgumentException e) {
            r.fail(400, e.getMessage());
        } catch (IllegalStateException e) {
            r.fail(409, e.getMessage());
        } catch (Exception e) {
            r.fail(500, e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            r.close();
        }
    }

    /** The single page; the app routes by path. */
    private void page(Req r) throws IOException {
        byte[] b = resource("index.html");
        String lang = cfg.get("console.language").equals("de") ? "de" : "en";
        String html = new String(b, StandardCharsets.UTF_8).replace("{{v}}", assetVersion).replace("{{lang}}", lang)
                .replace("{{title}}", title().replace("&", "&amp;").replace("<", "&lt;"));
        r.header("Cache-Control", "no-store");
        r.send(200, "text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
    }

    private void asset(Req r, String name) throws IOException {
        if (!name.matches("[a-z0-9_-]+(/[a-z0-9_.-]+)*\\.(js|css|woff2|svg|txt|png)")) throw new Http(404, "not found");
        byte[] b = resource(name);
        String type = switch (name.substring(name.lastIndexOf('.') + 1)) {
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "woff2" -> "font/woff2";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            default -> "text/plain; charset=utf-8";
        };
        // names carry the version in the query, so the browser and Cloudflare may keep them
        r.header("Cache-Control", "public, max-age=31536000, immutable");
        r.send(200, type, b);
    }

    private byte[] resource(String name) throws IOException {
        byte[] b = assets.get(name);
        if (b != null) return b;
        try (InputStream in = Web.class.getClassLoader().getResourceAsStream("console/" + name)) {
            if (in == null) throw new Http(404, "not found");
            b = in.readAllBytes();
        }
        assets.put(name, b);
        return b;
    }

    // ---- guarding ----

    /** Counts a failed sign in or a bad key. */
    void failed(String ip) {
        Deque<Long> d = failures.computeIfAbsent(ip, k -> new ArrayDeque<>());
        synchronized (d) {
            d.addLast(System.currentTimeMillis());
        }
    }

    /** Too many failures from this address in ten minutes. */
    boolean blocked(String ip) {
        Deque<Long> d = failures.get(ip);
        if (d == null) return false;
        synchronized (d) {
            long cut = System.currentTimeMillis() - 600_000;
            while (!d.isEmpty() && d.peekFirst() < cut) d.removeFirst();
            return d.size() >= 10;
        }
    }

    /** Writes a file only the container's user can read. */
    static void writePrivate(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not POSIX
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** An HTTP error with its status. */
    static final class Http extends RuntimeException {
        private static final long serialVersionUID = 1L;
        final int status;

        Http(int status, String msg) {
            super(msg);
            this.status = status;
        }
    }

    /** One request and its answer. */
    static final class Req {
        final Web web;
        final HttpExchange ex;
        private boolean sent;
        private Map<String, Object> body;
        private Map<String, String> query;
        Access.Who who;

        Req(Web web, HttpExchange ex) {
            this.web = web;
            this.ex = ex;
            header("X-Content-Type-Options", "nosniff");
            header("Referrer-Policy", "no-referrer");
            header("X-Frame-Options", "DENY");
            header("Strict-Transport-Security", "max-age=31536000");
            header("Content-Security-Policy", "default-src 'self'; img-src 'self' data: https://mc-heads.net https://cdn.modrinth.com https://media.forgecdn.net; "
                    + "style-src 'self'; script-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; "
                    + "base-uri 'none'; form-action 'self'");
            header("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        }

        String method() {
            return ex.getRequestMethod();
        }

        /** The visitor's address: Cloudflare's header when only Cloudflare can connect. */
        String ip() {
            if (web.behindCloudflare) {
                String cf = ex.getRequestHeaders().getFirst("CF-Connecting-IP");
                if (cf != null && !cf.isBlank()) return cf.trim();
            }
            return ex.getRemoteAddress().getAddress().getHostAddress();
        }

        String headerIn(String name) {
            String v = ex.getRequestHeaders().getFirst(name);
            return v == null ? "" : v;
        }

        String cookie(String name) {
            for (String h : ex.getRequestHeaders().getOrDefault("Cookie", List.of())) {
                for (String part : h.split(";")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && part.substring(0, eq).trim().equals(name)) return part.substring(eq + 1).trim();
                }
            }
            return null;
        }

        Map<String, String> query() {
            if (query != null) return query;
            query = new LinkedHashMap<>();
            String q = ex.getRequestURI().getRawQuery();
            if (q != null) {
                for (String p : q.split("&")) {
                    int eq = p.indexOf('=');
                    String k = URLDecoder.decode(eq < 0 ? p : p.substring(0, eq), StandardCharsets.UTF_8);
                    String v = eq < 0 ? "" : URLDecoder.decode(p.substring(eq + 1), StandardCharsets.UTF_8);
                    query.put(k, v);
                }
            }
            return query;
        }

        String q(String key, String fallback) {
            String v = query().get(key);
            return v == null ? fallback : v;
        }

        Map<String, Object> body() throws IOException {
            if (body != null) return body;
            byte[] b = ex.getRequestBody().readNBytes((int) MAX_BODY + 1);
            if (b.length > MAX_BODY) throw new Http(413, "the request is too large");
            body = b.length == 0 ? new LinkedHashMap<>() : Json.object(new String(b, StandardCharsets.UTF_8));
            return body;
        }

        void header(String k, String v) {
            ex.getResponseHeaders().set(k, v);
        }

        void json(int status, Object o) throws IOException {
            send(status, "application/json; charset=utf-8", Json.write(o).getBytes(StandardCharsets.UTF_8));
        }

        void ok(Object o) throws IOException {
            json(200, Json.map("ok", true, "data", o));
        }

        void send(int status, String type, byte[] b) throws IOException {
            if (sent) return;
            sent = true;
            header("Content-Type", type);
            ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
            if (b.length > 0) {
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(b);
                }
            }
        }

        /** Starts a stream of server sent events; the caller writes until the client goes. */
        OutputStream stream() throws IOException {
            sent = true;
            header("Content-Type", "text/event-stream; charset=utf-8");
            header("Cache-Control", "no-store");
            header("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            return ex.getResponseBody();
        }

        void fail(int status, String msg) {
            if (sent) return;
            try {
                json(status, Json.map("ok", false, "error", msg == null ? "error" : msg));
            } catch (IOException ignored) {
                // the client is gone
            }
        }

        void close() {
            ex.close();
        }
    }
}
