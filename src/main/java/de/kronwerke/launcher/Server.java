package de.kronwerke.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs Minecraft as a child process and keeps it running. Everything the child prints goes
 * to the launcher's own output, so the panel's console looks as before.
 */
public final class Server {
    public enum State { STOPPED, UPDATING, STARTING, RUNNING, STOPPING, CRASHED }

    private final Path root;
    private final Config cfg;
    private final String java;
    private final Consumer<String> out;
    private final Pack pack;

    private final Object lock = new Object();
    private State state = State.STOPPED;
    private Instant since = Instant.now();
    private String detail = "";
    private Process child;
    private OutputStream childIn;
    private boolean wantRunning;
    private boolean updateNext;
    private boolean exitWhenStopped;
    private final Deque<Instant> crashes = new ArrayDeque<>();
    private int starts;

    private final Deque<String> console = new ArrayDeque<>();
    private static final int CONSOLE_LINES = 2000;
    private final List<Consumer<String>> consoleListeners = new ArrayList<>();
    private final List<Runnable> stateListeners = new ArrayList<>();

    public Server(Path root, Config cfg, String java, Consumer<String> out) {
        this.root = root;
        this.cfg = cfg;
        this.java = java;
        this.out = out;
        this.pack = new Pack(root, java, this::print);
    }

    public Pack pack() {
        return pack;
    }

    // ---- what others see ----

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

    public long pid() {
        synchronized (lock) {
            return child != null && child.isAlive() ? child.pid() : 0;
        }
    }

    public List<String> console(int n) {
        synchronized (console) {
            List<String> all = new ArrayList<>(console);
            return all.subList(Math.max(0, all.size() - n), all.size());
        }
    }

    public void onConsole(Consumer<String> c) {
        synchronized (consoleListeners) {
            consoleListeners.add(c);
        }
    }

    public void onState(Runnable r) {
        synchronized (stateListeners) {
            stateListeners.add(r);
        }
    }

    /** A line for the console: the panel and the ring buffer. */
    void print(String line) {
        out.accept(line);
        synchronized (console) {
            console.addLast(line);
            while (console.size() > CONSOLE_LINES) console.removeFirst();
        }
        List<Consumer<String>> ls;
        synchronized (consoleListeners) {
            ls = new ArrayList<>(consoleListeners);
        }
        for (Consumer<String> c : ls) c.accept(line);
    }

    void note(String msg) {
        print("[Kronwerke] " + msg);
    }

    private void set(State s, String why) {
        synchronized (lock) {
            state = s;
            detail = why;
            since = Instant.now();
        }
        note("Minecraft " + s.name().toLowerCase() + (why.isEmpty() ? "" : ": " + why));
        List<Runnable> ls;
        synchronized (stateListeners) {
            ls = new ArrayList<>(stateListeners);
        }
        for (Runnable r : ls) r.run();
    }

    // ---- control ----

    public void start(boolean update) {
        synchronized (lock) {
            wantRunning = true;
            updateNext |= update;
            crashes.clear();
            lock.notifyAll();
        }
    }

    public void stop() {
        synchronized (lock) {
            wantRunning = false;
            lock.notifyAll();
        }
        stopChild();
    }

    public void restart(boolean update) {
        synchronized (lock) {
            updateNext |= update;
        }
        stopChild();
        start(false);
    }

    /** The panel's stop: stop Minecraft, then end the launcher so the container stops. */
    public void shutdown() {
        synchronized (lock) {
            exitWhenStopped = true;
            wantRunning = false;
            lock.notifyAll();
        }
        stopChild();
    }

