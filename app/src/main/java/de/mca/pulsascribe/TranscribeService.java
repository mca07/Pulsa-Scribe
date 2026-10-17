package de.mca.pulsascribe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import de.mca.pulsascribe.core.Adts;
import de.mca.pulsascribe.core.Chunker;
import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Vordergrund-Dienst: bereitet die Tonspur auf, transkribiert Teil für Teil mit Gemini und erstellt
 * die Zusammenfassung. Ein Auftrag nach dem anderen; jeder Schritt wird gespeichert, damit ein
 * erneuter Versuch beim nächsten offenen Teil weitermacht.
 */
public final class TranscribeService extends Service {
    static final String ACTION_START = "de.mca.pulsascribe.START";
    static final String ACTION_SUMMARY = "de.mca.pulsascribe.SUMMARY";
    static final String ACTION_CANCEL = "de.mca.pulsascribe.CANCEL";
    static final String EXTRA_ID = "id";

    private static final String CH_WORK = "work", CH_DONE = "done";
    private static final int NOTIF_WORK = 1;
    static final int CHUNK_SEC = 15 * 60;      // Ziel je Teil
    static final int CHUNK_WINDOW_SEC = 60;    // Suchfenster für leise Stelle
    static final int MIN_SPLIT_FRAMES_SEC = 120;

    /** Fortschritt nur im Speicher (für die Anzeige): id → {Schritt-Text-Res, Prozent}. */
    private static final Map<String, int[]> live = new HashMap<>();

    static synchronized int[] liveProgress(String id) {
        int[] v = live.get(id);
        return v == null ? null : v.clone();
    }

    private static synchronized void setLive(String id, int res, int pct) {
        if (id == null) return;
        if (res == 0) live.remove(id);
        else live.put(id, new int[]{res, pct});
        Store.progress();
    }

    // ------------------------------------------------------------------ Starten von außen

