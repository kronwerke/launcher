package de.kronwerke.launcher.web;

import de.kronwerke.launcher.Json;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who may use the console: people with passkeys, machines with API keys, sessions in
 * between. Everything is kept in the console folder's access.json, secrets only as SHA-256
 * hashes. No passwords exist.
 * <p>
 * Passkeys are WebAuthn credentials checked here without a library: the browser hands over
 * the public key as SPKI (getPublicKey()), so no CBOR has to be read; the signature is checked
 * over the authenticator data and the hash of the client data, as the spec says.
 */
final class Access {
    /** What a person with a role may do; "access" is managing people and keys. */
    static final Map<String, Set<String>> ROLES = Map.of(
            "owner", Set.of("read", "players", "command", "power", "files", "pack", "config", "access"),
            "admin", Set.of("read", "players", "command", "power", "files", "pack", "config"),
            "mod", Set.of("read", "players"),
            "view", Set.of("read"));
    /** What an API key may be given. */
    static final List<String> SCOPES = List.of("read", "players", "command", "power", "files", "pack", "config");
    static final long SESSION_SECONDS = 12 * 3600;
    static final long CHALLENGE_SECONDS = 300;
    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Path file;
    private final Path setupFile;
    private final String rpId;
    private final String rpName;
    private final List<String> origins;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Object> data;
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    private String setupCode;

    /** Who is asking: a user through a session, or a key. */
    record Who(String id, String name, String kind, Set<String> scopes, String role, Map<String, Object> session) {
        boolean can(String scope) {
            return scopes.contains(scope);
        }
    }

    private record Challenge(byte[] bytes, String purpose, Map<String, Object> context, long expires) {}

    @SuppressWarnings("unchecked")
    Access(Path dir, String rpId, List<String> origins, String rpName) throws IOException {
        this.rpName = rpName;
        this.file = dir.resolve("access.json");
        this.setupFile = dir.resolve("setup.code");
        this.rpId = rpId;
        this.origins = origins;
        Files.createDirectories(dir);
        Map<String, Object> d = Files.exists(file) ? Json.object(Files.readString(file)) : new LinkedHashMap<>();
        for (String k : List.of("users", "invites", "keys", "sessions", "pairings")) {
            if (!(d.get(k) instanceof List)) d.put(k, new ArrayList<>());
        }
        this.data = d;
        if (users().isEmpty()) {
            setupCode = Files.exists(setupFile) ? Files.readString(setupFile).trim() : null;
            if (setupCode == null || setupCode.isEmpty()) {
                setupCode = code();
                Web.writePrivate(setupFile, setupCode + "\n");
            }
        } else {
            Files.deleteIfExists(setupFile);
        }
    }

    /** The one time code for the first passkey, or null once someone exists. */
    synchronized String setupCode() {
        return setupCode;
    }

    // ---- data ----

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> list(String key) {
        return (List<Map<String, Object>>) data.get(key);
    }

    synchronized List<Map<String, Object>> users() {
        return list("users");
    }

    private synchronized void save() throws IOException {
        Web.writePrivate(file, Json.write(data));
    }

