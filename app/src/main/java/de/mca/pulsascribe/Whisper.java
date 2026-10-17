package de.mca.pulsascribe;

import java.io.BufferedReader;
import java.io.FileReader;

/** Native whisper.cpp-Bibliothek. Lädt die fp16-Variante, wenn die CPU sie kann. */
final class Whisper {
    private Whisper() {}

    private static boolean loaded, failed;

    static synchronized boolean load() {
        if (loaded) return true;
        if (failed) return false;
        try {
            if (cpuHasFp16()) {
                try {
                    System.loadLibrary("scribe_whisper_fp16");
                    loaded = true;
                    return true;
                } catch (UnsatisfiedLinkError ignored) { }
            }
            System.loadLibrary("scribe_whisper");
            loaded = true;
        } catch (UnsatisfiedLinkError e) {
            failed = true;
        }
        return loaded;
    }

    private static boolean cpuHasFp16() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith("Features") && l.contains("fphp") && l.contains("asimdhp")) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static native long nInit(String modelPath);

    static native void nFree(long ctx);

    static native void nAbort(boolean on);

    static native int nProgress();

    static native byte[] nTranscribe(long ctx, float[] pcm, String lang, int threads);
}
