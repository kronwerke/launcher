package de.kronwerke.launcher;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/** Tests without a test framework: every check throws on failure, main runs them all. */
public final class Tests {
    private static int passed;

    public static void main(String[] args) throws Exception {
        jsonRoundTrip();
        jsonRejectsGarbage();
        packToml();
        filesStayInsideAndKeepTheKey();
        tailReadsTheEnd();
        rconPrepareKeepsValues();
        keyIsStable();
        configWritesTemplate();
        System.out.println(passed + " checks passed");
    }

    static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
        passed++;
    }

    static void fails(ThrowingRunnable r, String what) {
        try {
            r.run();
        } catch (Exception e) {
            passed++;
            return;
        }
        throw new AssertionError("expected a failure: " + what);
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void jsonRoundTrip() {
        String text = "{\"type\":\"req\",\"id\":7,\"op\":\"write\",\"args\":{\"path\":\"config/a b.toml\",\"n\":-1.5,\"on\":true,\"x\":null,"
                + "\"list\":[1,\"two\",[]],\"s\":\"quote \\\" slash \\\\ nl \\n tab \\t u \\u00e4\"}}";
        Map<String, Object> m = Json.object(text);
        check(m.get("id").equals(7L), "numbers are longs");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) m.get("args");
        check(a.get("n").equals(-1.5), "fractions are doubles");
        check(a.get("s").equals("quote \" slash \\ nl \n tab \t u \u00e4"), "escapes");
        check(a.containsKey("x") && a.get("x") == null, "null");
        check(Json.object(Json.write(m)).equals(m), "write then parse gives the same");
        check(Json.write(Json.map("c", "\u0001")).equals("{\"c\":\"\\u0001\"}"), "control characters are escaped");
    }

    static void jsonRejectsGarbage() {
        for (String bad : List.of("", "{", "{\"a\"}", "{\"a\":1,}", "[1 2]", "\"open", "{\"a\":tru}", "{} x", "-")) {
            fails(() -> Json.parse(bad), bad);
        }
    }

    static void packToml() {
        Pack.Info i = Pack.parse("name = \"Kronwerke Season 2\"\nversion = \"0.4.0\"\n[versions]\nminecraft = \"1.21.1\"\nneoforge = \"21.1.252\"\n");
        check(i.version().equals("0.4.0") && i.neoforge().equals("21.1.252") && i.minecraft().equals("1.21.1"), "pack.toml fields");
    }

    static void filesStayInsideAndKeepTheKey() throws Exception {
        Path root = Files.createTempDirectory("kwl");
        Path key = root.resolve("kronwerke/link.key");
        Files.createDirectories(key.getParent());
        Files.writeString(key, "secret");
        Files.createDirectories(root.resolve("config"));
        ServerFiles f = new ServerFiles(root, key);
        fails(() -> f.read("../etc/passwd"), "parent folder");
        fails(() -> f.read("/etc/passwd"), "absolute path");
        fails(() -> f.read("kronwerke/link.key"), "the key");
        fails(() -> f.read("config/../kronwerke/./link.key"), "the key through a detour");
        fails(() -> f.write("world/level.dat", enc("x")), "world is not writable");
        fails(() -> f.write("kronwerke/link.key", enc("x")), "the key is not writable");
        f.write("config/sub/a.toml", enc("hello"));
        check(new String(Base64.getDecoder().decode((String) f.read("config/sub/a.toml").get("data"))).equals("hello"), "write and read");
        check(f.list("kronwerke").isEmpty(), "the key is not listed");
        check(f.list("config").size() == 1, "list");
        f.delete("config/sub");
        check(!Files.exists(root.resolve("config/sub")), "delete a folder");
        check(f.writable(root.resolve("server.properties")) && !f.writable(root.resolve("eula.txt")), "single files");
    }

    static String enc(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    static void tailReadsTheEnd() throws Exception {
        Path root = Files.createTempDirectory("kwl");
        StringBuilder b = new StringBuilder();
        for (int k = 1; k <= 50000; k++) b.append("line ").append(k).append('\n');
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("logs/latest.log"), b.toString());
        ServerFiles f = new ServerFiles(root, root.resolve("kronwerke/link.key"));
        List<String> t = f.tail("logs/latest.log", 3);
        check(t.equals(List.of("line 49998", "line 49999", "line 50000")), "last three lines, got " + t);
        check(f.tail("logs/latest.log", 100000).size() == 50000, "more than there are");
        Files.writeString(root.resolve("logs/short.log"), "a\nb");
        check(f.tail("logs/short.log", 5).equals(List.of("a", "b")), "no final newline");
    }

    static void rconPrepareKeepsValues() throws IOException {
        Path dir = Files.createTempDirectory("kwl");
        Path p = dir.resolve("server.properties");
        Rcon.prepare(p);
        String pass = Rcon.property(p, "rcon.password");
        check(pass.length() == 36 && Rcon.property(p, "enable-rcon").equals("true"), "fresh file gets rcon");
        Files.writeString(p, "motd=Hi\nenable-rcon=false\nrcon.port=25599\nrcon.password=\n");
        Rcon.prepare(p);
        check(Rcon.property(p, "enable-rcon").equals("true"), "rcon turned on");
        check(Rcon.property(p, "rcon.port").equals("25599"), "port kept");
        check(!Rcon.property(p, "rcon.password").isEmpty(), "empty password replaced");
        String set = Rcon.property(p, "rcon.password");
        Rcon.prepare(p);
        check(Rcon.property(p, "rcon.password").equals(set) && Rcon.property(p, "motd").equals("Hi"), "existing values kept");
    }

    static void keyIsStable() throws IOException {
        Path dir = Files.createTempDirectory("kwl");
        String a = Link.loadKey(dir.resolve("kronwerke/link.key"));
        String b = Link.loadKey(dir.resolve("kronwerke/link.key"));
        check(a.equals(b) && a.length() == 64, "the key survives a restart");
        check(Link.fingerprint(a).length() == 12 && !a.startsWith(Link.fingerprint(a)), "fingerprint is a hash, not the key");
    }

    static void configWritesTemplate() throws IOException {
        Path dir = Files.createTempDirectory("kwl");
        Config c = Config.load(dir.resolve("kronwerke/launcher.properties"));
        check(Files.exists(dir.resolve("kronwerke/launcher.properties")), "template written");
        check(c.get("memory").equals("16G") && c.flag("autostart") && c.get("link.url").isEmpty(), "defaults");
        Files.writeString(dir.resolve("kronwerke/launcher.properties"), "memory=8G\n");
        check(Config.load(dir.resolve("kronwerke/launcher.properties")).get("memory").equals("8G")
                && Config.load(dir.resolve("kronwerke/launcher.properties")).flag("restart.on.crash"), "overrides on top of defaults");
    }
}
