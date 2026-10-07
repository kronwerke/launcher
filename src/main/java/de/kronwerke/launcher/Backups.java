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
 * launcher/backups/<server>/, saving resumes. backup.keep in the server's file says how many
 * stay (default 5). A restore stops the server, moves the current world aside (never deletes
 * it) and unpacks the backup.
 */
public final class Backups {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Fleet fleet;
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    Backups(Fleet fleet) {
        this.fleet = fleet;
    }

    public Path dir(Server s) {
        return fleet.home().resolve("backups").resolve(s.name());
    }

    /** The world folder: level-name from server.properties, "world" without one. */
    static String levelName(Server s) {
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
            String name = s.name() + "-" + LocalDateTime.now(fleet.schedule().zone()).format(STAMP) + ".zip";
            Path tmp = d.resolve(name + ".part");
            fleet.event("backup", s.name(), "backup started (" + why + ")");
            try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(tmp))) {
                z.setLevel(Deflater.BEST_SPEED);
                Path base = world.getParent();
                try (Stream<Path> walk = Files.walk(world)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        if (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(p)) continue;
                        if (p.getFileName().toString().equals("session.lock")) continue;
                        z.putNextEntry(new ZipEntry(base.relativize(p).toString().replace('\\', '/')));
                        try (InputStream in = Files.newInputStream(p)) {
                            in.transferTo(z);
                        } catch (IOException e) {
                            // a file that vanished while zipping (a region rewritten): skip it
                        }
                        z.closeEntry();
                    }
                }
            }
            Files.move(tmp, d.resolve(name), StandardCopyOption.REPLACE_EXISTING);
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
                    if (!to.startsWith(root.resolve(level))) continue; // only the world, nothing outside it
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
                boolean moved = fleet.network().evacuate(s, 60_000);
                fleet.event("reset", s.name(), moved ? "players sent away" : "no mod to send players away; they are kicked by the stop");
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
