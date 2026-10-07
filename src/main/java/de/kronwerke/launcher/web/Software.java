package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Json;
import de.kronwerke.launcher.Main;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/**
 * Server software the console can install: where each one's versions and jars come from.
 * Everything is fetched from the makers' own services and checked against their checksums.
 */
final class Software {
    /** One kind of server software. */
    record Kind(String id, String name, String type, String loader, String about) {}

    static final List<Kind> KINDS = List.of(
            new Kind("paper", "Paper", "jar", "paper", "Plugins, schnell, die übliche Wahl"),
            new Kind("purpur", "Purpur", "jar", "paper", "Paper mit mehr Einstellungen"),
            new Kind("folia", "Folia", "jar", "paper", "Paper auf vielen Threads, für große Server"),
            new Kind("fabric", "Fabric", "jar", "fabric", "Leichte Mods"),
            new Kind("neoforge", "NeoForge", "neoforge", "neoforge", "Große Mods und Modpacks"),
            new Kind("vanilla", "Vanilla", "jar", "", "Minecraft, wie Mojang es liefert"),
            new Kind("velocity", "Velocity", "jar", "velocity", "Ein Proxy vor mehreren Servern"),
            new Kind("custom", "Eigene Jar", "jar", "", "Jede Jar, die du hochlädst"));

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

    static Kind kind(String id) {
        for (Kind k : KINDS) if (k.id().equals(id)) return k;
        throw new IllegalArgumentException("unknown software " + id);
    }

    static List<Object> kinds() {
        List<Object> out = new ArrayList<>();
        for (Kind k : KINDS) out.add(Json.map("id", k.id(), "name", k.name(), "about", k.about()));
        return out;
    }

    /** Versions, newest first; stable ones only unless all. */
    List<String> versions(String kind, boolean all) throws Exception {
        String key = kind + all;
        Object[] c = cache.get(key);
        if (c != null && System.currentTimeMillis() - (long) c[0] < 3600_000) {
            @SuppressWarnings("unchecked")
            List<String> v = (List<String>) c[1];
            return v;
        }
        List<String> out = new ArrayList<>();
        switch (kind) {
            case "paper", "folia", "velocity" -> {
                Map<?, ?> p = (Map<?, ?>) getJson("https://fill.papermc.io/v3/projects/" + kind);
                if (p.get("versions") instanceof Map<?, ?> groups) {
                    for (Object g : groups.values()) {
                        for (Object v : (List<?>) g) {
                            String s = String.valueOf(v);
                            if (all || !s.matches(".*-(rc|pre|snapshot|beta|alpha|SNAPSHOT).*")) out.add(s);
                        }
                    }
                }
            }
            case "purpur" -> {
                Map<?, ?> p = (Map<?, ?>) getJson("https://api.purpurmc.org/v2/purpur");
                for (Object v : (List<?>) p.get("versions")) out.add(0, String.valueOf(v));
            }
            case "fabric" -> {
                for (Object o : (List<?>) getJson("https://meta.fabricmc.net/v2/versions/game")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (all || Boolean.TRUE.equals(m.get("stable"))) out.add(String.valueOf(m.get("version")));
                }
            }
            case "vanilla" -> {
                Map<?, ?> p = (Map<?, ?>) getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
                for (Object o : (List<?>) p.get("versions")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (all || "release".equals(m.get("type"))) out.add(String.valueOf(m.get("id")));
                }
            }
            case "neoforge" -> {
                String xml = get("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml");
                Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
                while (m.find()) {
                    String v = m.group(1);
                    if (all || !v.matches(".*-(beta|alpha).*")) out.add(0, v);
                }
            }
            case "custom" -> {
                // nothing to list: the jar comes from the person
            }
            default -> throw new IllegalArgumentException("unknown software " + kind);
        }
        cache.put(key, new Object[] {System.currentTimeMillis(), out});
        return out;
    }

    /** The Minecraft version a NeoForge version is for: 21.1.x is 1.21.1, 26.3.0.x is 26.3. */
    static String minecraftOf(String neoforge) {
        String[] p = neoforge.split("[.-]");
        if (p.length < 2) return "";
        int major = Integer.parseInt(p[0]);
        if (major >= 26) return p[0] + "." + p[1];
        return p[1].equals("0") ? "1." + p[0] : "1." + p[0] + "." + p[1];
    }

