package de.kronwerke.launcher;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Minecraft RCON on the loopback interface of the container. The port is not one of the
 * server's allocations, so nobody outside can reach it; the password is still random.
 */
public final class Rcon {
    private Rcon() {}

    /**
     * Makes sure server.properties turns RCON on with a password. Runs before every start;
     * existing values are kept.
     */
    public static void prepare(Path serverProperties) throws IOException {
        List<String> lines = Files.exists(serverProperties)
                ? new ArrayList<>(Files.readAllLines(serverProperties, StandardCharsets.ISO_8859_1))
                : new ArrayList<>();
        boolean enable = false, port = false, pass = false;
        for (int k = 0; k < lines.size(); k++) {
            String l = lines.get(k);
            if (l.startsWith("enable-rcon=")) {
                lines.set(k, "enable-rcon=true");
                enable = true;
            } else if (l.startsWith("rcon.port=")) {
                port = true;
            } else if (l.startsWith("rcon.password=")) {
                if (l.substring("rcon.password=".length()).isBlank()) {
                    lines.set(k, "rcon.password=" + randomPassword());
                }
                pass = true;
            }
        }
        if (!enable) lines.add("enable-rcon=true");
        if (!port) lines.add("rcon.port=25575");
        if (!pass) lines.add("rcon.password=" + randomPassword());
        Files.write(serverProperties, lines, StandardCharsets.ISO_8859_1);
    }

    static String randomPassword() {
        byte[] b = new byte[18];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    static String property(Path serverProperties, String key) throws IOException {
        for (String l : Files.readAllLines(serverProperties, StandardCharsets.ISO_8859_1)) {
            if (l.startsWith(key + "=")) return l.substring(key.length() + 1).trim();
        }
        return "";
    }

    /** Runs one command and returns what the server answered. */
    public static String command(Path serverProperties, String cmd) throws IOException {
        int port = Integer.parseInt(property(serverProperties, "rcon.port").isEmpty() ? "25575" : property(serverProperties, "rcon.port"));
        String password = property(serverProperties, "rcon.password");
        try (Socket s = connect(port)) {
            // commands run on the server thread, which can be busy for a while after a start
            s.setSoTimeout(60_000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            send(out, 1, 3, password);
            Packet auth = read(in);
            if (auth.id == -1) throw new IOException("rcon: wrong password");
            send(out, 2, 2, cmd);
            // an empty command after the real one marks the end of a split answer
            send(out, 3, 2, "");
            StringBuilder b = new StringBuilder();
            while (true) {
                Packet p = read(in);
                if (p.id == 3) break;
                b.append(p.body);
            }
            return b.toString();
        }
    }

    private static Socket connect(int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress("127.0.0.1", port), 3000);
                return s;
            } catch (IOException e) {
                s.close();
                last = e;
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw last == null ? new IOException("rcon: interrupted") : last;
    }

    private record Packet(int id, int type, String body) {}

    private static void send(OutputStream out, int id, int type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
    }

    private static Packet read(InputStream in) throws IOException {
        byte[] head = new DataInputStream(in).readNBytes(4);
        if (head.length < 4) throw new IOException("rcon: connection closed");
        int len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 20) throw new IOException("rcon: bad packet length " + len);
        byte[] rest = in.readNBytes(len);
        if (rest.length < len) throw new IOException("rcon: connection closed");
        ByteBuffer b = ByteBuffer.wrap(rest).order(ByteOrder.LITTLE_ENDIAN);
        int id = b.getInt(), type = b.getInt();
        String body = new String(rest, 8, len - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }
}
