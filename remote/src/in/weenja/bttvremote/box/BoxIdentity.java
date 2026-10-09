package in.weenja.bttvremote.box;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * The phone's key and self-signed certificate, made once. The box remembers the certificate at pairing,
 * so it must never change. Also keeps the box certificate hash from the pairing (the pin).
 */
final class BoxIdentity {

    private static final String KEY = "client.key";
    private static final String CERT = "client.crt";
    private static final String PIN = "box.sha256";

    final PrivateKey key;
    final X509Certificate cert;
    private final File dir;

    private BoxIdentity(File dir, PrivateKey key, X509Certificate cert) {
        this.dir = dir;
        this.key = key;
        this.cert = cert;
    }

    static synchronized BoxIdentity load(File dir) throws IOException, GeneralSecurityException {
        File k = new File(dir, KEY), c = new File(dir, CERT);
        if (!k.exists() || !c.exists()) create(dir, k, c);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(k.toPath())));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(Files.readAllBytes(c.toPath())));
        return new BoxIdentity(dir, key, cert);
    }

    /** The hash of the box certificate from the last pairing, or null. */
    String pin() {
        String[] p = pinFile();
        return p != null ? p[0] : null;
    }

    /** The protocol version found at pairing: 1 or 2. */
    int version() {
        String[] p = pinFile();
        return p != null && p.length > 1 && p[1].equals("1") ? 1 : 2;
    }

    void savePin(X509Certificate box, int version) throws IOException, GeneralSecurityException {
        write(new File(dir, PIN), (hash(box) + "\n" + version).getBytes(StandardCharsets.US_ASCII));
    }

    /** Line 1: certificate hash, line 2: protocol version. */
    private String[] pinFile() {
        try {
            return new String(Files.readAllBytes(new File(dir, PIN).toPath()), StandardCharsets.US_ASCII).trim().split("\n");
        } catch (IOException e) {
            return null;
        }
    }

    static boolean paired(File dir) {
        return new File(dir, PIN).exists();
    }

    static String hash(X509Certificate c) throws GeneralSecurityException {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(c.getEncoded());
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format(Locale.US, "%02x", b));
        return s.toString();
    }

    /**
     * TLS to the box with this certificate. With a pin, a box with another certificate is refused.
     * Without one (pairing), any box certificate is taken: the pairing code then proves it is the right box.
     */
    SSLSocket connect(String host, int port, String pin) throws IOException, GeneralSecurityException {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{keyManager()}, new TrustManager[]{new PinTrust(pin)}, null);
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(host, port), 3000);
            raw.setTcpNoDelay(true);
            SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(raw, host, port, true);
            s.setSoTimeout(5000);
            s.startHandshake();
            return s;
        } catch (IOException e) {
            raw.close();
            throw e;
        }
    }

    private X509KeyManager keyManager() {
        return new X509KeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{"phone"};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return "phone";
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return new X509Certificate[]{cert};
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return key;
            }
        };
    }

    /** The box certificate is self-signed: no CA can check it. The pin from the pairing does. */
    private static final class PinTrust implements X509TrustManager {
        private final String pin;

        PinTrust(String pin) {
            this.pin = pin;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("client mode only");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("no box certificate");
            if (pin == null) return;
            try {
                if (!pin.equals(hash(chain[0]))) throw new CertificateException("the box certificate changed: pair again");
            } catch (GeneralSecurityException e) {
                throw new CertificateException(e);
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ---- a self-signed X.509 certificate, DER by hand (Android has no public builder) ----

    private static final byte[] OID_SHA256_RSA = {0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B};
    private static final byte[] OID_CN = {0x06, 0x03, 0x55, 0x04, 0x03};

    private static void create(File dir, File keyFile, File certFile) throws IOException, GeneralSecurityException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("cannot create " + dir);
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();

        byte[] name = tlv(0x30, tlv(0x31, tlv(0x30, cat(OID_CN, tlv(0x0C, "atvremote".getBytes(StandardCharsets.UTF_8))))));
        byte[] alg = tlv(0x30, cat(OID_SHA256_RSA, new byte[]{0x05, 0x00}));
        long now = System.currentTimeMillis();
        byte[] validity = tlv(0x30, cat(time(now - 86_400_000L), time(now + 20 * 365 * 86_400_000L)));
        byte[] serial = tlv(0x02, new BigInteger(62, new SecureRandom()).add(BigInteger.ONE).toByteArray());
        byte[] version = tlv(0xA0, tlv(0x02, new byte[]{2}));
        byte[] tbs = tlv(0x30, cat(version, serial, alg, name, validity, name, kp.getPublic().getEncoded()));

        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] cert = tlv(0x30, cat(tbs, alg, tlv(0x03, cat(new byte[]{0}, sig))));

        write(keyFile, kp.getPrivate().getEncoded());
        write(certFile, cert);
    }

    /** UTCTime: fine until 2049. */
    private static byte[] time(long ms) {
        SimpleDateFormat f = new SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return tlv(0x17, f.format(new Date(ms)).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = value.length;
        if (n < 0x80) {
            o.write(n);
        } else {
            int bytes = n > 0xFFFF ? 3 : n > 0xFF ? 2 : 1;
            o.write(0x80 | bytes);
            for (int i = bytes - 1; i >= 0; i--) o.write(n >> (8 * i));
        }
        o.write(value, 0, n);
        return o.toByteArray();
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] p : parts) o.write(p, 0, p.length);
        return o.toByteArray();
    }

    /** Write, then rename: a crash never leaves half a key. */
    private static void write(File f, byte[] data) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) {
            o.write(data);
            o.getFD().sync();
        }
        if (!tmp.renameTo(f)) throw new IOException("cannot write " + f);
    }
}
