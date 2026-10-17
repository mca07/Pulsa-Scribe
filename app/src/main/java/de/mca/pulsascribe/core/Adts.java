package de.mca.pulsascribe.core;

import java.io.IOException;
import java.io.InputStream;

/**
 * AAC im ADTS-Format: jeder Rahmen trägt einen 7-Byte-Kopf. Damit lässt sich die Tonspur
 * ohne Container speichern, abspielen und an Rahmengrenzen in Teile schneiden.
 */
public final class Adts {
    private Adts() {}

    public static final int HEADER = 7;
    public static final int SAMPLES_PER_FRAME = 1024;
    public static final int MAX_FRAME = 8191; // 13-Bit-Längenfeld

    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
            16000, 12000, 11025, 8000, 7350};

    /** Index der Abtastrate im ADTS-Kopf, -1 wenn nicht darstellbar. */
    public static int rateIndex(int rate) {
        for (int i = 0; i < RATES.length; i++) if (RATES[i] == rate) return i;
        return -1;
    }

    public static int rate(int index) {
        return index >= 0 && index < RATES.length ? RATES[index] : -1;
    }

    /** Raten, die der AAC-Encoder von Android sicher annimmt. */
    public static boolean encoderRate(int rate) {
        switch (rate) {
            case 8000: case 11025: case 12000: case 16000: case 22050:
            case 24000: case 32000: case 44100: case 48000: return true;
            default: return false;
        }
    }

    /** Schreibt einen ADTS-Kopf für AAC-LC (profile 2) in out[0..6]. payload = Länge ohne Kopf. */
    public static void header(byte[] out, int rateIndex, int channels, int payload) {
        if (rateIndex < 0 || rateIndex > 12) throw new IllegalArgumentException("Rate");
        if (channels < 1 || channels > 7) throw new IllegalArgumentException("Kanäle");
        int len = payload + HEADER;
        if (payload < 0 || len > MAX_FRAME) throw new IllegalArgumentException("Rahmen zu groß");
        int profile = 2; // AAC LC → im Kopf als profile-1 = 1
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xF1; // MPEG-4, Layer 0, ohne CRC
        out[2] = (byte) (((profile - 1) << 6) | (rateIndex << 2) | (channels >> 2));
        out[3] = (byte) (((channels & 3) << 6) | (len >> 11));
        out[4] = (byte) ((len >> 3) & 0xFF);
        out[5] = (byte) (((len & 7) << 5) | 0x1F);
        out[6] = (byte) 0xFC;
    }

    /** Liest Kopfdaten: {rateIndex, channels, frameLength} oder null, wenn kein gültiger Kopf. */
    public static int[] parseHeader(byte[] b, int off) {
        if (off < 0 || off + HEADER > b.length) return null;
        if ((b[off] & 0xFF) != 0xFF || (b[off + 1] & 0xF6) != 0xF0) return null;
        int rateIndex = (b[off + 2] >> 2) & 0x0F;
        if (rateIndex > 12) return null;
        int channels = ((b[off + 2] & 1) << 2) | ((b[off + 3] >> 6) & 3);
        int len = ((b[off + 3] & 3) << 11) | ((b[off + 4] & 0xFF) << 3) | ((b[off + 5] >> 5) & 7);
        boolean crc = (b[off + 1] & 1) == 0;
        if (len < (crc ? 9 : HEADER)) return null;
        return new int[]{rateIndex, channels, len};
    }

    /** Ergebnis eines Durchlaufs über eine ADTS-Datei. */
    public static final class Index {
        public final int sampleRate;
        public final long[] offsets; // Byte-Position jedes Rahmens, dazu am Ende die Dateigröße
        public final int frames;

        Index(int sampleRate, long[] offsets, int frames) {
            this.sampleRate = sampleRate;
            this.offsets = offsets;
            this.frames = frames;
        }

        public long durationMs() {
            return sampleRate <= 0 ? 0 : frames * (long) SAMPLES_PER_FRAME * 1000L / sampleRate;
        }

        public long frameToMs(int frame) {
            return sampleRate <= 0 ? 0 : frame * (long) SAMPLES_PER_FRAME * 1000L / sampleRate;
        }
    }

    /**
     * Liest alle Rahmenköpfe. Bricht bei kaputten Daten ab (nichts wird geraten).
     * maxFrames schützt vor riesigen Dateien.
     */
    public static Index index(InputStream in, int maxFrames) throws IOException {
        return index(in, maxFrames, false);
    }

    /**
     * Wie {@link #index(InputStream, int)}; mit tolerantTail endet der Index beim letzten vollständigen
     * Rahmen, wenn die Datei mitten im Rahmen abbricht (z. B. Aufnahme durch Absturz unterbrochen).
     */
    public static Index index(InputStream in, int maxFrames, boolean tolerantTail) throws IOException {
        byte[] h = new byte[HEADER];
        long pos = 0;
        long[] offs = new long[1024];
        int n = 0;
        int rate = -1;
        while (true) {
            int r = readFully(in, h);
            if (r == 0) break;
            if (r < HEADER) {
                if (tolerantTail) break;
                throw new IOException("Datei endet mitten im Rahmen");
            }
            int[] p = parseHeader(h, 0);
            if (p == null) {
                if (tolerantTail && n > 0) break; // Rest unbrauchbar, bisheriges bleibt
                throw new IOException("Kein gültiger AAC-Rahmen");
            }
            if (rate < 0) rate = rate(p[0]);
            else if (rate(p[0]) != rate) throw new IOException("Abtastrate wechselt");
            if (n + 2 > offs.length) {
                if (offs.length >= maxFrames + 1) throw new IOException("Zu viele Rahmen");
                long[] g = new long[Math.min(offs.length * 2, maxFrames + 2)];
                System.arraycopy(offs, 0, g, 0, n);
                offs = g;
            }
            long skip = p[2] - HEADER;
            boolean cut = false;
            while (skip > 0) {
                long s = in.skip(skip);
                if (s <= 0) {
                    if (in.read() < 0) { cut = true; break; }
                    s = 1;
                }
                skip -= s;
            }
            if (cut) {
                if (tolerantTail) break;
                throw new IOException("Datei endet mitten im Rahmen");
            }
            offs[n++] = pos;
            pos += p[2];
            if (n > maxFrames) throw new IOException("Zu viele Rahmen");
        }
        long[] out = new long[n + 1];
        System.arraycopy(offs, 0, out, 0, n);
        out[n] = pos;
        return new Index(rate < 0 ? 0 : rate, out, n);
    }

    private static int readFully(InputStream in, byte[] b) throws IOException {
        int got = 0;
        while (got < b.length) {
            int r = in.read(b, got, b.length - got);
            if (r < 0) break;
            got += r;
        }
        return got;
    }
}
