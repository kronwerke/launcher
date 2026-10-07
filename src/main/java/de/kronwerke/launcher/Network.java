package de.kronwerke.launcher;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Servers that belong together: chat, joins and leaves, the tab list, the whitelist and bans,
 * and whatever a mod wants to send to the other servers.
 * <p>
 * Two ways in. Any Minecraft server works without help: the launcher reads chat and joins from
 * its console and shows them on the others with tellraw over RCON (the bridge). A mod that
 * connects to the bus (bus.port, loopback only) does more: it reports chat and players itself,
 * gets the other servers' players for its tab list, can keep chat within a radius, and can
 * send its own messages to the other servers. Kronwerke Core is such a mod; docs/BUS.md has
 * the protocol.
 * <p>
 * Which servers belong together and what they share is in each server's file: network,
 * sync.chat, chat.radius, sync.joins, sync.tablist, sync.lists, sync.players.
 */
public final class Network {
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
    static final Pattern CHAT = Pattern.compile("\\]: (?:\\[Not Secure\\] )?<([A-Za-z0-9_]{2,16})> (.+)$");
    static final Pattern JOIN = Pattern.compile("\\]: ([A-Za-z0-9_]{2,16}) joined the game$");
    static final Pattern LEAVE = Pattern.compile("\\]: ([A-Za-z0-9_]{2,16}) left the game$");
    /** Commands that change a list every server of a network keeps. */
    static final List<String> MIRRORED = List.of("ban ", "ban-ip ", "pardon ", "pardon-ip ", "op ", "deop ", "whitelist add ", "whitelist remove ");
    /** Files a server of a network links from the first one's folder when sync.lists is on. */
    static final List<String> LIST_FILES = List.of("whitelist.json");

    /** What a server shares with the others of its network. */
    public record Policy(String network, String chat, int radius, boolean joins, boolean tablist, boolean lists, boolean players) {
        public static Policy of(Config c) {
            String chat = c.get("sync.chat").toLowerCase();
            if (!List.of("network", "server", "radius").contains(chat)) chat = "network";
            return new Policy(c.get("network"), chat, Math.max(1, Math.min(10_000, c.number("chat.radius", 100))),
                    !c.get("sync.joins").equalsIgnoreCase("false"), !c.get("sync.tablist").equalsIgnoreCase("false"),
                    !c.get("sync.lists").equalsIgnoreCase("false"), c.flag("sync.players"));
        }

        public boolean inNetwork() {
            return !network.isEmpty();
        }

        public Map<String, Object> json() {
            return Json.map("network", network, "chat", chat, "radius", radius, "joins", joins, "tablist", tablist,
                    "lists", lists, "players", players);
        }
    }

    /** The keys of a server's file that make its policy. */
    public static final List<String> KEYS = List.of("network", "sync.chat", "chat.radius", "sync.joins", "sync.tablist", "sync.lists", "sync.players", "label");

    private final Fleet fleet;
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private final Map<String, List<Object>> players = new ConcurrentHashMap<>();
    private final Deque<Map<String, Object>> feed = new ArrayDeque<>();
    private final List<Consumer<Map<String, Object>>> listeners = new ArrayList<>();
    private final Map<Path, Long> listTimes = new ConcurrentHashMap<>();
    private volatile ServerSocket socket;
    private volatile boolean closed;

    Network(Fleet fleet) {
        this.fleet = fleet;
    }

    // ---- what others see ----

    public Policy policy(Server s) {
        return Policy.of(s.config());
    }

    /** How the other servers name it: label, or its name. */
    public static String label(Server s) {
        String l = s.config().get("label");
        return l.isEmpty() ? s.name() : l;
    }

    /** The console's palette, by place in the start order, for servers without a colour. */
    static final List<String> PALETTE = List.of("#e5b451", "#8bb6dc", "#bb98f4", "#7fd0c8");

    String color(Server s) {
        String c = s.config().get("color");
        if (c.matches("#[0-9a-fA-F]{6}")) return c;
        int i = fleet.servers().indexOf(s);
        return PALETTE.get(Math.max(0, i) % PALETTE.size());
    }

