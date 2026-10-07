package de.kronwerke.launcher;

import de.kronwerke.boot.Boot;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The launcher, as {@link Boot} starts it: the servers, the link to the bot, the console.
 * {@link #boot()} returns when the container stops ("exit") or a new version takes over
 * ("reload").
 *
 * Console lines typed in the panel go to the first server, except:
 *   stop                       stops every server and then the launcher, like before
 *   @name command              a command for another server
 *   launcher status            what every server is doing
 *   launcher start [name]      start a server (all without a name)
 *   launcher stop [name]       stop a server (all without a name); the launcher stays
 *   launcher restart [name]    restart a server (all without a name)
 *   launcher update            pack update: stop all, update, start again
 *   launcher reload            hand the servers to a freshly loaded launcher
 * ("kronwerke" works in place of "launcher", as it did before.)
 */
public final class Main {
    public static final String VERSION = Main.class.getPackage() == null || Main.class.getPackage().getImplementationVersion() == null
            ? "dev" : Main.class.getPackage().getImplementationVersion();

    private Main() {
    }

    /** Running without Boot, from classes: same as starting the jar. */
    public static void main(String[] args) throws Exception {
        Boot.main(args);
    }

    public static String boot() throws Exception {
        PrintStream out = Boot.out();
        Path root = Boot.root();
        Path home = Home.of(root);
        Config cfg = Config.load(home.resolve("launcher.properties"));
        String java = cfg.get("java").isEmpty() ? ProcessHandle.current().info().command().orElse("java") : cfg.get("java");
        Fleet fleet = new Fleet(root, cfg, java, out);
        fleet.note("Launcher " + VERSION + ", java " + System.getProperty("java.version") + ", servers "
                + String.join(", ", fleet.servers().stream().map(Server::name).toList()));

        Link link = null;
        if (!cfg.get("link.url").isEmpty()) {
            link = new Link(cfg.get("link.url"), cfg.get("link.name"), root, fleet);
            fleet.note("Link to " + cfg.get("link.url") + ", fingerprint " + link.fingerprint());
            Thread t = new Thread(link::run, "link");
            t.setDaemon(true);
            t.start();
        }

        de.kronwerke.launcher.web.Web web = null;
        if (!cfg.get("console.port").isEmpty()) {
            try {
                web = new de.kronwerke.launcher.web.Web(fleet, cfg);
                web.start();
            } catch (Exception e) {
                fleet.note("Console could not start: " + e.getMessage());
                web = null;
            }
        }

        AtomicBoolean ending = new AtomicBoolean();
        Thread console = new Thread(() -> readConsole(fleet, ending), "console-reader");
        console.setDaemon(true);
        console.start();

        // the panel's kill signal: give Minecraft the chance to save
        Boot.onShutdown(fleet::shutdown);

        String result = fleet.run();
        ending.set(true);
        if (link != null) link.close();
        if (web != null) web.stop();
        console.join(2000);
        fleet.awaitWork();
        if (!"reload".equals(result)) fleet.note("Launcher stopped");
        return result;
    }

    static void readConsole(Fleet fleet, AtomicBoolean ending) {
        try {
            // lines typed during a reload wait until the servers are taken over
            fleet.awaitStarted();
        } catch (InterruptedException e) {
            return;
        }
        while (!ending.get()) {
            String line;
            try {
                line = Boot.nextLine(500);
            } catch (InterruptedException e) {
                return;
            }
            if (line == null) continue;
            try {
                handle(fleet, line);
            } catch (RuntimeException e) {
                fleet.note(e.getMessage());
            }
        }
    }

    static void handle(Fleet fleet, String line) {
        String t = line.trim();
        if (t.equals("stop") || t.equals("end")) {
            new Thread(fleet::shutdown, "shutdown").start();
            return;
        }
        if (t.startsWith("@")) {
            int sp = t.indexOf(' ');
            if (sp < 0) throw new IllegalArgumentException("@name command");
            Server s = fleet.server(t.substring(1, sp));
            if (!s.send(t.substring(sp + 1))) s.note("Not running; `launcher start " + s.name() + "` starts it");
            else fleet.network().mirror(s, t.substring(sp + 1));
            return;
        }
        boolean ours = t.equals("launcher") || t.startsWith("launcher ") || t.equals("kronwerke") || t.startsWith("kronwerke ");
        if (!ours) {
            Server s = fleet.main();
            if (!s.send(line)) s.note("Minecraft is not running; `launcher start` starts it");
            else fleet.network().mirror(s, line);
            return;
        }
        String[] a = t.split("\\s+");
        String verb = a.length > 1 ? a[1] : "status";
        String name = a.length > 2 ? a[2] : "";
        switch (verb) {
            case "status" -> {
                for (Server s : fleet.servers()) {
                    fleet.note(s.name() + ": " + s.state().name().toLowerCase() + " since " + s.since()
                            + (s.detail().isEmpty() ? "" : " (" + s.detail() + ")") + ", starts " + s.starts()
                            + (s.pid() > 0 ? ", pid " + s.pid() : ""));
                }
            }
            case "start" -> each(fleet, name, Server::start);
            case "stop" -> each(fleet, name, s -> new Thread(s::stop, "stop-" + s.name()).start());
            case "restart" -> each(fleet, name, s -> new Thread(s::restart, "restart-" + s.name()).start());
            case "update" -> new Thread(() -> {
                try {
                    fleet.update();
                } catch (Exception e) {
                    fleet.note("Pack update failed: " + e.getMessage());
                }
            }, "update").start();
            case "reload" -> new Thread(fleet::reload, "reload").start();
            default -> fleet.note("launcher status | start [name] | stop [name] | restart [name] | update | reload");
        }
    }

    private static void each(Fleet fleet, String name, java.util.function.Consumer<Server> action) {
        if (!name.isEmpty()) {
            action.accept(fleet.server(name));
            return;
        }
        for (Server s : fleet.servers()) action.accept(s);
    }
}
