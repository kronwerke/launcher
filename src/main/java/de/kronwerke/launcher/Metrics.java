package de.kronwerke.launcher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * The last hour of one server, sampled every ten seconds: CPU, memory, tick time and
 * players. Kept in memory only; the console draws its graphs from it.
 */
public final class Metrics {
    static final int KEEP = 360;

    /** One sample. cpu in percent of one core, rss in bytes, mspt in milliseconds; -1 unknown. */
    public record Sample(long time, double cpu, long rss, double tps, double mspt, int players) {
        Map<String, Object> json() {
            return Json.map("t", time, "cpu", round(cpu), "rss", rss, "tps", round(tps), "mspt", round(mspt), "players", players);
        }
    }

    private final Deque<Sample> samples = new ArrayDeque<>();
    private List<String> players = List.of();
    private long lastCpu = -1, lastAt;

    synchronized void reset() {
        lastCpu = -1;
        players = List.of();
    }

    /** CPU percent since the last call, from the process's total CPU time. */
    synchronized double cpu(long cpuMillis, long now) {
        double pct = -1;
        if (cpuMillis >= 0 && lastCpu >= 0 && now > lastAt) pct = 100.0 * (cpuMillis - lastCpu) / (now - lastAt);
        lastCpu = cpuMillis;
        lastAt = now;
        return pct;
    }

    synchronized void add(Sample s) {
        samples.addLast(s);
        while (samples.size() > KEEP) samples.removeFirst();
    }

    synchronized void players(List<String> names) {
        players = List.copyOf(names);
    }

    public synchronized List<String> players() {
        return players;
    }

    public synchronized Sample last() {
        return samples.peekLast();
    }

    public synchronized List<Sample> all() {
        return new ArrayList<>(samples);
    }

    /** Mean tick time over the last n samples that have one, or -1. */
    public synchronized double meanMspt(int n) {
        double sum = 0;
        int count = 0;
        var it = samples.descendingIterator();
        while (it.hasNext() && count < n) {
            Sample s = it.next();
            if (s.mspt() < 0) continue;
            sum += s.mspt();
            count++;
        }
        return count == 0 ? -1 : sum / count;
    }

    static double round(double v) {
        return v < 0 ? -1 : Math.round(v * 10) / 10.0;
    }

    /** Parses the overall line of `neoforge tps`: mean tick time in ms and TPS, -1 when missing. */
    static double[] tps(String answer) {
        var m = DIM.matcher(answer);
        while (m.find()) {
            if (m.group(1).equals("Overall")) return new double[] {Double.parseDouble(m.group(3)), Double.parseDouble(m.group(2))};
        }
        return new double[] {-1, -1};
    }

    /** Every dimension's tick time from `neoforge tps`, slowest first, without the overall line. */
    static List<Map<String, Object>> dimensions(String answer) {
        List<Map<String, Object>> out = new ArrayList<>();
        var m = DIM.matcher(answer);
        while (m.find()) {
            if (m.group(1).equals("Overall")) continue;
            out.add(Json.map("name", m.group(1), "tps", Double.parseDouble(m.group(2)), "mspt", Double.parseDouble(m.group(3))));
        }
        out.sort((a, b) -> Double.compare((double) b.get("mspt"), (double) a.get("mspt")));
        return out;
    }

    private static final java.util.regex.Pattern DIM =
            java.util.regex.Pattern.compile("(?m)^(.+?): ([0-9.]+) TPS \\(([0-9.]+) ms/tick\\)");

    private List<Map<String, Object>> dims = List.of();

    synchronized void dimensions(List<Map<String, Object>> d) {
        dims = List.copyOf(d);
    }

    public synchronized List<Map<String, Object>> dimensions() {
        return dims;
    }
}
