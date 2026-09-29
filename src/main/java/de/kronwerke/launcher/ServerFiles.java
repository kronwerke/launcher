package de.kronwerke.launcher;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * File access for the link. Everything stays inside the server folder. Reading is allowed
 * anywhere there except the link key; writing only where a pack or config change belongs.
 */
public final class ServerFiles {
    static final long MAX_READ = 8L << 20;
    static final Set<String> WRITE_DIRS = Set.of("config", "defaultconfigs", "kubejs", "mods", "kronwerke", "world/serverconfig", "world/datapacks");
    static final Set<String> WRITE_FILES = Set.of("server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "user_jvm_args.txt");

    private final Path root;
    private final Path key;

    public ServerFiles(Path root, Path key) {
        this.root = root.toAbsolutePath().normalize();
        this.key = key.toAbsolutePath().normalize();
    }

    Path resolve(String rel) throws IOException {
        if (rel == null) rel = "";
        if (rel.startsWith("/") || rel.contains("\\") || rel.contains("\0")) throw new IOException("bad path");
        Path p = root.resolve(rel).normalize();
        if (!p.startsWith(root)) throw new IOException("outside the server folder");
        if (p.equals(key)) throw new IOException("the link key is not readable over the link");
        return p;
    }

    boolean writable(Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        if (WRITE_FILES.contains(rel)) return true;
        for (String d : WRITE_DIRS) {
            if (rel.startsWith(d + "/")) return true;
        }
        return false;
    }

    public List<Map<String, Object>> list(String rel) throws IOException {
        Path dir = resolve(rel);
        if (!Files.isDirectory(dir)) throw new IOException("not a folder: " + rel);
        List<Map<String, Object>> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.sorted(Comparator.comparing(Path::toString)).toList()) {
                if (p.equals(key)) continue;
                boolean d = Files.isDirectory(p);
                out.add(Json.map("name", p.getFileName().toString(), "dir", d,
                        "size", d ? 0L : Files.size(p), "modified", Files.getLastModifiedTime(p).toMillis()));
            }
        }
        return out;
    }

    public Map<String, Object> read(String rel) throws IOException {
        Path p = resolve(rel);
        if (!Files.isRegularFile(p)) throw new IOException("not a file: " + rel);
        long size = Files.size(p);
        if (size > MAX_READ) throw new IOException("file is " + size + " bytes, the limit is " + MAX_READ + "; use tail");
        return Json.map("path", rel, "size", size, "data", Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
    }

    public Map<String, Object> write(String rel, String base64) throws IOException {
        Path p = resolve(rel);
        if (!writable(p)) throw new IOException("not writable over the link: " + rel);
        byte[] data = Base64.getDecoder().decode(base64);
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".kwtmp");
        Files.write(tmp, data);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Json.map("path", rel, "size", (long) data.length);
    }

    public Map<String, Object> delete(String rel) throws IOException {
        Path p = resolve(rel);
        if (!writable(p)) throw new IOException("not writable over the link: " + rel);
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path q : s.sorted(Comparator.reverseOrder()).toList()) Files.delete(q);
            }
        } else {
            Files.delete(p);
        }
        return Json.map("path", rel);
    }

    /** The last lines of a text file, read from the end so big logs are cheap. */
    public List<String> tail(String rel, int lines) throws IOException {
        Path p = resolve(rel);
        if (!Files.isRegularFile(p)) throw new IOException("not a file: " + rel);
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "r")) {
            long len = f.length();
            long pos = len;
            int found = 0;
            int chunk = 64 << 10;
            byte[] buf = new byte[chunk];
            long start = 0;
            outer:
            while (pos > 0) {
                int n = (int) Math.min(chunk, pos);
                pos -= n;
                f.seek(pos);
                f.readFully(buf, 0, n);
                for (int k = n - 1; k >= 0; k--) {
                    if (buf[k] == '\n' && pos + k != len - 1) {
                        if (++found >= lines) {
                            start = pos + k + 1;
                            break outer;
                        }
                    }
                }
            }
            f.seek(start);
            byte[] rest = new byte[(int) Math.min(len - start, MAX_READ)];
            f.readFully(rest);
            String text = new String(rest, StandardCharsets.UTF_8);
            List<String> out = new ArrayList<>(List.of(text.split("\n", -1)));
            if (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) out.remove(out.size() - 1);
            return out;
        }
    }
}
