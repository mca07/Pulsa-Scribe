package de.mca.pulsascribe.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Teilt lange Aufnahmen in Abschnitte für die Transkription. Geschnitten wird an der leisesten
 * Stelle kurz vor der Zielgrenze, damit möglichst kein Wort zerschnitten wird.
 */
public final class Chunker {
    private Chunker() {}

    /**
     * @param frames     Anzahl Rahmen
     * @param energy     Lautstärke je Rahmen (darf null oder kürzer sein)
     * @param fps        Rahmen pro Sekunde (> 0)
     * @param targetSec  gewünschte Teillänge
     * @param windowSec  Suchfenster vor der Grenze
     * @return Startrahmen jedes Teils, beginnend mit 0; letzter Teil endet bei frames
     */
    public static int[] cuts(int frames, float[] energy, double fps, int targetSec, int windowSec) {
        if (frames <= 0 || fps <= 0 || targetSec <= 0) return new int[]{0};
        int target = (int) Math.round(targetSec * fps);
        int window = (int) Math.round(Math.max(0, Math.min(windowSec, targetSec / 2)) * fps);
        // Kurzer Rest lohnt keinen eigenen Teil: bis 125 % der Ziellänge bleibt alles ein Teil
        if (frames <= target + target / 4) return new int[]{0};
        int smooth = Math.max(1, (int) Math.round(0.4 * fps)); // ~0,4 s glätten
        List<Integer> out = new ArrayList<>();
        out.add(0);
        int start = 0;
        while (frames - start > target + target / 4) {
            int limit = start + target;
            int from = Math.max(start + target - window, start + 1);
            int best = limit;
            if (energy != null && from < limit) {
                double bestVal = Double.MAX_VALUE;
                for (int f = from; f <= limit; f++) {
                    double sum = 0;
                    int cnt = 0;
                    for (int k = f - smooth / 2; k <= f + smooth / 2; k++) {
                        if (k >= 0 && k < energy.length && k < frames) { sum += energy[k]; cnt++; }
                    }
                    if (cnt == 0) continue;
                    double v = sum / cnt;
                    // Bei Gleichstand die spätere Stelle (längerer Teil)
                    if (v <= bestVal) { bestVal = v; best = f; }
                }
            }
            if (best <= start) best = limit;
            out.add(best);
            start = best;
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
}