    private Map<String, Object> user(String id) {
        for (Map<String, Object> u : users()) if (id.equals(u.get("id"))) return u;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> passkeys(Map<String, Object> user) {
        Object p = user.get("passkeys");
        if (!(p instanceof List)) {
            p = new ArrayList<>();
            user.put("passkeys", p);
        }
        return (List<Map<String, Object>>) p;
    }

    // ---- sessions and keys ----

    /** The session for a cookie value, or null. Expired ones are dropped. */
    synchronized Who session(String token) {
        if (token == null || token.isEmpty()) return null;
        String h = hash(token);
        long now = Instant.now().getEpochSecond();
        for (Iterator<Map<String, Object>> it = list("sessions").iterator(); it.hasNext(); ) {
            Map<String, Object> s = it.next();
            if (Json.num(s, "expires", 0) < now) {
                it.remove();
                continue;
            }
            if (!h.equals(s.get("hash"))) continue;
            Map<String, Object> u = user(Json.str(s, "user", ""));
            if (u == null) {
                it.remove();
                return null;
            }
            String role = Json.str(u, "role", "view");
            return new Who(Json.str(u, "id", ""), Json.str(u, "name", ""), "session", ROLES.getOrDefault(role, Set.of()), role, s);
        }
        return null;
    }

    /** A new session for a user: the cookie value, kept only as a hash. */
    synchronized String newSession(String userId, String ip, String agent) throws IOException {
        String token = token("ses_");
        list("sessions").add(Json.map("hash", hash(token), "user", userId, "csrf", token(""), "ip", ip,
                "agent", agent.length() > 160 ? agent.substring(0, 160) : agent, "created", Instant.now().getEpochSecond(),
                "expires", Instant.now().getEpochSecond() + SESSION_SECONDS));
        save();
        return token;
    }

    synchronized void endSession(Map<String, Object> s) throws IOException {
        list("sessions").remove(s);
        save();
    }

    /** The key for a bearer token, or null. */
    synchronized Who key(String token) throws IOException {
        // key_ since 0.3; kwc_ keys from 0.2 keep working
        if (token == null || !(token.startsWith("key_") || token.startsWith("kwc_"))) return null;
        String h = hash(token);
        long now = Instant.now().getEpochSecond();
        for (Map<String, Object> k : list("keys")) {
            if (!h.equals(k.get("hash"))) continue;
            long exp = Json.num(k, "expires", 0);
            if (exp > 0 && exp < now) return null;
            k.put("used", now);
            save();
            @SuppressWarnings("unchecked")
            List<Object> sc = (List<Object>) k.getOrDefault("scopes", List.of());
            Set<String> scopes = new java.util.HashSet<>();
            for (Object o : sc) scopes.add(String.valueOf(o));
            return new Who(Json.str(k, "id", ""), Json.str(k, "name", ""), "key", scopes, "key", null);
        }
        return null;
    }

    /** Makes an API key; the token is returned once and never again. */
    synchronized Map<String, Object> newKey(String name, List<String> scopes, long days, String by) throws IOException {
        if (name.isBlank() || name.length() > 60) throw new IllegalArgumentException("a name of up to 60 characters");
        for (String s : scopes) if (!SCOPES.contains(s)) throw new IllegalArgumentException("unknown scope " + s);
        if (scopes.isEmpty()) throw new IllegalArgumentException("at least one scope");
        String token = token("key_");
        String id = "k_" + token("").substring(0, 10);
        long now = Instant.now().getEpochSecond();
        list("keys").add(Json.map("id", id, "name", name.trim(), "hash", hash(token), "prefix", token.substring(0, 10),
                "scopes", new ArrayList<>(scopes), "created", now, "expires", days > 0 ? now + days * 86400 : 0, "used", 0, "by", by));
        save();
        return Json.map("id", id, "token", token);
    }

    synchronized boolean dropKey(String id) throws IOException {
        boolean r = list("keys").removeIf(k -> id.equals(k.get("id")));
        if (r) save();
        return r;
    }

    /** Makes an invite link token for a role, valid one day, used once. */
    synchronized String newInvite(String role, String name, String by) throws IOException {
        if (!ROLES.containsKey(role)) throw new IllegalArgumentException("unknown role " + role);
        String token = token("inv_");
        list("invites").add(Json.map("hash", hash(token), "id", "i_" + token("").substring(0, 10), "role", role, "name", name.trim(),
                "expires", Instant.now().getEpochSecond() + 86400, "by", by));
        save();
        return token;
    }

    /**
     * A code that lets the signed in user add a passkey on another device, for ten minutes and
     * once. Typed on the other device, so it is short.
     */
    synchronized Map<String, Object> newPairing(String userId) throws IOException {
        String code = code();
        long exp = Instant.now().getEpochSecond() + 600;
        list("pairings").removeIf(p -> userId.equals(p.get("user")) || Json.num(p, "expires", 0) < Instant.now().getEpochSecond());
        list("pairings").add(Json.map("hash", hash(normalizeCode(code)), "user", userId, "expires", exp));
        save();
        return Json.map("code", code, "expires", exp);
    }

    private Map<String, Object> pairing(String code) {
        String h = hash(normalizeCode(code));
        long now = Instant.now().getEpochSecond();
        for (Map<String, Object> p : list("pairings")) {
            if (h.equals(p.get("hash")) && Json.num(p, "expires", 0) >= now) return p;
        }
        return null;
    }

    synchronized boolean dropInvite(String id) throws IOException {
        boolean r = list("invites").removeIf(i -> id.equals(i.get("id")));
        if (r) save();
        return r;
    }

    synchronized boolean dropUser(String id, String by) throws IOException {
        Map<String, Object> u = user(id);
        if (u == null) return false;
        if (id.equals(by)) throw new IllegalArgumentException("you cannot remove yourself");
        if ("owner".equals(u.get("role")) && users().stream().filter(x -> "owner".equals(x.get("role"))).count() < 2) {
            throw new IllegalArgumentException("the last owner stays");
        }
        users().remove(u);
        list("sessions").removeIf(s -> id.equals(s.get("user")));
        save();
        return true;
    }

    synchronized void setRole(String id, String role, String by) throws IOException {
        if (!ROLES.containsKey(role)) throw new IllegalArgumentException("unknown role " + role);
        Map<String, Object> u = user(id);
        if (u == null) throw new IllegalArgumentException("no such user");
        if (id.equals(by) && !role.equals("owner")) throw new IllegalArgumentException("you cannot lower your own role");
        u.put("role", role);
        save();
    }

    /** Removes one passkey of a user; their last one only with the user. */
    synchronized boolean dropPasskey(String userId, String credId) throws IOException {
        Map<String, Object> u = user(userId);
        if (u == null) return false;
        List<Map<String, Object>> keys = passkeys(u);
        if (keys.size() < 2) throw new IllegalArgumentException("the last passkey stays; remove the person instead");
        boolean r = keys.removeIf(k -> credId.equals(k.get("id")));
        if (r) save();
        return r;
    }

    /** Everything for the access page, without hashes. */
    synchronized Map<String, Object> overview(String currentSession) {
        List<Object> us = new ArrayList<>();
        for (Map<String, Object> u : users()) {
            List<Object> pk = new ArrayList<>();
            for (Map<String, Object> k : passkeys(u)) {
                pk.add(Json.map("id", k.get("id"), "label", k.get("label"), "created", k.get("created"), "used", k.get("used")));
            }
            long sessions = list("sessions").stream().filter(s -> u.get("id").equals(s.get("user"))).count();
            us.add(Json.map("id", u.get("id"), "name", u.get("name"), "role", u.get("role"), "created", u.get("created"),
                    "passkeys", pk, "sessions", sessions));
        }
        List<Object> ks = new ArrayList<>();
        for (Map<String, Object> k : list("keys")) {
            ks.add(Json.map("id", k.get("id"), "name", k.get("name"), "prefix", k.get("prefix"), "scopes", k.get("scopes"),
                    "created", k.get("created"), "expires", k.get("expires"), "used", k.get("used")));
        }
        List<Object> is = new ArrayList<>();
        long now = Instant.now().getEpochSecond();
        for (Map<String, Object> i : list("invites")) {
            if (Json.num(i, "expires", 0) < now) continue;
            is.add(Json.map("id", i.get("id"), "role", i.get("role"), "name", i.get("name"), "expires", i.get("expires")));
        }
        return Json.map("users", us, "keys", ks, "invites", is, "scopes", SCOPES, "roles", List.of("owner", "admin", "mod", "view"));
    }

    // ---- passkeys ----

    /**
     * Options for navigator.credentials.create. purpose is "setup" (needs the setup code),
     * "invite" (needs an invite token) or "add" (a signed in user adds a passkey).
     */
    synchronized Map<String, Object> registerOptions(String purpose, String secret, Who who, String name) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        switch (purpose) {
            case "setup" -> {
                if (setupCode == null) throw new SecurityException("setup is done");
                if (!constantEquals(normalizeCode(secret), normalizeCode(setupCode))) throw new SecurityException("wrong setup code");
                ctx.put("role", "owner");
                ctx.put("name", name);
            }
            case "invite" -> {
                Map<String, Object> inv = invite(secret);
                if (inv == null) throw new SecurityException("the invite is unknown, used or expired");
                ctx.put("role", inv.get("role"));
                ctx.put("invite", inv.get("hash"));
                ctx.put("name", name.isBlank() ? Json.str(inv, "name", "") : name);
            }
            case "add" -> {
                if (who == null || !"session".equals(who.kind())) throw new SecurityException("sign in first");
                ctx.put("user", who.id());
            }
            case "pair" -> {
                Map<String, Object> p = pairing(secret);
                if (p == null) throw new SecurityException("the code is unknown or expired");
                Map<String, Object> u = user(Json.str(p, "user", ""));
                if (u == null) throw new SecurityException("the code is unknown or expired");
                ctx.put("user", u.get("id"));
                ctx.put("pairing", p.get("hash"));
            }
            default -> throw new IllegalArgumentException("unknown purpose");
        }
        String userName = ctx.containsKey("user") ? Json.str(user(String.valueOf(ctx.get("user"))), "name", "") : Json.str(ctx, "name", "").trim();
        if (userName.isEmpty() || userName.length() > 40) throw new IllegalArgumentException("a name of up to 40 characters");
        String userId = ctx.containsKey("user") ? (String) ctx.get("user") : "u_" + token("").substring(0, 12);
        ctx.put("newUser", userId);
        ctx.put("name", userName);
        byte[] ch = bytes(32);
        String id = token("");
        challenges.put(id, new Challenge(ch, "create", ctx, Instant.now().getEpochSecond() + CHALLENGE_SECONDS));
        List<Object> exclude = new ArrayList<>();
        Map<String, Object> existing = user(userId);
        if (existing != null) for (Map<String, Object> k : passkeys(existing)) exclude.add(Json.map("type", "public-key", "id", k.get("id")));
        Map<String, Object> options = Json.map(
                "challenge", b64(ch),
                "rp", Json.map("id", rpId, "name", rpName),
                "user", Json.map("id", b64(userId.getBytes(StandardCharsets.UTF_8)), "name", userName, "displayName", userName),
                "pubKeyCredParams", List.of(Json.map("type", "public-key", "alg", -7L), Json.map("type", "public-key", "alg", -8L),
                        Json.map("type", "public-key", "alg", -257L)),
                "authenticatorSelection", Json.map("residentKey", "required", "requireResidentKey", true, "userVerification", "required"),
                "attestation", "none",
                "excludeCredentials", exclude,
                "timeout", CHALLENGE_SECONDS * 1000);
        return Json.map("id", id, "options", options);
    }

