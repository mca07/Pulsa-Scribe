package de.mca.pulsascribe.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verschlüsselte Datensicherung: PBKDF2-SHA256 → AES-256-GCM, in Blöcken zu 1 MB, damit auch große
 * Sicherungen (mit Ton) ohne viel Speicher geprüft werden. Jeder Block ist einzeln authentifiziert;
 * Blocknummer und „letzter Block“ stecken in den Zusatzdaten (AAD), so fallen Vertauschen, Weglassen
 * und Abschneiden auf.
 *
 * Aufbau: "PSCB" | Version (1 Byte) | Iterationen (int) | Salt (16) | Nonce-Basis (8) | [ab Version 2: Länge (short) |
 * Passwort-Hinweis UTF-8, unverschlüsselt, aber über die AAD jedes Blocks gegen Austausch geschützt] | Blöcke …
 * Block: Länge des Chiffrats (int) | Chiffrat mit Tag.
 */
public final class Backup {
    private Backup() {}

    public static final byte[] MAGIC = {'P', 'S', 'C', 'B'};
    public static final int VERSION = 2;
    /** Passwort-Hinweis: lesbar ohne Passwort, deshalb kurz und einzeilig. */
    public static final int MAX_HINT = 100;
    public static final int ITERATIONS = 310_000;
    public static final int MIN_ITERATIONS = 100_000, MAX_ITERATIONS = 2_000_000;
    public static final int CHUNK = 1024 * 1024;
    public static final int MIN_PASSWORD = 8;
    private static final int TAG_BITS = 128;

    public static final class BadPasswordException extends IOException {
        public BadPasswordException() { super("Passwort falsch oder Datei verändert"); }
    }

    public static final class FormatException extends IOException {
        public FormatException(String m) { super(m); }
    }

