package de.kronwerke.launcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the launcher keeps its own files, next to the servers: launcher.properties, the
 * servers' files, keys, the console's data. That is whichever folder in the root holds a
 * launcher.properties, so the folder can be renamed to anything; a new install starts with
 * "launcher".
 */
public final class Home {
    private Home() {
    }

    public static Path of(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            List<Path> found = s.filter(p -> Files.isRegularFile(p.resolve("launcher.properties")))
                    .sorted().toList();
            if (!found.isEmpty()) return found.get(0);
        } catch (IOException ignored) {
            // no root to look in
        }
        return root.resolve("launcher");
    }
}
