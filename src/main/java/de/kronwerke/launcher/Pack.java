package de.kronwerke.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Brings the server to the state of the pack: NeoForge, mods and configs. */
public final class Pack {
    static final String BOOTSTRAP_URL =
            "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar";
    // fetched directly so the bootstrap never needs GitHub's API, which limits hosts that share an address
    static final String INSTALLER_URL =
            "https://github.com/packwiz/packwiz-installer/releases/latest/download/packwiz-installer.jar";
    static final String NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge/";

    private final Path root;
    private final String java;
    private final Consumer<String> log;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();

    public Pack(Path root, String java, Consumer<String> log) {
        this.root = root;
        this.java = java;
        this.log = log;
    }

    /** What pack.toml says. */
    public record Info(String name, String version, String minecraft, String neoforge) {}

    static Info parse(String toml) {
        return new Info(field(toml, "name"), field(toml, "version"), field(toml, "minecraft"), field(toml, "neoforge"));
    }

    private static String field(String toml, String key) {
        Matcher m = Pattern.compile("(?m)^\\s*" + Pattern.quote(key) + "\\s*=\\s*\"([^\"]*)\"").matcher(toml);
        return m.find() ? m.group(1) : "";
    }

    /** Fetches pack.toml and keeps a copy in the launcher's folder. */
    public Info fetch(String url) throws IOException, InterruptedException {
        String body = get(url);
        Files.createDirectories(Home.of(root));
        Files.writeString(Home.of(root).resolve("pack.toml"), body, StandardCharsets.UTF_8);
        return parse(body);
    }

    /** The pack as of the last update, or null. */
    public Info local() throws IOException {
        Path p = Home.of(root).resolve("pack.toml");
        return Files.exists(p) ? parse(Files.readString(p, StandardCharsets.UTF_8)) : null;
    }

    private String get(String url) throws IOException, InterruptedException {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60)).build(),
                HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IOException("GET " + url + ": HTTP " + r.statusCode());
        return r.body();
    }

    void download(String url, Path to) throws IOException, InterruptedException {
        Path tmp = to.resolveSibling(to.getFileName() + ".part");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5)).build(),
                HttpResponse.BodyHandlers.ofFile(tmp));
        if (r.statusCode() != 200) {
            Files.deleteIfExists(tmp);
            throw new IOException("GET " + url + ": HTTP " + r.statusCode());
        }
        Files.move(tmp, to, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Runs packwiz-installer for the server side of the pack. */
    public void update(String url) throws IOException, InterruptedException {
        Path boot = root.resolve("packwiz-installer-bootstrap.jar");
        if (!Files.exists(boot)) {
            log.accept("Downloading packwiz-installer-bootstrap");
            download(BOOTSTRAP_URL, boot);
        }
        Path installer = root.resolve("packwiz-installer.jar");
        if (!Files.exists(installer)) {
            log.accept("Downloading packwiz-installer");
            download(INSTALLER_URL, installer);
        }
        log.accept("Updating the pack from " + url);
        int code = run(List.of(java, "-jar", boot.toString(), "--bootstrap-no-update", "-g", "-s", "server", url));
        if (code != 0) throw new IOException("packwiz-installer exited with " + code);
    }

    /** The argument file NeoForge's installer writes for a version. */
    public Path argsFile(String neoforge) {
        String os = System.getProperty("os.name", "").toLowerCase().contains("win") ? "win_args.txt" : "unix_args.txt";
        return root.resolve("libraries/net/neoforged/neoforge/" + neoforge + "/" + os);
    }

    /** Installs the NeoForge server if the version is not there yet. */
    public void ensureNeoForge(String neoforge) throws IOException, InterruptedException {
        if (neoforge.isEmpty()) throw new IOException("the pack names no NeoForge version");
        if (Files.exists(argsFile(neoforge))) return;
        Path installer = root.resolve("neoforge-" + neoforge + "-installer.jar");
        log.accept("Installing NeoForge " + neoforge);
        download(NEOFORGE_MAVEN + neoforge + "/neoforge-" + neoforge + "-installer.jar", installer);
        int code = run(List.of(java, "-jar", installer.toString(), "--installServer", root.toString()));
        Files.deleteIfExists(installer);
        if (code != 0 || !Files.exists(argsFile(neoforge))) throw new IOException("the NeoForge installer failed (" + code + ")");
    }

    private int run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(new ArrayList<>(cmd)).directory(root.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        try (var r = p.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) log.accept(line);
        }
        return p.waitFor();
    }
}
