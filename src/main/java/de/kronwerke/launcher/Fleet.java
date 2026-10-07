package de.kronwerke.launcher;

import de.kronwerke.boot.Boot;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Every server the launcher runs, and what they share: the pack, the CPUs, the memory, the
 * order in which they start and stop. One pack update covers all of them, because the second
 * and every further server links the first one's mods and configs.
 */
public final class Fleet {
    /** What a second server takes from the root as links. config is linked entry by entry. */
    static final List<String> SHARED = List.of("mods", "defaultconfigs", "kubejs", "libraries", "ops.json");
    /** Gigabytes the JVM needs beyond its heap, per server. */
    static final long OVERHEAD_GB = 3;

    private final Path root;
    private final Path home;
    private final Config cfg;
    private final String java;
    private final Pack pack;
    private final PrintStream out;
    private final Map<String, Server> servers = new LinkedHashMap<>();
    private final Object packLock = new Object();
    private final java.util.Set<String> neoforgeChecked = new java.util.HashSet<>();
    private volatile boolean updating;

    private final CountDownLatch done = new CountDownLatch(1);
    private final CountDownLatch started = new CountDownLatch(1);
    private volatile String result = "exit";
    private final ExecutorService work = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "fleet-work");
        t.setDaemon(true);
        return t;
    });

    private final Deque<Map<String, Object>> events = new ArrayDeque<>();
    private final List<Consumer<Map<String, Object>>> eventListeners = new ArrayList<>();
    private final List<Consumer<Server>> stateListeners = new ArrayList<>();
    private final Metrics container = new Metrics();
    private volatile Map<String, List<Integer>> pinned = Map.of();
    private final Network network = new Network(this);
    private final Alerts alerts;
    private final Schedule schedule;
    private final Backups backups = new Backups(this);
    private final Sessions sessions;

    public Fleet(Path root, Config cfg, String java, PrintStream out) throws IOException {
        this.root = root;
        this.home = Home.of(root);
        this.cfg = cfg;
        this.java = java;
        this.out = out;
        this.pack = new Pack(root, java, line -> out(null, line));
        for (Config.ServerConfig sc : Config.servers(root, cfg)) servers.put(sc.name(), new Server(sc, root, this));
        if (Boot.shared().remove("events") instanceof List<?> saved) {
            for (Object e : saved) events.addLast(Json.object(String.valueOf(e)));
        }
        this.alerts = new Alerts(this);
        this.schedule = new Schedule(this);
        this.sessions = new Sessions(this);
        network.onFeed(sessions::on);
    }

    // ---- what others see ----

    /** The launcher's own folder. */
    public Path home() {
        return home;
    }

    public Path root() {
        return root;
    }

    public Config config() {
        return cfg;
    }

    public Pack pack() {
        return pack;
    }

    public boolean updating() {
        return updating;
    }

    public Metrics container() {
        return container;
    }

    /** The servers in start order. */
    public List<Server> servers() {
        synchronized (servers) {
            return new ArrayList<>(servers.values());
        }
    }

    /** A server by name; empty or null means the first one. */
    public Server server(String name) {
        synchronized (servers) {
            if (name == null || name.isEmpty()) return servers.values().iterator().next();
            Server s = servers.get(name);
            if (s == null) throw new IllegalArgumentException("no server " + name);
            return s;
        }
    }

    /** File access inside a server's folder, with every key and the console's data hidden. */
    public ServerFiles files(Server s) {
        Path k = home;
        return new ServerFiles(s.dir(), k.resolve("link.key"), k.resolve("bus.key"), k.resolve("console"));
    }

    public Server main() {
        return server(null);
    }

    public Alerts alerts() {
        return alerts;
    }

    public Schedule schedule() {
        return schedule;
    }

    public Backups backups() {
        return backups;
    }

    public Sessions sessions() {
        return sessions;
    }

    /** Chat, joins, lists and the bus between the servers. */
    public Network network() {
        return network;
    }

    public Map<String, List<Integer>> pinned() {
        return pinned;
    }

    /** Every line of every server; the first server's lines go to the panel as they are. */
    void out(String server, String line) {
        if (server == null || server.equals(main().name())) out.println(line);
        else out.println("[" + server + "] " + line);
    }

    /** How the launcher's own lines start: "[name] ", with name from launcher.properties. */
    public String tag() {
        return "[" + (cfg.get("name").isEmpty() ? "Launcher" : cfg.get("name")) + "] ";
    }

    /** A note in the panel and in the timeline. */
    void note(String msg) {
        out.println(tag() + msg);
        event("launcher", null, msg);
    }

    public void onEvent(Consumer<Map<String, Object>> c) {
        synchronized (eventListeners) {
            eventListeners.add(c);
        }
    }

    public void removeEvent(Consumer<Map<String, Object>> c) {
        synchronized (eventListeners) {
            eventListeners.remove(c);
        }
    }

    public void onState(Consumer<Server> c) {
        synchronized (stateListeners) {
            stateListeners.add(c);
        }
    }

    /** The timeline: starts, crashes, updates, CPU moves. Newest last. */
    public List<Map<String, Object>> events(int n) {
        synchronized (events) {
            List<Map<String, Object>> all = new ArrayList<>(events);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    public void event(String kind, String server, String text) {
        Map<String, Object> e = Json.map("t", Instant.now().toString(), "kind", kind, "server", server, "text", text);
        synchronized (events) {
            events.addLast(e);
            while (events.size() > 500) events.removeFirst();
        }
        List<Consumer<Map<String, Object>>> ls;
        synchronized (eventListeners) {
            ls = new ArrayList<>(eventListeners);
        }
        for (var l : ls) {
            try {
                l.accept(e);
            } catch (RuntimeException ignored) {
                // a listener's problem
            }
        }
    }

    void changed(Server s) {
        event("state", s.name(), s.state().name().toLowerCase() + (s.detail().isEmpty() ? "" : ": " + s.detail()));
        List<Consumer<Server>> ls;
        synchronized (stateListeners) {
            ls = new ArrayList<>(stateListeners);
        }
        for (var l : ls) l.accept(s);
        if (s.state() == Server.State.RUNNING || s.state() == Server.State.STOPPED) work.submit(() -> applyCpu(null));
        switch (s.state()) {
            case CRASHED -> {
                sessions.serverDown(s.name());
                alerts.send("crash", s.name(), "crashed" + (s.detail().isEmpty() ? "" : ": " + s.detail()));
            }
            case STOPPED -> {
                sessions.serverDown(s.name());
                if (!updating) alerts.send("stop", s.name(), "stopped");
            }
            case RUNNING -> alerts.send("start", s.name(), "running");
            default -> {
                // nothing to tell
            }
        }
    }

    // ---- running ----

    /**
     * Runs until the launcher should end. Returns "exit" when the container stops, "reload"
     * when the next launcher version takes over.
     */
    public String run() throws InterruptedException {
        boolean adopted = servers().stream().anyMatch(s -> Boot.shared().get("pump:" + s.name()) != null);
        if (!adopted && !cfg.get("pack.url").isEmpty()) {
            // a fresh container: bring the pack up to date before anything starts
            try {
                updatePack();
            } catch (Exception e) {
                note("Pack update failed: " + e.getMessage() + "; starting with the pack as it is");
            }
        }
        network.start();
        schedule.start();
        for (Server s : servers()) s.begin();
        started.countDown();
        Thread monitor = new Thread(this::monitor, "monitor");
        monitor.setDaemon(true);
        monitor.start();
        done.await();
        monitor.interrupt();
        return result;
    }

    /** Waits until every server has taken over or started its process. */
    public void awaitStarted() throws InterruptedException {
        started.await();
    }

    /** The panel's stop: every server down, then the launcher ends. */
    public void shutdown() {
        List<Server> all = servers();
        Collections.reverse(all);
        List<Thread> ts = new ArrayList<>();
        for (Server s : all) {
            Thread t = new Thread(s::leave, "leave-" + s.name());
            t.start();
            ts.add(t);
        }
        for (Thread t : ts) {
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        network.close();
        schedule.stop();
        sessions.save();
        result = "exit";
        done.countDown();
    }

    /** Hands every running server to the next launcher version and ends this one. */
    public void reload() {
        note("Handing the servers to the next launcher");
        network.close();
        schedule.stop();
        sessions.save();
        for (Server s : servers()) s.detach();
        synchronized (events) {
            Boot.shared().put("events", events.stream().map(Json::write).toList());
        }
        result = "reload";
        done.countDown();
    }

    /** Stops every server, updates the pack, starts the ones that were running. */
    public void update() throws Exception {
        synchronized (packLock) {
            if (updating) throw new IllegalStateException("an update is already running");
            updating = true;
        }
        try {
            List<Server> all = servers();
            List<Server> wanted = all.stream().filter(Server::wanted).toList();
            event("update", null, "pack update: stopping " + wanted.size() + " servers");
            List<Server> reverse = new ArrayList<>(all);
            Collections.reverse(reverse);
            List<Thread> ts = new ArrayList<>();
            for (Server s : reverse) {
                Thread t = new Thread(s::stop, "stop-" + s.name());
                t.start();
                ts.add(t);
            }
            for (Thread t : ts) t.join();
            for (Server s : all) s.set(Server.State.UPDATING, "pack");
            try {
                updatePack();
            } finally {
                for (Server s : all) {
                    if (s.state() == Server.State.UPDATING) s.set(Server.State.STOPPED, "");
                }
                for (Server s : wanted) s.start();
            }
        } finally {
            synchronized (packLock) {
                updating = false;
            }
        }
    }

    private void updatePack() throws IOException, InterruptedException {
        synchronized (packLock) {
            String url = cfg.get("pack.url");
            if (url.isEmpty()) throw new IOException("pack.url is empty");
            Pack.Info before = pack.local();
            Pack.Info info = pack.fetch(url);
            pack.update(url);
            neoforgeChecked.clear();
            event("update", null, "pack " + (before == null ? "?" : before.version()) + " to " + info.version());
        }
    }

    /**
     * Everything a server needs before it starts. A NeoForge server needs the pack on disk
     * (or its own neoforge version) and NeoForge installed; a second server its links to the
     * first one's files. Returns the pack, or null for a server without one.
     */
    Pack.Info ready(Server s) throws IOException {
        Pack.Info info = null;
        if (s.type().equals("neoforge")) {
            synchronized (packLock) {
                info = pack.local();
                String version = !s.config().get("neoforge").isEmpty() ? s.config().get("neoforge") : info == null ? "" : info.neoforge();
                if (version.isEmpty()) throw new IOException("no NeoForge version: set pack.url, or neoforge in the server's file");
                if (info == null) info = new Pack.Info("", "", "", version);
                else if (!version.equals(info.neoforge())) info = new Pack.Info(info.name(), info.version(), info.minecraft(), version);
                if (!neoforgeChecked.contains(version + "|" + s.dir())) {
                    if (!s.dir().equals(root)) link(s);
                    try {
                        pack.ensureNeoForge(version, s.dir());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("interrupted");
                    }
                    neoforgeChecked.add(version + "|" + s.dir());
                }
            }
        }
        if (!s.dir().equals(root)) link(s);
        checkMemory(s);
        return info;
    }

    /**
     * Makes a second server's folder: links for the shared folders, config linked entry by
     * entry so a server can keep its own copy of one (Simple Voice Chat's, for its port), and
     * the EULA as accepted in the root.
     */
    void link(Server s) throws IOException {
        Path dir = s.dir();
        Files.createDirectories(dir);
        List<String> share = s.config().get("share").isEmpty()
                ? (s.type().equals("neoforge") ? SHARED : List.of())
                : List.of(s.config().get("share").split(","));
        for (String raw : share) {
            String name = raw.trim();
            if (name.isEmpty() || name.equals("config")) continue;
            Path target = root.resolve(name), at = dir.resolve(name);
            if (!Files.exists(target)) continue;
            if (Files.isSymbolicLink(at)) continue;
            if (Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(dir.relativize(at) + " exists as its own copy; remove it so it can be shared");
            }
            Files.createSymbolicLink(at, dir.relativize(target));
        }
        List<String> own = new ArrayList<>(List.of(s.config().get("own").split(",")));
        if (!s.config().get("voice.port").isEmpty()) own.add("voicechat");
        own.replaceAll(String::trim);
        Path cfgDir = dir.resolve("config"), rootCfg = root.resolve("config");
        boolean shareConfig = s.config().get("share").isEmpty() ? s.type().equals("neoforge") : share.stream().anyMatch(x -> x.trim().equals("config"));
        if (shareConfig && Files.isDirectory(rootCfg)) {
            Files.createDirectories(cfgDir);
            try (Stream<Path> entries = Files.list(rootCfg)) {
                for (Path e : entries.toList()) {
                    String name = e.getFileName().toString();
                    Path at = cfgDir.resolve(name);
                    if (own.contains(name)) {
                        if (Files.isSymbolicLink(at)) Files.delete(at);
                        if (!Files.exists(at)) copy(e, at);
                    } else if (!Files.exists(at, LinkOption.NOFOLLOW_LINKS)) {
                        Files.createSymbolicLink(at, cfgDir.relativize(e));
                    }
                }
            }
        }
        Path eula = dir.resolve("eula.txt");
        if (s.minecraft() && !Files.exists(eula) && Files.exists(root.resolve("eula.txt"))) Files.copy(root.resolve("eula.txt"), eula);
        Path props = s.properties(), rootProps = root.resolve("server.properties");
        if (s.minecraft() && !Files.exists(props) && Files.exists(rootProps)) {
            // the first server's settings (whitelist, difficulty, view distance), with a world,
            // ports and RCON of its own
            List<String> keep = new ArrayList<>();
            for (String l : Files.readAllLines(rootProps, StandardCharsets.ISO_8859_1)) {
                if (!l.matches("(level-seed|level-name|server-port|query\\.port|rcon\\.port|rcon\\.password|enable-query)=.*")) keep.add(l);
            }
            Files.write(props, keep, StandardCharsets.ISO_8859_1);
        }
        network.linkLists(s, root);
    }

    private static void copy(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) {
            Files.copy(from, to, StandardCopyOption.COPY_ATTRIBUTES);
            return;
        }
        try (Stream<Path> walk = Files.walk(from)) {
            for (Path p : walk.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) Files.createDirectories(t);
                else Files.copy(p, t, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    /** Links a server's shared lists after it joined a network in the console. */
    public void linkLists(Server s) throws IOException {
        network.linkLists(s, root);
    }

    /** Heap of every server that runs or wants to, plus the JVM's own share, has to fit. */
    void checkMemory(Server starting) throws IOException {
        long limit = memoryLimitGb();
        if (limit <= 0) return;
        long sum = 0;
        for (Server s : servers()) {
            if (s != starting && !s.wanted() && s.pid() == 0) continue;
            long heap = gigabytes(s.config().get("memory"));
            if (heap > 0) sum += heap + OVERHEAD_GB;
        }
        if (sum > limit) {
            throw new IOException("the servers would need " + sum + " GB with this one, the container has " + limit
                    + " GB; lower a heap in " + root.relativize(home) + "/servers");
        }
    }

    public long memoryLimitGb() {
        if (!cfg.get("container.memory").isEmpty()) return cfg.number("container.memory", 0);
        String panel = System.getenv("SERVER_MEMORY");
        if (panel != null && panel.matches("[1-9][0-9]*")) return Long.parseLong(panel) / 1024;
        long max = Proc.containerMemory()[1];
        return max <= 0 ? 0 : max >> 30;
    }

    static long gigabytes(String mem) {
        if (mem == null || mem.isEmpty()) return 0;
        String m = mem.trim().toUpperCase();
        long n = Long.parseLong(m.replaceAll("[^0-9]", ""));
        if (m.endsWith("M")) return Math.max(1, n / 1024);
        if (m.endsWith("K")) return 1;
        return n;
    }

    /**
     * How a server is started. neoforge: java with NeoForge's argument file; jar: java -jar
     * with the server's jar (Paper, Fabric, vanilla, anything that is a jar); command: the
     * server's own command line, split at spaces, for everything else.
     */
    List<String> commandLine(Server s, Pack.Info info) throws IOException {
        Config sc = s.config();
        List<String> cmd = new ArrayList<>();
        String type = s.type();
        if (type.equals("command")) {
            for (String a : sc.get("command").split("\\s+")) if (!a.isBlank()) cmd.add(a);
            if (cmd.isEmpty()) throw new IOException("type=command needs command=...");
            return cmd;
        }
        cmd.add(sc.get("java").isEmpty() ? java : sc.get("java"));
        String mem = sc.get("memory");
        if (!mem.isEmpty()) {
            cmd.add("-Xms" + mem);
            cmd.add("-Xmx" + mem);
        }
        for (String a : (cfg.get("jvm.args") + " " + sc.get("jvm.args")).split("\\s+")) if (!a.isBlank()) cmd.add(a);
        cmd.add("-Dlauncher.server=" + s.name());
        cmd.add("-Dlauncher.role=" + (sc.get("role").isEmpty() ? s.name() : sc.get("role")));
        if (!cfg.get("bus.port").isEmpty()) {
            cmd.add("-Dlauncher.bus=127.0.0.1:" + cfg.get("bus.port"));
            cmd.add("-Dlauncher.bus.key=" + home.resolve("bus.key"));
        }
        if (type.equals("jar")) {
            String jar = sc.get("jar");
            if (jar.isEmpty()) throw new IOException("type=jar needs jar=...");
            if (!Files.exists(s.dir().resolve(jar))) throw new IOException(jar + " is not in " + root.relativize(s.dir()));
            cmd.add("-jar");
            cmd.add(jar);
        } else {
            if (Files.exists(s.dir().resolve("user_jvm_args.txt"))) cmd.add("@user_jvm_args.txt");
            cmd.add("@" + s.dir().relativize(Pack.argsFile(s.dir(), info.neoforge())));
        }
        String args = sc.get("args").isEmpty() ? "nogui" : sc.get("args");
        for (String a : args.split("\\s+")) if (!a.isBlank() && !a.equals("-")) cmd.add(a);
        return cmd;
    }

    // ---- CPU ----

    /**
     * Splits the container's CPUs between the running servers by their shares and pins each
     * one. Does nothing unless cpu.pin is on and taskset works.
     */
    synchronized void applyCpu(Server ignored) {
        if (!cfg.flag("cpu.pin")) {
            pinned = Map.of();
            return;
        }
        List<Integer> cpus = Proc.allowedCpus();
        List<Server> running = servers().stream().filter(s -> s.pid() > 0).toList();
        if (cpus.isEmpty() || running.isEmpty()) return;
        Map<String, Integer> shares = new LinkedHashMap<>();
        for (Server s : running) shares.put(s.name(), Math.max(1, s.config().number("cpu.share", 1)));
        Map<String, List<Integer>> plan = split(cpus, shares);
        Map<String, List<Integer>> done = new LinkedHashMap<>();
        for (Server s : running) {
            List<Integer> mine = plan.get(s.name());
            if (Proc.pin(s.pid(), mine)) done.put(s.name(), mine);
        }
        if (!done.equals(pinned)) {
            pinned = Map.copyOf(done);
            event("cpu", null, "CPUs: " + done);
        }
    }

    /** Splits CPUs into consecutive blocks by share, every server at least one. */
    static Map<String, List<Integer>> split(List<Integer> cpus, Map<String, Integer> shares) {
        Map<String, List<Integer>> out = new LinkedHashMap<>();
        int total = shares.values().stream().mapToInt(Integer::intValue).sum();
        int n = cpus.size(), at = 0, left = shares.size();
        int given = 0;
        if (n < shares.size()) {
            // fewer CPUs than servers: everyone shares all of them
            for (String name : shares.keySet()) out.put(name, cpus);
            return out;
        }
        for (var e : shares.entrySet()) {
            left--;
            int count;
            if (left == 0) {
                count = n - at;
            } else {
                given += e.getValue();
                count = (int) Math.round((double) n * given / total) - at;
                count = Math.max(1, Math.min(count, n - at - left));
            }
            out.put(e.getKey(), new ArrayList<>(cpus.subList(at, at + count)));
            at += count;
        }
        return out;
    }

    /** Applies pinning or its end at once, after cpu.pin changed. */
    public void applyCpuNow() {
        applyCpu(null);
        if (!cfg.flag("cpu.pin")) {
            // unpinned: every server may use every CPU again
            List<Integer> all = Proc.allowedCpus();
            for (Server s : servers()) if (s.pid() > 0 && !all.isEmpty()) Proc.pin(s.pid(), all);
        }
    }

    /** Changes a server's CPU share, in its file and at once. */
    public void share(Server s, int share) throws IOException {
        if (share < 1 || share > 64) throw new IllegalArgumentException("share must be 1 to 64");
        s.config().set("cpu.share", Integer.toString(share));
        event("cpu", s.name(), "share " + share);
        work.submit(() -> applyCpu(null));
    }

    // ---- watching ----

    private long containerCpuLast = -1, containerAt;

    /** Every ten seconds: CPU, memory, tick time and players of every server. */
    private volatile long diskUsed = -1;

    /** Bytes the container's folder uses, counted every five minutes; -1 before the first count. */
    public long diskUsed() {
        return diskUsed;
    }

    /** The disk quota: container.disk in GB, or -1 when unknown (panels do not tell). */
    public long diskLimit() {
        long gb = cfg.number("container.disk", 0);
        return gb > 0 ? gb << 30 : -1;
    }

    private void monitor() {
        long balancedAt = 0, countedAt = 0;
        while (true) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            for (Server s : servers()) work.submit(() -> sample(s, now));
            work.submit(network::tick);
            if (now - countedAt > 300_000) {
                countedAt = now;
                work.submit(() -> diskUsed = Proc.folderSize(root));
            }
            long c = Proc.containerCpuMillis();
            long[] mem = Proc.containerMemory();
            double pct = -1;
            if (c >= 0 && containerCpuLast >= 0) pct = 100.0 * (c - containerCpuLast) / (now - containerAt);
            containerCpuLast = c;
            containerAt = now;
            container.add(new Metrics.Sample(now / 1000, pct, mem[0], -1, -1, -1));
            if (cfg.flag("cpu.balance") && cfg.flag("cpu.pin") && now - balancedAt > 60_000) {
                if (balance()) balancedAt = now;
            }
        }
    }

    private void sample(Server s, long now) {
        long pid = s.pid();
        Metrics m = s.metrics();
        if (pid == 0) {
            m.reset();
            return;
        }
        double cpu = m.cpu(Proc.cpuMillis(pid), now);
        long rss = Proc.rss(pid);
        double tps = -1, mspt = -1;
        int players = -1;
        if (s.state() == Server.State.RUNNING && s.rcon()) {
            try {
                List<String> names = Server.players(s.command("list"));
                m.players(names);
                players = names.size();
                if (s.type().equals("neoforge")) {
                    String t = s.command("neoforge tps");
                    double[] v = Metrics.tps(t);
                    mspt = v[0];
                    tps = v[1];
                    m.dimensions(Metrics.dimensions(t));
                }
            } catch (IOException | RuntimeException ignored) {
                // busy or starting; the next sample tries again
            }
        }
        m.add(new Metrics.Sample(now / 1000, cpu, rss, tps, mspt, players));
        double mean = m.meanMspt(6);
        if (mean > alerts.mspt()) alerts.send("mspt", s.name(), String.format(Locale.ROOT, "tick time %.0f ms over the last minute", mean));
    }

    /**
     * Moves one CPU share to the busiest server when it struggles and another has room. True
     * when it moved something.
     */
    boolean balance() {
        List<Server> running = servers().stream().filter(s -> s.state() == Server.State.RUNNING).toList();
        if (running.size() < 2) return false;
        Server hot = null, cool = null;
        double hotMs = 0, coolMs = Double.MAX_VALUE;
        for (Server s : running) {
            double ms = s.metrics().meanMspt(6);
            if (ms < 0) continue;
            if (ms > hotMs) {
                hotMs = ms;
                hot = s;
            }
            if (ms < coolMs && s.config().number("cpu.share", 1) > 1) {
                coolMs = ms;
                cool = s;
            }
        }
        if (hot == null || cool == null || hot == cool || hotMs < 40 || coolMs > 25) return false;
        try {
            share(cool, cool.config().number("cpu.share", 1) - 1);
            share(hot, hot.config().number("cpu.share", 1) + 1);
            event("cpu", hot.name(), String.format("auto balance: %s at %.0f ms gets a share from %s at %.0f ms", hot.name(), hotMs, cool.name(), coolMs));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Runs a task off the caller's thread. */
    public void submit(Runnable r) {
        work.submit(r);
    }

    void awaitWork() throws InterruptedException {
        work.shutdown();
        work.awaitTermination(5, TimeUnit.SECONDS);
    }
}