    private Map<String, Object> invite(String token) {
        if (token == null || token.isEmpty()) return null;
        String h = hash(token);
        long now = Instant.now().getEpochSecond();
        for (Map<String, Object> i : list("invites")) {
            if (h.equals(i.get("hash")) && Json.num(i, "expires", 0) >= now) return i;
        }
        return null;
    }

    /**
     * Checks a new credential and stores it. Returns the user it belongs to, who is signed in
     * by the caller.
     */
    synchronized Map<String, Object> register(String challengeId, Map<String, Object> cred, String label) throws Exception {
        Challenge c = take(challengeId, "create");
        byte[] clientData = unb64(Json.str(cred, "clientDataJSON", ""));
        checkClientData(clientData, "webauthn.create", c.bytes());
        byte[] auth = unb64(Json.str(cred, "authenticatorData", ""));
        int flags = checkAuthData(auth);
        if ((flags & 0x40) == 0) throw new SecurityException("no credential in the authenticator data");
        byte[] credId = unb64(Json.str(cred, "id", ""));
        int len = ((auth[53] & 0xff) << 8) | (auth[54] & 0xff);
        if (len != credId.length || !Arrays.equals(Arrays.copyOfRange(auth, 55, 55 + len), credId)) {
            throw new SecurityException("the credential id does not match");
        }
        long alg = Json.num(cred, "publicKeyAlgorithm", 0);
        byte[] spki = unb64(Json.str(cred, "publicKey", ""));
        publicKey(spki, alg);
        for (Map<String, Object> u : users()) {
            for (Map<String, Object> k : passkeys(u)) {
                if (b64(credId).equals(k.get("id"))) throw new SecurityException("this passkey is already registered");
            }
        }
        Map<String, Object> ctx = c.context();
        if (ctx.containsKey("pairing")) {
            Object h = ctx.get("pairing");
            if (!list("pairings").removeIf(p -> h.equals(p.get("hash")))) throw new SecurityException("the code was used meanwhile");
        }
        String userId = Json.str(ctx, "newUser", "");
        Map<String, Object> u = user(userId);
        long now = Instant.now().getEpochSecond();
        if (u == null) {
            if (ctx.containsKey("invite")) {
                Object h = ctx.get("invite");
                if (!list("invites").removeIf(i -> h.equals(i.get("hash")))) throw new SecurityException("the invite was used meanwhile");
            } else if (setupCode == null || !"owner".equals(ctx.get("role"))) {
                throw new SecurityException("setup is done");
            }
            u = Json.map("id", userId, "name", ctx.get("name"), "role", ctx.get("role"), "created", now, "passkeys", new ArrayList<>());
            users().add(u);
        }
        passkeys(u).add(Json.map("id", b64(credId), "key", b64(spki), "alg", alg, "count", counter(auth),
                "label", label == null || label.isBlank() ? "Passkey" : label.trim(), "created", now, "used", now));
        save();
        if (setupCode != null) {
            setupCode = null;
            Files.deleteIfExists(setupFile);
        }
        return u;
    }