    /** Servers of the same network as s, without s. */
    List<Server> mates(Server s) {
        String n = policy(s).network();
        List<Server> out = new ArrayList<>();
        if (n.isEmpty()) return out;
        for (Server o : fleet.servers()) if (o != s && policy(o).network().equals(n)) out.add(o);
        return out;
    }

    public boolean onBus(String server) {
        return peers.containsKey(server);
    }

    public boolean busOpen() {
        return socket != null;
    }

    /** Chat, joins, leaves and messages from the console, newest last. */
    public List<Map<String, Object>> feed(int n) {
        synchronized (feed) {
            List<Map<String, Object>> all = new ArrayList<>(feed);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    public void onFeed(Consumer<Map<String, Object>> c) {
        synchronized (listeners) {
            listeners.add(c);
        }
    }

    public void removeFeed(Consumer<Map<String, Object>> c) {
        synchronized (listeners) {
            listeners.remove(c);
        }
    }

    /** Players a mod reported for a server: name, uuid and whatever else it sent. */
    public List<Object> players(String server) {
        return players.getOrDefault(server, List.of());
    }

    /** Every server's sharing, for the console. */
    public List<Object> state() {
        List<Object> out = new ArrayList<>();
        for (Server s : fleet.servers()) {
            Map<String, Object> m = policy(s).json();
            m.put("server", s.name());
            m.put("label", label(s));
            m.put("minecraft", s.minecraft());
            m.put("rcon", s.rcon());
            m.put("bus", onBus(s.name()));
            m.put("running", s.state() == Server.State.RUNNING);
            out.add(m);
        }
        return out;
    }

    // ---- the bridge ----

    /** Every console line of every server comes by here. */
    void line(Server s, String raw) {
        if (!s.minecraft() || onBus(s.name())) return;
        String line = ANSI.matcher(raw).replaceAll("");
        if (line.indexOf("]: ") < 0) return;
        Matcher m = CHAT.matcher(line);
        if (m.find()) {
            chat(s, m.group(1), "", m.group(2), "network", null);
            return;
        }
        m = JOIN.matcher(line);
        if (m.find()) {
            presence(s, "join", m.group(1), "", Map.of());
            return;
        }
        m = LEAVE.matcher(line);
        if (m.find()) presence(s, "leave", m.group(1), "", Map.of());
    }

    void chat(Server s, String player, String uuid, String text, String scope, Object extra) {
        Policy p = policy(s);
        record(Json.map("kind", "chat", "server", s.name(), "player", player, "text", text, "scope", scope));
        if (!p.inNetwork() || !p.chat().equals("network") || !scope.equals("network")) return;
        Map<String, Object> msg = Json.map("op", "chat", "from", s.name(), "label", label(s), "color", color(s),
                "player", player, "uuid", uuid, "text", text);
        if (extra instanceof Map<?, ?>) msg.put("extra", extra);
        for (Server o : mates(s)) {
            if (policy(o).chat().equals("network")) deliver(o, msg);
        }
    }

    void presence(Server s, String kind, String player, String uuid, Map<String, Object> extra) {
        if (kind.equals("join") && maintenance(s) && !operator(s, player)) {
            kick(s, player);
            return;
        }
        Map<String, Object> e = Json.map("kind", kind, "server", s.name(), "player", player);
        e.putAll(extra);
        record(e);
        Policy p = policy(s);
        if (!p.inNetwork() || !p.joins()) return;
        Map<String, Object> msg = Json.map("op", kind, "from", s.name(), "label", label(s), "color", color(s), "player", player, "uuid", uuid);
        msg.putAll(extra);
        for (Server o : mates(s)) {
            if (policy(o).joins()) deliver(o, msg);
        }
    }

    /** A line from the console's people to the players of the given servers (all when empty). */
    public void say(List<String> servers, String who, String text) {
        if (text.isBlank() || text.length() > 256) throw new IllegalArgumentException("1 to 256 characters");
        List<Server> to = new ArrayList<>();
        for (Server s : fleet.servers()) {
            if (s.minecraft() && (servers.isEmpty() || servers.contains(s.name()))) to.add(s);
        }
        if (to.isEmpty()) throw new IllegalArgumentException("no Minecraft server to send to");
        record(Json.map("kind", "say", "server", servers.isEmpty() ? "*" : String.join(",", servers), "player", who, "text", text));
        Map<String, Object> msg = Json.map("op", "say", "who", who, "text", text);
        for (Server s : to) deliver(s, msg);
    }

    /** To a server: over the bus when its mod is there, else as tellraw over RCON. */
    void deliver(Server to, Map<String, Object> msg) {
        Peer p = peers.get(to.name());
        if (p != null) {
            p.send(msg);
            return;
        }
        if (!to.rcon() || to.state() != Server.State.RUNNING) return;
        String raw = tellraw(msg);
        if (raw == null) return;
        fleet.submit(() -> {
            try {
                to.command("tellraw @a " + raw);
            } catch (IOException | RuntimeException ignored) {
                // a server that is just stopping misses the line
            }
        });
    }

    /** The bridge's text for a message, or null for one only mods understand. */
    static String tellraw(Map<String, Object> m) {
        String op = Json.str(m, "op", "");
        String label = Json.str(m, "label", ""), color = Json.str(m, "color", "gray");
        Map<String, Object> mark = Json.map("text", "[" + label + "] ", "color", color);
        return switch (op) {
            case "chat" -> Json.write(List.of("", mark, Json.map("text", "<" + Json.str(m, "player", "") + "> " + Json.str(m, "text", ""))));
            case "join", "leave" -> Json.write(List.of("", mark, Json.map("translate", op.equals("join") ? "multiplayer.player.joined" : "multiplayer.player.left",
                    "with", List.of(Json.str(m, "player", "")), "color", "yellow")));
            case "say" -> Json.write(List.of("", Json.map("text", "[" + Json.str(m, "who", "") + "] ", "color", "gold"), Json.map("text", Json.str(m, "text", ""))));
            default -> null;
        };
    }

    private void record(Map<String, Object> e) {
        e.put("t", Instant.now().toString());
        synchronized (feed) {
            feed.addLast(e);
            while (feed.size() > 500) feed.removeFirst();
        }
        List<Consumer<Map<String, Object>>> ls;
        synchronized (listeners) {
            ls = new ArrayList<>(listeners);
        }
        for (var l : ls) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // a listener's problem
            }
        }
    }

