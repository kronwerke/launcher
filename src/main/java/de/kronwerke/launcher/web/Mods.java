package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Json;
import de.kronwerke.launcher.Main;
import de.kronwerke.launcher.Pack;
import de.kronwerke.launcher.Server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The mods (or plugins) of a server with what Modrinth and CurseForge know about them: name,
 * icon, page, the newest version for this loader and Minecraft version. Matching is by file
 * hash, so it works for any jar from either site, whoever put it there. Searching and
 * installing go through Modrinth, which needs no key; CurseForge needs a key, either an own
 * one or a proxy that holds one (curseforge.proxy).
 */
final class Mods {
    static final String MODRINTH = "https://api.modrinth.com/v2/";
    static final String CURSEFORGE = "https://api.curseforge.com/v1/";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, String[]> hashes = new ConcurrentHashMap<>();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();
    private final String curseKey;
    private final String curseBase;

    /**
     * key: an own CurseForge key (kept in the console folder, never shown), used directly.
     * Without one, proxy: a service that adds its key itself, so the key never reaches a
     * launcher. Empty proxy and no key: no CurseForge.
     */
    Mods(String curseKey, String proxy) {
        this.curseKey = curseKey == null ? "" : curseKey.trim();
        String p = proxy == null ? "" : proxy.trim();
        if (!this.curseKey.isEmpty()) curseBase = CURSEFORGE;
        else if (p.startsWith("https://")) curseBase = (p.endsWith("/") ? p : p + "/") + "v1/";
        else curseBase = "";
    }

    boolean curse() {
        return !curseBase.isEmpty();
    }

    /** What the server runs: its loader, Minecraft version and the folder its jars live in. */
    record Target(String loader, String minecraft, Path dir, String kind) {}

    static Target target(Server s, Pack.Info pack) {
        var c = s.config();
        String loader = c.get("loader"), mc = c.get("minecraft");
        String jar = c.get("jar").toLowerCase();
        if (loader.isEmpty()) {
            if (s.type().equals("neoforge")) loader = "neoforge";
            else if (jar.contains("paper") || jar.contains("purpur") || jar.contains("folia")) loader = "paper";
            else if (jar.contains("fabric")) loader = "fabric";
            else if (jar.contains("velocity")) loader = "velocity";
            else if (jar.contains("forge")) loader = "forge";
            else if (Files.isDirectory(s.dir().resolve("plugins"))) loader = "paper";
            else loader = "";
        }
        if (mc.isEmpty() && pack != null && s.type().equals("neoforge")) mc = pack.minecraft();
        boolean plugins = loader.equals("paper") || loader.equals("velocity") || loader.equals("spigot") || loader.equals("bukkit");
        return new Target(loader, mc, s.dir().resolve(plugins ? "plugins" : "mods"), plugins ? "plugin" : "mod");
    }

