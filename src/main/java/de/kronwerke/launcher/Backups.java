package de.kronwerke.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Backups of a Minecraft server's world: saving is paused, the world is zipped into
 * launcher/backups/<server>/ (or backup.dir in launcher.properties, another disk), saving
 * resumes. backup.keep in the server's file says how many stay (default 5). A restore stops
 * the server, moves the current world aside (never deletes it) and unpacks the backup.
 * <p>
 * A backup never fills the disk: before it starts, there must be room for one more of the
 * size of the last one; when there is not, the oldest backups go first (one always stays),
 * and when that is still not enough, no backup is written. A half written file is always
 * removed. Chronik's live database is not copied while it is written to: Chronik writes a
 * consistent snapshot on request, and the backup holds that one. The stage file of Kronwerke
 * Core travels with the world, so a restored world opens the stage it was at.
 */
public final class Backups {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Fleet fleet;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    Backups(Fleet fleet) {
        this.fleet = fleet;
    }

    public Path dir(Server s) {
        String other = fleet.config().get("backup.dir");
        Path root = other.isEmpty() ? fleet.home().resolve("backups") : Path.of(other);
        return root.resolve(s.name());
    }

    /** Files next to the world that belong to its state and travel with it. */
    static final List<String> WITH_WORLD = List.of("kronwerke/stage.txt");
    /** Chronik's live database: never copied while it is written to. */
    static final Set<String> CHRONIK_LIVE = Set.of("chronik.db", "chronik.db-wal", "chronik.db-shm", "chronik-snapshot.done");
    static final String CHRONIK_SNAPSHOT = "chronik-snapshot.db";

    /** The world folder: level-name from server.properties, "world" without one. */
    public static String levelName(Server s) {
        try {
            for (String l : Files.readAllLines(s.properties(), StandardCharsets.ISO_8859_1)) {
                if (l.startsWith("level-name=")) {
                    String v = l.substring(11).trim();
                    if (!v.isEmpty() && !v.contains("..") && !v.contains("/")) return v;
                }
            }
        } catch (IOException ignored) {
            // no file yet
        }
        return "world";
    }

    public boolean busy(Server s) {
        return running.contains(s.name());
    }