    /** Options for navigator.credentials.get: any passkey of this site (discoverable). */
    Map<String, Object> loginOptions() {
        byte[] ch = bytes(32);
        String id = token("");
        challenges.put(id, new Challenge(ch, "get", Map.of(), Instant.now().getEpochSecond() + CHALLENGE_SECONDS));
        return Json.map("id", id, "options", Json.map("challenge", b64(ch), "rpId", rpId, "userVerification", "required",
                "timeout", CHALLENGE_SECONDS * 1000, "allowCredentials", List.of()));
    }

    /** Checks an assertion; the user it signs in, or a SecurityException. */
    synchronized Map<String, Object> login(String challengeId, Map<String, Object> cred) throws Exception {
        Challenge c = take(challengeId, "get");
        byte[] clientData = unb64(Json.str(cred, "clientDataJSON", ""));
        checkClientData(clientData, "webauthn.get", c.bytes());
        byte[] auth = unb64(Json.str(cred, "authenticatorData", ""));
        checkAuthData(auth);
        String credId = b64(unb64(Json.str(cred, "id", "")));
        for (Map<String, Object> u : users()) {
            for (Map<String, Object> k : passkeys(u)) {
                if (!credId.equals(k.get("id"))) continue;
                PublicKey key = publicKey(unb64(Json.str(k, "key", "")), Json.num(k, "alg", -7));
                Signature sig = Signature.getInstance(switch ((int) Json.num(k, "alg", -7)) {
                    case -7 -> "SHA256withECDSA";
                    case -8 -> "Ed25519";
                    default -> "SHA256withRSA";
                });
                sig.initVerify(key);
                sig.update(auth);
                sig.update(sha256(clientData));
                if (!sig.verify(unb64(Json.str(cred, "signature", "")))) throw new SecurityException("the signature is wrong");
                long count = counter(auth), before = Json.num(k, "count", 0);
                if (count != 0 && count <= before) throw new SecurityException("this passkey may be cloned (counter went back)");
                k.put("count", count);
                k.put("used", Instant.now().getEpochSecond());
                save();
                return u;
            }
        }
        throw new SecurityException("unknown passkey");
    }

