package de.mca.pulsascribe;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.SystemClock;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Speech;

/**
 * Vorlesen mit natürlicher Gemini-Stimme: ein Thread holt die Stücke (eins im Voraus), ein zweiter spielt sie
 * über AudioTrack ab. Bei einem Fehler meldet er, ab welchem Eintrag die Handy-Stimme übernehmen soll.
 */
final class GeminiSpeaker {
    interface Callback {
        void onItem(int index);

        void onDone();

        /** Ab Eintrag fromItem konnte keine Stimme erzeugt werden. */
        void onFail(int fromItem, int messageRes);
    }

    private static final Object END = new Object();

    private final String key;
    private volatile int generation;
    private volatile GeminiClient client;
    private volatile AudioTrack track;

    GeminiSpeaker(String key) {
        this.key = key;
    }

    private static final class Piece {
        final Speech.Audio audio;
        final int item;
        final int failRes;

        Piece(Speech.Audio audio, int item, int failRes) {
            this.audio = audio;
            this.item = item;
            this.failRes = failRes;
        }
    }

    void start(List<String> items, String language, String voice, float rate, Callback cb) {
        stop();
        final int gen = ++generation;
        List<Speech.Chunk> chunks = Speech.plan(items, Speech.FIRST_MAX, Speech.MAX);
        if (chunks.isEmpty()) { cb.onDone(); return; }
        String lc = Speech.languageCode(language);
        BlockingQueue<Object> queue = new ArrayBlockingQueue<>(2);
        GeminiClient c = new GeminiClient(key);
        client = c;

        Thread fetcher = new Thread(() -> {
            try {
                for (Speech.Chunk ch : chunks) {
                    if (gen != generation) return;
                    Piece p = fetch(c, ch, voice, lc, gen);
                    if (gen != generation) return;
                    queue.put(p);
                    if (p.audio == null) return; // Fehler: Abspieler meldet ihn nach dem bisher Geholten
                }
                queue.put(END);
            } catch (InterruptedException ignored) { }
        }, "tts-fetch");

        Thread player = new Thread(() -> {
            try {
                while (gen == generation) {
                    Object o = queue.poll(500, TimeUnit.MILLISECONDS);
                    if (o == null) continue;
                    if (o == END) {
                        if (gen == generation) cb.onDone();
                        return;
                    }
                    Piece p = (Piece) o;
                    if (p.audio == null) {
                        if (gen == generation) cb.onFail(p.item, p.failRes);
                        return;
                    }
                    cb.onItem(p.item);
                    play(p.audio, rate, gen);
                }
            } catch (InterruptedException ignored) { }
        }, "tts-play");
        fetcher.start();
        player.start();
    }

    private Piece fetch(GeminiClient c, Speech.Chunk ch, String voice, String lc, int gen) {
        String body = Speech.request(ch.text, voice, lc);
        int fail = R.string.voice_failed;
        for (String model : Speech.MODELS) {
            for (int attempt = 0; attempt < 2; attempt++) {
                if (gen != generation) return new Piece(null, ch.item, fail);
                try {
                    GeminiClient.Response r = c.json(model, body);
                    if (r.code == 200) {
                        Speech.Audio a = Speech.parse(r.body);
                        if (a != null) return new Piece(a, ch.item, 0);
                        continue; // Text statt Audio: noch einmal
                    }
                    if (r.code == 401 || r.code == 403 || r.code == 400) return new Piece(null, ch.item, R.string.voice_failed);
                    if (r.code == 429) fail = R.string.voice_quota;
                    if (!Gemini.tryNextModel(r.code)) return new Piece(null, ch.item, fail);
                    if (r.code == 404 || r.code == 429) break; // nächstes Modell
                } catch (IOException e) {
                    fail = R.string.voice_offline_msg;
                    break;
                }
            }
        }
        return new Piece(null, ch.item, fail);
    }

    private void play(Speech.Audio a, float rate, int gen) {
        int min = AudioTrack.getMinBufferSize(a.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(a.rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(Math.max(min * 4, 32 * 1024))
                .build();
        track = t;
        try {
            try {
                t.setPlaybackParams(new PlaybackParams().setSpeed(rate).setPitch(1f));
            } catch (RuntimeException ignored) { }
            t.play();
            int off = 0;
            while (off < a.pcm.length && gen == generation) {
                int n = t.write(a.pcm, off, Math.min(8192, a.pcm.length - off));
                if (n <= 0) break;
                off += n;
            }
            // Puffer ausspielen lassen
            int frames = a.pcm.length / 2;
            long deadline = SystemClock.elapsedRealtime() + (long) (frames * 1000L / a.rate / Math.max(0.5f, rate)) + 2000;
            while (gen == generation && t.getPlaybackHeadPosition() < frames && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(40);
            }
        } catch (RuntimeException ignored) {
        } finally {
            try { t.stop(); } catch (RuntimeException ignored) { }
            t.release();
            if (track == t) track = null;
        }
    }

    void stop() {
        generation++;
        GeminiClient c = client;
        if (c != null) c.cancel();
        client = null;
        AudioTrack t = track;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (RuntimeException ignored) { }
        }
    }
}
