package de.kronwerke.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;

/**
 * A running Minecraft process and the thread that reads its output. It lives in the boot
 * package so a launcher reload can hand the process to the next version: the old launcher
 * detaches, the new one attaches, and no line is lost in between (up to a few thousand are
 * kept while nobody listens).
 */
public final class Pump {
    private static final int BACKLOG = 5000;

    private final Process process;
    private final Object lock = new Object();
    private final Deque<String> backlog = new ArrayDeque<>();
    private Consumer<String> sink;
    private boolean ended;

    private Pump(Process process) {
        this.process = process;
    }

    /** Starts reading the process's output at once; lines wait until someone attaches. */
    public static Pump start(Process process, String name) {
        Pump p = new Pump(process);
        Thread t = new Thread(p::read, "output-" + name);
        t.setDaemon(true);
        t.start();
        return p;
    }

    public Process process() {
        return process;
    }

    /** Lines from now on go to sink, after every line that came while nobody listened. */
    public void attach(Consumer<String> to) {
        synchronized (lock) {
            while (!backlog.isEmpty()) to.accept(backlog.removeFirst());
            sink = to;
            lock.notifyAll();
        }
    }

    /** Lines are kept until the next attach. */
    public void detach() {
        synchronized (lock) {
            sink = null;
        }
    }

    /** True once the process closed its output, which is shortly before it exits. */
    public boolean ended() {
        synchronized (lock) {
            return ended;
        }
    }

    /** Waits until the output is closed and every line has been delivered. */
    public void awaitEnd() throws InterruptedException {
        synchronized (lock) {
            while (!ended || (sink == null && !backlog.isEmpty())) lock.wait(1000);
        }
    }

    /** Writes one line to the process's console. */
    public boolean send(String line) {
        OutputStream in = process.getOutputStream();
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

    private void read() {
        try (BufferedReader r = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) deliver(line);
        } catch (IOException ignored) {
            // the process is gone
        }
        synchronized (lock) {
            ended = true;
            lock.notifyAll();
        }
    }

    private void deliver(String line) {
        synchronized (lock) {
            if (sink != null) {
                try {
                    sink.accept(line);
                } catch (RuntimeException ignored) {
                    // a listener's problem must not stop the output
                }
                return;
            }
            backlog.addLast(line);
            while (backlog.size() > BACKLOG) backlog.removeFirst();
        }
    }
}
