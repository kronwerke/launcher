package de.kronwerke.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The jar's entry point, and the only part that never reloads. It owns what has to outlive a
 * launcher update: the panel's console (stdin and stdout), the running Minecraft processes
 * (see {@link Pump}) and a map of state handed from one launcher version to the next.
 * <p>
 * The launcher itself lives in {@code de.kronwerke.launcher} and is loaded in a class loader
 * of its own, from the jar named in {@code kronwerke/launcher/current} or else from this jar.
 * When it returns {@code "reload"}, the next version is loaded and takes over the running
 * servers without stopping them. Only JDK types and this package cross that line.
 * <p>
 * Keep this package small and its API stable: an update replaces everything else, but a jar
 * started by the panel keeps its own Boot until the container restarts.
 */
public final class Boot {
    /** What this package offers; a launcher checks it before using anything newer. */
    public static final int API = 1;

    private static final String LAUNCHER_MAIN = "de.kronwerke.launcher.Main";
    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);
    private static final BlockingQueue<String> CONSOLE = new LinkedBlockingQueue<>();
    private static final Map<String, Object> SHARED = new ConcurrentHashMap<>();
    private static volatile Runnable onShutdown;

    private Boot() {
    }

    /** The panel's console: everything printed here is what the panel shows. */
    public static PrintStream out() {
        return OUT;
    }

    /** Lines typed in the panel's console, in order. */
    public static BlockingQueue<String> console() {
        return CONSOLE;
    }

    /** State that survives a reload: running processes, counters, keys already loaded. */
    public static Map<String, Object> shared() {
        return SHARED;
    }

    /** What to run when the container is stopped (the panel's kill signal). Replaced on reload. */
    public static void onShutdown(Runnable r) {
        onShutdown = r;
    }

    /** The folder the panel started us in. */
    public static Path root() {
        return Path.of("").toAbsolutePath();
    }

    /** The jar the panel starts, or null when running from classes. */
    public static Path ownJar() {
        try {
            Path p = Path.of(Boot.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("version")) {
            OUT.println(version());
            return;
        }
        Thread stdin = new Thread(Boot::readStdin, "console");
        stdin.setDaemon(true);
        stdin.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Runnable r = onShutdown;
            if (r != null) r.run();
        }, "shutdown"));

        Path dir = root().resolve("kronwerke/launcher");
        while (true) {
            Path jar = chosenJar(dir);
            String result;
            try {
                result = runLauncher(jar);
            } catch (Throwable t) {
                if (jar == null || jar.equals(ownJar())) throw t;
                // a broken update must not take the server down: back to the jar the panel started
                OUT.println("[Kronwerke] Launcher " + jar.getFileName() + " failed to start (" + t + "), going back to the built in one");
                Files.deleteIfExists(dir.resolve("current"));
                continue;
            }
            if (!"reload".equals(result)) break;
            OUT.println("[Kronwerke] Reloading the launcher");
        }
        System.exit(0);
    }

    /** kronwerke/launcher/current names a jar in the same folder; anything else means this jar. */
    /** For tests: the same choice as at start. */
    public static Path chosenJarFor(Path dir) {
        return chosenJar(dir);
    }

    static Path chosenJar(Path dir) {
        try {
            Path current = dir.resolve("current");
            if (Files.exists(current)) {
                String name = Files.readString(current).trim();
                Path jar = dir.resolve(name).normalize();
                if (!name.isEmpty() && jar.startsWith(dir) && Files.isRegularFile(jar)) return jar;
            }
        } catch (IOException ignored) {
            // unreadable: fall back
        }
        return ownJar();
    }

    private static String runLauncher(Path jar) throws Exception {
        if (jar == null) {
            // running from classes, as in tests and in an IDE
            return call(Class.forName(LAUNCHER_MAIN));
        }
        try (Loader loader = new Loader(jar.toUri().toURL(), Boot.class.getClassLoader())) {
            Class<?> main = loader.loadClass(LAUNCHER_MAIN);
            ClassLoader before = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(loader);
            try {
                return call(main);
            } finally {
                Thread.currentThread().setContextClassLoader(before);
            }
        }
    }

    private static String call(Class<?> main) throws Exception {
        Method m = main.getMethod("boot");
        Object r = m.invoke(null);
        return r == null ? "exit" : r.toString();
    }

    static String version() {
        String v = Boot.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static void readStdin() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) CONSOLE.offer(line);
        } catch (IOException ignored) {
            // no console
        }
    }

    /** Waits for a console line, or null after the timeout. */
    public static String nextLine(long millis) throws InterruptedException {
        return CONSOLE.poll(millis, TimeUnit.MILLISECONDS);
    }

    /**
     * Loads the launcher's own package from its jar first, everything else (this package, the
     * JDK) from the parent. Without that, the parent would hand out the classes of the jar the
     * panel started, and an update would change nothing.
     */
    static final class Loader extends URLClassLoader {
        static {
            registerAsParallelCapable();
        }

        Loader(URL jar, ClassLoader parent) {
            super(new URL[] {jar}, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.startsWith("de.kronwerke.launcher.")) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = findClass(name);
                if (resolve) resolveClass(c);
                return c;
            }
        }

        @Override
        public URL getResource(String name) {
            if (name.startsWith("de/kronwerke/launcher/") || name.startsWith("console/")) {
                URL u = findResource(name);
                if (u != null) return u;
            }
            return super.getResource(name);
        }

        @Override
        public InputStream getResourceAsStream(String name) {
            URL u = getResource(name);
            try {
                return u == null ? null : u.openStream();
            } catch (IOException e) {
                return null;
            }
        }
    }
}
