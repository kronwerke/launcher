package de.kronwerke.launcher;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Entry point. Set as the server jar in the panel; it prepares the server, runs Minecraft
 * as a child and stays in between.
 *
 * Console lines typed in the panel go to Minecraft, except:
 *   stop                 stops Minecraft and then the launcher, like before
 *   kronwerke status     what the launcher is doing
 *   kronwerke restart    restart Minecraft (kronwerke update: with a pack update first)
 *   kronwerke start      start Minecraft after it was stopped or gave up after crashes
 */
public final class Main {
    public static final String VERSION = Main.class.getPackage().getImplementationVersion() == null
            ? "dev" : Main.class.getPackage().getImplementationVersion();

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("version")) {
            System.out.println(VERSION);
            return;
        }
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        Path root = Path.of("").toAbsolutePath();
        Config cfg = Config.load(root.resolve("kronwerke/launcher.properties"));
        String java = cfg.get("java").isEmpty()
                ? ProcessHandle.current().info().command().orElse("java") : cfg.get("java");
        Server server = new Server(root, cfg, java, out::println);
        server.note("Kronwerke launcher " + VERSION + ", java " + System.getProperty("java.version"));

        if (!cfg.get("link.url").isEmpty()) {
            Path jar = null;
            try {
                jar = Path.of(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (Exception ignored) {
                // no jar, running from classes
            }
            Link link = new Link(cfg.get("link.url"), cfg.get("link.name"), root, server, jar);
            server.note("Link to " + cfg.get("link.url") + ", fingerprint " + link.fingerprint());
            Thread t = new Thread(link::run, "link");
            t.setDaemon(true);
            t.start();
        }

        Thread console = new Thread(() -> readConsole(server), "console");
        console.setDaemon(true);
        console.start();

        // the panel's kill signal: give Minecraft the chance to save
        Runtime.getRuntime().addShutdownHook(new Thread(server::shutdown, "shutdown"));

        server.run();
        server.note("Launcher stopped");
        System.exit(0);
    }

    static void readConsole(Server server) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                switch (t) {
                    case "stop", "end" -> new Thread(server::shutdown).start();
                    case "kronwerke status" -> server.note(server.state().name().toLowerCase() + " since " + server.since()
                            + (server.detail().isEmpty() ? "" : " (" + server.detail() + ")") + ", starts " + server.starts());
                    case "kronwerke restart" -> new Thread(() -> server.restart(false)).start();
                    case "kronwerke update" -> new Thread(() -> server.restart(true)).start();
                    case "kronwerke start" -> server.start(false);
                    case "kronwerke stop" -> new Thread(server::stop).start();
                    default -> {
                        if (!server.send(line)) server.note("Minecraft is not running; `kronwerke start` starts it");
                    }
                }
            }
        } catch (Exception ignored) {
            // no console
        }
    }
}
