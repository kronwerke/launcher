package de.kronwerke.launcher;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * kronwerke/launcher.properties for the launcher, and one file per server in
 * kronwerke/servers. Each is written with every key and its default on the first start, so
 * the files themselves are the documentation.
 */
public final class Config {
    static final String TEMPLATE = """
            # Kronwerke launcher. The servers it runs are in kronwerke/servers, one file each.

            # The packwiz pack. Before a start with an update every server's mods and configs are
            # brought to this state. Empty turns pack updates off.
            pack.url=https://raw.githubusercontent.com/kronwerke/pack/main/pack.toml

            # The java to run Minecraft with. Empty is the same java as the launcher.
            java=

            # JVM flags for every server, separated by spaces. A server's own jvm.args come after.
            jvm.args=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15

            # The Discord bot's link endpoint. Empty turns the link off. The launcher creates its
            # own key in kronwerke/link.key on the first start; the team accepts it once in the
            # bot's control channel.
            link.url=
            link.name=kronwerke

            # The web console. The port is one of the container's allocations; empty turns the
            # console off. host is the name it is reached by (passkeys are bound to it).
            console.port=
            console.host=console.kronwerke.com
            console.bind=0.0.0.0

            # Who may connect: "cloudflare" accepts only Cloudflare's origin pull certificate,
            # a path names another CA file, "off" accepts anyone (only for a test on loopback).
            console.client.ca=cloudflare

            # TLS certificate as PEM files, like a Cloudflare origin certificate. Empty makes a
            # self signed one in kronwerke/console. console.tls=off only works on loopback.
            console.cert=
            console.key=
            console.tls=

            # More origins passkeys may come from, comma separated, and the passkey domain if it
            # is not the host. For tests only.
            console.origins=
            console.rpid=

            # The bus Kronwerke Core connects to (chat, tablist, moves between servers). Loopback only.
            bus.port=25580

            # Limit each server to its share of the container's CPUs. Only when the container's
            # CPUs are known; see kronwerke/servers for the shares.
            cpu.pin=false

            # Move CPU shares between servers by how busy they are (needs cpu.pin).
            cpu.balance=false

            # Memory the container may use in total, in GB. Empty reads the container's limit.
            container.memory=
            """;

    static final String SERVER_TEMPLATE = """
            # A server the Kronwerke launcher runs. The file name is the server's name.

            # Its folder, relative to the container's root. The first server lives in the root.
            dir=%s

            # Game port (server-port). Empty leaves server.properties as it is.
            port=%s

            # RCON on loopback, for the launcher's own commands.
            rcon.port=%s

            # Simple Voice Chat's UDP port. Empty leaves its config as it is.
            voice.port=%s

            # Heap. Applies on the server's next start.
            memory=%s

            # More JVM flags for this server only.
            jvm.args=

            # Share of the CPU against the other servers. Moves while running when cpu.pin is on.
            cpu.share=%s

            # Start with the launcher, and start again after a crash (three in ten minutes and it
            # waits for a person).
            autostart=%s
            restart.on.crash=true

            # Order in which servers start (low first) and stop (high first).
            order=%s

            # What Kronwerke Core does on this server: main, or the name of a world like mining.
            role=%s
            """;

    private final Properties p = new Properties();
    private final Path file;

    private Config(Path file) {
        this.file = file;
    }

    public static Config load(Path file) throws IOException {
        return load(file, TEMPLATE);
    }

    static Config load(Path file, String template) throws IOException {
        Config c = new Config(file);
        try (Reader r = new StringReader(template)) {
            c.p.load(r);
        }
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                c.p.load(r);
            }
        } else {
            Files.createDirectories(file.getParent());
            Files.writeString(file, template, StandardCharsets.UTF_8);
        }
        return c;
    }

    public Path file() {
        return file;
    }

    public String get(String key) {
        return p.getProperty(key, "").trim();
    }

    public boolean flag(String key) {
        return get(key).equalsIgnoreCase("true");
    }

    public int number(String key, int fallback) {
        try {
            return Integer.parseInt(get(key));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Changes one key in the file, keeping comments and every other line as they are. Used by
     * the console; the running value changes too.
     */
    public synchronized void set(String key, String value) throws IOException {
        if (key.contains("=") || key.contains("\n") || value.contains("\n")) throw new IllegalArgumentException("bad key or value");
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : new ArrayList<>();
        boolean found = false;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i).stripLeading();
            if (l.startsWith(key + "=") || l.startsWith(key + " =")) {
                lines.set(i, key + "=" + value);
                found = true;
                break;
            }
        }
        if (!found) lines.add(key + "=" + value);
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        p.setProperty(key, value);
    }

    /** One server's settings. */
    public record ServerConfig(String name, Config cfg) {
        public String dir() {
            return cfg.get("dir").isEmpty() ? "." : cfg.get("dir");
        }

        public int order() {
            return cfg.number("order", 50);
        }
    }

    /**
     * The servers in kronwerke/servers. Without that folder (a 0.1 install) it is created with
     * one server, main, in the root, taking memory and the start settings from the old
     * launcher.properties.
     */
    public static List<ServerConfig> servers(Path root, Config launcher) throws IOException {
        Path dir = root.resolve("kronwerke/servers");
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            String mem = launcher.get("memory").isEmpty() ? "20G" : launcher.get("memory");
            String auto = launcher.get("autostart").isEmpty() ? "true" : launcher.get("autostart");
            Files.writeString(dir.resolve("main.properties"), serverTemplate(".", "", "25575", "", mem, "6", auto, "10", "main"),
                    StandardCharsets.UTF_8);
        }
        List<ServerConfig> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                String name = f.getFileName().toString().replaceFirst("\\.properties$", "");
                if (!name.matches("[a-z0-9][a-z0-9_-]{0,23}")) continue;
                out.add(new ServerConfig(name, load(f, serverTemplate(".", "", "", "", "8G", "3", "true", "50", name))));
            }
        }
        out.sort(Comparator.comparingInt(ServerConfig::order).thenComparing(ServerConfig::name));
        return out;
    }

    static String serverTemplate(String dir, String port, String rcon, String voice, String memory, String share,
                                 String autostart, String order, String role) {
        return SERVER_TEMPLATE.formatted(dir, port, rcon, voice, memory, share, autostart, order, role);
    }
}