    /** Neue Datei: uri wird mit Leserecht an den Dienst übergeben. */
    static void start(Context c, String id, Uri source) {
        Intent i = new Intent(c, TranscribeService.class).setAction(ACTION_START).putExtra(EXTRA_ID, id);
        if (source != null) {
            i.setClipData(ClipData.newRawUri("audio", source));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        c.startForegroundService(i);
    }

    static void summary(Context c, String id) {
        c.startForegroundService(new Intent(c, TranscribeService.class).setAction(ACTION_SUMMARY).putExtra(EXTRA_ID, id));
    }

    static void cancel(Context c, String id) {
        Intent i = new Intent(c, TranscribeService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id);
        c.startService(i);
    }

    // ------------------------------------------------------------------ Dienst

    private static final class Job {
        final String id;
        final Uri source;
        final boolean summaryOnly;

        Job(String id, Uri source, boolean summaryOnly) {
            this.id = id;
            this.source = source;
            this.summaryOnly = summaryOnly;
        }
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Thread worker;
    private volatile String runningId;
    private volatile boolean cancelCurrent;
    private volatile GeminiClient client;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel work = new NotificationChannel(CH_WORK, getString(R.string.ch_work), NotificationManager.IMPORTANCE_LOW);
        NotificationChannel done = new NotificationChannel(CH_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(work);
        nm.createNotificationChannel(done);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(getString(R.string.n_starting), 0, true);
        if (intent == null) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        String id = intent.getStringExtra(EXTRA_ID);
        if (!Text.isId(id)) {
            stopIfIdle();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_CANCEL.equals(action)) {
            cancelJob(id);
        } else if (ACTION_START.equals(action) || ACTION_SUMMARY.equals(action)) {
            Uri src = null;
            ClipData cd = intent.getClipData();
            if (cd != null && cd.getItemCount() == 1) src = cd.getItemAt(0).getUri();
            if (src != null && !"content".equals(src.getScheme())) src = null; // nur content://
            enqueue(new Job(id, src, ACTION_SUMMARY.equals(action)));
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private synchronized void enqueue(Job job) {
        if (job.id.equals(runningId)) return;
        for (Job j : queue) if (j.id.equals(job.id)) return;
        Transcript t = Store.get(this, job.id);
        if (t == null) return;
        Transcript n = Store.copy(t);
        n.status = job.summaryOnly ? Transcript.SUMMARY : Transcript.QUEUED;
        n.error = "";
        Store.save(this, n);
        queue.add(job);
        if (worker == null) {
            worker = new Thread(this::loop, "transcribe");
            worker.start();
        }
    }

    private synchronized void cancelJob(String id) {
        if (id.equals(runningId)) {
            cancelCurrent = true;
            GeminiClient c = client;
            if (c != null) c.cancel();
            return;
        }
        for (Job j : queue) {
            if (j.id.equals(id)) {
                queue.remove(j);
                markCanceled(id);
                break;
            }
        }
    }

    private void markCanceled(String id) {
        Transcript t = Store.get(this, id);
        if (t == null) return;
        Transcript n = Store.copy(t);
        n.status = Transcript.CANCELED;
        n.error = "";
        Store.save(this, n);
        setLive(id, 0, 0);
    }

    private synchronized Job next() {
        Job j = queue.poll();
        if (j == null) {
            worker = null;
            runningId = null;
        } else {
            runningId = j.id;
            cancelCurrent = false;
        }
        return j;
    }

    private void loop() {
        acquireWake();
        try {
            Job job;
            while ((job = next()) != null) {
                try {
                    process(job);
                } catch (GoneException e) {
                    // Transkript wurde gelöscht: nichts mehr zu tun
                } catch (Throwable e) {
                    fail(job.id, getString(R.string.err_unexpected, e.getClass().getSimpleName()));
                } finally {
                    setLive(job.id, 0, 0);
                    synchronized (this) { runningId = null; }
                    client = null;
                }
            }
        } finally {
            releaseWake();
            new android.os.Handler(getMainLooper()).post(this::stopIfIdle);
        }
    }

    private synchronized void stopIfIdle() {
        if (worker == null && queue.isEmpty()) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }
    }

    @Override
    public void onTimeout(int startId, int fgsType) {
        // Android 15: Zeitlimit für dataSync erreicht → sauber beenden
        String id = runningId;
        if (id != null) {
            cancelCurrent = true;
            GeminiClient c = client;
            if (c != null) c.cancel();
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ Ablauf

    private void process(Job job) throws IOException {
        Transcript base = Store.get(this, job.id);
        if (base == null) return;
        Transcript t = Store.copy(base);
        String key = GeminiKey.get(this);
        if (key == null) {
            fail(t.id, getString(R.string.err_no_key));
            return;
        }
        client = new GeminiClient(key);

        if (!job.summaryOnly) {
            // 1) Tonspur aufbereiten
            File audio = Store.audioFile(this, t.id);
            if (!t.audioReady || !audio.isFile()) {
                Uri source = job.source;
                if (source == null && !t.sourceUri.isEmpty()) source = Uri.parse(t.sourceUri); // dauerhafte Freigabe
                if (source == null) {
                    fail(t.id, getString(R.string.err_need_file_again));
                    return;
                }
                t.status = Transcript.PREPARE;
                t.chunksDone = 0;
                t.segments.clear();
                t.cuts = new int[0];
                persist(t);
                setLive(t.id, R.string.st_prepare, 0);
                notifyWork(t, getString(R.string.st_prepare), 0);
                AudioPrep.Result r;
                try {
                    r = AudioPrep.run(this, source, audio, new AudioPrep.Progress() {
                        long last;

                        @Override
                        public void onProgress(int percent) {
                            long now = SystemClock.elapsedRealtime();
                            if (now - last < 400 && percent < 100) return;
                            last = now;
                            setLive(job.id, R.string.st_prepare, percent);
                            notifyWork(job.id, getString(R.string.st_prepare), percent);
                        }

                        @Override
                        public boolean isCanceled() {
                            return cancelCurrent;
                        }
                    });
                } catch (AudioPrep.PrepException e) {
                    fail(t.id, getString(e.messageRes));
                    return;
                } catch (IOException e) {
                    if (cancelCurrent) { markCanceled(t.id); return; }
                    fail(t.id, getString(R.string.err_open_file));
                    return;
                }
                double fps = r.sampleRate / (double) Adts.SAMPLES_PER_FRAME;
                t.cuts = Chunker.cuts(r.frames, r.energy, fps, CHUNK_SEC, CHUNK_WINDOW_SEC);
                t.durationMs = r.durationMs;
                t.audioReady = true;
                persist(t);
            }

            // 2) Teile transkribieren
            Adts.Index idx;
            try (InputStream in = new FileInputStream(audio)) {
                idx = Adts.index(in, 4_000_000);
            } catch (IOException e) {
                fail(t.id, getString(R.string.err_audio_broken));
                return;
            }
            if (idx.frames == 0) { fail(t.id, getString(R.string.err_no_audio)); return; }
            if (t.cuts.length == 0 || t.cuts[t.cuts.length - 1] >= idx.frames) {
                // Nach einem Update oder kaputten Daten neu teilen (ohne Lautstärke)
                double fps = idx.sampleRate / (double) Adts.SAMPLES_PER_FRAME;
                t.cuts = Chunker.cuts(idx.frames, null, fps, CHUNK_SEC, CHUNK_WINDOW_SEC);
                t.chunksDone = 0;
                t.segments.clear();
            }
            t.durationMs = idx.durationMs();
            t.status = Transcript.TRANSCRIBE;
            t.incomplete = false;
            persist(t);

            while (t.chunksDone < t.cuts.length) {
                if (cancelCurrent) { markCanceled(t.id); return; }
                int i = t.chunksDone;
                int startFrame = t.cuts[i];
                int endFrame = i + 1 < t.cuts.length ? t.cuts[i + 1] : idx.frames;
                int n = t.cuts.length;
                int pct = (int) (100L * startFrame / Math.max(1, idx.frames));
                setLive(t.id, R.string.st_transcribe, pct);
                notifyWork(t.id, n > 1 ? getString(R.string.st_transcribe_part, i + 1, n) : getString(R.string.st_transcribe), pct);

                Gemini.ChunkInfo info = new Gemini.ChunkInfo();
                info.index = i;
                info.count = n;
                info.durationMs = idx.frameToMs(endFrame) - idx.frameToMs(startFrame);
                info.knownSpeakers = Gemini.knownSpeakers(t);
                info.previousTail = i > 0 ? Gemini.tail(t, 300) : "";
                String prompt = Gemini.transcribePrompt(t.language, t.diarize, t.cleanFillers, t.terms, info);

                Outcome o = callAudio(prompt, audio, idx.offsets[startFrame], idx.offsets[endFrame], info.durationMs,
                        idx.frameToMs(startFrame), t.diarize);
                if (cancelCurrent) { markCanceled(t.id); return; }
                if (o.error != null) {
                    // Bisheriges bleibt erhalten; "Erneut versuchen" macht hier weiter
                    t.status = Transcript.ERROR;
                    t.error = o.error;
                    persist(t);
                    notifyDone(t, false);
                    return;
                }
                if (o.truncated) {
                    // Antwort zu lang: Teil halbieren und erneut versuchen (nur bei ausreichender Länge)
                    int half = startFrame + (endFrame - startFrame) / 2;
                    double fps = idx.sampleRate / (double) Adts.SAMPLES_PER_FRAME;
                    if (endFrame - startFrame > MIN_SPLIT_FRAMES_SEC * fps && t.cuts.length < Transcript.MAX_CHUNKS) {
                        int[] nc = new int[t.cuts.length + 1];
                        System.arraycopy(t.cuts, 0, nc, 0, i + 1);
                        nc[i + 1] = half;
                        System.arraycopy(t.cuts, i + 1, nc, i + 2, t.cuts.length - i - 1);
                        t.cuts = nc;
                        persist(t);
                        continue;
                    }
                    t.incomplete = true; // geretteter Teil wird übernommen
                }
                t.segments.addAll(o.segments.list);
                t.model = o.model;
                t.chunksDone = i + 1;
                persist(t);
                pause(1500);
            }
        }

        // 3) Zusammenfassung
        if ((t.summarize || job.summaryOnly) && !t.segments.isEmpty()) {
            if (cancelCurrent) { markCanceled(t.id); return; }
            t.status = Transcript.SUMMARY;
            persist(t);
            setLive(t.id, R.string.st_summary, 0);
            notifyWork(t.id, getString(R.string.st_summary), -1);
            String body = Gemini.summaryRequest(Gemini.summaryPrompt(t, Settings.summaryLanguage(this)));
            Outcome o = callJson(body);
            if (cancelCurrent) {
                if (job.summaryOnly) { t.status = Transcript.DONE; persist(t); }
                else markCanceled(t.id);
                return;
            }
            if (o.error == null && o.summary != null) {
                t.summary = o.summary;
                t.error = "";
            } else {
                t.error = getString(R.string.err_summary_failed) + (o.error != null ? " " + o.error : "");
            }
        } else if (job.summaryOnly) {
            t.error = getString(R.string.err_summary_empty);
        } else {
            t.error = "";
        }
        if (t.segments.isEmpty() && !job.summaryOnly) t.error = getString(R.string.err_no_speech);
        t.status = Transcript.DONE;
        persist(t);
        if (!Settings.keepAudio(this) && !job.summaryOnly) Store.deleteAudio(this, t.id);
        notifyDone(t, true);
    }

    /** Gelöscht während der Arbeit → Abbruch ohne Wiederauferstehung. */
    private static final class GoneException extends RuntimeException { }

    private void persist(Transcript t) {
        if (!Store.update(this, t)) {
            cancelCurrent = true;
            throw new GoneException();
        }
    }

    /** Ergebnis eines Aufrufs (mit Modell-Rückfall). */
    private static final class Outcome {
        Gemini.Segments segments;
        Transcript.Summary summary;
        boolean truncated;
        String model = "";
        String error;
    }

    private Outcome callAudio(String prompt, File audio, long from, long to, long chunkMs, long offsetMs, boolean diarize) {
        return call(model -> client.audio(model, prompt, audio, from, to), (reply, o) -> {
            boolean maybeCut = "MAX_TOKENS".equals(reply.finishReason);
            Gemini.Segments s = Gemini.parseSegments(reply.text, chunkMs, offsetMs, diarize, maybeCut);
            if (s == null) return false;
            o.segments = s;
            o.truncated = maybeCut || s.truncated;
            return true;
        });
    }

    private Outcome callJson(String body) {
        return call(model -> client.json(model, body), (reply, o) -> {
            o.summary = Gemini.parseSummary(reply.text);
            return o.summary != null;
        });
    }

    private interface Request {
        GeminiClient.Response send(String model) throws IOException;
    }

    private interface Accept {
        boolean accept(Gemini.Reply reply, Outcome o);
    }

    /**
     * Probiert die Modelle der Reihe nach, beginnend beim zuletzt erfolgreichen. Vorübergehende
     * Fehler (404/429/5xx, Zeitüberschreitung, unbrauchbare Antwort) führen zum nächsten Modell,
     * dauerhafte (400/401/403) brechen sofort ab. Nie eine Endlosschleife: höchstens 2 Runden.
     */
    private Outcome call(Request req, Accept accept) {
        Outcome o = new Outcome();
        int start = Settings.modelIndex(this);
        int models = Gemini.MODELS.length;
        String lastDetail = "";
        boolean quota = false, network = false;
        for (int attempt = 0; attempt < models * 2; attempt++) {
            if (cancelCurrent) { o.error = getString(R.string.canceled); return o; }
            int mi = (start + attempt) % models;
            String model = Gemini.MODELS[mi];
            if (attempt > 0) pause(attempt < models ? 2000 : 20_000); // zweite Runde mit längerer Pause
            GeminiClient.Response r;
            try {
                r = req.send(model);
            } catch (IOException e) {
                if (cancelCurrent) { o.error = getString(R.string.canceled); return o; }
                network = true;
                lastDetail = e.getClass().getSimpleName();
                continue;
            }
            network = false;
            if (r.code == 200) {
                Gemini.Reply reply = Gemini.reply(r.body);
                if (reply != null && !reply.blockReason.isEmpty()) {
                    o.error = getString(R.string.err_blocked, reply.blockReason);
                    return o;
                }
                if (reply != null && accept.accept(reply, o)) {
                    o.model = model;
                    Settings.setModelIndex(this, mi);
                    return o;
                }
                lastDetail = "200 · " + model + (reply != null && !reply.finishReason.isEmpty() ? " · " + reply.finishReason : "");
                continue;
            }
            String status = Gemini.errorStatus(r.body);
            lastDetail = r.code + (status.isEmpty() ? "" : " " + status) + " · " + model;
            if (r.code == 429) quota = true;
            if (!Gemini.tryNextModel(r.code)) {
                o.error = (r.code == 401 || r.code == 403 || (r.code == 400 && status.contains("INVALID_ARGUMENT") && r.body.contains("API key")))
                        ? getString(R.string.err_key_rejected, lastDetail)
                        : getString(R.string.err_request, lastDetail);
                return o;
            }
        }
        if (network) o.error = getString(R.string.err_network);
        else if (quota) o.error = getString(R.string.err_quota, lastDetail);
        else o.error = getString(R.string.err_all_models, lastDetail);
        return o;
    }

    private void pause(long ms) {
        long end = SystemClock.elapsedRealtime() + ms;
        while (!cancelCurrent && SystemClock.elapsedRealtime() < end) {
            try { Thread.sleep(200); } catch (InterruptedException e) { return; }
        }
    }

    private void fail(String id, String message) {
        Transcript t = Store.get(this, id);
        if (t == null) return;
        Transcript n = Store.copy(t);
        n.status = Transcript.ERROR;
        n.error = Text.line(message, 300);
        Store.save(this, n);
        notifyDone(n, false);
    }

    // ------------------------------------------------------------------ Benachrichtigungen

    private void goForeground(String text, int pct, boolean indeterminate) {
        Notification n = workNotification(null, text, pct, indeterminate);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_WORK, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_WORK, n);
        }
    }

    private void notifyWork(Transcript t, String text, int pct) {
        notifyWork(t.id, text, pct);
    }

    private void notifyWork(String id, String text, int pct) {
        Transcript t = Store.get(this, id);
        String title = t == null ? getString(R.string.app_name) : t.title;
        Notification n = workNotification(id, title + " · " + text, Math.max(0, pct), pct < 0);
        getSystemService(NotificationManager.class).notify(NOTIF_WORK, n);
    }

    private Notification workNotification(String id, String text, int pct, boolean indeterminate) {
        Notification.Builder b = new Notification.Builder(this, CH_WORK)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(getString(R.string.n_working))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, pct, indeterminate)
                .setContentIntent(openApp(null));
        if (id != null) {
            Intent cancel = new Intent(this, TranscribeService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id);
            PendingIntent pi = PendingIntent.getService(this, 2, cancel,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            b.addAction(new Notification.Action.Builder(null, getString(R.string.cancel), pi).build());
        }
        return b.build();
    }

    private void notifyDone(Transcript t, boolean ok) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return;
        String text = ok ? (t.error.isEmpty() ? getString(R.string.n_done_text) : t.error) : t.error;
        Notification n = new Notification.Builder(this, CH_DONE)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(ok ? getString(R.string.n_done, t.title) : getString(R.string.n_failed, t.title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(t.id))
                .build();
        nm.notify(100 + (t.id.hashCode() & 0xFFFF), n);
    }

    private PendingIntent openApp(String id) {
        Intent i;
        if (id == null) {
            i = new Intent(this, MainActivity.class);
        } else {
            i = new Intent(this, TranscriptActivity.class).putExtra(EXTRA_ID, id);
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int req = id == null ? 0 : 10 + (id.hashCode() & 0xFFFF);
        return PendingIntent.getActivity(this, req, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void acquireWake() {
        PowerManager pm = getSystemService(PowerManager.class);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulsaScribe:transcribe");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(3 * 60 * 60 * 1000L);
    }

    private void releaseWake() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
}
