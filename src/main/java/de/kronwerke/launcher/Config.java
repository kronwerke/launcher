package de.kronwerke.launcher;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * kronwerke/launcher.properties. Written with every key and its default on the first
 * start, so the file itself is the documentation.
 */
public final class Config {
    static final String TEMPLATE = """
            # Kronwerke launcher. Changes apply on the next start of the container.

            # The packwiz pack. Before every start the server's mods and configs are brought to
            # this state. Empty turns pack updates off.
            pack.url=https://raw.githubusercontent.com/kronwerke/pack/main/pack.toml

            # Heap for Minecraft. The launcher itself needs very little, so the panel's own
            # memory share can stay small.
            memory=16G

            # More JVM flags for Minecraft, separated by spaces.
            jvm.args=-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15

            # The java to run Minecraft with. Empty is the same java as the launcher.
            java=

            # Start Minecraft when the launcher starts.
            autostart=true

            # Start Minecraft again after a crash. After three crashes within ten minutes the
            # launcher gives up until someone starts it.
            restart.on.crash=true

            # The Discord bot's link endpoint. Empty turns the link off. The launcher creates its
            # own key in kronwerke/link.key on the first start; the team accepts it once in the
            # bot's control channel.
            link.url=
            link.name=kronwerke
            """;

    private final Properties p = new Properties();

    public static Config load(Path file) throws IOException {
        Config c = new Config();
        try (Reader r = new java.io.StringReader(TEMPLATE)) {
            c.p.load(r);
        }
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                c.p.load(r);
            }
        } else {
            Files.createDirectories(file.getParent());
            Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
        }
        return c;
    }

    public String get(String key) {
        return p.getProperty(key, "").trim();
    }

    public boolean flag(String key) {
        return get(key).equalsIgnoreCase("true");
    }
}