    // ---- maintenance ----

    public static boolean maintenance(Server s) {
        return s.config().flag("maintenance");
    }

    static String maintenanceMessage(Server s, Config launcher) {
        String m = s.config().get("maintenance.message");
        if (!m.isEmpty()) return m;
        return launcher.get("console.language").equals("de") ? "Wartungsarbeiten, bis gleich." : "Maintenance, back soon.";
    }

    /** An operator by ops.json in the server's folder. */
    static boolean operator(Server s, String name) {
        try {
            Path f = s.dir().resolve("ops.json");
            if (!Files.exists(f)) return false;
            if (Json.parse(Files.readString(f)) instanceof List<?> l) {
                for (Object o : l) if (o instanceof Map<?, ?> m && name.equalsIgnoreCase(String.valueOf(m.get("name")))) return true;
            }
        } catch (IOException | RuntimeException ignored) {
            // unreadable: nobody is an operator
        }
        return false;
    }

    private void kick(Server s, String player) {
        if (!s.rcon() || s.state() != Server.State.RUNNING) return;
        String msg = maintenanceMessage(s, fleet.config());
        fleet.submit(() -> {
            try {
                s.command("kick " + player + " " + msg);
            } catch (IOException | RuntimeException ignored) {
                // gone already
            }
        });
    }

    /** Turns maintenance on or off; on sends everyone but operators away at once. Returns who was sent away. */
    public List<String> maintenance(Server s, boolean on, String message) throws IOException {
        if (message != null) {
            if (message.length() > 200 || message.contains("\n")) throw new IllegalArgumentException("one line, at most 200 characters");
            s.config().set("maintenance.message", message.trim());
        }
        s.config().set("maintenance", Boolean.toString(on));
        List<String> sent = new ArrayList<>();
        if (on) {
            for (String p : s.metrics().players()) {
                if (!operator(s, p)) {
                    kick(s, p);
                    sent.add(p);
                }
            }
        }
        fleet.event("maintenance", s.name(), on ? "maintenance on" + (sent.isEmpty() ? "" : ", sent away: " + String.join(", ", sent)) : "maintenance off");
        return sent;
    }