    /** Every jar in the server's folder, matched against Modrinth and CurseForge. */
    Map<String, Object> list(Server s, Target t, boolean fresh) throws Exception {
        List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(t.dir())) {
            try (Stream<Path> st = Files.list(t.dir())) {
                jars = st.filter(p -> p.getFileName().toString().endsWith(".jar") && Files.isRegularFile(p)).sorted().toList();
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Map<String, Object>> bySha1 = new LinkedHashMap<>(), bySha512 = new LinkedHashMap<>();
        for (Path p : jars) {
            String[] h = hash(p);
            Map<String, Object> m = Json.map("file", p.getFileName().toString(), "size", Files.size(p), "sha1", h[0]);
            Map<String, Object> meta = jarMeta(p);
            m.putAll(meta);
            out.add(m);
            bySha1.put(h[0], m);
            bySha512.put(h[1], m);
        }
        String key = s.name() + "|" + t.loader() + "|" + t.minecraft() + "|" + String.join(",", bySha1.keySet());
        Object[] hit = cache.get(key);
        if (!fresh && hit != null && System.currentTimeMillis() - (long) hit[0] < 30 * 60_000) {
            return Json.map("mods", hit[1], "loader", t.loader(), "minecraft", t.minecraft(), "kind", t.kind(), "curseforge", curse());
        }
        String error = "";
        try {
            modrinth(bySha512, t);
        } catch (Exception e) {
            error = "Modrinth: " + e.getMessage();
        }
        if (curse()) {
            try {
                curseforge(jars, out);
            } catch (Exception e) {
                error = (error.isEmpty() ? "" : error + "; ") + "CurseForge: " + e.getMessage();
            }
        }
        cache.put(key, new Object[] {System.currentTimeMillis(), out});
        return Json.map("mods", out, "loader", t.loader(), "minecraft", t.minecraft(), "kind", t.kind(),
                "curseforge", curse(), "error", error);
    }

    @SuppressWarnings("unchecked")
    private void modrinth(Map<String, Map<String, Object>> bySha512, Target t) throws Exception {
        if (bySha512.isEmpty()) return;
        Object found = post(MODRINTH + "version_files", Json.map("hashes", new ArrayList<>(bySha512.keySet()), "algorithm", "sha512"));
        if (!(found instanceof Map<?, ?> versions)) return;
        Map<String, List<Map<String, Object>>> byProject = new LinkedHashMap<>();
        for (var e : versions.entrySet()) {
            Map<String, Object> m = bySha512.get(String.valueOf(e.getKey()));
            if (m == null || !(e.getValue() instanceof Map<?, ?> v)) continue;
            String project = String.valueOf(v.get("project_id"));
            m.put("source", "modrinth");
            m.put("project", project);
            m.put("version", v.get("version_number"));
            m.put("versionId", v.get("id"));
            byProject.computeIfAbsent(project, k -> new ArrayList<>()).add(m);
        }
        if (byProject.isEmpty()) return;
        String ids = Json.write(new ArrayList<>(byProject.keySet()));
        Object projects = get(MODRINTH + "projects?ids=" + URLEncoder.encode(ids, StandardCharsets.UTF_8));
        if (projects instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> p)) continue;
                for (Map<String, Object> m : byProject.getOrDefault(String.valueOf(p.get("id")), List.of())) {
                    m.put("title", p.get("title"));
                    m.put("icon", p.get("icon_url"));
                    m.put("summary", p.get("description"));
                    m.put("url", "https://modrinth.com/" + p.get("project_type") + "/" + p.get("slug"));
                    m.put("downloads", p.get("downloads"));
                }
            }
        }
        if (t.loader().isEmpty() || t.minecraft().isEmpty()) return;
        Object updates = post(MODRINTH + "version_files/update", Json.map("hashes", new ArrayList<>(bySha512.keySet()),
                "algorithm", "sha512", "loaders", List.of(t.loader()), "game_versions", List.of(t.minecraft())));
        if (updates instanceof Map<?, ?> up) {
            for (var e : up.entrySet()) {
                Map<String, Object> m = bySha512.get(String.valueOf(e.getKey()));
                if (m == null || !(e.getValue() instanceof Map<?, ?> v)) continue;
                if (!String.valueOf(v.get("id")).equals(String.valueOf(m.get("versionId")))) {
                    m.put("latest", v.get("version_number"));
                    m.put("latestId", v.get("id"));
                }
            }
        }
    }

    /** CurseForge matches by its own fingerprint of the jar. */
    private void curseforge(List<Path> jars, List<Map<String, Object>> out) throws Exception {
        Map<Long, Map<String, Object>> byPrint = new LinkedHashMap<>();
        for (int i = 0; i < jars.size(); i++) {
            if (out.get(i).get("source") != null) continue;
            byPrint.put(fingerprint(Files.readAllBytes(jars.get(i))), out.get(i));
        }
        if (byPrint.isEmpty()) return;
        Object res = postCurse("fingerprints/432", Json.map("fingerprints", new ArrayList<>(byPrint.keySet())));
        if (!(res instanceof Map<?, ?> r) || !(r.get("data") instanceof Map<?, ?> data)) return;
        if (!(data.get("exactMatches") instanceof List<?> matches)) return;
        List<Long> mods = new ArrayList<>();
        Map<Long, List<Map<String, Object>>> byMod = new LinkedHashMap<>();
        for (Object o : matches) {
            if (!(o instanceof Map<?, ?> mm) || !(mm.get("file") instanceof Map<?, ?> f)) continue;
            Map<String, Object> m = byPrint.get(((Number) f.get("fileFingerprint")).longValue());
            if (m == null) continue;
            long id = ((Number) mm.get("id")).longValue();
            m.put("source", "curseforge");
            m.put("project", String.valueOf(id));
            m.put("version", f.get("displayName"));
            mods.add(id);
            byMod.computeIfAbsent(id, k -> new ArrayList<>()).add(m);
        }
        if (mods.isEmpty()) return;
        Object info = postCurse("mods", Json.map("modIds", mods));
        if (info instanceof Map<?, ?> r2 && r2.get("data") instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> p)) continue;
                for (Map<String, Object> m : byMod.getOrDefault(((Number) p.get("id")).longValue(), List.of())) {
                    m.put("title", p.get("name"));
                    m.put("summary", p.get("summary"));
                    if (p.get("logo") instanceof Map<?, ?> logo) m.put("icon", logo.get("thumbnailUrl"));
                    if (p.get("links") instanceof Map<?, ?> links) m.put("url", links.get("websiteUrl"));
                    m.put("downloads", p.get("downloadCount"));
                }
            }
        }
    }

    /** Modrinth's search, narrowed to what fits the server. */
    Object search(Target t, String q, int offset) throws Exception {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + t.kind()));
        // the server has to run it: client only mods (shaders, minimaps) stay out
        facets.add(List.of("server_side:required", "server_side:optional"));
        if (!t.loader().isEmpty()) facets.add(List.of("categories:" + t.loader()));
        if (!t.minecraft().isEmpty()) facets.add(List.of("versions:" + t.minecraft()));
        String url = MODRINTH + "search?limit=20&offset=" + offset + "&index=" + (q.isBlank() ? "downloads" : "relevance")
                + "&query=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&facets=" + URLEncoder.encode(Json.write(facets), StandardCharsets.UTF_8);
        return get(url);
    }

    /**
     * Downloads the newest fitting version of a Modrinth project into the server's folder,
     * with its required dependencies that are not there yet. replace names a jar the new one
     * takes the place of (an update). Returns the files written.
     */
    List<String> install(Target t, String project, String replace, List<String> present) throws Exception {
        List<String> written = new ArrayList<>();
        installOne(t, project, replace, present, written, 0);
        return written;
    }

    private void installOne(Target t, String project, String replace, List<String> present, List<String> written, int depth) throws Exception {
        if (!project.matches("[A-Za-z0-9_-]{2,64}")) throw new IllegalArgumentException("bad project id");
        String url = MODRINTH + "project/" + project + "/version?loaders=" + URLEncoder.encode(Json.write(List.of(t.loader())), StandardCharsets.UTF_8)
                + (t.minecraft().isEmpty() ? "" : "&game_versions=" + URLEncoder.encode(Json.write(List.of(t.minecraft())), StandardCharsets.UTF_8));
        Object vs = get(url);
        if (!(vs instanceof List<?> list) || list.isEmpty()) throw new IOException("no version of " + project + " for " + t.loader() + " " + t.minecraft());
        Map<?, ?> v = (Map<?, ?>) list.get(0);
        Map<?, ?> file = null;
        for (Object f : (List<?>) v.get("files")) {
            if (f instanceof Map<?, ?> fm && (file == null || Boolean.TRUE.equals(fm.get("primary")))) file = fm;
        }
        if (file == null) throw new IOException("the version has no file");
        String name = String.valueOf(file.get("filename"));
        if (!name.matches("[A-Za-z0-9_.+()\\[\\] -]+\\.jar")) throw new IOException("odd file name " + name);
        String sha1 = String.valueOf(((Map<?, ?>) file.get("hashes")).get("sha1"));
        Files.createDirectories(t.dir());
        Path tmp = t.dir().resolve(name + ".part");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(String.valueOf(file.get("url"))))
                .header("User-Agent", agent()).timeout(Duration.ofMinutes(3)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("download: HTTP " + r.statusCode());
        }
        if (!hash(tmp, "SHA-1").equals(sha1)) {
            Files.deleteIfExists(tmp);
            throw new IOException("checksum mismatch for " + name);
        }
        Files.move(tmp, t.dir().resolve(name), StandardCopyOption.REPLACE_EXISTING);
        written.add(name);
        if (replace != null && !replace.isEmpty() && !replace.equals(name)) remove(t, replace);
        if (depth > 3 || !(v.get("dependencies") instanceof List<?> deps)) return;
        for (Object d : deps) {
            if (!(d instanceof Map<?, ?> dm) || !"required".equals(dm.get("dependency_type")) || dm.get("project_id") == null) continue;
            String dep = String.valueOf(dm.get("project_id"));
            if (present.contains(dep)) continue;
            present.add(dep);
            try {
                installOne(t, dep, null, present, written, depth + 1);
            } catch (IOException e) {
                written.add("(" + dep + ": " + e.getMessage() + ")");
            }
        }
    }

    /** Moves a jar to the server's .removed folder, so a mistake can be undone by hand. */
    static void remove(Target t, String file) throws IOException {
        if (!file.endsWith(".jar") || file.contains("/") || file.contains("\\") || file.startsWith(".")) throw new IllegalArgumentException("bad file");
        Path p = t.dir().resolve(file);
        if (!Files.isRegularFile(p)) throw new IOException("no such file " + file);
        Path bin = t.dir().resolve(".removed");
        Files.createDirectories(bin);
        Files.move(p, bin.resolve(file), StandardCopyOption.REPLACE_EXISTING);
    }

    // ---- what is inside a jar ----

    private static final Pattern TOML = Pattern.compile("(?m)^\\s*(displayName|version|modId)\\s*=\\s*\"([^\"]*)\"");

    /** The name and version a mod declares, for jars neither site knows. */
    static Map<String, Object> jarMeta(Path jar) {
        Map<String, Object> out = new LinkedHashMap<>();
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (String name : List.of("META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
                ZipEntry e = z.getEntry(name);
                if (e == null) continue;
                String toml = read(z, e);
                Matcher m = TOML.matcher(toml);
                while (m.find()) {
                    if (m.group(1).equals("displayName") && !out.containsKey("name")) out.put("name", m.group(2));
                    if (m.group(1).equals("version") && !out.containsKey("declared") && !m.group(2).startsWith("${")) out.put("declared", m.group(2));
                    if (m.group(1).equals("modId") && !out.containsKey("modId")) out.put("modId", m.group(2));
                }
                return out;
            }
            for (String name : List.of("fabric.mod.json", "plugin.yml", "paper-plugin.yml", "velocity-plugin.json")) {
                ZipEntry e = z.getEntry(name);
                if (e == null) continue;
                String text = read(z, e);
                if (name.endsWith(".json")) {
                    Object j = Json.parse(text);
                    if (j instanceof Map<?, ?> m) {
                        if (m.get("name") != null) out.put("name", m.get("name"));
                        if (m.get("version") != null) out.put("declared", m.get("version"));
                    }
                } else {
                    Matcher n = Pattern.compile("(?m)^name:\\s*['\"]?([^'\"\\n]+)").matcher(text);
                    Matcher v = Pattern.compile("(?m)^version:\\s*['\"]?([^'\"\\n]+)").matcher(text);
                    if (n.find()) out.put("name", n.group(1).trim());
                    if (v.find()) out.put("declared", v.group(1).trim());
                }
                return out;
            }
        } catch (IOException | RuntimeException ignored) {
            // not a readable jar
        }
        return out;
    }

    private static String read(ZipFile z, ZipEntry e) throws IOException {
        try (InputStream in = z.getInputStream(e)) {
            return new String(in.readNBytes(256 << 10), StandardCharsets.UTF_8);
        }
    }

    // ---- hashes ----

    private String[] hash(Path p) throws Exception {
        String key = p + "|" + Files.size(p) + "|" + Files.getLastModifiedTime(p).toMillis();
        String[] h = hashes.get(key);
        if (h == null) {
            byte[] b = Files.readAllBytes(p);
            h = new String[] {HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(b)),
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(b))};
            hashes.put(key, h);
        }
        return h;
    }

    private static String hash(Path p, String alg) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(Files.readAllBytes(p)));
    }

    /** CurseForge's fingerprint: MurmurHash2 (seed 1) over the file without whitespace bytes. */
    @SuppressWarnings("fallthrough")
    static long fingerprint(byte[] data) {
        ByteArrayOutputStream b = new ByteArrayOutputStream(data.length);
        for (byte x : data) if (x != 9 && x != 10 && x != 13 && x != 32) b.write(x);
        byte[] d = b.toByteArray();
        final int m = 0x5bd1e995;
        int len = d.length;
        int h = 1 ^ len;
        int i = 0;
        while (len >= 4) {
            int k = (d[i] & 0xff) | ((d[i + 1] & 0xff) << 8) | ((d[i + 2] & 0xff) << 16) | ((d[i + 3] & 0xff) << 24);
            k *= m;
            k ^= k >>> 24;
            k *= m;
            h *= m;
            h ^= k;
            i += 4;
            len -= 4;
        }
        switch (len) {
            case 3:
                h ^= (d[i + 2] & 0xff) << 16;
            case 2:
                h ^= (d[i + 1] & 0xff) << 8;
            case 1:
                h ^= d[i] & 0xff;
                h *= m;
            default:
                break;
        }
        h ^= h >>> 13;
        h *= m;
        h ^= h >>> 15;
        return h & 0xffffffffL;
    }

    // ---- HTTP ----

    private static String agent() {
        return "kronwerke/launcher/" + Main.VERSION + " (github.com/kronwerke/launcher)";
    }

    private Object get(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", agent())
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) return null;
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return Json.parse(r.body());
    }

    private Object post(String url, Object body) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", agent())
                .header("Content-Type", "application/json").timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return Json.parse(r.body());
    }

    private Object postCurse(String path, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(curseBase + path)).header("User-Agent", agent())
                .header("Content-Type", "application/json").header("Accept", "application/json").timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (!curseKey.isEmpty()) b.header("x-api-key", curseKey);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode());
        return Json.parse(r.body());
    }
}