    static SecretKey derive(char[] password, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, 256);
        try {
            byte[] k = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            return new SecretKeySpec(k, "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] nonce(byte[] base, long index) {
        return ByteBuffer.allocate(12).put(base).putInt((int) index).array();
    }

    private static byte[] aad(long index, boolean last, byte[] hint) {
        int extra = hint == null ? 0 : 2 + hint.length; // Version 1: ohne Hinweis
        ByteBuffer b = ByteBuffer.allocate(13 + extra).put(MAGIC).putLong(index).put((byte) (last ? 1 : 0));
        if (hint != null) b.putShort((short) hint.length).put(hint);
        return b.array();
    }

    /** Hinweis bereinigen: eine Zeile, höchstens MAX_HINT Zeichen. */
    public static String cleanHint(String h) {
        String c = Text.line(h, MAX_HINT);
        while (c.getBytes(StandardCharsets.UTF_8).length > 400) c = c.substring(0, c.length() - 1);
        return c;
    }

    /** Verrät der Hinweis das Passwort (enthält es, auch ohne Groß-/Kleinschreibung)? */
    public static boolean hintRevealsPassword(String hint, char[] password) {
        if (hint == null || hint.isEmpty() || password == null || password.length == 0) return false;
        String h = hint.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        String p = new String(password).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", "");
        return !p.isEmpty() && (h.contains(p) || new StringBuilder(h).reverse().toString().contains(p));
    }

    // ------------------------------------------------------------------ Schreiben

    /** Öffnet einen verschlüsselnden Strom; close() schreibt den letzten Block. */
    public static OutputStream encrypt(OutputStream out, char[] password) throws IOException {
        return encrypt(out, password, ITERATIONS, "");
    }

    public static OutputStream encrypt(OutputStream out, char[] password, String hint) throws IOException {
        return encrypt(out, password, ITERATIONS, hint);
    }

    static OutputStream encrypt(OutputStream out, char[] password, int iterations) throws IOException {
        return encrypt(out, password, iterations, "");
    }

    static OutputStream encrypt(OutputStream out, char[] password, int iterations, String hint) throws IOException {
        if (password == null || password.length < MIN_PASSWORD) throw new IOException("Passwort zu kurz");
        // hint == null nur für Tests: altes Format Version 1 ohne Hinweis
        String h = hint == null ? "" : cleanHint(hint);
        if (hintRevealsPassword(h, password)) throw new IOException("Hinweis enthält das Passwort");
        byte[] hb = hint == null ? null : h.getBytes(StandardCharsets.UTF_8);
        SecureRandom r = new SecureRandom();
        byte[] salt = new byte[16], base = new byte[8];
        r.nextBytes(salt);
        r.nextBytes(base);
        SecretKey key;
        try {
            key = derive(password, salt, iterations);
        } catch (GeneralSecurityException e) {
            throw new IOException("Schlüssel", e);
        }
        DataOutputStream d = new DataOutputStream(out);
        d.write(MAGIC);
        d.writeByte(hb == null ? 1 : VERSION);
        d.writeInt(iterations);
        d.write(salt);
        d.write(base);
        if (hb != null) {
            d.writeShort(hb.length);
            d.write(hb);
        }
        return new EncryptStream(d, key, base, hb);
    }

    private static final class EncryptStream extends OutputStream {
        private final DataOutputStream out;
        private final SecretKey key;
        private final byte[] base, hint;
        private final byte[] buf = new byte[CHUNK];
        private int len;
        private long index;
        private boolean closed;

        EncryptStream(DataOutputStream out, SecretKey key, byte[] base, byte[] hint) {
            this.out = out;
            this.key = key;
            this.base = base;
            this.hint = hint;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int n) throws IOException {
            if (closed) throw new IOException("geschlossen");
            while (n > 0) {
                if (len == CHUNK) flushChunk(false);
                int k = Math.min(n, CHUNK - len);
                System.arraycopy(b, off, buf, len, k);
                len += k;
                off += k;
                n -= k;
            }
        }

        private void flushChunk(boolean last) throws IOException {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(base, index)));
                c.updateAAD(aad(index, last, hint));
                byte[] ct = c.doFinal(buf, 0, len);
                out.writeInt(ct.length);
                out.write(ct);
            } catch (GeneralSecurityException e) {
                throw new IOException("Verschlüsselung", e);
            }
            index++;
            len = 0;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            flushChunk(true);
            closed = true;
            out.flush();
            out.close();
        }
    }

    // ------------------------------------------------------------------ Lesen

    /** Öffnet einen entschlüsselnden Strom. Fehler beim ersten Block bedeutet meist: falsches Passwort. */
    public static InputStream decrypt(InputStream in, char[] password) throws IOException {
        DataInputStream d = new DataInputStream(in);
        Header h = header(d);
        SecretKey key;
        try {
            key = derive(password, h.salt, h.iterations);
        } catch (GeneralSecurityException e) {
            throw new IOException("Schlüssel", e);
        }
        return new DecryptStream(d, key, h.base, h.hint);
    }

    private static final class Header {
        int iterations;
        byte[] salt = new byte[16], base = new byte[8];
        byte[] hint; // null bei Version 1
    }

    private static Header header(DataInputStream d) throws IOException {
        byte[] magic = new byte[4];
        Header h = new Header();
        try {
            d.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) throw new FormatException("Keine Pulsa-Scribe-Sicherung");
            int version = d.readUnsignedByte();
            if (version != 1 && version != 2) throw new FormatException("Unbekannte Version");
            h.iterations = d.readInt();
            if (h.iterations < MIN_ITERATIONS || h.iterations > MAX_ITERATIONS) throw new FormatException("Ungültige Parameter");
            d.readFully(h.salt);
            d.readFully(h.base);
            if (version >= 2) {
                int n = d.readUnsignedShort();
                if (n > 400) throw new FormatException("Ungültiger Hinweis");
                h.hint = new byte[n];
                d.readFully(h.hint);
            }
        } catch (EOFException e) {
            throw new FormatException("Keine Pulsa-Scribe-Sicherung");
        }
        return h;
    }

    /** Passwort-Hinweis aus dem Dateikopf, ohne Passwort lesbar ("" wenn keiner). Nur zur Anzeige. */
    public static String readHint(InputStream in) throws IOException {
        Header h = header(new DataInputStream(in));
        return h.hint == null ? "" : cleanHint(new String(h.hint, StandardCharsets.UTF_8));
    }

    private static final class DecryptStream extends FilterInputStream {
        private final DataInputStream in;
        private final SecretKey key;
        private final byte[] base, hint;
        private byte[] plain = new byte[0];
        private int pos;
        private long index;
        private boolean done;

        DecryptStream(DataInputStream in, SecretKey key, byte[] base, byte[] hint) {
            super(in);
            this.in = in;
            this.key = key;
            this.base = base;
            this.hint = hint;
        }

        private boolean next() throws IOException {
            if (done) return false;
            int n;
            try {
                n = in.readInt();
            } catch (EOFException e) {
                throw new FormatException("Sicherung unvollständig");
            }
            if (n < 16 || n > CHUNK + 16) throw new FormatException("Sicherung beschädigt");
            byte[] ct = new byte[n];
            try {
                in.readFully(ct);
            } catch (EOFException e) {
                throw new FormatException("Sicherung unvollständig");
            }
            // Wer der letzte Block ist, steht nur in den AAD: erst als „nicht letzter“, dann als „letzter“ prüfen
            byte[] p = open(ct, false);
            boolean last = false;
            if (p == null) {
                p = open(ct, true);
                last = true;
            }
            if (p == null) {
                if (index == 0) throw new BadPasswordException();
                throw new FormatException("Sicherung beschädigt oder verändert");
            }
            index++;
            if (last) {
                done = true;
                if (in.read() >= 0) throw new FormatException("Daten nach dem Ende");
            }
            plain = p;
            pos = 0;
            return p.length > 0 || !done;
        }

        private byte[] open(byte[] ct, boolean last) {
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce(base, index)));
                c.updateAAD(aad(index, last, hint));
                return c.doFinal(ct);
            } catch (GeneralSecurityException e) {
                return null;
            }
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int r = read(one, 0, 1);
            return r < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            while (pos >= plain.length) {
                if (done || !next()) {
                    if (pos >= plain.length) return -1;
                }
            }
            int k = Math.min(len, plain.length - pos);
            System.arraycopy(plain, pos, b, off, k);
            pos += k;
            return k;
        }

        @Override
        public int available() {
            return plain.length - pos;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] tmp = new byte[8192];
            long left = n;
            while (left > 0) {
                int r = read(tmp, 0, (int) Math.min(tmp.length, left));
                if (r < 0) break;
                left -= r;
            }
            return n - left;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }

    // ------------------------------------------------------------------ Inhalt prüfen

    private static final Pattern ENTRY = Pattern.compile("folders\\.json|t/[a-z0-9]{8,32}\\.(json|aac|jpg)");
    public static final int MAX_ENTRIES = 30_000;

    /** Erlaubte Dateinamen in der Sicherung (keine Pfade, keine fremden Dateien). */
    public static boolean validEntry(String name) {
        return name != null && ENTRY.matcher(name).matches();
    }

    /** Größenobergrenze je Dateiart. */
    public static long maxSize(String name) {
        if (name.endsWith(".aac")) return 1024L * 1024 * 1024;
        if (name.endsWith(".jpg")) return 10L * 1024 * 1024;
        return 16L * 1024 * 1024;
    }

    /** Liest einen Eintrag vollständig, aber höchstens max Bytes. */
    public static byte[] readLimited(InputStream in, long max) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (bos.size() + n > max) throw new FormatException("Eintrag zu groß");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }
}
