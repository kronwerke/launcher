package de.kronwerke.launcher.web;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/**
 * TLS for the console. The certificate is either one we make ourselves (Cloudflare's "Full"
 * mode accepts it) or one given as PEM files, like a Cloudflare origin certificate. The client
 * certificate check lets only Cloudflare in: its origin pull certificate is signed by a CA
 * bundled with the launcher.
 */
final class Tls {
    private Tls() {
    }

    /**
     * The SSL context for the console. certFile and keyFile are PEM; when both are empty a self
     * signed certificate is made once and kept in dir.
     */
    static SSLContext context(Path dir, String host, String certFile, String keyFile, String clientCa) throws Exception {
        KeyStore ks;
        char[] pass;
        if (!certFile.isEmpty() && !keyFile.isEmpty()) {
            pass = random().toCharArray();
            ks = KeyStore.getInstance("PKCS12");
            ks.load(null, null);
            List<Certificate> chain = certificates(Files.readString(Path.of(certFile)));
            if (chain.isEmpty()) throw new IOException("no certificate in " + certFile);
            ks.setKeyEntry("console", privateKey(Files.readString(Path.of(keyFile))), pass, chain.toArray(new Certificate[0]));
        } else {
            Path store = dir.resolve("tls.p12"), passFile = dir.resolve("tls.pass");
            if (!Files.exists(store) || !Files.exists(passFile)) selfSigned(dir, host);
            pass = Files.readString(passFile).trim().toCharArray();
            ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(store)) {
                ks.load(in, pass);
            }
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pass);

        TrustManagerFactory tmf = null;
        if (!clientCa.isEmpty() && !clientCa.equals("off")) {
            String pem;
            if (clientCa.equals("cloudflare")) {
                try (InputStream in = Tls.class.getResourceAsStream("origin-pull-ca.pem")) {
                    if (in == null) throw new IOException("the bundled origin pull CA is missing");
                    pem = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
                }
            } else {
                pem = Files.readString(Path.of(clientCa));
            }
            KeyStore ts = KeyStore.getInstance("PKCS12");
            ts.load(null, null);
            int i = 0;
            for (Certificate c : certificates(pem)) ts.setCertificateEntry("ca" + i++, c);
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ts);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), new SecureRandom());
        return ctx;
    }

    /** A self signed certificate made with the JDK's keytool, valid for ten years. */
    static void selfSigned(Path dir, String host) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        if (!Files.isExecutable(keytool)) {
            throw new IOException("keytool is missing in " + keytool.getParent() + "; give console.cert and console.key instead");
        }
        String pass = random();
        Path store = dir.resolve("tls.p12");
        Files.deleteIfExists(store);
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "console", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=" + host, "-ext", "SAN=dns:" + host, "-validity", "3650",
                "-keystore", store.toString(), "-storetype", "PKCS12", "-storepass", pass, "-keypass", pass)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) throw new IOException("keytool failed: " + out.trim());
        Web.writePrivate(dir.resolve("tls.pass"), pass + "\n");
    }

    static List<Certificate> certificates(String pem) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<Certificate> out = new ArrayList<>();
        Matcher m = Pattern.compile("-----BEGIN CERTIFICATE-----(.+?)-----END CERTIFICATE-----", Pattern.DOTALL).matcher(pem);
        while (m.find()) {
            byte[] der = Base64.getMimeDecoder().decode(m.group(1));
            out.add(cf.generateCertificate(new ByteArrayInputStream(der)));
        }
        return out;
    }

    /** A PKCS#8 private key ("BEGIN PRIVATE KEY"), EC or RSA. */
    static PrivateKey privateKey(String pem) throws Exception {
        Matcher m = Pattern.compile("-----BEGIN PRIVATE KEY-----(.+?)-----END PRIVATE KEY-----", Pattern.DOTALL).matcher(pem);
        if (!m.find()) throw new IOException("the key must be PKCS#8 (BEGIN PRIVATE KEY)");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(m.group(1)));
        for (String alg : new String[] {"EC", "RSA", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(alg).generatePrivate(spec);
            } catch (Exception ignored) {
                // try the next
            }
        }
        throw new IOException("unknown key type");
    }

    static String random() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        return HexFormat.of().formatHex(b);
    }
}