    // ---- lists ----

    /**
     * A command that changes a shared list (bans, operators, the whitelist) is run on the other
     * running servers of the network too, so their lists in memory stay the same.
     */
    public void mirror(Server from, String cmd) {
        String c = cmd.trim().replaceFirst("^/", "");
        String low = c.toLowerCase();
        if (MIRRORED.stream().noneMatch(low::startsWith)) return;
        Policy p = policy(from);
        if (!p.inNetwork() || !p.lists()) return;
        for (Server o : mates(from)) {
            if (!o.rcon() || o.state() != Server.State.RUNNING || !policy(o).lists()) continue;
            fleet.submit(() -> {
                try {
                    o.command(c);
                } catch (IOException | RuntimeException ignored) {
                    // it reads the shared file on its next start
                }
            });
        }
    }

    /**
     * Every ten seconds: a whitelist file that changed (a mod or a person on one server) is
     * read again by every running server that uses it.
     */
    void tick() {
        Map<Path, List<Server>> users = new LinkedHashMap<>();
        for (Server s : fleet.servers()) {
            Policy p = policy(s);
            if (!s.rcon() || !p.inNetwork() || !p.lists()) continue;
            for (String f : LIST_FILES) {
                Path at = s.dir().resolve(f);
                try {
                    if (Files.exists(at)) users.computeIfAbsent(at.toRealPath(), k -> new ArrayList<>()).add(s);
                } catch (IOException ignored) {
                    // gone in between
                }
            }
        }
        for (var e : users.entrySet()) {
            long t;
            try {
                t = Files.getLastModifiedTime(e.getKey()).toMillis();
            } catch (IOException ex) {
                continue;
            }
            Long before = listTimes.put(e.getKey(), t);
            if (before == null || before == t || e.getValue().size() < 2) continue;
            for (Server s : e.getValue()) {
                if (s.state() != Server.State.RUNNING) continue;
                fleet.submit(() -> {
                    try {
                        s.command("whitelist reload");
                    } catch (IOException | RuntimeException ignored) {
                        // next change tries again
                    }
                });
            }
        }
    }

    /**
     * For a server's folder when it joins a network: the whitelist linked from the first
     * server's folder. A copy of its own stays (and only the mirrored commands keep it equal).
     */
    void linkLists(Server s, Path root) throws IOException {
        Policy p = policy(s);
        if (!s.minecraft() || !p.inNetwork() || !p.lists() || s.dir().equals(root)) return;
        for (String f : LIST_FILES) {
            Path target = root.resolve(f), at = s.dir().resolve(f);
            if (!Files.exists(target) || Files.exists(at, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
            Files.createSymbolicLink(at, s.dir().relativize(target));
        }
    }

    /** A policy changed in the console: every mod on the bus gets the new state. */
    public void changed() {
        for (Peer p : peers.values()) {
            try {
                p.send(welcome(fleet.server(p.server), "policy"));
            } catch (RuntimeException ignored) {
                // a server that was removed
            }
        }
    }

    // ---- the bus ----

    /** Closes the bus and opens it again with the port as it is now. */
    public void reopen() {
        close();
        start();
    }

    /** Opens the bus when bus.port is set. */
    void start() {
        closed = false;
        String port = fleet.config().get("bus.port");
        if (port.isEmpty()) return;
        try {
            key();
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(port)));
            socket = ss;
            Thread t = new Thread(() -> accept(ss), "bus");
            t.setDaemon(true);
            t.start();
        } catch (IOException | RuntimeException e) {
            fleet.note("Bus could not open on " + port + ": " + e.getMessage());
        }
    }

