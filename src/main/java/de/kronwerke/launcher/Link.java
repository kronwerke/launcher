package de.kronwerke.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The connection to the Discord bot. The launcher dials out, so the server needs no open
 * port. The bot sends requests, the launcher answers them and reports what happens.
 *
 * Messages are JSON objects with a "type":
 *   launcher: hello {key, name, launcher, state, pack}, res {id, ok, data | error},
 *             event {event: state | console, ...}, ping
 *   bot:      welcome, pending {fingerprint}, req {id, op, args}, pong
 */
public final class Link {
    static final String UPDATE_PREFIX = "https://github.com/kronwerke/launcher/releases/download/";

    private final String url;
    private final String name;
    private final Path root;
    private final Server server;
    private final ServerFiles files;
    private final String key;
    private final Path ownJar;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "link-work");
        t.setDaemon(true);
        return t;
    });

    private final Object sendLock = new Object();
    private volatile WebSocket ws;
    private volatile long lastSeen;
    private volatile boolean follow;
    private volatile boolean accepted;
    private final List<String> pending = new ArrayList<>();

    public Link(String url, String name, Path root, Server server, Path ownJar) throws IOException {
        this.url = url;
        this.name = name;
        this.root = root;
        this.server = server;
        this.ownJar = ownJar;
        Path keyFile = root.resolve("kronwerke/link.key");
        this.files = new ServerFiles(root, keyFile);
        this.key = loadKey(keyFile);
        server.onState(this::sendState);
        server.onConsole(line -> {
            if (!follow) return;
            synchronized (pending) {
                if (pending.size() < 5000) pending.add(line);
            }
        });
    }

    static String loadKey(Path file) throws IOException {
        if (Files.exists(file)) {
            String k = Files.readString(file).trim();
            if (k.length() >= 32) return k;
        }
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String k = HexFormat.of().formatHex(b);
        Files.createDirectories(file.getParent());
        Files.writeString(file, k + "\n");
        try {
            Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            // not a POSIX file system
        }
        return k;
    }

    /** What the team sees in the control channel to accept this server. */
    public String fingerprint() {
        return fingerprint(key);
    }

    static String fingerprint(String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(key.getBytes());
            return HexFormat.of().formatHex(d).substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Keeps the connection up. Runs on its own thread until the launcher exits. */
    public void run() {
        long wait = 5;
        while (true) {
            try {
                connect();
                wait = 5;
                while (ws != null && !ws.isOutputClosed() && !ws.isInputClosed()) {
                    Thread.sleep(1000);
                    flushConsole();
                    long idle = System.currentTimeMillis() - lastSeen;
                    if (idle > 90_000) {
                        server.note("Link: no answer from the bot for 90 seconds, reconnecting");
                        ws.abort();
                        break;
                    }
                    if (idle > 30_000 && (System.currentTimeMillis() / 1000) % 30 == 0) send(Json.map("type", "ping"));
                }
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                server.note("Link: " + e.getMessage() + ", trying again in " + wait + " seconds");
            }
            ws = null;
            follow = false;
            if (accepted) {
                accepted = false;
                continue;
            }
            try {
                Thread.sleep(wait * 1000);
            } catch (InterruptedException e) {
                return;
            }
            wait = Math.min(wait * 2, 120);
        }
    }

    private void connect() throws Exception {
        StringBuilder buf = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                lastSeen = System.currentTimeMillis();
                buf.append(data);
                if (last) {
                    String msg = buf.toString();
                    buf.setLength(0);
                    work.submit(() -> handle(msg));
                }
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket w, java.nio.ByteBuffer message) {
                lastSeen = System.currentTimeMillis();
                w.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket w, int status, String reason) {
                if (status == 4002) {
                    server.note("Link: accepted by the team, connecting again");
                    accepted = true;
                } else {
                    server.note("Link closed by the bot: " + status + " " + reason);
                }
                return null;
            }

            @Override
            public void onError(WebSocket w, Throwable error) {
                server.note("Link error: " + error.getMessage());
            }
        };
        WebSocket w = http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(20))
                .buildAsync(URI.create(url), listener).get(30, TimeUnit.SECONDS);
        lastSeen = System.currentTimeMillis();
        ws = w;
        Pack.Info info = server.pack().local();
        send(Json.map("type", "hello", "key", key, "name", name, "launcher", Main.VERSION,
                "state", server.state().name().toLowerCase(), "pack", info == null ? "" : info.version()));
    }

    void send(Map<String, Object> msg) {
        WebSocket w = ws;
        if (w == null) return;
        String text = Json.write(msg);
        synchronized (sendLock) {
            try {
                w.sendText(text, true).get(30, TimeUnit.SECONDS);
            } catch (Exception e) {
                w.abort();
            }
        }
    }

    private void sendState() {
        send(Json.map("type", "event", "event", "state", "state", server.state().name().toLowerCase(),
                "detail", server.detail(), "since", server.since().toString()));
    }

    private void flushConsole() {
        List<String> lines;
        synchronized (pending) {
            if (pending.isEmpty()) return;
            lines = new ArrayList<>(pending);
            pending.clear();
        }
        send(Json.map("type", "event", "event", "console", "lines", lines));
    }

    @SuppressWarnings("unchecked")
    void handle(String text) {
        Map<String, Object> m;
        try {
            m = Json.object(text);
        } catch (IllegalArgumentException e) {
            return;
        }
        switch (Json.str(m, "type", "")) {
            case "welcome" -> server.note("Link: connected to the bot");
            case "pending" -> server.note("Link: waiting until the team accepts this server, fingerprint "
                    + Json.str(m, "fingerprint", fingerprint()) + " (`!link accept " + fingerprint() + "`)");
            case "ping" -> send(Json.map("type", "pong"));
            case "req" -> {
                Object id = m.get("id");
                Object a = m.get("args");
                Map<String, Object> args = a instanceof Map ? (Map<String, Object>) a : Map.of();
                try {
                    Object data = op(Json.str(m, "op", ""), args);
                    send(Json.map("type", "res", "id", id, "ok", true, "data", data));
                } catch (Exception e) {
                    send(Json.map("type", "res", "id", id, "ok", false, "error", e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            }
            default -> {
            }
        }
    }

    Object op(String op, Map<String, Object> a) throws Exception {
        Path props = root.resolve("server.properties");
        switch (op) {
            case "status": {
                Pack.Info info = server.pack().local();
                Map<String, Object> s = Json.map("state", server.state().name().toLowerCase(), "detail", server.detail(),
                        "since", server.since().toString(), "pid", server.pid(), "starts", server.starts(),
                        "launcher", Main.VERSION, "pack", info == null ? "" : info.version(),
                        "neoforge", info == null ? "" : info.neoforge(), "java", System.getProperty("java.version"));
                if (server.state() == Server.State.RUNNING) {
                    try {
                        s.put("players", Rcon.command(props, "list"));
                    } catch (IOException e) {
                        s.put("players", "rcon: " + e.getMessage());
                    }
                }
                return s;
            }
            case "command": {
                String cmd = Json.str(a, "cmd", "");
                if (cmd.isBlank()) throw new IllegalArgumentException("cmd is empty");
                if (server.state() != Server.State.RUNNING) throw new IllegalStateException("Minecraft is " + server.state().name().toLowerCase());
                return Rcon.command(props, cmd);
            }
            case "start":
                server.start(Json.bool(a, "update", false));
                return "starting";
            case "stop":
                work.submit(server::stop);
                return "stopping";
            case "restart": {
                boolean update = Json.bool(a, "update", false);
                work.submit(() -> server.restart(update));
                return update ? "restarting with a pack update" : "restarting";
            }
            case "console":
                return server.console((int) Math.min(Json.num(a, "lines", 50), 2000));
            case "follow":
                follow = Json.bool(a, "on", true);
                return follow ? "following the console" : "not following";
            case "logs":
                return files.tail(Json.str(a, "path", "logs/latest.log"), (int) Math.min(Json.num(a, "lines", 100), 5000));
            case "ls":
                return files.list(Json.str(a, "path", ""));
            case "read":
                return files.read(Json.str(a, "path", ""));
            case "write":
                return files.write(Json.str(a, "path", ""), Json.str(a, "data", ""));
            case "delete":
                return files.delete(Json.str(a, "path", ""));
            case "launcher-update":
                return updateSelf(Json.str(a, "url", ""), Json.str(a, "sha256", ""));
            default:
                throw new IllegalArgumentException("unknown op " + op);
        }
    }

    /** Replaces the launcher jar; the new one runs from the next start of the container. */
    private Object updateSelf(String from, String sha256) throws Exception {
        if (!from.startsWith(UPDATE_PREFIX)) throw new IllegalArgumentException("updates only come from " + UPDATE_PREFIX);
        if (!sha256.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("sha256 missing");
        if (ownJar == null || !Files.isRegularFile(ownJar)) throw new IllegalStateException("not running from a jar");
        Path tmp = ownJar.resolveSibling(ownJar.getFileName() + ".new");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(from)).timeout(Duration.ofMinutes(2)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        String got = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(tmp)));
        if (!got.equals(sha256)) {
            Files.deleteIfExists(tmp);
            throw new IOException("checksum mismatch: " + got);
        }
        Files.move(tmp, ownJar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return "installed, active from the next start of the container (" + Instant.now() + ")";
    }
}
