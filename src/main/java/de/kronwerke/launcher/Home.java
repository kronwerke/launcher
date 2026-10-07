package de.kronwerke.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the launcher keeps its own files, next to the servers: launcher.properties, the
 * servers' files, keys, the console's data. A new install uses "launcher"; an install that
 * already has "kronwerke" (the first one, from before the name was free) keeps it.
 */
public final class Home {
    private Home() {
    }

    public static Path of(Path root) {
        Path old = root.resolve("kronwerke");
        if (Files.exists(old.resolve("launcher.properties"))) return old;
        return root.resolve("launcher");
    }
}
