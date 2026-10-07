package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Json;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Map;

/** A passkey made in software, run through setup, sign in and the checks that must refuse. */
public final class AccessTests {
    private AccessTests() {
    }

    public static int run() throws Exception {
        int checks = 0;
        Path dir = Files.createTempDirectory("kwa");
        String origin = "https://console.example";
        Access a = new Access(dir, "console.example", List.of(origin));
        String code = a.setupCode();
        check(code != null && Files.exists(dir.resolve("setup.code")), "a setup code while nobody exists");
        checks++;

        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = g.generateKeyPair();
        byte[] credId = "credential-one".getBytes(StandardCharsets.UTF_8);

        expectFail(() -> a.registerOptions("setup", "KW-WRONG-CODE", null, "Samuel"), "wrong setup code");
        checks++;
        Map<String, Object> o = a.registerOptions("setup", code.toLowerCase().replace("-", " "), null, "Samuel");
        Map<String, Object> opts = obj(o.get("options"));
        byte[] challenge = Access.unb64((String) opts.get("challenge"));
        Map<String, Object> cred = createResponse(kp, credId, challenge, origin, "console.example", 0);
        Map<String, Object> user = a.register((String) o.get("id"), cred, "Test");
        check("owner".equals(user.get("role")) && a.setupCode() == null && !Files.exists(dir.resolve("setup.code")), "first passkey makes the owner");
        checks++;
        expectFail(() -> a.register((String) o.get("id"), cred, "Again"), "a challenge works once");
        checks++;

        Map<String, Object> lo = a.loginOptions();
        byte[] ch2 = Access.unb64((String) obj(lo.get("options")).get("challenge"));
        Map<String, Object> as = assertion(kp, credId, ch2, origin, "console.example", 5);
        check("owner".equals(a.login((String) lo.get("id"), as).get("role")), "sign in with the passkey");
        checks++;

        Map<String, Object> lo2 = a.loginOptions();
        byte[] ch3 = Access.unb64((String) obj(lo2.get("options")).get("challenge"));
        expectFail(() -> a.login((String) lo2.get("id"), assertion(kp, credId, ch3, "https://evil.example", "console.example", 6)), "wrong origin");
        checks++;
        Map<String, Object> lo3 = a.loginOptions();
        byte[] ch4 = Access.unb64((String) obj(lo3.get("options")).get("challenge"));
        expectFail(() -> a.login((String) lo3.get("id"), assertion(kp, credId, ch4, origin, "console.example", 3)), "counter went back");
        checks++;
        Map<String, Object> lo4 = a.loginOptions();
        byte[] ch5 = Access.unb64((String) obj(lo4.get("options")).get("challenge"));
        Map<String, Object> bad = assertion(kp, credId, ch5, origin, "console.example", 9);
        bad.put("signature", Access.b64(new byte[] {48, 6, 2, 1, 1, 2, 1, 1}));
        expectFail(() -> a.login((String) lo4.get("id"), bad), "a wrong signature");
        checks++;
        Map<String, Object> lo5 = a.loginOptions();
        byte[] ch6 = Access.unb64((String) obj(lo5.get("options")).get("challenge"));
        expectFail(() -> a.login((String) lo5.get("id"), assertion(kp, credId, ch6, origin, "other.example", 10)), "a passkey for another site");
        checks++;

        // sessions and keys
        String token = a.newSession((String) user.get("id"), "127.0.0.1", "test");
        Access.Who who = a.session(token);
        check(who != null && who.can("access") && !Files.readString(dir.resolve("access.json")).contains(token), "a session, stored as a hash");
        checks++;
        Map<String, Object> k = a.newKey("Ops", List.of("read", "command"), 0, who.id());
        Access.Who kw = a.key((String) k.get("token"));
        check(kw != null && kw.can("command") && !kw.can("files") && !kw.can("access"), "a key has exactly its scopes");
        checks++;
        expectFail(() -> a.newKey("Ops", List.of("access"), 0, who.id()), "no key may manage access");
        checks++;
        check(a.key("kwc_nope") == null && a.dropKey((String) k.get("id")) && a.key((String) k.get("token")) == null, "dropped keys stop working");
        checks++;

        // a fresh Access from the same file keeps everything and has no setup code
        Access again = new Access(dir, "console.example", List.of(origin));
        check(again.setupCode() == null && again.session(token) != null, "survives a restart");
        checks++;
        String inv = again.newInvite("mod", "Tomy", who.id());
        Map<String, Object> io = again.registerOptions("invite", inv, null, "");
        Map<String, Object> ic = createResponse(kpOf(), "credential-two".getBytes(StandardCharsets.UTF_8),
                Access.unb64((String) obj(io.get("options")).get("challenge")), origin, "console.example", 0);
        Map<String, Object> mod = again.register((String) io.get("id"), ic, "Phone");
        check("mod".equals(mod.get("role")) && "Tomy".equals(mod.get("name")), "an invite makes a person with its role");
        checks++;
        expectFail(() -> again.registerOptions("invite", inv, null, "x"), "an invite works once");
        checks++;
        return checks;
    }

    private static KeyPair kpOf() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        return g.generateKeyPair();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> obj(Object o) {
        return (Map<String, Object>) o;
    }

    private static byte[] clientData(String type, byte[] challenge, String origin) {
        return Json.write(Json.map("type", type, "challenge", Access.b64(challenge), "origin", origin, "crossOrigin", false))
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] authData(String rpId, int flags, long counter, byte[] credId) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.writeBytes(Access.sha256(rpId.getBytes(StandardCharsets.UTF_8)));
        b.write(flags);
        b.writeBytes(ByteBuffer.allocate(4).putInt((int) counter).array());
        if (credId != null) {
            b.writeBytes(new byte[16]);
            b.write(credId.length >> 8);
            b.write(credId.length & 0xff);
            b.writeBytes(credId);
            b.writeBytes(new byte[] {(byte) 0xa0});
        }
        return b.toByteArray();
    }

    private static Map<String, Object> createResponse(KeyPair kp, byte[] credId, byte[] challenge, String origin, String rpId, long counter) {
        return Json.map("id", Access.b64(credId), "publicKey", Access.b64(kp.getPublic().getEncoded()), "publicKeyAlgorithm", -7L,
                "authenticatorData", Access.b64(authData(rpId, 0x45, counter, credId)),
                "clientDataJSON", Access.b64(clientData("webauthn.create", challenge, origin)));
    }

    private static Map<String, Object> assertion(KeyPair kp, byte[] credId, byte[] challenge, String origin, String rpId, long counter) throws Exception {
        byte[] auth = authData(rpId, 0x05, counter, null);
        byte[] cd = clientData("webauthn.get", challenge, origin);
        Signature s = Signature.getInstance("SHA256withECDSA");
        s.initSign(kp.getPrivate());
        s.update(auth);
        s.update(Access.sha256(cd));
        return Json.map("id", Access.b64(credId), "authenticatorData", Access.b64(auth), "clientDataJSON", Access.b64(cd),
                "signature", Access.b64(s.sign()), "userHandle", null);
    }

    interface Throwing {
        void run() throws Exception;
    }

    private static void expectFail(Throwing r, String what) {
        try {
            r.run();
        } catch (Exception e) {
            return;
        }
        throw new AssertionError("expected a refusal: " + what);
    }

    private static void check(boolean ok, String what) {
        if (!ok) throw new AssertionError(what);
    }
}