    /** Sends a console line to Minecraft. */
    public boolean send(String line) {
        OutputStream in;
        synchronized (lock) {
            in = childIn;
        }
        if (in == null) return false;
        try {
            synchronized (in) {
                in.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private void stopChild() {
        Process p;
        synchronized (lock) {
            p = child;
            if (p == null || !p.isAlive()) return;
            if (state != State.STOPPING) {
                state = State.STOPPING;
                since = Instant.now();
            }
        }
        note("Stopping Minecraft");
        send("stop");
        try {
            if (!p.waitFor(150, TimeUnit.SECONDS)) {
                note("Minecraft did not stop in 150 seconds, killing it");
                p.destroyForcibly();
                p.waitFor(20, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** The supervisor loop. Returns when the launcher should exit. */
    public void run() throws InterruptedException {
        synchronized (lock) {
            wantRunning = cfg.flag("autostart");
        }
        while (true) {
            boolean update;
            synchronized (lock) {
                while (!wantRunning && !exitWhenStopped) lock.wait();
                if (exitWhenStopped) return;
                update = updateNext || starts == 0;
                updateNext = false;
            }
            int code = runOnce(update);
            boolean crashed;
            synchronized (lock) {
                if (exitWhenStopped) {
                    set(State.STOPPED, "exit code " + code);
                    return;
                }
                crashed = wantRunning && state != State.STOPPING;
            }
            if (!crashed) {
                String why = code != Integer.MIN_VALUE ? "exit code " + code : detail().startsWith("eula") ? detail() : "";
                set(State.STOPPED, why);
                continue;
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
            }
        }
    }

    private boolean eulaAccepted() {
        try {
            for (String l : Files.readAllLines(root.resolve("eula.txt"))) {
                if (l.trim().equalsIgnoreCase("eula=true")) return true;
            }
        } catch (IOException e) {
            // no eula.txt yet
        }
        return false;
    }

    /** Falls back to "running" when no RCON line follows "Done" within a minute. */
    private void runningSoon(Process p) {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                return;
            }
            if (p.isAlive() && state() == State.STARTING) set(State.RUNNING, "no RCON line seen");
        }, "running-fallback");
        t.setDaemon(true);
        t.start();
    }

    /** Update, start, wait for the exit. Integer.MIN_VALUE when it never started. */
    private int runOnce(boolean update) {
        Pack.Info info;
        try {
            String url = cfg.get("pack.url");
            if (update && !url.isEmpty()) {
                set(State.UPDATING, "pack");
                info = pack.fetch(url);
                pack.update(url);
            } else {
                info = pack.local();
            }
            if (info == null) throw new IOException("no pack.toml yet and pack.url is empty");
            pack.ensureNeoForge(info.neoforge());
            Rcon.prepare(root.resolve("server.properties"));
        } catch (Exception e) {
            synchronized (lock) {
                detail = "preparing: " + e.getMessage();
            }
            note("Preparing failed: " + e.getMessage());
            return Integer.MIN_VALUE;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(java);
        String mem = cfg.get("memory");
        if (!mem.isEmpty()) {
            cmd.add("-Xms" + mem);
            cmd.add("-Xmx" + mem);
        }
        for (String a : cfg.get("jvm.args").split("\\s+")) if (!a.isBlank()) cmd.add(a);
        Path userArgs = root.resolve("user_jvm_args.txt");
        if (Files.exists(userArgs)) cmd.add("@user_jvm_args.txt");
        cmd.add("@" + root.relativize(pack.argsFile(info.neoforge())));
        cmd.add("nogui");

        synchronized (lock) {
            // stopped while the pack was updating
            if (!wantRunning || exitWhenStopped) return Integer.MIN_VALUE;
        }
        if (!eulaAccepted()) {
            note("Minecraft's EULA is not accepted yet. Read https://aka.ms/MinecraftEULA, put eula=true into eula.txt, then `kronwerke start`.");
            synchronized (lock) {
                wantRunning = false;
                detail = "eula.txt is not accepted";
            }
            return Integer.MIN_VALUE;
        }
        Process p;
        try {
            set(State.STARTING, "pack " + info.version() + ", NeoForge " + info.neoforge());
            p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
        } catch (IOException e) {
            synchronized (lock) {
                detail = "start: " + e.getMessage();
            }
            note("Could not start Minecraft: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        synchronized (lock) {
            child = p;
            childIn = p.getOutputStream();
            starts++;
        }
        try (BufferedReader r = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                print(line);
                if (state() != State.STARTING) continue;
                // RCON comes up right after "Done"; commands only work from then on
                if (line.contains("RCON running on")) {
                    set(State.RUNNING, "");
                } else if (line.contains("Done (") && line.contains("For help, type")) {
                    runningSoon(p);
                }
            }
        } catch (IOException ignored) {
            // the child is gone
        }
        int code;
        try {
            code = p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            code = -1;
        }
        synchronized (lock) {
            child = null;
            childIn = null;
        }
        return code;
    }
}
