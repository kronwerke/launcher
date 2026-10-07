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
        serversFromAnOldInstall();
        homeFolders();
        cpuSplit();
        answersFromMinecraft();
        propertiesKeepTheRest();
        bootPicksTheCurrentJar();
        networkReadsConsoles();
        scheduleFindsTheNextTime();
        passed += de.kronwerke.launcher.web.AccessTests.run();
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
        check(c.get("bus.port").isEmpty() && c.get("pack.url").isEmpty() && !c.flag("cpu.pin") && c.get("console.language").equals("en"), "defaults");
        Files.writeString(dir.resolve("kronwerke/launcher.properties"), "# hi\nlink.url=wss://x/link\n");
        Config d = Config.load(dir.resolve("kronwerke/launcher.properties"));
        check(d.get("link.url").equals("wss://x/link") && d.get("link.name").equals("launcher"), "overrides on top of defaults");
        d.set("link.name", "other");
        d.set("cpu.pin", "true");
        String file = Files.readString(dir.resolve("kronwerke/launcher.properties"));
        check(file.startsWith("# hi\nlink.url=wss://x/link\n") && file.contains("link.name=other") && d.flag("cpu.pin"), "set keeps the rest, got " + file);
        fails(() -> d.set("a=b", "c"), "no = in keys");
    }

    static void serversFromAnOldInstall() throws IOException {
        Path dir = Files.createTempDirectory("kwl");
        Files.createDirectories(dir.resolve("kronwerke"));
        Files.writeString(dir.resolve("kronwerke/launcher.properties"), "pack.url=https://x/pack.toml\nmemory=24G\nautostart=true\n");
        Config c = Config.load(dir.resolve("kronwerke/launcher.properties"));
        List<Config.ServerConfig> s = Config.servers(dir, c);
        check(s.size() == 1 && s.get(0).name().equals("main") && s.get(0).dir().equals("."), "one server, main, in the root");
        check(s.get(0).cfg().get("type").equals("neoforge") && s.get(0).cfg().get("memory").equals("24G") && s.get(0).cfg().get("rcon.port").equals("25575")
                && s.get(0).cfg().get("port").isEmpty(), "memory taken over, ports left alone");
        Files.writeString(dir.resolve("kronwerke/servers/mining.properties"), "dir=servers/mining\nport=27212\norder=20\n");
        Files.writeString(dir.resolve("kronwerke/servers/Bad Name.properties"), "dir=x\n");
        s = Config.servers(dir, c);
        check(s.size() == 2 && s.get(1).name().equals("mining") && s.get(1).cfg().get("memory").equals("8G")
                && s.get(1).cfg().get("role").equals("mining"), "a second server with defaults, bad names ignored");
    }

    static void homeFolders() throws IOException {
        Path a = Files.createTempDirectory("kwl");
        check(Home.of(a).equals(a.resolve("launcher")) && de.kronwerke.boot.Boot.jarDirFor(a).equals(a.resolve("launcher/jars")), "a new install uses launcher");
        Files.createDirectories(a.resolve("acme"));
        Files.writeString(a.resolve("acme/launcher.properties"), "");
        check(Home.of(a).equals(a.resolve("acme")) && de.kronwerke.boot.Boot.jarDirFor(a).equals(a.resolve("acme/jars"))
                && Updater.jars(Home.of(a)).equals(a.resolve("acme/jars")), "a renamed folder is found");
        Files.createDirectories(a.resolve("acme/launcher"));
        Files.writeString(a.resolve("acme/launcher/current"), "x.jar");
        check(de.kronwerke.boot.Boot.jarDirFor(a).equals(a.resolve("acme/launcher")) && Updater.jars(Home.of(a)).equals(a.resolve("acme/launcher")),
                "an older install keeps its jars where they are");
        Path b = Files.createTempDirectory("kwl");
        Config c = Config.load(Home.of(b).resolve("launcher.properties"));
        List<Config.ServerConfig> s = Config.servers(b, c);
        check(s.get(0).cfg().get("type").equals("jar") && !s.get(0).cfg().flag("autostart"), "without a pack the first server waits for a jar");
    }

    static void cpuSplit() {
        Map<String, List<Integer>> p = Fleet.split(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), new java.util.LinkedHashMap<>(Map.of("main", 6)));
        check(p.get("main").size() == 9, "one server gets everything");
        var shares = new java.util.LinkedHashMap<String, Integer>();
        shares.put("main", 6);
        shares.put("mining", 3);
        p = Fleet.split(List.of(0, 1, 2, 3, 4, 5, 6, 7, 8), shares);
        check(p.get("main").equals(List.of(0, 1, 2, 3, 4, 5)) && p.get("mining").equals(List.of(6, 7, 8)), "6 to 3, got " + p);
        shares.put("main", 30);
        shares.put("mining", 1);
        p = Fleet.split(List.of(0, 1, 2, 3), shares);
        check(p.get("mining").size() == 1 && p.get("main").size() == 3, "everyone keeps at least one, got " + p);
        shares.put("nether", 1);
        p = Fleet.split(List.of(0, 1), shares);
        check(p.get("nether").size() == 2, "fewer CPUs than servers: all share");
        check(Proc.parseCpuList("0-3,8,10-11").equals(List.of(0, 1, 2, 3, 8, 10, 11)) && Proc.cpuList(List.of(1, 2)).equals("1,2"), "cpu lists");
        check(Fleet.gigabytes("20G") == 20 && Fleet.gigabytes("8192M") == 8 && Fleet.gigabytes("") == 0, "heap sizes");
    }

    static void answersFromMinecraft() {
        String tps = "Overworld: 20.000 TPS (12.005 ms/tick)\ndeeperdarker:otherside: 19.500 TPS (40.100 ms/tick)\nOverall: 20.000 TPS (12.388 ms/tick)\n";
        double[] v = Metrics.tps(tps);
        check(v[0] == 12.388 && v[1] == 20.0, "overall tick time");
        var dims = Metrics.dimensions(tps);
        check(dims.size() == 2 && dims.get(0).get("name").equals("deeperdarker:otherside"), "dimensions, slowest first");
        check(Metrics.tps("nope")[0] == -1, "no overall line");
        check(Server.players("There are 2 of a max of 40 players online: Elchi_Sam, KwTester\n").equals(List.of("Elchi_Sam", "KwTester")), "list");
        check(Server.players("There are 0 of a max of 40 players online: \n").isEmpty(), "nobody online");
    }

    static void propertiesKeepTheRest() throws IOException {
        Path f = Files.createTempFile("kwl", ".properties");
        Files.writeString(f, "#c\nserver-port=25565\nmotd=Hi\n");
        Server.Properties.set(f, Map.of("server-port", "27212", "accepts-transfers", "true"));
        String s = Files.readString(f);
        check(s.equals("#c\nserver-port=27212\nmotd=Hi\naccepts-transfers=true\n"), "changed in place, new ones at the end, got " + s);
    }

    static void bootPicksTheCurrentJar() throws IOException {
        Path dir = Files.createTempDirectory("kwl");
        check(de.kronwerke.boot.Boot.chosenJarFor(dir) == null, "nothing there: the built in one (none from classes)");
        Files.writeString(dir.resolve("launcher-abc.jar"), "x");
        Files.writeString(dir.resolve("current"), "launcher-abc.jar\n");
        check(dir.resolve("launcher-abc.jar").equals(de.kronwerke.boot.Boot.chosenJarFor(dir)), "the named jar");
        Files.writeString(dir.resolve("current"), "../../etc/passwd\n");
        check(de.kronwerke.boot.Boot.chosenJarFor(dir) == null, "nothing outside the folder");
    }

    static void networkReadsConsoles() throws IOException {
        java.util.regex.Matcher m = Network.CHAT.matcher("[12:00:01] [Server thread/INFO] [minecraft/MinecraftServer]: <Elchi_Sam> hallo du");
        check(m.find() && m.group(1).equals("Elchi_Sam") && m.group(2).equals("hallo du"), "NeoForge chat line");
        check(Network.CHAT.matcher("[12:00:01 INFO]: [Not Secure] <Notch> hi").find(), "Paper unsigned chat line");
        check(!Network.CHAT.matcher("[12:00:01] [Server thread/INFO]: [Rcon] tellraw @a <x> y").find(), "the bridge's own tellraw is not chat");
        check(!Network.CHAT.matcher("[12:00:01] [Server thread/INFO]: [main] <Elchi_Sam> hi").find(), "a relayed line is not chat again");
        m = Network.JOIN.matcher("[12:00:01] [Server thread/INFO] [minecraft/MinecraftServer]: KwTester joined the game");
        check(m.find() && m.group(1).equals("KwTester"), "join line");
        check(Network.LEAVE.matcher("[12:00:01] [Server thread/INFO]: KwTester left the game").find(), "leave line");
        String t = Network.tellraw(Json.map("op", "chat", "label", "Mine", "color", "#7fd0c8", "player", "A\"b", "text", "x"));
        check(Json.parse(t) instanceof List<?> l && l.size() == 3, "tellraw is a JSON list: " + t);
        check(Network.tellraw(Json.map("op", "message")) == null, "only mods understand their own messages");
        Path dir = Files.createTempDirectory("net");
        Config c = Config.load(dir.resolve("s.properties"), Config.serverTemplate("jar", "", ".", "", "", "", "1G", "1", "true", "10", "x"));
        Network.Policy p = Network.Policy.of(c);
        check(!p.inNetwork() && p.chat().equals("network") && p.joins() && p.tablist() && p.lists() && !p.players() && p.radius() == 100, "defaults: " + p);
        c.set("sync.chat", "nonsense");
        c.set("network", "kw");
        check(Network.Policy.of(c).chat().equals("network") && Network.Policy.of(c).inNetwork(), "unknown chat mode falls back");
        check(Network.mac("k".getBytes(StandardCharsets.UTF_8), "n:main").length() == 64, "HMAC-SHA256 in hex");
    }

    static void scheduleFindsTheNextTime() {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.of(2026, 10, 7, 4, 59, 0, 0, java.time.ZoneId.of("Europe/Berlin")); // a Wednesday
        Map<String, Object> daily = Json.map("kind", "daily", "time", "05:00", "days", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), "enabled", true);
        check(Schedule.next(daily, now).getHour() == 5 && Schedule.next(daily, now).getDayOfMonth() == 7, "daily: today at 5");
        check(Schedule.next(daily, now.withMinute(0).withHour(5)).getDayOfMonth() == 8, "daily: at 5 itself the next is tomorrow");
        Map<String, Object> weekend = Json.map("kind", "daily", "time", "05:00", "days", List.of(6L, 7L), "enabled", true);
        check(Schedule.next(weekend, now).getDayOfWeek() == java.time.DayOfWeek.SATURDAY, "weekend only: Saturday");
        Map<String, Object> every = Json.map("kind", "every", "hours", 6L, "enabled", true);
        check(Schedule.next(every, now).getHour() == 6, "every 6 h: 6 o'clock next");
        check(Schedule.next(every, now.withHour(23)).getHour() == 0, "every 6 h: midnight after 23");
        check(Schedule.next(Json.map("kind", "every", "hours", 6L, "enabled", false), now) == null, "off: never");
    }
}