    void close() {
        closed = true;
        ServerSocket ss = socket;
        socket = null;
        if (ss != null) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closing anyway
            }
        }
        for (Peer p : peers.values()) p.close();
        peers.clear();
    }

    /** The bus key, made on first use; only the container's own processes can read it. */
    byte[] key() throws IOException {
        Path f = fleet.home().resolve("bus.key");
        if (!Files.exists(f)) {
            byte[] b = new byte[32];
            new SecureRandom().nextBytes(b);
            Files.writeString(f, HexFormat.of().formatHex(b) + "\n");
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // not a POSIX file system
            }
        }
        return Files.readString(f).trim().getBytes(StandardCharsets.UTF_8);
    }

    static String mac(byte[] key, String text) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Takes connections until this socket is closed (a reopen makes a new one). */
    private void accept(ServerSocket ss) {
        while (socket == ss) {
            try {
                Socket c = ss.accept();
                Thread t = new Thread(() -> serve(c), "bus-peer");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (socket != ss) return;
            }
        }
    }

    private void serve(Socket c) {
        Peer p = null;
        try (c) {
            c.setSoTimeout(15_000);
            BufferedReader in = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter out = new BufferedWriter(new OutputStreamWriter(c.getOutputStream(), StandardCharsets.UTF_8));
            byte[] n = new byte[16];
            new SecureRandom().nextBytes(n);
            String nonce = HexFormat.of().formatHex(n);
            out.write(Json.write(Json.map("op", "hello", "nonce", nonce, "launcher", Main.VERSION)) + "\n");
            out.flush();
            String first = in.readLine();
            if (first == null || first.length() > 4096) return;
            Map<String, Object> auth = Json.object(first);
            String name = Json.str(auth, "server", "");
            String expected = mac(key(), nonce + ":" + name);
            if (!"auth".equals(auth.get("op")) || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    Json.str(auth, "mac", "").getBytes(StandardCharsets.UTF_8))) {
                out.write(Json.write(Json.map("op", "error", "text", "wrong key")) + "\n");
                out.flush();
                return;
            }
            Server s = fleet.server(name);
            c.setSoTimeout(0);
            p = new Peer(name, c, out);
            Peer old = peers.put(name, p);
            if (old != null) old.close();
            p.send(welcome(s, "welcome"));
            fleet.event("bus", name, "connected" + (auth.get("mod") == null ? "" : " (" + auth.get("mod") + ")"));
            announce(s);
            for (String l; (l = in.readLine()) != null; ) {
                if (l.length() > 1 << 20) continue;
                try {
                    handle(s, p, Json.object(l));
                } catch (RuntimeException e) {
                    p.send(Json.map("op", "error", "text", String.valueOf(e.getMessage())));
                }
            }
        } catch (IOException | RuntimeException e) {
            // gone
        } finally {
            if (p != null && peers.remove(p.server, p)) {
                players.remove(p.server);
                if (!closed) {
                    fleet.event("bus", p.server, "disconnected");
                    try {
                        Server s = fleet.server(p.server);
                        spread(s, Json.map("op", "players", "from", s.name(), "label", label(s), "color", color(s), "list", List.of()), Policy::tablist);
                        announce(s);
                    } catch (RuntimeException ignored) {
                        // removed
                    }
                }
            }
        }
    }

    /** The first message after the key was right, and the same shape when anything changes. */
    Map<String, Object> welcome(Server s, String op) {
        List<Object> mates = new ArrayList<>();
        Map<String, Object> others = new LinkedHashMap<>();
        for (Server o : mates(s)) {
            mates.add(Json.map("name", o.name(), "label", label(o), "color", color(o), "bus", onBus(o.name()),
                    "running", o.state() == Server.State.RUNNING, "policy", policy(o).json(),
                    "role", role(o), "host", fleet.config().get("public.host"), "port", port(o)));
            if (policy(o).tablist()) others.put(o.name(), players(o.name()));
        }
        java.time.ZonedDateTime reset = fleet.schedule().nextReset(s.name());
        return Json.map("op", op, "server", s.name(), "label", label(s), "color", color(s), "policy", policy(s).json(),
                "peers", mates, "players", others, "role", role(s), "host", fleet.config().get("public.host"), "port", port(s),
                "reset", reset == null ? null : reset.toInstant().toString());
    }

    static String role(Server s) {
        return s.config().get("role").isEmpty() ? s.name() : s.config().get("role");
    }

    /** The game port players connect to: port= in its file, or server-port in server.properties. */
    static String port(Server s) {
        if (!s.config().get("port").isEmpty()) return s.config().get("port");
        try {
            for (String l : Files.readAllLines(s.properties(), StandardCharsets.ISO_8859_1)) {
                if (l.startsWith("server-port=")) return l.substring(12).trim();
            }
        } catch (IOException ignored) {
            // no file yet
        }
        return "25565";
    }

    // ---- evacuation ----

    private final Map<String, java.util.concurrent.CompletableFuture<Boolean>> evacuations = new ConcurrentHashMap<>();

    /**
     * Asks the mod of a server to send every player to another server of the network (before a
     * world reset), and waits until it says done. False without a mod on the bus or after the time.
     */
    public boolean evacuate(Server s, long millis) {
        Peer p = peers.get(s.name());
        if (p == null) return false;
        var f = new java.util.concurrent.CompletableFuture<Boolean>();
        evacuations.put(s.name(), f);
        p.send(Json.map("op", "evacuate"));
        try {
            return f.get(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        } finally {
            evacuations.remove(s.name());
        }
    }

    /** Tells the other mods of the network who is on the bus now. */
    private void announce(Server s) {
        for (Server o : mates(s)) {
            Peer p = peers.get(o.name());
            if (p != null) p.send(welcome(o, "policy"));
        }
    }

    private void spread(Server from, Map<String, Object> msg, java.util.function.Predicate<Policy> wants) {
        Policy mine = policy(from);
        if (!mine.inNetwork() || !wants.test(mine)) return;
        for (Server o : mates(from)) {
            Peer p = peers.get(o.name());
            if (p != null && wants.test(policy(o))) p.send(msg);
        }
    }

    private void handle(Server s, Peer p, Map<String, Object> m) {
        String op = Json.str(m, "op", "");
        switch (op) {
            case "ping" -> p.send(Json.map("op", "pong", "t", m.get("t")));
            case "chat" -> chat(s, Json.str(m, "player", ""), Json.str(m, "uuid", ""), Json.str(m, "text", ""), Json.str(m, "scope", "network"), m.get("extra"));
            case "join", "leave" -> {
                Map<String, Object> extra = new LinkedHashMap<>();
                for (String k : List.of("to", "via")) if (m.get(k) != null) extra.put(k, String.valueOf(m.get(k)));
                if (m.get("extra") instanceof Map<?, ?> x) extra.put("extra", x);
                presence(s, op, Json.str(m, "player", ""), Json.str(m, "uuid", ""), extra);
            }
            case "players" -> {
                List<Object> list = m.get("list") instanceof List<?> l ? new ArrayList<>(l) : List.of();
                if (list.size() > 1000) list = list.subList(0, 1000);
                players.put(s.name(), list);
                spread(s, Json.map("op", "players", "from", s.name(), "label", label(s), "color", color(s), "list", list), Policy::tablist);
            }
            case "send" -> {
                // a mod's own message to one server of its network, or to all ("*")
                String to = Json.str(m, "to", "*"), topic = Json.str(m, "topic", "");
                if (topic.isEmpty()) throw new IllegalArgumentException("send needs a topic");
                if (topic.startsWith("player.") && !policy(s).players()) throw new IllegalArgumentException("sync.players is off for " + s.name());
                Map<String, Object> msg = Json.map("op", "message", "from", s.name(), "topic", topic, "data", m.get("data"), "id", m.get("id"));
                boolean any = false;
                for (Server o : mates(s)) {
                    if (!to.equals("*") && !to.equals(o.name())) continue;
                    if (topic.startsWith("player.") && !policy(o).players()) continue;
                    Peer q = peers.get(o.name());
                    if (q != null) {
                        q.send(msg);
                        any = true;
                    }
                }
                if (m.get("id") != null) p.send(Json.map("op", "sent", "id", m.get("id"), "delivered", any));
            }
            case "event" -> fleet.event("bus", s.name(), Json.str(m, "text", ""));
            case "evacuated" -> {
                var f = evacuations.get(s.name());
                if (f != null) f.complete(true);
            }
            default -> throw new IllegalArgumentException("unknown op " + op);
        }
    }

    /** One mod on the bus. */
    static final class Peer {
        final String server;
        private final Socket socket;
        private final BufferedWriter out;

        Peer(String server, Socket socket, BufferedWriter out) {
            this.server = server;
            this.socket = socket;
            this.out = out;
        }

        synchronized void send(Map<String, Object> m) {
            try {
                out.write(Json.write(m));
                out.write('\n');
                out.flush();
            } catch (IOException e) {
                close();
            }
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }
}