    /**
     * Downloads the server jar of a kind and version into dir and returns what the server's file
     * needs: type, jar, loader, minecraft, neoforge. NeoForge is only noted; the launcher
     * installs it itself before the start.
     */
    Map<String, String> install(String kind, String version, Path dir) throws Exception {
        Kind k = kind(kind);
        Files.createDirectories(dir);
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("type", k.type());
        cfg.put("loader", k.loader());
        switch (kind) {
            case "neoforge" -> {
                cfg.put("neoforge", version);
                cfg.put("jar", "");
                cfg.put("minecraft", minecraftOf(version));
                return cfg;
            }
            case "paper", "folia", "velocity" -> {
                Map<?, ?> b = (Map<?, ?>) getJson("https://fill.papermc.io/v3/projects/" + kind + "/versions/" + version + "/builds/latest");
                Map<?, ?> d = (Map<?, ?>) ((Map<?, ?>) b.get("downloads")).get("server:default");
                String sha = String.valueOf(((Map<?, ?>) d.get("checksums")).get("sha256"));
                download(String.valueOf(d.get("url")), dir.resolve(kind + ".jar"), "SHA-256", sha);
            }
            case "purpur" -> {
                Map<?, ?> b = (Map<?, ?>) getJson("https://api.purpurmc.org/v2/purpur/" + version + "/latest");
                download("https://api.purpurmc.org/v2/purpur/" + version + "/latest/download", dir.resolve("purpur.jar"), "MD5", String.valueOf(b.get("md5")));
            }
            case "fabric" -> {
                String loader = firstStable((List<?>) getJson("https://meta.fabricmc.net/v2/versions/loader"));
                String installer = firstStable((List<?>) getJson("https://meta.fabricmc.net/v2/versions/installer"));
                download("https://meta.fabricmc.net/v2/versions/loader/" + version + "/" + loader + "/" + installer + "/server/jar",
                        dir.resolve("fabric.jar"), null, null);
            }
            case "vanilla" -> {
                Map<?, ?> manifest = (Map<?, ?>) getJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
                String url = null;
                for (Object o : (List<?>) manifest.get("versions")) {
                    Map<?, ?> m = (Map<?, ?>) o;
                    if (version.equals(m.get("id"))) url = String.valueOf(m.get("url"));
                }
                if (url == null) throw new IOException("no Minecraft " + version);
                Map<?, ?> v = (Map<?, ?>) getJson(url);
                Map<?, ?> server = (Map<?, ?>) ((Map<?, ?>) v.get("downloads")).get("server");
                if (server == null) throw new IOException("Minecraft " + version + " has no server jar");
                download(String.valueOf(server.get("url")), dir.resolve("vanilla.jar"), "SHA-1", String.valueOf(server.get("sha1")));
            }
            default -> throw new IllegalArgumentException("upload the jar for custom software");
        }
        cfg.put("jar", kind + ".jar");
        cfg.put("minecraft", kind.equals("velocity") ? "" : version);
        return cfg;
    }

    private static String firstStable(List<?> list) {
        for (Object o : list) {
            Map<?, ?> m = (Map<?, ?>) o;
            if (Boolean.TRUE.equals(m.get("stable"))) return String.valueOf(m.get("version"));
        }
        return String.valueOf(((Map<?, ?>) list.get(0)).get("version"));
    }

    private void download(String url, Path to, String alg, String expected) throws Exception {
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", agent())
                .timeout(Duration.ofMinutes(5)).build(), HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("download: HTTP " + r.statusCode());
        }
        if (alg != null && expected != null && !expected.isEmpty() && !expected.equals("null")) {
            String got = HexFormat.of().formatHex(MessageDigest.getInstance(alg).digest(Files.readAllBytes(tmp)));
            if (!got.equalsIgnoreCase(expected)) {
                Files.deleteIfExists(tmp);
                throw new IOException("checksum mismatch");
            }
        }
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String agent() {
        return "kronwerke/launcher/" + Main.VERSION + " (github.com/kronwerke/launcher)";
    }

    private String get(String url) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", agent())
                .timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException(url.replaceAll("^https://([^/]+).*", "$1") + ": HTTP " + r.statusCode());
        return r.body();
    }

    private Object getJson(String url) throws Exception {
        return Json.parse(get(url));
    }
}
