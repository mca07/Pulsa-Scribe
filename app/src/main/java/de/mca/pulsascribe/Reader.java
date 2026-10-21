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
 * Vorlesen: natürliche Gemini-Stimme (Standard, wenn ein Schlüssel da ist) oder Sprachausgabe von Android
 * (offline). Fällt die Gemini-Stimme aus, übernimmt die Handy-Stimme ab der Stelle.
 * Bei der Handy-Stimme wird automatisch die beste installierte Stimme der Sprache gewählt (oder die eingestellte).
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
    private GeminiSpeaker natural;

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
        if (Settings.naturalVoice(ctx) && naturalAvailable(ctx)) {
            speakNatural(items, language);
            return;
        }
        Runnable start = () -> doSpeak(items, language, 0);
        if (!ready) {
            pendingStart = start;
            return;
        }
        start.run();
    }

    /** OpenAI-Stimme, wenn OpenAI der Anbieter ist; sonst Gemini-Stimme, wenn ein Gemini-Schlüssel da ist. */
    static boolean naturalAvailable(Context c) {
        return (de.mca.pulsascribe.core.Ai.OPENAI.equals(Settings.provider(c)) && AiKeys.has(c, de.mca.pulsascribe.core.Ai.OPENAI))
                || GeminiKey.has(c);
    }

    static boolean usesOpenAiVoice(Context c) {
        return de.mca.pulsascribe.core.Ai.OPENAI.equals(Settings.provider(c)) && AiKeys.has(c, de.mca.pulsascribe.core.Ai.OPENAI);
    }

    private String naturalKind;

    private void speakNatural(List<String> items, String language) {
        if (tts != null) tts.stop();
        String kind = usesOpenAiVoice(ctx) ? "openai" : "gemini";
        if (natural != null && !kind.equals(naturalKind)) { natural.stop(); natural = null; }
        if (natural == null) {
            if ("openai".equals(kind)) {
                natural = GeminiSpeaker.openAi(AiKeys.get(ctx, de.mca.pulsascribe.core.Ai.OPENAI));
            } else {
                String key = GeminiKey.get(ctx);
                if (key == null) { doSpeakWhenReady(items, language, 0); return; }
                natural = new GeminiSpeaker(key);
            }
            naturalKind = kind;
        }
        speaking = true;
        if (listener != null) listener.onState(true, 0);
        String voice = "openai".equals(kind) ? Settings.openAiVoice(ctx) : Settings.voice(ctx);
        natural.start(items, language, voice, Settings.speechRate(ctx), new GeminiSpeaker.Callback() {
            @Override
            public void onItem(int index) {
                setSpeaking(true, index);
            }

            @Override
            public void onDone() {
                setSpeaking(false, -1);
            }

            @Override
            public void onFail(int fromItem, int messageRes) {
                main.post(() -> {
                    if (listener != null) listener.onError(messageRes);
                    if (fromItem >= 0 && fromItem < items.size()) {
                        doSpeakWhenReady(items.subList(fromItem, items.size()), language, fromItem);
                    } else {
                        setSpeaking(false, -1);
                    }
                });
            }
        });
    }

    private void doSpeakWhenReady(List<String> items, String language, int offset) {
        if (failed) { setSpeaking(false, -1); return; }
        List<String> copy = new ArrayList<>(items);
        Runnable start = () -> doSpeak(copy, language, offset);
        if (!ready) pendingStart = start;
        else start.run();
    }

    /** Beste Handy-Stimme: eingestellte, sonst höchste Qualität der Sprache (installiert, offline bevorzugt). */
    private void chooseVoice(Locale loc) {
        try {
            java.util.Set<android.speech.tts.Voice> all = tts.getVoices();
            if (all == null || all.isEmpty()) return;
            String wanted = Settings.deviceVoice(ctx);
            android.speech.tts.Voice best = null;
            for (android.speech.tts.Voice v : all) {
                if (v.getLocale() == null || !v.getLocale().getLanguage().equals(loc.getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                if (v.getName().equals(wanted)) { best = v; break; }
                if (best == null || score(v, loc) > score(best, loc)) best = v;
            }
            if (best != null) tts.setVoice(best);
        } catch (RuntimeException ignored) { }
    }

    static int score(android.speech.tts.Voice v, Locale loc) {
        int s = v.getQuality() * 10;
        if (!v.isNetworkConnectionRequired()) s += 3;
        if (loc.getCountry().isEmpty() || loc.getCountry().equals(v.getLocale().getCountry())) s += 2;
        return s;
    }

    /** Handy-Stimmen einer Sprache für die Auswahl in den Einstellungen (beste zuerst). */
    List<android.speech.tts.Voice> voices(Locale loc) {
        List<android.speech.tts.Voice> out = new ArrayList<>();
        if (tts == null || !ready) return out;
        try {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                if (v.getLocale() == null || !v.getLocale().getLanguage().equals(loc.getLanguage())) continue;
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                out.add(v);
            }
        } catch (RuntimeException ignored) { }
        out.sort((a, b) -> score(b, loc) - score(a, loc));
        return out;
    }

    boolean isReady() {
        return ready;
    }

    private void doSpeak(List<String> items, String language, int offset) {
        if (tts == null) return;
        tts.stop();
        Locale loc = "auto".equals(language) || language == null || language.isEmpty() ? Locale.getDefault() : Locale.forLanguageTag(language);
        int r = tts.setLanguage(loc);
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault());
            if (listener != null) listener.onError(R.string.read_lang_missing);
        }
        chooseVoice(r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED ? Locale.getDefault() : loc);
        tts.setSpeechRate(Settings.speechRate(ctx));
        int max = Math.max(200, Math.min(3500, TextToSpeech.getMaxSpeechInputLength() - 50));
        boolean first = true;
        int total = 0;
        for (int i = 0; i < items.size(); i++) {
            for (String piece : Export.splitForSpeech(items.get(i), max)) {
                if (piece.isEmpty()) continue;
                Bundle params = new Bundle();
                tts.speak(piece, first ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD, params, "i" + (i + offset) + "_" + (total++));
                first = false;
            }
        }
        if (first) return; // nichts zu sprechen
        speaking = true;
        if (listener != null) listener.onState(true, offset);
    }

    void stop() {
        pendingStart = null;
        if (natural != null) natural.stop();
        if (tts != null) tts.stop();
        setSpeaking(false, -1);
    }

    void release() {
        listener = null;
        pendingStart = null;
        if (natural != null) natural.stop();
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