    private Challenge take(String id, String purpose) {
        Challenge c = id == null ? null : challenges.remove(id);
        long now = Instant.now().getEpochSecond();
        challenges.values().removeIf(x -> x.expires() < now);
        if (c == null || c.expires() < now || !c.purpose().equals(purpose)) throw new SecurityException("the request expired, try again");
        return c;
    }

    private void checkClientData(byte[] raw, String type, byte[] challenge) {
        Map<String, Object> cd = Json.object(new String(raw, StandardCharsets.UTF_8));
        if (!type.equals(cd.get("type"))) throw new SecurityException("wrong type");
        if (!b64(challenge).equals(b64(unb64(Json.str(cd, "challenge", ""))))) throw new SecurityException("wrong challenge");
        if (!origins.contains(Json.str(cd, "origin", ""))) throw new SecurityException("wrong origin " + cd.get("origin"));
        if (Json.bool(cd, "crossOrigin", false)) throw new SecurityException("cross origin");
    }

    /** rpIdHash, user present and verified. Returns the flags. */
    private int checkAuthData(byte[] auth) throws Exception {
        if (auth.length < 37) throw new SecurityException("authenticator data too short");
        if (!Arrays.equals(Arrays.copyOfRange(auth, 0, 32), sha256(rpId.getBytes(StandardCharsets.UTF_8)))) {
            throw new SecurityException("the passkey is for another site");
        }
        int flags = auth[32] & 0xff;
        if ((flags & 0x01) == 0 || (flags & 0x04) == 0) throw new SecurityException("the passkey did not verify the user");
        return flags;
    }

