package de.mca.pulsascribe;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.mca.pulsascribe.core.Export;

/**
 * Vorlesen mit der Sprachausgabe von Android (offline, nichts verlässt das Gerät).
 * Lange Texte werden an Satzgrenzen in Stücke unter der Höchstlänge geteilt und nacheinander gesprochen.
 */
final class Reader {
    interface Listener {
        void onState(boolean speaking, int index); // index = Nummer des gerade gesprochenen Eintrags, -1 = keiner

        void onError(int messageRes);
    }

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ready, failed;
    private Runnable pendingStart;
    private Listener listener;
    private boolean speaking;

    Reader(Context c, Listener l) {
        ctx = c.getApplicationContext();
        listener = l;
        tts = new TextToSpeech(ctx, status -> main.post(() -> {
            if (status == TextToSpeech.SUCCESS && tts != null) {
                ready = true;
                tts.setOnUtteranceProgressListener(progress);
                if (pendingStart != null) {
                    Runnable r = pendingStart;
                    pendingStart = null;
                    r.run();
                }
            } else {
                failed = true;
                pendingStart = null;
                if (listener != null) listener.onError(R.string.read_unavailable);
            }
        }));
    }

    boolean isSpeaking() {
        return speaking;
    }

    /**
     * Spricht die Einträge der Reihe nach. language = Code aus der festen Liste ("de", "en" …)
     * oder "auto" (Gerätesprache).
     */
    void speak(List<String> items, String language) {
        if (failed) {
            if (listener != null) listener.onError(R.string.read_unavailable);
            return;
        }
        Runnable start = () -> doSpeak(items, language);
        if (!ready) {
            pendingStart = start;
            return;
        }
        start.run();
    }

    private void doSpeak(List<String> items, String language) {
        if (tts == null) return;
        tts.stop();
        Locale loc = "auto".equals(language) || language == null || language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(language);
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
            if (listener != null) listener.onError(R.string.read_lang_missing);
        }
        tts.setSpeechRate(Settings.speechRate(ctx));
        int max = Math.max(200, Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 50));
        boolean first = true;
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            for (String piece : Export.splitForSpeech(items.get(i), max)) {
                if (piece.isEmpty()) continue;
                Bundle params = new Bundle();
                tts.speak(piece, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, "i" + i + "_" + (total++));
                first = false;
            }
        }
        if (first) return; // nichts zu sprechen
        speaking = true;
        if (listener != null) listener.onState(true, 0);
    }

    void stop() {
        pendingStart = null;
        if (tts != null) tts.stop();
        setSpeaking(false, -1);
    }

    void release() {
        listener = null;
        pendingStart = null;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
    }

    private void setSpeaking(boolean s, int index) {
        main.post(() -> {
            speaking = s;
            if (listener != null) listener.onState(s, index);
        });
    }

    private final UtteranceProgressListener progress = new UtteranceProgressListener() {
        @Override
        public void onStart(String id) {
            setSpeaking(true, indexOf(id));
        }

        @Override
        public void onDone(String id) {
            if (tts != null && !tts.isSpeaking()) {
                // Kurz warten: zwischen zwei Stücken meldet isSpeaking() kurz false
                main.postDelayed(() -> {
                    if (tts != null && !tts.isSpeaking()) setSpeaking(false, -1);
                }, 300);
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onError(String id) {
            setSpeaking(false, -1);
        }

        @Override
        public void onStop(String id, boolean interrupted) {
            setSpeaking(false, -1);
        }
    };

    private static int indexOf(String id) {
        try {
            int us = id.indexOf('_');
            return Integer.parseInt(id.substring(1, us));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    /** Für Listen: einfache Hilfe zum Zusammenstellen. */
    static List<String> of(String... parts) {
        List<String> l = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) l.add(p);
        return l;
    }
}
