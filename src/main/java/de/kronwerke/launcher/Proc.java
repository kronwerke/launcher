package de.kronwerke.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * What Linux tells about processes and the container: CPU time, memory, the CPU limit, which
 * CPUs we may use. Everything returns a neutral value where the file is missing, so the
 * launcher also runs on a machine without cgroups.
 */
public final class Proc {
    /** Clock ticks per second for /proc/[pid]/stat. 100 on every Linux we run on. */
    static final long TICKS = 100;

    private Proc() {
    }

    /** CPU time of a process (all its threads) in milliseconds, or -1. */
    public static long cpuMillis(long pid) {
        if (pid <= 0) return -1;
        try {
            String stat = Files.readString(Path.of("/proc/" + pid + "/stat"));
            // the command name may contain spaces; the fields we want come after its ")"
            String[] f = stat.substring(stat.lastIndexOf(')') + 2).split(" ");
            long utime = Long.parseLong(f[11]), stime = Long.parseLong(f[12]);
            return (utime + stime) * 1000 / TICKS;
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    /** Resident memory of a process in bytes, or -1. */
    public static long rss(long pid) {
        if (pid <= 0) return -1;
        return statusField(Path.of("/proc/" + pid + "/status"), "VmRSS:");
    }

    static long statusField(Path file, String field) {
        try {
            for (String l : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (l.startsWith(field)) {
                    String[] p = l.substring(field.length()).trim().split("\\s+");
                    return Long.parseLong(p[0]) * 1024;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux, or the process is gone
        }
        return -1;
    }

    /** The container's CPU limit in cores (cgroup v2 cpu.max, then v1), or the CPUs we see. */
    public static double cpuLimit() {
        try {
            Path v2 = Path.of("/sys/fs/cgroup/cpu.max");
            if (Files.exists(v2)) {
                String[] p = Files.readString(v2).trim().split("\\s+");
                if (!p[0].equals("max")) return Double.parseDouble(p[0]) / Double.parseDouble(p[1]);
            }
            Path quota = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_quota_us"), period = Path.of("/sys/fs/cgroup/cpu/cpu.cfs_period_us");
            if (Files.exists(quota) && Files.exists(period)) {
                long q = Long.parseLong(Files.readString(quota).trim());
                if (q > 0) return (double) q / Long.parseLong(Files.readString(period).trim());
            }
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return Runtime.getRuntime().availableProcessors();
    }

    /** CPU time the whole container used, in milliseconds, or -1. */
    public static long containerCpuMillis() {
        try {
            Path v2 = Path.of("/sys/fs/cgroup/cpu.stat");
            if (Files.exists(v2)) {
                for (String l : Files.readAllLines(v2)) {
                    if (l.startsWith("usage_usec ")) return Long.parseLong(l.substring(11).trim()) / 1000;
                }
            }
            Path v1 = Path.of("/sys/fs/cgroup/cpuacct/cpuacct.usage");
            if (Files.exists(v1)) return Long.parseLong(Files.readString(v1).trim()) / 1_000_000;
        } catch (IOException | RuntimeException ignored) {
            // fall through
        }
        return -1;
    }

    /** Memory the container uses and may use, in bytes; -1 where unknown. */
    public static long[] containerMemory() {
        long used = -1, max = -1;
        try {
            Path cur = Path.of("/sys/fs/cgroup/memory.current"), lim = Path.of("/sys/fs/cgroup/memory.max");
            if (Files.exists(cur)) {
                used = Long.parseLong(Files.readString(cur).trim());
                String m = Files.readString(lim).trim();
                max = m.equals("max") ? -1 : Long.parseLong(m);
            } else {
                Path u1 = Path.of("/sys/fs/cgroup/memory/memory.usage_in_bytes"), l1 = Path.of("/sys/fs/cgroup/memory/memory.limit_in_bytes");
                if (Files.exists(u1)) used = Long.parseLong(Files.readString(u1).trim());
                if (Files.exists(l1)) {
                    long l = Long.parseLong(Files.readString(l1).trim());
                    max = l > (1L << 50) ? -1 : l;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // unknown
        }
        if (max < 0) {
            long total = statusField(Path.of("/proc/meminfo"), "MemTotal:");
            if (total > 0) max = total;
        }
        return new long[] {used, max};
    }

    /** The CPUs this process may run on (Cpus_allowed_list), or an empty list. */
    public static List<Integer> allowedCpus() {
        try {
            for (String l : Files.readAllLines(Path.of("/proc/self/status"))) {
                if (l.startsWith("Cpus_allowed_list:")) return parseCpuList(l.substring(18).trim());
            }
        } catch (IOException | RuntimeException ignored) {
            // not Linux
        }
        return List.of();
    }

    static List<Integer> parseCpuList(String s) {
        List<Integer> out = new ArrayList<>();
        for (String part : s.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int dash = part.indexOf('-');
            if (dash < 0) {
                out.add(Integer.parseInt(part));
            } else {
                int a = Integer.parseInt(part.substring(0, dash)), b = Integer.parseInt(part.substring(dash + 1));
                for (int i = a; i <= b; i++) out.add(i);
            }
        }
        return out;
    }

    static String cpuList(List<Integer> cpus) {
        StringBuilder b = new StringBuilder();
        for (int c : cpus) {
            if (b.length() > 0) b.append(',');
            b.append(c);
        }
        return b.toString();
    }

    /** Pins every thread of a process to the given CPUs with taskset. False when that failed. */
    public static boolean pin(long pid, List<Integer> cpus) {
        if (pid <= 0 || cpus.isEmpty()) return false;
        return run(List.of("taskset", "-a", "-p", "-c", cpuList(cpus), Long.toString(pid)));
    }

    /** Whether taskset exists here. */
    public static boolean canPin() {
        return run(List.of("taskset", "-p", Long.toString(ProcessHandle.current().pid())));
    }

    /** Disk use of the file system holding dir: used and total bytes. */
    public static long[] disk(Path dir) {
        try {
            var store = Files.getFileStore(dir);
            return new long[] {store.getTotalSpace() - store.getUsableSpace(), store.getTotalSpace()};
        } catch (IOException e) {
            return new long[] {-1, -1};
        }
    }

    private static boolean run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
