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
 * launcher.properties for the launcher, and one file per server in the folder servers next
 * to it. Each is written with every key and its default on the first start, so
 * the files themselves are the documentation.
 */
public final class Config {
    static final String TEMPLATE = """
            # The launcher. The servers it runs are in the folder "servers" next to this file,
            # one file each. Changes apply on the next `launcher reload`.

            # The name in the console's title and in front of the launcher's own lines.
            name=

            # A packwiz pack. Before a start with an update every NeoForge server's mods and
            # configs are brought to this state. Empty turns pack updates off.
            pack.url=

            # The java to run Minecraft with. Empty is the same java as the launcher.
            java=

            # JVM flags for every Java server, separated by spaces. A server's own jvm.args come after.
            jvm.args=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15

            # A controller to dial out to over WebSocket (the Kronwerke Discord bot speaks this).
            # Empty turns the link off. The launcher creates its key in link.key on the first
            # start; the controller accepts it once.
            link.url=
            link.name=launcher

            # The web console. The port is one the outside can reach; empty turns the console
            # off. host is the name it is reached by (passkeys are bound to it).
            console.port=
            console.host=
            console.bind=0.0.0.0

            # The console's language (en, de) and accent colour.
            console.language=en
            console.accent=#e5b451

            # Who may connect: "cloudflare" accepts only Cloudflare's origin pull certificate,
            # a path names another CA file, "off" accepts anyone (only for a test on loopback).
            console.client.ca=cloudflare

            # TLS certificate as PEM files, like a Cloudflare origin certificate. Empty makes a
            # self signed one in the console folder. console.tls=off only works on loopback.
            console.cert=
            console.key=
            console.tls=

            # The season page: on when Kronwerke Core is in the first server's mods (auto), on, off.
            console.season=auto

            # More origins passkeys may come from, comma separated, and the passkey domain if it
            # is not the host. For tests only.
            console.origins=
            console.rpid=

            # CurseForge for the mods page goes through a proxy that holds a key, so the key never
            # sits in a launcher. An own key in console/curseforge.key is used directly instead.
            # Empty and no key: Modrinth only.
            curseforge.proxy=https://api.kronwerke.com/curseforge

            # Where launcher updates come from: a GitHub repository with releases like this one's.
            update.repo=kronwerke/launcher

            # A loopback port mods can connect to for chat, tablist and moves between servers
            # (Kronwerke Core does). Empty turns it off.
            bus.port=

            # Limit each server to its share of the container's CPUs. Only when the container's
            # CPUs are known; see the servers' files for the shares.
            cpu.pin=false

            # Move CPU shares between servers by how busy they are (needs cpu.pin).
            cpu.balance=false

            # Memory the servers may use together, in GB. Empty reads the panel's SERVER_MEMORY or
            # the container's limit.
            container.memory=

            # The disk quota in GB, for the console's bar (panels do not tell it). Empty shows only
            # what is used.
            container.disk=
            """;

    static final String SERVER_TEMPLATE = """
            # A server the launcher runs. The file name is the server's name.

            # neoforge: a NeoForge server (version from the pack, or neoforge=); jar: java -jar
            # with the jar below (Paper, Fabric, vanilla); command: any other program.
            type=%s
            jar=%s
            command=
            neoforge=

            # Its folder, relative to the container's root. The first server may live in the root.
            dir=%s

            # Minecraft: game port (server-port), RCON on loopback for the launcher's commands,
            # accepts-transfers (true for a server players are moved to), Simple Voice Chat's
            # UDP port. Empty leaves each as it is.
            port=%s
            rcon.port=%s
            transfers=
            voice.port=%s

            # Java servers: heap (applies on the next start), more JVM flags for this server, the
            # java to use (empty: the launcher's setting), program arguments.
            memory=%s
            jvm.args=
            java=
            args=nogui

            # What the console types to stop it ("stop" for Minecraft; "-" or empty for a program
            # sends a stop signal), and a regular expression on a console line that means
            # "running" (Minecraft: RCON or the Done line; a program: at once).
            stop=
            ready=

            # A second server takes these from the first one's folder as links (comma separated;
            # config is linked entry by entry). Empty: mods, defaultconfigs, kubejs, libraries,
            # ops.json and config for NeoForge servers, nothing for others. own names config
            # entries it keeps a copy of instead.
            share=
            own=

            # Share of the CPU against the other servers. Moves while running when cpu.pin is on.
            cpu.share=%s

            # Start with the launcher, and start again after a crash (three in ten minutes and it
            # waits for a person).
            autostart=%s
            restart.on.crash=true

            # Order in which servers start (low first) and stop (high first).
            order=%s

            # Passed to the server as -Dlauncher.role; Kronwerke Core reads it (main, mining, ...).
            role=%s

            # The server's colour in the console, empty for one from the palette.
            color=
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
     * The servers. Without their folder (a first start, or a 0.1 install) it is created with
     * one server, main, in the root, taking memory and the start settings from an older
     * launcher.properties.
     */
    public static List<ServerConfig> servers(Path root, Config launcher) throws IOException {
        Path dir = Home.of(root).resolve("servers");
        if (!Files.isDirectory(dir)) {
            Files.createDirectories(dir);
            String mem = launcher.get("memory").isEmpty() ? "8G" : launcher.get("memory");
            String auto = launcher.get("autostart").isEmpty() ? "true" : launcher.get("autostart");
            boolean pack = !launcher.get("pack.url").isEmpty();
            // without a pack nothing says what to run yet; the file tells what to fill in
            Files.writeString(dir.resolve("main.properties"), serverTemplate(pack ? "neoforge" : "jar", "", ".", "", "25575", "", mem, "6",
                    pack ? auto : "false", "10", "main"), StandardCharsets.UTF_8);
        }
        List<ServerConfig> out = new ArrayList<>();
        try (Stream<Path> s = Files.list(dir)) {
            for (Path f : s.filter(f -> f.getFileName().toString().endsWith(".properties")).sorted().toList()) {
                String name = f.getFileName().toString().replaceFirst("\\.properties$", "");
                if (!name.matches("[a-z0-9][a-z0-9_-]{0,23}")) continue;
                out.add(new ServerConfig(name, load(f, serverTemplate("neoforge", "", ".", "", "", "", "8G", "3", "true", "50", name))));
            }
        }
        out.sort(Comparator.comparingInt(ServerConfig::order).thenComparing(ServerConfig::name));
        return out;
    }

    static String serverTemplate(String type, String jar, String dir, String port, String rcon, String voice, String memory,
                                 String share, String autostart, String order, String role) {
        return SERVER_TEMPLATE.formatted(type, jar, dir, port, rcon, voice, memory, share, autostart, order, role);
    }
}