    public List<Object> list(Server s) throws IOException {
        List<Object> out = new ArrayList<>();
        Path d = dir(s);
        if (!Files.isDirectory(d)) return out;
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.filter(p -> p.getFileName().toString().endsWith(".zip")).sorted(Comparator.comparing(Path::getFileName).reversed()).toList()) {
                out.add(Json.map("name", p.getFileName().toString(), "size", Files.size(p), "modified", Files.getLastModifiedTime(p).toMillis()));
            }
        }
        return out;
    }

    public Path file(Server s, String name) {
        if (!name.matches("[a-z0-9_-]+-[0-9]{8}-[0-9]{6}\\.zip")) throw new IllegalArgumentException("not a backup name");
        Path p = dir(s).resolve(name);
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("no backup " + name);
        return p;
    }

    /** Makes a backup now. Returns its file name. */
    public String run(Server s, String why) {
        if (!s.minecraft()) throw new IllegalArgumentException(s.name() + " is not a Minecraft server");
        if (!running.add(s.name())) throw new IllegalStateException("a backup of " + s.name() + " is running");
        boolean paused = false;
        long t0 = System.currentTimeMillis();
        Path tmp = null;
        try {
            Path world = s.dir().resolve(levelName(s));
            if (!Files.isDirectory(world)) throw new IOException("no world folder " + world.getFileName());
            if (s.state() == Server.State.RUNNING && s.rcon()) {
                s.command("save-off");
                paused = true;
                s.command("save-all flush");
            }
            Path d = dir(s);
            Files.createDirectories(d);
            makeRoom(s, d);
            // a stopped server's Chronik files are at rest and go in as they are
            boolean live = s.state() == Server.State.RUNNING;
            boolean snapshot = live && paused && chronikSnapshot(s, world);
            String name = s.name() + "-" + LocalDateTime.now(fleet.schedule().zone()).format(STAMP) + ".zip";
            tmp = d.resolve(name + ".part");
            fleet.event("backup", s.name(), "backup started (" + why + ")");
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
                z.setLevel(Deflater.BEST_SPEED);
                Path base = world.getParent();
                try (Stream<Path> walk = Files.walk(world)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) continue;
                        String file = p.getFileName().toString();
                        if (file.equals("session.lock")) continue;
                        // Chronik: its snapshot instead of the live files, which change under the zip
                        if (live && p.getParent().equals(world) && CHRONIK_LIVE.contains(file)) continue;
                        if (p.getParent().equals(world) && file.equals(CHRONIK_SNAPSHOT) && !snapshot) continue;
                        z.putNextEntry(new ZipEntry(base.relativize(p).toString().replace('\\', '/')));
                        try (InputStream in = Files.newInputStream(p)) {
                            in.transferTo(z);
                        } catch (IOException e) {
                            // a file that vanished while zipping (a region rewritten): skip it
                        }
                        z.closeEntry();
                    }
                }
                for (String extra : WITH_WORLD) {
                    Path p = s.dir().resolve(extra);
                    if (!Files.isRegularFile(p)) continue;
                    z.putNextEntry(new ZipEntry(extra));
                    Files.copy(p, z);
                    z.closeEntry();
                }
            }
            if (snapshot) Files.deleteIfExists(world.resolve(CHRONIK_SNAPSHOT));
            Files.move(tmp, d.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            tmp = null;
            prune(s);
            long size = Files.size(d.resolve(name));
            String done = "backup " + name + " (" + (size >> 20) + " MB, " + (System.currentTimeMillis() - t0) / 1000 + " s)";
            fleet.event("backup", s.name(), done);
            fleet.alerts().send("backup", s.name(), done);
            return name;
        } catch (IOException | RuntimeException e) {
            fleet.event("backup", s.name(), "backup failed: " + e.getMessage());
            fleet.alerts().send("backup-failed", s.name(), "backup failed: " + e.getMessage());
            throw new IllegalStateException("backup failed: " + e.getMessage(), e);
        } finally {
            // a half written backup never stays: it would take the room the next one needs
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // gone already
                }
            }
            if (paused) {
                try {
                    s.command("save-on");
                } catch (IOException | RuntimeException e) {
                    fleet.event("backup", s.name(), "save-on failed: " + e.getMessage() + "; type save-on in the console");
                }
            }
            running.remove(s.name());
        }
    }

    /**
     * Room for one more backup of the size of the newest one, plus a margin: when the disk is
     * short, the oldest backups of this server go first (the newest stays). Leftover .part files
     * of a crash go too. Without enough room in the end, no backup is started.
     */
    void makeRoom(Server s, Path d) throws IOException {
        try (Stream<Path> st = Files.list(d)) {
            for (Path p : st.filter(p -> p.getFileName().toString().endsWith(".zip.part")).toList()) Files.deleteIfExists(p);
        }
        List<Path> all = backups(d);
        long need = all.isEmpty() ? worldSize(s) / 2 : Files.size(all.get(0));
        need += need / 5 + (256L << 20); // a fifth more, and room for the server to keep saving
        java.nio.file.FileStore store = Files.getFileStore(d);
        for (int i = all.size() - 1; i >= 1 && store.getUsableSpace() < need; i--) {
            fleet.event("backup", s.name(), "short on disk space: removed the old backup " + all.get(i).getFileName());
            Files.deleteIfExists(all.get(i));
        }
        if (store.getUsableSpace() < need) {
            throw new IOException("not enough disk space for a backup: " + (store.getUsableSpace() >> 20) + " MB free, about " + (need >> 20) + " MB needed");
        }
    }

    private static List<Path> backups(Path d) throws IOException {
        List<Path> all = new ArrayList<>();
        if (!Files.isDirectory(d)) return all;
        try (Stream<Path> st = Files.list(d)) {
            st.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(all::add);
        }
        all.sort(Comparator.comparing(Path::getFileName).reversed());
        return all;
    }

    private static long worldSize(Server s) {
        try (Stream<Path> walk = Files.walk(s.dir().resolve(levelName(s)))) {
            return walk.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Asks Chronik (when the server has it) for a consistent copy of its database, written next
     * to the live one as chronik-snapshot.db, and waits up to two minutes for it. False without
     * Chronik or when it did not answer: the backup then holds no Chronik log at all rather than
     * a torn one.
     */
    boolean chronikSnapshot(Server s, Path world) {
        if (!Files.exists(world.resolve("chronik.db"))) return false;
        Path done = world.resolve("chronik-snapshot.done");
        try {
            Files.deleteIfExists(done);
            Files.deleteIfExists(world.resolve(CHRONIK_SNAPSHOT));
            String out = s.command("chronik snapshot");
            if (out == null || !out.contains("OK")) {
                fleet.event("backup", s.name(), "Chronik made no snapshot (" + (out == null ? "" : out.trim()) + "); the backup holds no Chronik log");
                return false;
            }
            for (int i = 0; i < 240; i++) {
                if (Files.exists(done)) return Files.exists(world.resolve(CHRONIK_SNAPSHOT));
                Thread.sleep(500);
            }
            fleet.event("backup", s.name(), "Chronik's snapshot took longer than two minutes; the backup holds no Chronik log");
        } catch (IOException | RuntimeException e) {
            fleet.event("backup", s.name(), "Chronik snapshot failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    /** Keeps the newest backup.keep backups of a server. */
    void prune(Server s) throws IOException {
        int keep = Math.max(1, s.config().number("backup.keep", 5));
        List<Path> all = new ArrayList<>();
        try (Stream<Path> st = Files.list(dir(s))) {
            st.filter(p -> p.getFileName().toString().endsWith(".zip")).forEach(all::add);
        }
        all.sort(Comparator.comparing(Path::getFileName).reversed());
        for (int i = keep; i < all.size(); i++) Files.deleteIfExists(all.get(i));
    }

    public void delete(Server s, String name) throws IOException {
        Files.delete(file(s, name));
    }

    /**
     * Stops the server, moves its world to <world>.before-<stamp>, unpacks the backup, and starts
     * it again if it was running.
     */
    public String restore(Server s, String name) throws IOException {
        Path zip = file(s, name);
        if (!running.add(s.name())) throw new IllegalStateException("a backup of " + s.name() + " is running");
        try {
            boolean was = s.wanted();
            s.stop();
            String level = levelName(s);
            Path world = s.dir().resolve(level);
            String aside = level + ".before-" + LocalDateTime.now(fleet.schedule().zone()).format(STAMP);
            if (Files.exists(world)) Files.move(world, s.dir().resolve(aside));
            Path root = s.dir().normalize();
            try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    Path to = root.resolve(e.getName()).normalize();
                    // Chronik's snapshot becomes its database again
                    if (to.equals(root.resolve(level).resolve(CHRONIK_SNAPSHOT))) to = root.resolve(level).resolve("chronik.db");
                    // only the world and the files that travel with it, nothing else
                    if (!to.startsWith(root.resolve(level)) && !WITH_WORLD.contains(e.getName())) continue;
                    if (e.isDirectory()) {
                        Files.createDirectories(to);
                        continue;
                    }
                    Files.createDirectories(to.getParent());
                    try (OutputStream out = Files.newOutputStream(to)) {
                        in.transferTo(out);
                    }
                }
            }
            fleet.event("backup", s.name(), "restored " + name + "; the old world is in " + aside);
            if (was) s.start();
            return aside;
        } finally {
            running.remove(s.name());
        }
    }

    /**
     * A fresh world: the players are sent to another server of the network first (when a mod
     * on the bus can), the server stops, its world moves to <world>.reset-<stamp>, older reset
     * worlds are deleted (one stays, for a look back), and the server starts with a new world.
     */
    public String reset(Server s) {
        if (!s.minecraft()) throw new IllegalArgumentException(s.name() + " is not a Minecraft server");
        if (!running.add(s.name())) throw new IllegalStateException("a backup or reset of " + s.name() + " is running");
        try {
            boolean was = s.wanted();
            if (s.state() == Server.State.RUNNING) {
                Network.Evacuation ev = fleet.network().evacuate(s, 60_000);
                if (ev == Network.Evacuation.FAILED) {
                    String why = fleet.network().evacuationReason(s);
                    fleet.alerts().send("reset", s.name(), "world reset called off: " + why);
                    throw new IllegalStateException("the players could not be sent away (" + why + "), the world stays; try again when the network is up");
                }
                fleet.event("reset", s.name(), ev == Network.Evacuation.MOVED ? "players sent away" : "no mod to send players away; they are kicked by the stop");
            }
            s.stop();
            String level = levelName(s);
            Path world = s.dir().resolve(level);
            String aside = level + ".reset-" + LocalDateTime.now(fleet.schedule().zone()).format(STAMP);
            boolean moved = Files.exists(world);
            if (moved) Files.move(world, s.dir().resolve(aside));
            List<Path> old = new ArrayList<>();
            try (Stream<Path> st = Files.list(s.dir())) {
                st.filter(p -> p.getFileName().toString().startsWith(level + ".reset-") && !p.getFileName().toString().equals(aside)).forEach(old::add);
            }
            if (moved) for (Path p : old) deleteTree(p); // the last old world stays for a look back
            fleet.event("reset", s.name(), "new world; the old one is in " + aside);
            fleet.alerts().send("reset", s.name(), "world reset");
            if (was) s.start();
            return aside;
        } catch (IOException e) {
            fleet.event("reset", s.name(), "reset failed: " + e.getMessage());
            throw new IllegalStateException("reset failed: " + e.getMessage(), e);
        } finally {
            running.remove(s.name());
        }
    }

    private static void deleteTree(Path p) throws IOException {
        try (Stream<Path> walk = Files.walk(p)) {
            for (Path x : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }

    static Map<String, Object> info(Path p) throws IOException {
        return Json.map("name", p.getFileName().toString(), "size", Files.size(p));
    }
}