    private static long counter(byte[] auth) {
        return ByteBuffer.wrap(auth, 33, 4).getInt() & 0xffffffffL;
    }

    static PublicKey publicKey(byte[] spki, long alg) throws Exception {
        String kf = switch ((int) alg) {
            case -7 -> "EC";
            case -8 -> "Ed25519";
            case -257 -> "RSA";
            default -> throw new SecurityException("unsupported algorithm " + alg);
        };
        return KeyFactory.getInstance(kf).generatePublic(new X509EncodedKeySpec(spki));
    }

    // ---- small things ----

    String token(String prefix) {
        byte[] b = bytes(30);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private byte[] bytes(int n) {
        byte[] b = new byte[n];
        random.nextBytes(b);
        return b;
    }

    /** XXXX-XXXX from letters and digits that cannot be confused. */
    private String code() {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i == 4) b.append('-');
            b.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    static String normalizeCode(String c) {
        // codes from 0.2 started with KW-; the letters are only decoration
        String n = c == null ? "" : c.toUpperCase().replaceAll("[^A-Z0-9]", "");
        return n.length() == 10 && n.startsWith("KW") ? n.substring(2) : n;
    }

    static boolean constantEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    static String hash(String s) {
        return HexFormat.of().formatHex(sha256(s.getBytes(StandardCharsets.UTF_8)));
    }

    static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static byte[] unb64(String s) {
        String t = s.replace('+', '-').replace('/', '_').replace("=", "");
        return Base64.getUrlDecoder().decode(t);
    }
}
