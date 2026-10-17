package de.mca.pulsascribe.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Offline-Erkennung (whisper.cpp): Modellliste mit Prüfsummen, Audio-Hilfen, Prüfung der Ausgabe. */
public final class Offline {
    private Offline() {}

    public static final int RATE = 16000;

    /** Feste Modellliste; Prüfsummen aus den Git-LFS-Zeigern von huggingface.co/ggerganov/whisper.cpp. */
    public static final class Model {
        public final String id, label, file, sha256;
        public final long size;

        Model(String id, String label, String file, String sha256, long size) {
            this.id = id;
            this.label = label;
            this.file = file;
            this.sha256 = sha256;
            this.size = size;
        }

        public String url() {
            return "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + file;
        }
    }

    public static final Model[] MODELS = {
            new Model("base", "Schnell (Base, 60 MB)", "ggml-base-q5_1.bin",
                    "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898", 59_707_625L),
            new Model("small", "Ausgewogen (Small, 190 MB)", "ggml-small-q5_1.bin",
                    "ae85e4a935d7a567bd102fe55afc16bb595bdb618e11b2fc7591bc08120411bb", 190_085_487L),
            new Model("turbo", "Genau (Large v3 Turbo, 574 MB)", "ggml-large-v3-turbo-q5_0.bin",
                    "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", 574_041_195L),
    };

    public static Model model(String id) {
        for (Model m : MODELS) if (m.id.equals(id)) return m;
        return null;
    }

    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    public static boolean validHash(String h) {
        return h != null && HEX64.matcher(h).matches();
    }

    /** AudioSpecificConfig (2 Byte) für AAC-LC: Objekt 2, Ratenindex, Kanäle. */
    public static byte[] audioSpecificConfig(int rateIndex, int channels) {
        int v = (2 << 11) | ((rateIndex & 0x0F) << 7) | ((channels & 0x0F) << 3);
        return new byte[]{(byte) (v >> 8), (byte) v};
    }

    /** Lineare Umrechnung auf 16 kHz (Sprache; für Whisper ausreichend). */
    public static float[] resample(float[] in, int n, int inRate) {
        if (inRate == RATE) {
            float[] out = new float[n];
            System.arraycopy(in, 0, out, 0, n);
            return out;
        }
        int outN = (int) ((long) n * RATE / inRate);
        float[] out = new float[outN];
        double step = (double) inRate / RATE;
        for (int i = 0; i < outN; i++) {
            double pos = i * step;
            int k = (int) pos;
            double f = pos - k;
            float a = in[Math.min(k, n - 1)], b = in[Math.min(k + 1, n - 1)];
            out[i] = (float) (a + (b - a) * f);
        }
        return out;
    }

    private static final Pattern NOISE = Pattern.compile("^[\\[(*♪].*[\\])*♪]$");

    /**
     * Prüft die Ausgabe von whisper.cpp: Zeiten in Hundertstelsekunden, innerhalb des Abschnitts;
     * Geräusch-Markierungen wie [Musik] oder (Applaus) entfallen; Wiederholungen werden zusammengefasst;
     * kurze Stücke werden zu Absätzen von höchstens ~30 s zusammengeführt.
     */
    public static List<Transcript.Segment> parse(byte[] json, long offsetMs, long windowMs) {
        List<Transcript.Segment> out = new ArrayList<>();
        if (json == null) return out;
        List<Object> list;
        try {
            list = Json.arr(Json.parse(new String(json, StandardCharsets.UTF_8)));
        } catch (Json.ParseException e) {
            return null;
        }
        if (list == null) return null;
        String last = null;
        int repeat = 0;
        long startMs = -1, prev = 0;
        StringBuilder para = new StringBuilder();
        for (Object o : list) {
            if (out.size() >= Transcript.MAX_SEGMENTS) break;
            Map<String, Object> m = Json.obj(o);
            if (m == null) continue;
            long cs = Json.num(m, "t", -1);
            String x = Text.line(Json.str(m, "x"), Transcript.MAX_TEXT);
            if (x.isEmpty() || NOISE.matcher(x).matches()) continue;
            if (x.equals(last)) { if (++repeat >= 2) continue; } else { repeat = 0; last = x; }
            long ms = cs < 0 ? prev : cs * 10;
            if (ms > windowMs + 5_000) ms = prev;
            if (ms < prev) ms = prev;
            prev = ms;
            if (startMs < 0) startMs = ms;
            boolean sentenceEnd = para.length() > 0 && endsSentence(para);
            if (para.length() > 0 && ((ms - startMs > 30_000 && sentenceEnd) || para.length() + x.length() > 1500)) {
                out.add(new Transcript.Segment(offsetMs + startMs, "", para.toString().trim()));
                para.setLength(0);
                startMs = ms;
            }
            if (para.length() > 0) para.append(' ');
            para.append(x);
        }
        if (para.length() > 0) out.add(new Transcript.Segment(offsetMs + Math.max(0, startMs), "", para.toString().trim()));
        return out;
    }

    private static boolean endsSentence(CharSequence s) {
        char c = s.charAt(s.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '…';
    }
}
