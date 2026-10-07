package de.kronwerke.launcher;

import de.kronwerke.boot.Boot;
import de.kronwerke.boot.Pump;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One Minecraft server: its folder, its process and the loop that keeps it running. The
 * {@link Fleet} owns the pack and decides the order; a server only starts, stops and watches
 * its own process.
 * <p>
 * The process is held in a {@link Pump}, so a launcher reload hands it to the next version
 * without a restart.
 */
public final class Server {
    public enum State { STOPPED, UPDATING, STARTING, RUNNING, STOPPING, CRASHED }

    private final String name;
    private final Path dir;
    private final Config cfg;
    private final Fleet fleet;

    private final Object lock = new Object();
    private State state = State.STOPPED;
    private Instant since = Instant.now();
    private String detail = "";
    private Pump child;
    private boolean wantRunning;
    private boolean leaving;
    private boolean detaching;
    private Thread loop;
    private final Deque<Instant> crashes = new ArrayDeque<>();
    private int starts;

    private final Deque<String> console = new ArrayDeque<>();
    public static final int CONSOLE_LINES = 5000;
    private final List<Consumer<String>> consoleListeners = new ArrayList<>();
    private final List<Runnable> stateListeners = new ArrayList<>();
    private final Metrics metrics = new Metrics();

    Server(Config.ServerConfig sc, Path root, Fleet fleet) {
        this.name = sc.name();
        this.cfg = sc.cfg();
        this.dir = root.resolve(sc.dir()).normalize();
        this.fleet = fleet;
    }

    // ---- what others see ----

    public String name() {
        return name;
    }

    public Path dir() {
        return dir;
    }

    public Config config() {
        return cfg;
    }

    Pack fleetPack() {
        return fleet.pack();
    }

