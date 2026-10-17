package de.mca.pulsascribe.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/** Verschlüsselte Sicherung: Rundreise, falsches Passwort, Manipulation, Abschneiden, Dateinamen. */
public class BackupTest {
    private static final char[] PW = "geheim-123".toCharArray();

    private static byte[] enc(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream os = Backup.encrypt(bos, PW, Backup.MIN_ITERATIONS)) {
            os.write(data);
        }
        return bos.toByteArray();
    }

    private static byte[] dec(byte[] file, char[] pw) throws IOException {
        try (InputStream in = Backup.decrypt(new ByteArrayInputStream(file), pw)) {
            return in.readAllBytes();
        }
    }

    private static byte[] random(int n) {
        byte[] b = new byte[n];
        new Random(42).nextBytes(b);
        return b;
    }

    @Test
    public void roundTripSmallAndLarge() throws IOException {
        for (int n : new int[]{0, 10, Backup.CHUNK, Backup.CHUNK * 2 + 123}) {
            byte[] data = random(n);
            assertTrue(Arrays.equals(data, dec(enc(data), PW)));
        }
    }

    @Test
    public void wrongPasswordIsDetected() throws IOException {
        byte[] file = enc(random(1000));
        try {
            dec(file, "falsches-pw".toCharArray());
            throw new AssertionError("falsches Passwort angenommen");
        } catch (Backup.BadPasswordException expected) { }
    }

    @Test
    public void tamperingIsDetected() throws IOException {
        byte[] file = enc(random(Backup.CHUNK + 500));
        byte[] bad = file.clone();
        bad[bad.length - 30] ^= 1; // im letzten Block
        try {
            dec(bad, PW);
            throw new AssertionError("Manipulation angenommen");
        } catch (IOException expected) { }
    }

    @Test
    public void truncationIsDetected() throws IOException {
        byte[] file = enc(random(Backup.CHUNK * 2 + 10));
        // Letzten Block weglassen: Header 33 Byte, dann zwei volle Blöcke à (4 + CHUNK + 16)
        int cut = 4 + 1 + 4 + 16 + 8 + 2 * (4 + Backup.CHUNK + 16);
        try {
            dec(Arrays.copyOf(file, cut), PW);
            throw new AssertionError("abgeschnittene Datei angenommen");
        } catch (Backup.FormatException expected) { }
        // Mitten im Block abschneiden
        try {
            dec(Arrays.copyOf(file, file.length - 5), PW);
            throw new AssertionError("abgeschnittene Datei angenommen");
        } catch (IOException expected) { }
    }

    @Test
    public void trailingDataIsRejected() throws IOException {
        byte[] file = enc(random(100));
        byte[] more = Arrays.copyOf(file, file.length + 3);
        try {
            dec(more, PW);
            throw new AssertionError("Zusatzdaten angenommen");
        } catch (Backup.FormatException expected) { }
    }

    @Test
    public void foreignFilesAndBadParametersAreRejected() throws IOException {
        try {
            dec("PK\u0003\u0004 kein backup".getBytes(), PW);
            throw new AssertionError();
        } catch (Backup.FormatException expected) { }
        byte[] file = enc(random(10));
        byte[] it = file.clone();
        it[5] = 0x7F; // Iterationen riesig
        try {
            dec(it, PW);
            throw new AssertionError();
        } catch (Backup.FormatException expected) { }
        try {
            Backup.encrypt(new ByteArrayOutputStream(), "kurz".toCharArray());
            throw new AssertionError();
        } catch (IOException expected) { }
    }

    @Test
    public void entryNames() {
        assertTrue(Backup.validEntry("folders.json"));
        assertTrue(Backup.validEntry("t/abcdefgh12.json"));
        assertTrue(Backup.validEntry("t/abcdefgh12.aac"));
        assertTrue(Backup.validEntry("t/abcdefgh12.jpg"));
        assertFalse(Backup.validEntry("../t/abcdefgh12.json"));
        assertFalse(Backup.validEntry("t/abcdefgh12.apk"));
        assertFalse(Backup.validEntry("t/ABCDEFGH12.json"));
        assertFalse(Backup.validEntry("shared_prefs/x.xml"));
        assertFalse(Backup.validEntry("/t/abcdefgh12.json"));
        assertEquals(1024L * 1024 * 1024, Backup.maxSize("t/x.aac"));
    }

    @Test
    public void readLimitedStopsAtMax() throws IOException {
        try {
            Backup.readLimited(new ByteArrayInputStream(new byte[100]), 50);
            throw new AssertionError();
        } catch (IOException expected) { }
        assertEquals(100, Backup.readLimited(new ByteArrayInputStream(new byte[100]), 100).length);
    }
}