    public Metrics metrics() {
        return metrics;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    public Instant since() {
        synchronized (lock) {
            return since;
        }
    }

    public String detail() {
        synchronized (lock) {
            return detail;
        }
    }

    public int starts() {
        synchronized (lock) {
            return starts;
        }
    }

    public boolean wanted() {
        synchronized (lock) {
            return wantRunning;
        }
    }

    public long pid() {
        synchronized (lock) {
            return child != null && child.process().isAlive() ? child.process().pid() : 0;
        }
    }

    /** neoforge, jar or command. */
    public String type() {
        String t = cfg.get("type");
        return t.isEmpty() ? "neoforge" : t;
    }

    /** A Minecraft server: EULA, server.properties, the "Done" line. */
    public boolean minecraft() {
        return !type().equals("command");
    }

    /** Commands go over RCON (rcon=false turns it off for a Minecraft server). */
    public boolean rcon() {
        return minecraft() && !cfg.get("rcon").equalsIgnoreCase("false");
    }

    public Path properties() {
        return dir.resolve("server.properties");
    }

    public List<String> console(int n) {
        synchronized (console) {
            List<String> all = new ArrayList<>(console);
            return new ArrayList<>(all.subList(Math.max(0, all.size() - n), all.size()));
        }
    }

    public void onConsole(Consumer<String> c) {
        synchronized (consoleListeners) {
            consoleListeners.add(c);
        }
    }

    public void removeConsole(Consumer<String> c) {
        synchronized (consoleListeners) {
            consoleListeners.remove(c);
        }
    }

    public void onState(Runnable r) {
        synchronized (stateListeners) {
            stateListeners.add(r);
        }
    }

    /** A line for the console: the panel, the ring buffer, whoever follows. */
    void print(String line) {
        fleet.out(name, line);
        synchronized (console) {
            console.addLast(line);
            while (console.size() > CONSOLE_LINES) console.removeFirst();
        }
        List<Consumer<String>> ls;
        synchronized (consoleListeners) {
            ls = new ArrayList<>(consoleListeners);
        }
        for (Consumer<String> c : ls) {
            try {
                c.accept(line);
            } catch (RuntimeException ignored) {
                // one listener must not stop the others
            }
        }
        watch(line);
        fleet.network().line(this, line);
    }

    void note(String msg) {
        print(fleet.tag() + msg);
    }

    /** A launcher note in this server's console, for parts outside this package. */
    public void notice(String msg) {
        note(msg);
    }

    void set(State s, String why) {
        synchronized (lock) {
            state = s;
            detail = why;
            since = Instant.now();
            lock.notifyAll();
        }
        note((minecraft() ? "Minecraft " : "Process ") + s.name().toLowerCase() + (why.isEmpty() ? "" : ": " + why));
        fleet.changed(this);
        List<Runnable> ls;
        synchronized (stateListeners) {
            ls = new ArrayList<>(stateListeners);
        }
        for (Runnable r : ls) r.run();
    }

    // ---- control ----

    /** Wants the server running. Clears the crash count, so it also starts after giving up. */
    public void start() {
        synchronized (lock) {
            wantRunning = true;
            crashes.clear();
            lock.notifyAll();
        }
    }

    /** Stops Minecraft and keeps it stopped. Returns when it is down. */
    public void stop() {
        synchronized (lock) {
            wantRunning = false;
            lock.notifyAll();
        }
        stopChild();
        settle();
    }

    /** Waits until the supervisor has seen the exit, so the next state change comes after its own. */
    private void settle() {
        long until = System.currentTimeMillis() + 10_000;
        synchronized (lock) {
            while ((child != null || state == State.STOPPING) && System.currentTimeMillis() < until) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    /** Stops Minecraft and starts it again. */
    public void restart() {
        stopChild();
        start();
    }

    /** The end: stop Minecraft and the loop with it. */
    void leave() {
        synchronized (lock) {
            leaving = true;
            wantRunning = false;
            lock.notifyAll();
        }
        stopChild();
        join();
    }

    /** For a reload: let go of the process without stopping it. */
    void detach() {
        Pump p;
        synchronized (lock) {
            detaching = true;
            p = child;
            lock.notifyAll();
        }
        Map<String, Object> shared = Boot.shared();
        if (p != null && p.process().isAlive()) {
            p.detach();
            shared.put("pump:" + name, p);
        } else {
            shared.remove("pump:" + name);
        }
        synchronized (console) {
            shared.put("console:" + name, new ArrayList<>(console));
        }
        shared.put("metrics:" + name, metrics.save());
        synchronized (lock) {
            shared.put("server:" + name, Json.write(Json.map("state", state.name(), "detail", detail, "since", since.toString(),
                    "starts", starts, "want", wantRunning)));
        }
        if (loop != null) loop.interrupt();
        join();
    }

    private void join() {
        Thread t = loop;
        if (t == null || t == Thread.currentThread()) return;
        try {
            t.join(200_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends a console line to Minecraft. */
    public boolean send(String line) {
        Pump p;
        synchronized (lock) {
            p = child;
        }
        return p != null && p.send(line);
    }

    /** Runs a command over RCON and returns the answer. */
    public String command(String cmd) throws IOException {
        if (state() != State.RUNNING) throw new IllegalStateException(name + " is " + state().name().toLowerCase());
        if (!rcon()) {
            if (!send(cmd)) throw new IOException("not running");
            return "";
        }
        return Rcon.command(properties(), cmd);
    }

    private void stopChild() {
        Pump p;
        synchronized (lock) {
            p = child;
            if (p == null || !p.process().isAlive()) return;
            if (state != State.STOPPING) {
                state = State.STOPPING;
                since = Instant.now();
            }
        }
        note("Stopping");
        fleet.changed(this);
        String stop = cfg.get("stop").isEmpty() ? (minecraft() ? "stop" : "") : cfg.get("stop");
        if (stop.isEmpty() || stop.equals("-")) p.process().destroy();
        else p.send(stop);
        try {
            if (!p.process().waitFor(150, TimeUnit.SECONDS)) {
                note("Did not stop in 150 seconds, killing it");
                p.process().destroyForcibly();
                p.process().waitFor(20, TimeUnit.SECONDS);
            }
            // the last lines still arrive after the exit
            p.awaitEnd();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Starts the supervisor thread, after taking over a process a previous launcher left running. */
    void begin() {
        Object adopted = Boot.shared().get("pump:" + name);
        Object saved = Boot.shared().get("server:" + name);
        if (Boot.shared().remove("metrics:" + name) instanceof List<?> old) metrics.restore(old);
        if (Boot.shared().remove("console:" + name) instanceof List<?> lines) {
            synchronized (console) {
                for (Object l : lines) console.addLast(String.valueOf(l));
            }
        }
        synchronized (lock) {
            wantRunning = cfg.flag("autostart");
            if (saved instanceof String s) {
                Map<String, Object> m = Json.object(s);
                starts = (int) Json.num(m, "starts", 0);
                wantRunning = Json.bool(m, "want", wantRunning);
                try {
                    state = State.valueOf(Json.str(m, "state", "STOPPED"));
                    since = Instant.parse(Json.str(m, "since", Instant.now().toString()));
                } catch (RuntimeException ignored) {
                    // an older launcher's names
                }
                detail = Json.str(m, "detail", "");
            }
            if (adopted instanceof Pump p && p.process().isAlive()) {
                child = p;
                if (state != State.RUNNING && state != State.STARTING) state = State.RUNNING;
            } else if (state == State.RUNNING || state == State.STARTING || state == State.STOPPING) {
                state = State.STOPPED;
            }
        }
        Pump p = adopted instanceof Pump a ? a : null;
        loop = new Thread(() -> supervise(p), "server-" + name);
        loop.start();
    }

    /** The supervisor loop: start, wait, start again after a crash. */
    private void supervise(Pump adopted) {
        try {
            if (adopted != null && adopted.process().isAlive()) {
                note("Took over the running process (pid " + adopted.process().pid() + ")");
                adopted.attach(this::print);
                fleet.applyCpu(this);
                if (afterExit(waitFor(adopted))) return;
            }
            while (true) {
                synchronized (lock) {
                    while (!wantRunning && !leaving && !detaching) lock.wait();
                    if (leaving || detaching) return;
                }
                int code = runOnce();
                if (afterExit(code)) return;
            }
        } catch (InterruptedException e) {
            // detached for a reload, or the launcher is ending
        }
    }

    /** Handles an ended process. True when the loop should end. */
    private boolean afterExit(int code) throws InterruptedException {
        boolean crashed, ending;
        synchronized (lock) {
            child = null;
            if (detaching) return true;
            ending = leaving;
            crashed = wantRunning && state != State.STOPPING && !leaving;
        }
        if (ending) {
            set(State.STOPPED, code == Integer.MIN_VALUE ? "" : "exit code " + code);
            return true;
        }
        if (!crashed) {
            String why = code != Integer.MIN_VALUE ? "exit code " + code : detail().startsWith("eula") ? detail() : "";
            set(State.STOPPED, why);
            return false;
        }
        set(State.CRASHED, code == Integer.MIN_VALUE ? detail() : "exit code " + code);
        boolean again;
        synchronized (lock) {
            Instant now = Instant.now();
            crashes.addLast(now);
            while (!crashes.isEmpty() && crashes.peekFirst().isBefore(now.minusSeconds(600))) crashes.removeFirst();
            again = cfg.flag("restart.on.crash") && crashes.size() < 3;
            if (!again) wantRunning = false;
        }
        if (again) {
            note("Starting again in 15 seconds");
            synchronized (lock) {
                lock.wait(15000);
            }
        } else {
            note("Three crashes in ten minutes: staying stopped until someone starts it");
            fleet.alerts().send("down", name, "three crashes in ten minutes; stays stopped until someone starts it");
        }
        return false;
    }

    private int waitFor(Pump p) throws InterruptedException {
        int code = p.process().waitFor();
        p.awaitEnd();
        return code;
    }

    private boolean eulaAccepted() {
        try {
            for (String l : Files.readAllLines(dir.resolve("eula.txt"))) {
                if (l.trim().equalsIgnoreCase("eula=true")) return true;
            }
        } catch (IOException e) {
            // no eula.txt yet
        }
        return false;
    }

    /** Prepare, start, wait for the exit. Integer.MIN_VALUE when it never started. */
    private int runOnce() throws InterruptedException {
        Pack.Info info;
        List<String> cmd;
        try {
            info = fleet.ready(this);
            prepare();
            cmd = fleet.commandLine(this, info);
        } catch (IOException | RuntimeException e) {
            synchronized (lock) {
                detail = "preparing: " + e.getMessage();
            }
            note("Preparing failed: " + e.getMessage());
            return Integer.MIN_VALUE;
        }

        synchronized (lock) {
            // stopped while the pack was updating
            if (!wantRunning || leaving || detaching) return Integer.MIN_VALUE;
        }
        if (minecraft() && !eulaAccepted()) {
            note("Minecraft's EULA is not accepted yet. Read https://aka.ms/MinecraftEULA, put eula=true into eula.txt, then start it again.");
            synchronized (lock) {
                wantRunning = false;
                detail = "eula.txt is not accepted";
            }
            return Integer.MIN_VALUE;
        }
        Pump p;
        doneAt = 0;
        try {
            set(State.STARTING, info == null ? type() : (info.version().isEmpty() ? "" : "pack " + info.version() + ", ") + "NeoForge " + info.neoforge());
            Process proc = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            p = Pump.start(proc, name);
        } catch (IOException e) {
            synchronized (lock) {
                detail = "start: " + e.getMessage();
            }
            note("Could not start Minecraft: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        synchronized (lock) {
            child = p;
            starts++;
        }
        metrics.reset();
        p.attach(this::print);
        if (!minecraft() && cfg.get("ready").isEmpty()) set(State.RUNNING, "");
        fleet.applyCpu(this);
        if (rcon()) runningSoon(p);
        return waitFor(p);
    }

    /** Falls back to "running" when no RCON line follows within two minutes of "Done". */
    private void runningSoon(Pump p) {
        Thread t = new Thread(() -> {
            long until = System.currentTimeMillis() + 30 * 60_000;
            try {
                while (System.currentTimeMillis() < until && p.process().isAlive() && state() == State.STARTING) {
                    Thread.sleep(5000);
                    if (doneAt > 0 && System.currentTimeMillis() - doneAt > 120_000) {
                        set(State.RUNNING, "no RCON line seen");
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
                // gone
            }
        }, "running-fallback-" + name);
        t.setDaemon(true);
        t.start();
    }

    private volatile long doneAt;
    private static final Pattern LIST = Pattern.compile("There are (\\d+) of a max of (\\d+) players online:?(.*)");

    /** Reads the console for the moments that matter. */
    private void watch(String line) {
        if (state() != State.STARTING) return;
        String ready = cfg.get("ready");
        if (!ready.isEmpty()) {
            if (readyPattern(ready).matcher(line).find()) set(State.RUNNING, "");
        } else if (rcon()) {
            // RCON comes up right after "Done"; commands only work from then on
            if (line.contains("RCON running on")) {
                set(State.RUNNING, "");
            } else if (line.contains("Done (") && line.contains("For help, type")) {
                doneAt = System.currentTimeMillis();
            }
        } else if (minecraft() && line.contains("Done (") && line.contains("For help, type")) {
            set(State.RUNNING, "");
        }
    }

    private Pattern ready;
    private String readySource;

    private Pattern readyPattern(String src) {
        if (!src.equals(readySource)) {
            ready = Pattern.compile(src);
            readySource = src;
        }
        return ready;
    }

    /** Ports, RCON and the files a second server needs, before every start. */
    void prepare() throws IOException {
        if (!minecraft()) return;
        Path props = properties();
        Map<String, String> set = new java.util.LinkedHashMap<>();
        if (!cfg.get("port").isEmpty()) set.put("server-port", cfg.get("port"));
        if (!cfg.get("rcon.port").isEmpty()) set.put("rcon.port", cfg.get("rcon.port"));
        if (!cfg.get("transfers").isEmpty()) set.put("accepts-transfers", cfg.get("transfers"));
        if (!set.isEmpty()) Properties.set(props, set);
        if (rcon()) Rcon.prepare(props);
        if (!cfg.get("voice.port").isEmpty()) {
            Path voice = dir.resolve("config/voicechat/voicechat-server.properties");
            if (Files.isSymbolicLink(dir.resolve("config/voicechat"))) {
                throw new IOException("config/voicechat is shared with another server; give this server its own folder for voice.port");
            }
            if (Files.exists(voice)) Properties.set(voice, Map.of("port", cfg.get("voice.port")));
        }
    }

    /** Parses the answer of `list`: players online and their names. */
    static List<String> players(String answer) {
        Matcher m = LIST.matcher(answer.replace('\n', ' '));
        if (!m.find()) return List.of();
        List<String> names = new ArrayList<>();
        for (String n : m.group(3).split(",")) {
            if (!n.isBlank()) names.add(n.trim());
        }
        return names;
    }

    /** Small helper to change keys in a .properties file without touching the rest. */
    public static final class Properties {
        public static void set(Path file, Map<String, String> values) throws IOException {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.ISO_8859_1)) : new ArrayList<>();
            Map<String, String> left = new java.util.LinkedHashMap<>(values);
            boolean changed = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                int eq = l.indexOf('=');
                if (eq <= 0 || l.startsWith("#")) continue;
                String k = l.substring(0, eq).trim();
                if (left.containsKey(k)) {
                    String v = left.remove(k);
                    if (!l.substring(eq + 1).trim().equals(v)) {
                        lines.set(i, k + "=" + v);
                        changed = true;
                    }
                }
            }
            for (var e : left.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
                changed = true;
            }
            if (changed) Files.write(file, lines, StandardCharsets.ISO_8859_1);
        }
    }
}
