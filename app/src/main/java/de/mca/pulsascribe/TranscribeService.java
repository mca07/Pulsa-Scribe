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
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.mca.pulsascribe.core.Adts;
import de.mca.pulsascribe.core.Ai;
import de.mca.pulsascribe.core.Chunker;
import de.mca.pulsascribe.core.Folders;
import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;
import de.mca.pulsascribe.core.WebPage;

/**
 * Vordergrund-Dienst für alles, was mit Gemini spricht: Ton aufbereiten und Teil für Teil
 * transkribieren, Bilder lesen, Zusammenfassungen, Fragen, Übersetzungen, Ordner-Auswertungen.
 * Ein Auftrag nach dem anderen; jeder Schritt wird gespeichert, damit „Erneut versuchen“ weitermacht.
 */
public final class TranscribeService extends Service {
    static final String ACTION_START = "de.mca.pulsascribe.START";
    static final String ACTION_SUMMARY = "de.mca.pulsascribe.SUMMARY";
    static final String ACTION_ASK = "de.mca.pulsascribe.ASK";
    static final String ACTION_TRANSLATE = "de.mca.pulsascribe.TRANSLATE";
    static final String ACTION_FOLDER = "de.mca.pulsascribe.FOLDER";
    static final String ACTION_CANCEL = "de.mca.pulsascribe.CANCEL";
    static final String EXTRA_ID = "id";
    static final String EXTRA_ARG = "arg";
    static final String EXTRA_ARG2 = "arg2";

    private static final String CH_WORK = "work", CH_DONE = "done";
    private static final int NOTIF_WORK = 1;
    static final int CHUNK_SEC = 15 * 60;      // Ziel je Teil
    static final int CHUNK_WINDOW_SEC = 60;    // Suchfenster für leise Stelle
    static final int MIN_SPLIT_FRAMES_SEC = 120;
    static final int MAX_FOLDER_ITEMS = 60;

    private static final int T_FULL = 0, T_SUMMARY = 1, T_ASK = 2, T_TRANSLATE = 3, T_FOLDER = 4;

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

    /** Mehrere Dateien zu einem Eintrag: alle Quellen mit vorübergehender Lesefreigabe übergeben. */
    static void startMulti(Context c, String id, java.util.List<Uri> sources) {
        Intent i = new Intent(c, TranscribeService.class).setAction(ACTION_START).putExtra(EXTRA_ID, id);
        ClipData cd = null;
        for (Uri u : sources) {
            if (cd == null) cd = ClipData.newRawUri("audio", u);
            else cd.addItem(new ClipData.Item(u));
        }
        if (cd != null) {
            i.setClipData(cd);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }
        c.startForegroundService(i);
    }

    static void summary(Context c, String id) {
        c.startForegroundService(new Intent(c, TranscribeService.class).setAction(ACTION_SUMMARY).putExtra(EXTRA_ID, id));
    }

    static void ask(Context c, String id, String question) {
        ask(c, id, question, false);
    }

    static void ask(Context c, String id, String question, boolean expert) {
        c.startForegroundService(new Intent(c, TranscribeService.class).setAction(ACTION_ASK).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_ARG, Text.line(question, 300)).putExtra(EXTRA_ARG2, expert ? "expert" : ""));
    }

    static void translate(Context c, String id, String lang) {
        c.startForegroundService(new Intent(c, TranscribeService.class).setAction(ACTION_TRANSLATE).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_ARG, Languages.clean(lang)));
    }

    /** id = neu angelegtes Ergebnis-Transkript, folder = auszuwertender Ordner. */
    static void folder(Context c, String id, String folder, String focus) {
        c.startForegroundService(new Intent(c, TranscribeService.class).setAction(ACTION_FOLDER).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_ARG, folder).putExtra(EXTRA_ARG2, Text.line(focus, 300)));
    }

    static void cancel(Context c, String id) {
        Intent i = new Intent(c, TranscribeService.class).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id);
        c.startService(i);
    }

    // ------------------------------------------------------------------ Dienst

    private static final class Job {
        final String id;
        final Uri source;
        final int type;
        final String arg, arg2;
        String prevStatus = Transcript.DONE;
        /** Bei zusammengeführten Aufnahmen: alle Quellen mit vorübergehender Freigabe. */
        java.util.List<Uri> sources = new java.util.ArrayList<>();

        Job(String id, Uri source, int type, String arg, String arg2) {
            this.id = id;
            this.source = source;
            this.type = type;
            this.arg = arg == null ? "" : arg;
            this.arg2 = arg2 == null ? "" : arg2;
        }
    }

    private final ArrayDeque<Job> queue = new ArrayDeque<>();
    private Thread worker;
    private volatile String runningId;
    private volatile boolean cancelCurrent;
    /** Aktiver KI-Anbieter und dessen Client (null bei Gemini); client = Gemini (auch als Ausweg, wenn Schlüssel da). */
    private volatile String provider = Ai.GEMINI;
    private volatile AiClient ai;
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
        nm.createNotificationChannel(new NotificationChannel(CH_WORK, getString(R.string.ch_work), NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_DONE, getString(R.string.ch_done), NotificationManager.IMPORTANCE_DEFAULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        goForeground(getString(R.string.n_starting));
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
        String arg = intent.getStringExtra(EXTRA_ARG), arg2 = intent.getStringExtra(EXTRA_ARG2);
        if (ACTION_CANCEL.equals(action)) {
            cancelJob(id);
        } else if (ACTION_START.equals(action)) {
            Uri src = null;
            java.util.List<Uri> all = new java.util.ArrayList<>();
            ClipData cd = intent.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount() && i < Transcript.MAX_PARTS; i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null && "content".equals(u.getScheme())) all.add(u); // nur content://
                }
            }
            if (all.size() == 1) src = all.get(0);
            Job j = new Job(id, src, T_FULL, null, null);
            if (all.size() > 1) j.sources = all;
            enqueue(j);
        } else if (ACTION_SUMMARY.equals(action)) {
            enqueue(new Job(id, null, T_SUMMARY, null, null));
        } else if (ACTION_ASK.equals(action)) {
            String q = Text.line(arg, 300);
            if (!q.isEmpty()) enqueue(new Job(id, null, T_ASK, q, "expert".equals(intent.getStringExtra(EXTRA_ARG2)) ? "expert" : null));
        } else if (ACTION_TRANSLATE.equals(action)) {
            String l = Languages.clean(arg);
            if (!Languages.AUTO.equals(l)) enqueue(new Job(id, null, T_TRANSLATE, l, null));
        } else if (ACTION_FOLDER.equals(action)) {
            if (arg != null && (arg.isEmpty() || Text.isId(arg))) enqueue(new Job(id, null, T_FOLDER, arg, Text.line(arg2, 300)));
        }
        stopIfIdle();
        return START_NOT_STICKY;
    }

    private synchronized void enqueue(Job job) {
        if (job.id.equals(runningId)) return;
        for (Job j : queue) if (j.id.equals(job.id)) return;
        Transcript t = Store.get(this, job.id);
        if (t == null) return;
        if (job.type == T_ASK || job.type == T_TRANSLATE || job.type == T_SUMMARY) {
            if (t.isWorking()) return;
            job.prevStatus = t.status;
        }
        Transcript n = Store.copy(t);
        n.status = job.type == T_FULL || job.type == T_FOLDER ? Transcript.QUEUED : Transcript.SUMMARY;
        if (job.type == T_FULL || job.type == T_FOLDER || job.type == T_SUMMARY) n.error = "";
        Store.save(this, n);
        int res = job.type == T_ASK ? R.string.st_ask : job.type == T_TRANSLATE ? R.string.st_translate
                : job.type == T_SUMMARY ? R.string.st_summary : 0;
        if (res != 0) setLive(job.id, res, -1);
        queue.add(job);
        if (worker == null) {
            worker = new Thread(this::loop, "transcribe");
            worker.start();
        }
    }

    private synchronized void cancelJob(String id) {
        if (id.equals(runningId)) {
            cancelCurrent = true;
            OfflineRunner.abort(true);
            GeminiClient c = client;
            if (c != null) c.cancel();
            AiClient a = ai;
            if (a != null) a.cancel();
            return;
        }
        for (Job j : queue) {
            if (j.id.equals(id)) {
                queue.remove(j);
                if (j.type == T_FULL || j.type == T_FOLDER) markCanceled(id);
                else restore(j, null);
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

    /** Nach Fragen/Übersetzen/Zusammenfassung: alten Status wiederherstellen, ggf. Hinweis setzen. */
    private void restore(Job job, String note) {
        Transcript t = Store.get(this, job.id);
        if (t == null) return;
        Transcript n = Store.copy(t);
        n.status = Transcript.SUMMARY.equals(job.prevStatus) ? Transcript.DONE : job.prevStatus;
        if (note != null) n.error = note;
        Store.save(this, n);
        setLive(job.id, 0, 0);
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
                    provider = Settings.provider(this);
                    String key = GeminiKey.get(this);
                    String pkey = Ai.GEMINI.equals(provider) ? key : AiKeys.get(this, provider);
                    Transcript jt = Store.get(this, job.id);
                    boolean offline = job.type == T_FULL && jt != null && jt.isAudio()
                            && Transcript.ENGINE_WHISPER.equals(jt.engine);
                    if (pkey == null && !offline) {
                        String msg = Ai.GEMINI.equals(provider) ? getString(R.string.err_no_key)
                                : getString(R.string.err_no_key_provider, Ai.label(provider));
                        if (job.type == T_FULL || job.type == T_FOLDER) fail(job.id, msg);
                        else restore(job, msg);
                        continue;
                    }
                    client = key == null ? null : new GeminiClient(key);
                    ai = Ai.GEMINI.equals(provider) || pkey == null ? null : new AiClient(provider, pkey);
                    switch (job.type) {
                        case T_FULL: process(job, false); break;
                        case T_SUMMARY: process(job, true); break;
                        case T_ASK: ask(job); break;
                        case T_TRANSLATE: translate(job); break;
                        default: folderSummary(job);
                    }
                } catch (GoneException e) {
                    // Transkript wurde gelöscht: nichts mehr zu tun
                } catch (Throwable e) {
                    if (job.type == T_FULL || job.type == T_FOLDER) {
                        fail(job.id, getString(R.string.err_unexpected, e.getClass().getSimpleName()));
                    } else {
                        restore(job, getString(R.string.err_unexpected, e.getClass().getSimpleName()));
                    }
                } finally {
                    setLive(job.id, 0, 0);
                    synchronized (this) { runningId = null; }
                    client = null;
                    ai = null;
                }
            }
        } finally {
            OfflineRunner.close(); // Modell aus dem Speicher
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
        if (runningId != null) {
            cancelCurrent = true;
            GeminiClient c = client;
            if (c != null) c.cancel();
            AiClient a = ai;
            if (a != null) a.cancel();
        }
        stopSelf();
    }

    // ------------------------------------------------------------------ Transkribieren und zusammenfassen

    private void process(Job job, boolean summaryOnly) throws IOException {
        Transcript base = Store.get(this, job.id);
        if (base == null) return;
        Transcript t = Store.copy(base);

        if (!summaryOnly) {
            if (t.isAudio()) {
                if (!transcribeAudio(job, t)) return;
            } else if (Transcript.KIND_IMAGE.equals(t.kind) && t.segments.isEmpty()) {
                if (!readImage(t)) return;
            } else if (Transcript.KIND_WEB.equals(t.kind) && t.segments.isEmpty()) {
                if (!readWeb(t)) return;
            } else if (Transcript.KIND_PDF.equals(t.kind) && t.segments.isEmpty()) {
                File f = Store.pdfFile(this, t.id);
                if (!f.isFile()) { fail(t.id, getString(R.string.err_image_missing)); return; }
                t.status = Transcript.TRANSCRIBE;
                persist(t);
                setLive(t.id, R.string.st_ocr, -1);
                notifyWork(t.id, getString(R.string.st_ocr), -1);
                if (!readPdf(t, f)) return;
            }
        }

        // Zusammenfassung (offline ohne Schlüssel: keine)
        if ((t.summarize || summaryOnly) && !t.segments.isEmpty() && textReady()) {
            if (cancelCurrent) { finishCanceled(job, t, summaryOnly); return; }
            t.status = Transcript.SUMMARY;
            persist(t);
            setLive(t.id, R.string.st_summary, -1);
            if (!summaryOnly) notifyWork(t.id, getString(R.string.st_summary), -1);
            String sumPrompt = Gemini.summaryPrompt(t, Settings.summaryLanguage(this));
            List<String> tags = new ArrayList<>();
            Outcome o = callText(sumPrompt, Gemini.summarySchema(), 0, (reply, oc) -> {
                oc.summary = Gemini.parseSummary(reply.text);
                if (oc.summary != null) tags.addAll(Gemini.parseTags(reply.text));
                return oc.summary != null;
            });
            if (cancelCurrent) { finishCanceled(job, t, summaryOnly); return; }
            if (o.error == null && o.summary != null) {
                t.summary = o.summary;
                t.sumModel = o.model;
                t.error = "";
                if (t.tags.isEmpty()) t.tags.addAll(tags);
            } else {
                t.error = getString(R.string.err_summary_failed) + (o.error != null ? " " + o.error : "");
            }
        } else if (summaryOnly) {
            t.error = getString(R.string.err_summary_empty);
        } else {
            t.error = "";
        }
        if (t.segments.isEmpty() && !summaryOnly) t.error = getString(t.isAudio() ? R.string.err_no_speech : R.string.err_no_text);
        t.status = summaryOnly && Transcript.ERROR.equals(job.prevStatus) ? Transcript.ERROR : Transcript.DONE;
        if (summaryOnly && Transcript.ERROR.equals(job.prevStatus) && t.error.isEmpty()) t.error = base.error;
        persist(t);
        if (!summaryOnly && t.isAudio() && !Settings.keepAudio(this)) Store.deleteAudio(this, t.id);
        if (!summaryOnly) notifyDone(t, true);
    }

    private void finishCanceled(Job job, Transcript t, boolean summaryOnly) {
        if (summaryOnly) restore(job, null);
        else markCanceled(t.id);
    }

    /** Ton aufbereiten und alle offenen Teile transkribieren. false = abgebrochen oder Fehler (schon gemeldet). */
    private boolean transcribeAudio(Job job, Transcript t) throws IOException {
        File audio = Store.audioFile(this, t.id);
        if (!t.audioReady || !audio.isFile()) {
            Uri source = job.source;
            if (source == null && !t.sourceUri.isEmpty()) source = Uri.parse(t.sourceUri); // dauerhafte Freigabe
            java.util.List<Uri> multi = new java.util.ArrayList<>();
            if (t.parts.size() > 1) {
                if (job.sources.size() == t.parts.size()) multi.addAll(job.sources);
                else {
                    for (Transcript.Part pt : t.parts) {
                        if (pt.uri.isEmpty()) { multi.clear(); break; }
                        multi.add(Uri.parse(pt.uri));
                    }
                }
                if (multi.isEmpty()) {
                    fail(t.id, getString(R.string.err_need_file_again));
                    return false;
                }
                source = multi.get(0);
            }
            if (source == null) {
                fail(t.id, getString(R.string.err_need_file_again));
                return false;
            }
            t.status = Transcript.PREPARE;
            t.chunksDone = 0;
            t.segments.clear();
            t.cuts = new int[0];
            persist(t);
            setLive(t.id, R.string.st_prepare, 0);
            notifyWork(t.id, getString(R.string.st_prepare), 0);
            AudioPrep.Result r;
            long[] partStarts = new long[Math.max(1, multi.size())];
            try {
                r = AudioPrep.runAll(this, multi.size() > 1 ? multi : java.util.Collections.singletonList(source), audio, partStarts, new AudioPrep.Progress() {
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
                return false;
            } catch (IOException e) {
                if (cancelCurrent) { markCanceled(t.id); return false; }
                fail(t.id, getString(R.string.err_open_file));
                return false;
            }
            if (multi.size() > 1) {
                for (int i = 0; i < t.parts.size() && i < partStarts.length; i++) t.parts.get(i).startMs = partStarts[i];
            }
            double fps = r.sampleRate / (double) Adts.SAMPLES_PER_FRAME;
            t.cuts = Chunker.cuts(r.frames, r.energy, fps, CHUNK_SEC, CHUNK_WINDOW_SEC);
            t.durationMs = r.durationMs;
            t.audioReady = true;
            persist(t);
        }

        Adts.Index idx;
        try (InputStream in = new FileInputStream(audio)) {
            idx = Adts.index(in, 4_000_000, true); // tolerant: unterbrochene Aufnahme bleibt nutzbar
        } catch (IOException e) {
            fail(t.id, getString(R.string.err_audio_broken));
            return false;
        }
        if (idx.frames == 0) { fail(t.id, getString(R.string.err_no_audio)); return false; }
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

        // Wer transkribiert? Gewählter Anbieter; kann er keinen Ton (Claude), dann Gemini oder das Offline-Modell.
        boolean whisper = Transcript.ENGINE_WHISPER.equals(t.engine);
        boolean external = !whisper && !Ai.GEMINI.equals(provider) && Ai.canAudio(provider) && ai != null;
        boolean viaGemini = !whisper && !external && client != null;
        if (!whisper && !external && !viaGemini) {
            if (OfflineModels.usable(this) != null) whisper = true;
            else { fail(t.id, getString(R.string.err_provider_audio, Ai.label(provider))); return false; }
        }
        java.util.Map<String, String> speakerMap = new java.util.LinkedHashMap<>();

        while (t.chunksDone < t.cuts.length) {
            if (cancelCurrent) { markCanceled(t.id); return false; }
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
            if (external) {
                long offsetMs = idx.frameToMs(startFrame);
                Gemini.Segments segs = transcribeExternal(t, audio, idx, startFrame, endFrame, info.durationMs, offsetMs, speakerMap);
                if (cancelCurrent) { markCanceled(t.id); return false; }
                if (segs == null) return false; // Fehler schon gemeldet
                t.segments.addAll(segs.list);
                t.model = Ai.transcriptionModel(provider, false);
                t.chunksDone = i + 1;
                persist(t);
                continue;
            }
            if (whisper) {
                // Offline: whisper.cpp auf dem Gerät
                List<Transcript.Segment> segs;
                try {
                    de.mca.pulsascribe.core.Offline.Model m = OfflineModels.usable(this);
                    if (m == null) { fail(t.id, getString(R.string.off_err_no_model)); return false; }
                    OfflineRunner.open(OfflineModels.file(this, m));
                    final int pi = i, pn = n, sf = startFrame, ef = endFrame, total = idx.frames;
                    segs = OfflineRunner.run(audio, idx, startFrame, endFrame, t.language, new OfflineRunner.Progress() {
                        long last;

                        @Override
                        public void on(int percent) {
                            long now = SystemClock.elapsedRealtime();
                            if (now - last < 1000) return;
                            last = now;
                            int overall = (int) ((sf + (long) (ef - sf) * percent / 100) * 100 / Math.max(1, total));
                            setLive(t.id, R.string.st_offline, overall);
                            notifyWork(t.id, pn > 1 ? getString(R.string.st_transcribe_part, pi + 1, pn) : getString(R.string.st_offline), overall);
                        }

                        @Override
                        public boolean canceled() {
                            return cancelCurrent;
                        }
                    });
                    t.model = "whisper-" + m.id;
                } catch (OfflineRunner.OfflineException e) {
                    if (cancelCurrent) { markCanceled(t.id); return false; }
                    t.status = Transcript.ERROR;
                    t.error = getString(e.messageRes);
                    persist(t);
                    notifyDone(t, false);
                    return false;
                } catch (IOException e) {
                    if (cancelCurrent) { markCanceled(t.id); return false; }
                    t.status = Transcript.ERROR;
                    t.error = getString(R.string.off_err_run);
                    persist(t);
                    notifyDone(t, false);
                    return false;
                }
                t.segments.addAll(segs);
                t.chunksDone = i + 1;
                persist(t);
                continue;
            }
            String prompt = Gemini.transcribePrompt(t.language, t.diarize, t.cleanFillers, t.terms, info);
            long from = idx.offsets[startFrame], to = idx.offsets[endFrame];
            long chunkMs = info.durationMs, offsetMs = idx.frameToMs(startFrame);
            boolean diarize = t.diarize;
            Outcome o = call(model -> client.audio(model, prompt, audio, from, to), (reply, oc) -> {
                boolean maybeCut = "MAX_TOKENS".equals(reply.finishReason);
                Gemini.Segments s = Gemini.parseSegments(reply.text, chunkMs, offsetMs, diarize, maybeCut);
                if (s == null) return false;
                oc.segments = s;
                oc.truncated = maybeCut || s.truncated;
                return true;
            });
            if (cancelCurrent) { markCanceled(t.id); return false; }
            if (o.error != null) {
                // Bisheriges bleibt erhalten; "Erneut versuchen" macht hier weiter
                t.status = Transcript.ERROR;
                t.error = o.error;
                persist(t);
                notifyDone(t, false);
                return false;
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
        return true;
    }

    /** Ein Teil per OpenAI/Mistral: als .m4a verpacken, hochladen, Abschnitte mit Zeiten und Sprechern. null = Fehler (gemeldet). */
    private Gemini.Segments transcribeExternal(Transcript t, File audio, Adts.Index idx, int startFrame, int endFrame,
                                               long chunkMs, long offsetMs, java.util.Map<String, String> speakerMap) {
        File m4a = new File(getCacheDir(), "chunk_" + t.id + ".m4a");
        try {
            M4a.write(audio, idx, startFrame, endFrame, m4a);
        } catch (IOException e) {
            fail(t.id, getString(R.string.err_audio_broken));
            return null;
        }
        try {
            String last = "";
            boolean quota = false;
            for (int mi = 0; mi < 2; mi++) {
                String model = Ai.transcriptionModel(provider, mi == 1);
                for (int attempt = 0; attempt < 3; attempt++) {
                    if (cancelCurrent) return null;
                    if (attempt > 0) pause(attempt == 1 ? 3000 : 20_000);
                    GeminiClient.Response r;
                    try {
                        r = ai.transcribe(Ai.transcriptionFields(provider, model, t.language, t.diarize, t.terms), m4a, "audio.m4a", "audio/mp4");
                    } catch (IOException e) {
                        if (cancelCurrent) return null;
                        last = e.getClass().getSimpleName();
                        continue;
                    }
                    if (r.code == 200) {
                        Gemini.Segments s = Ai.parseTranscription(r.body, chunkMs, offsetMs, t.diarize, speakerMap);
                        if (s != null) return s;
                        last = "200 · " + model;
                        continue;
                    }
                    String status = Ai.errorStatus(provider, r.body);
                    last = r.code + (status.isEmpty() ? "" : " " + status) + " · " + model;
                    if (r.code == 401 || r.code == 403) {
                        fail(t.id, getString(R.string.err_key_rejected, last));
                        return null;
                    }
                    if (r.code == 400 || r.code == 404 || r.code == 422) break; // anderes Modell versuchen
                    if (r.code == 429) quota = true;
                }
            }
            fail(t.id, getString(quota ? R.string.err_quota : R.string.err_all_models, last));
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            m4a.delete();
        }
    }

    /** Text aus dem Bild lesen. false = Fehler (schon gemeldet). */
    private boolean readImage(Transcript t) {
        File img = Store.imageFile(this, t.id);
        if (!img.isFile()) {
            fail(t.id, getString(R.string.err_image_missing));
            return false;
        }
        t.status = Transcript.TRANSCRIBE;
        persist(t);
        setLive(t.id, R.string.st_ocr, -1);
        notifyWork(t.id, getString(R.string.st_ocr), -1);
        String prompt = Gemini.ocrPrompt(t.language);
        final Gemini.Ocr[] res = new Gemini.Ocr[1];
        Outcome o = callAttach(prompt, Gemini.ocrSchema(), img, Ai.JPEG, (reply, oc) -> {
            res[0] = Gemini.parseOcr(reply.text);
            return res[0] != null;
        });
        if (cancelCurrent) { markCanceled(t.id); return false; }
        if (o.error != null || res[0] == null) {
            t.status = Transcript.ERROR;
            t.error = o.error != null ? o.error : getString(R.string.err_no_text);
            persist(t);
            notifyDone(t, false);
            return false;
        }
        for (String p : res[0].paragraphs) t.segments.add(new Transcript.Segment(0, "", p));
        if (Settings.isDefaultImageTitle(this, t.title) && !res[0].title.isEmpty()) {
            // Titel aus dem Bild übernehmen, wenn noch der Standardtitel gesetzt ist
            Transcript cur = Store.get(this, t.id);
            if (cur != null && cur.title.equals(t.title)) {
                Transcript n = Store.copy(cur);
                n.title = res[0].title;
                Store.save(this, n);
            }
        }
        t.model = o.model;
        persist(t);
        return true;
    }

    // ------------------------------------------------------------------ Links: Webseite, PDF, YouTube

    private boolean readWeb(Transcript t) {
        t.status = Transcript.TRANSCRIBE;
        persist(t);
        String yt = WebPage.youTubeId(t.url);
        if (!yt.isEmpty()) return readYouTube(t, WebPage.youTubeUrl(yt));
        setLive(t.id, R.string.st_web, -1);
        notifyWork(t.id, getString(R.string.st_web), -1);
        WebPage.Page page = null;
        int failRes = 0;
        File pdf = new File(getCacheDir(), "web_" + t.id + ".pdf");
        try {
            WebFetch.Result r = WebFetch.fetch(t.url, pdf, () -> cancelCurrent);
            if (r.pdf != null) return readPdf(t, r.pdf);
            page = WebPage.extract(r.html);
            if (page.length() < WebPage.MIN_TEXT) {
                page = null;
                if (WebPage.looksLikeLogin(r.html)) failRes = R.string.web_login;
            }
        } catch (WebFetch.Failure f) {
            failRes = f.res;
        } catch (IOException e) {
            failRes = R.string.web_offline;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            pdf.delete();
        }
        if (cancelCurrent) { markCanceled(t.id); return false; }
        String model = "";
        if (page == null && client != null && failRes != R.string.web_private && failRes != R.string.web_bad_url
                && failRes != R.string.web_too_big && failRes != R.string.web_unsupported && failRes != R.string.web_login) {
            // Ausweg: Gemini ruft die Seite selbst ab (Cookie-Wände, Seiten nur mit JavaScript)
            String body = WebPage.urlRequest(WebPage.urlPrompt(t.url, t.language));
            final WebPage.Page[] res = new WebPage.Page[1];
            Outcome o = call(m -> client.json(m, body), (reply, oc) -> {
                res[0] = reply.urlRetrieved ? WebPage.parseUrlReply(reply.text) : null;
                return true; // auch „nicht abrufbar“ ist eine gültige Antwort – nicht andere Modelle versuchen
            });
            if (cancelCurrent) { markCanceled(t.id); return false; }
            if (o.error != null && failRes == 0) failRes = R.string.web_failed;
            page = res[0];
            model = o.model;
        }
        if (page == null || page.paragraphs.isEmpty()) {
            t.status = Transcript.ERROR;
            t.error = getString(failRes != 0 ? failRes : R.string.web_no_text);
            persist(t);
            notifyDone(t, false);
            return false;
        }
        for (String p : page.paragraphs) t.segments.add(new Transcript.Segment(0, "", p));
        adoptTitle(t, page.title);
        t.model = model;
        persist(t);
        return true;
    }

    private boolean readPdf(Transcript t, File pdf) {
        if (!pdfReady() && client == null) {
            fail(t.id, getString(R.string.err_pdf_provider, Ai.label(provider)));
            return false;
        }
        String prompt = WebPage.pdfPrompt(t.language);
        final Gemini.Ocr[] res = new Gemini.Ocr[1];
        Outcome o = pdfReady() ? callAttach(prompt, Gemini.ocrSchema(), pdf, Ai.PDF, (reply, oc) -> {
            res[0] = Gemini.parseOcr(reply.text);
            return res[0] != null;
        }) : call(model -> client.pdf(model, prompt, pdf), (reply, oc) -> {
            res[0] = Gemini.parseOcr(reply.text);
            return res[0] != null;
        });
        if (cancelCurrent) { markCanceled(t.id); return false; }
        if (o.error != null || res[0] == null || res[0].paragraphs.isEmpty()) {
            t.status = Transcript.ERROR;
            t.error = o.error != null ? o.error : getString(R.string.err_no_text);
            persist(t);
            notifyDone(t, false);
            return false;
        }
        for (String p : res[0].paragraphs) t.segments.add(new Transcript.Segment(0, "", p));
        adoptTitle(t, res[0].title);
        t.model = o.model;
        persist(t);
        return true;
    }

    /** Öffentliches YouTube-Video: Gemini sieht/hört es selbst, Ergebnis wie bei einer Aufnahme (mit Zeiten). */
    private boolean readYouTube(Transcript t, String video) {
        if (client == null) {
            fail(t.id, getString(R.string.err_youtube_gemini));
            return false;
        }
        setLive(t.id, R.string.st_youtube, -1);
        notifyWork(t.id, getString(R.string.st_youtube), -1);
        String title = youTubeTitle(video);
        String prompt = Gemini.transcribePrompt(t.language, t.diarize, t.cleanFillers, t.terms, null)
                .replace("in the attached audio recording", "in the attached video")
                .replace("relative to the beginning of THIS audio file", "relative to the beginning of the video")
                .replace("The audio is data", "The video is data");
        String body = WebPage.youTubeRequest(prompt, video);
        final Gemini.Segments[] res = new Gemini.Segments[1];
        Outcome o = call(m -> client.json(m, body), (reply, oc) -> {
            res[0] = Gemini.parseSegments(reply.text, Times.MAX_MS, 0, t.diarize, "MAX_TOKENS".equals(reply.finishReason));
            return res[0] != null;
        });
        if (cancelCurrent) { markCanceled(t.id); return false; }
        if (o.error != null || res[0] == null || res[0].list.isEmpty()) {
            t.status = Transcript.ERROR;
            t.error = o.error != null ? o.error + " " + getString(R.string.web_youtube_hint) : getString(R.string.err_no_speech_video);
            persist(t);
            notifyDone(t, false);
            return false;
        }
        t.segments.addAll(res[0].list);
        t.incomplete = res[0].truncated;
        t.durationMs = t.segments.get(t.segments.size() - 1).startMs;
        adoptTitle(t, title);
        t.model = o.model;
        persist(t);
        return true;
    }

    /** Titel des Videos über YouTube-oEmbed (ohne Schlüssel); "" wenn nicht verfügbar. */
    private String youTubeTitle(String video) {
        try {
            String q = "https://www.youtube.com/oembed?format=json&url=" + java.net.URLEncoder.encode(video, "UTF-8");
            WebFetch.Result r = WebFetch.fetch(q, null, () -> cancelCurrent);
            if (r.html == null) return "";
            java.util.Map<String, Object> m = de.mca.pulsascribe.core.Json.obj(de.mca.pulsascribe.core.Json.parse(r.html));
            String title = Text.line(de.mca.pulsascribe.core.Json.str(m, "title"), Transcript.MAX_TITLE);
            String author = Text.line(de.mca.pulsascribe.core.Json.str(m, "author_name"), 60);
            return title.isEmpty() ? "" : (author.isEmpty() ? title : title + " – " + author);
        } catch (Exception e) {
            return "";
        }
    }

    /** Titel der Seite übernehmen, solange noch der Vorschlag (Adresse) als Titel gesetzt ist. */
    private void adoptTitle(Transcript t, String title) {
        if (title == null || title.isEmpty()) return;
        Transcript cur = Store.get(this, t.id);
        if (cur == null) return;
        String host = WebPage.host(t.url);
        if (cur.title.equals(host) || cur.title.equals(getString(R.string.web_youtube_title))) {
            Transcript n = Store.copy(cur);
            n.title = title;
            Store.save(this, n);
        }
    }

    // ------------------------------------------------------------------ Fragen, Übersetzen, Ordner

    private void ask(Job job) {
        Transcript base = Store.get(this, job.id);
        if (base == null) return;
        if (base.segments.isEmpty()) { restore(job, getString(R.string.err_summary_empty)); return; }
        boolean expert = "expert".equals(job.arg2);
        String prompt = Gemini.questionPrompt(base, job.arg, Settings.summaryLanguage(this), expert, Settings.aboutMe(this), null,
                webReady());
        // (Prompt wurde oben für den aktiven Anbieter gebaut)
        long maxMs = Math.max(base.durationMs, base.segments.get(base.segments.size() - 1).startMs) + 60_000;
        final Transcript.Answer[] ans = new Transcript.Answer[1];
        Accept acceptAnswer = (reply, oc) -> {
            ans[0] = expert ? Gemini.parseExpert(reply, job.arg) : Gemini.parseAnswer(reply.text, job.arg, maxMs);
            return ans[0] != null;
        };
        Outcome o = expert ? callExpert(prompt, acceptAnswer) : callText(prompt, Gemini.answerSchema(), 0.1, acceptAnswer);
        if (cancelCurrent) { restore(job, null); return; }
        Transcript cur = Store.get(this, job.id);
        if (cur == null) return;
        Transcript n = Store.copy(cur);
        n.status = Transcript.SUMMARY.equals(job.prevStatus) ? Transcript.DONE : job.prevStatus;
        if (o.error == null && ans[0] != null) {
            ans[0].askedMs = System.currentTimeMillis();
            n.answers.add(0, ans[0]);
            while (n.answers.size() > Transcript.MAX_QA) n.answers.remove(n.answers.size() - 1);
        } else {
            n.error = getString(R.string.err_ask_failed) + (o.error != null ? " " + o.error : "");
        }
        Store.save(this, n);
    }

    private void translate(Job job) {
        Transcript base = Store.get(this, job.id);
        if (base == null) return;
        Transcript.Translation tr = new Transcript.Translation();
        int total = base.segments.size();
        for (int i = 0; i < total; i += Gemini.TRANSLATE_BATCH) {
            if (cancelCurrent) { restore(job, null); return; }
            setLive(job.id, R.string.st_translate, total == 0 ? -1 : (int) (100L * i / total));
            List<String> lines = new ArrayList<>();
            for (int k = i; k < Math.min(total, i + Gemini.TRANSLATE_BATCH); k++) lines.add(base.segments.get(k).text);
            String trPrompt = Gemini.translatePrompt(lines, job.arg);
            final List<String>[] got = new List[1];
            Outcome o = callText(trPrompt, Gemini.linesSchema(), 0, (reply, oc) -> {
                got[0] = Gemini.parseLines(reply.text, lines.size());
                return got[0] != null;
            });
            if (cancelCurrent) { restore(job, null); return; }
            if (o.error != null || got[0] == null) {
                restore(job, getString(R.string.err_translate_failed) + (o.error != null ? " " + o.error : ""));
                return;
            }
            tr.segments.addAll(got[0]);
        }
        if (base.summary != null) {
            String tsPrompt = Gemini.translateSummaryPrompt(base.summary, job.arg);
            Outcome o = callText(tsPrompt, Gemini.summarySchema(), 0, (reply, oc) -> {
                oc.summary = Gemini.parseSummary(reply.text);
                return oc.summary != null;
            });
            if (cancelCurrent) { restore(job, null); return; }
            if (o.summary != null) tr.summary = o.summary;
        }
        Transcript cur = Store.get(this, job.id);
        if (cur == null) return;
        if (cur.segments.size() != tr.segments.size()) {
            restore(job, getString(R.string.err_translate_changed));
            return;
        }
        Transcript n = Store.copy(cur);
        n.translations.remove(job.arg);
        while (n.translations.size() >= Transcript.MAX_TRANSLATIONS) n.translations.remove(n.translations.keySet().iterator().next());
        n.translations.put(job.arg, tr);
        n.status = Transcript.SUMMARY.equals(job.prevStatus) ? Transcript.DONE : job.prevStatus;
        n.error = "";
        Store.save(this, n);
    }

    private void folderSummary(Job job) {
        Transcript base = Store.get(this, job.id);
        if (base == null) return;
        Folders folders = Store.folders(this);
        Set<String> ids = folders.subtree(folders.clean(job.arg));
        List<Transcript> items = new ArrayList<>();
        for (Transcript x : Store.all(this)) {
            if (x.id.equals(job.id) || x.aggregate || x.isWorking() || !ids.contains(x.folder)) continue;
            if (x.segments.isEmpty() && (x.summary == null || x.summary.isEmpty())) continue;
            items.add(x);
            if (items.size() >= MAX_FOLDER_ITEMS) break;
        }
        Transcript t = Store.copy(base);
        if (items.isEmpty()) {
            t.status = Transcript.ERROR;
            t.error = getString(R.string.err_folder_empty);
            persist(t);
            return;
        }
        t.status = Transcript.SUMMARY;
        persist(t);
        setLive(t.id, R.string.st_folder, -1);
        notifyWork(t.id, getString(R.string.st_folder), -1);
        String name = job.arg.isEmpty() ? getString(R.string.folder_all) : folders.pathText(job.arg);
        String fPrompt = Gemini.folderPrompt(name, items, job.arg2, Settings.summaryLanguage(this));
        List<String> tags = new ArrayList<>();
        Outcome o = callText(fPrompt, Gemini.summarySchema(), 0, (reply, oc) -> {
            oc.summary = Gemini.parseSummary(reply.text);
            if (oc.summary != null) tags.addAll(Gemini.parseTags(reply.text));
            return oc.summary != null;
        });
        if (cancelCurrent) { markCanceled(t.id); return; }
        if (o.error != null || o.summary == null) {
            t.status = Transcript.ERROR;
            t.error = o.error != null ? o.error : getString(R.string.err_summary_failed);
            persist(t);
            notifyDone(t, false);
            return;
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.GERMANY);
        t.segments.clear();
        for (Transcript x : items) t.segments.add(new Transcript.Segment(0, "", x.title + " (" + df.format(new java.util.Date(x.createdMs)) + ")"));
        t.summary = o.summary;
        t.tags.clear();
        t.tags.addAll(tags);
        t.status = Transcript.DONE;
        t.error = "";
        t.model = o.model;
        persist(t);
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

    // ------------------------------------------------------------------ Gemini-Aufruf mit Rückfall

    private static final class Outcome {
        Gemini.Segments segments;
        Transcript.Summary summary;
        boolean truncated;
        String model = "";
        String error;
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
        return callWith(Ai.GEMINI, req, accept);
    }

    private boolean textReady() {
        return Ai.GEMINI.equals(provider) ? client != null : ai != null;
    }

    private boolean pdfReady() {
        return Ai.canPdf(provider) && textReady();
    }

    private boolean webReady() {
        return Ai.canWeb(provider);
    }

    /** Text mit festem Antwortschema beim aktiven Anbieter. */
    private Outcome callText(String prompt, String schema, double temperature, Accept accept) {
        if (!Ai.GEMINI.equals(provider) && ai != null) {
            AiClient a = ai;
            return callWith(provider, m -> a.json(Ai.request(provider, m, prompt, schema, null, false).prefix), accept);
        }
        String body = Gemini.textRequest(prompt, schema, temperature);
        return call(m -> client.json(m, body), accept);
    }

    /** Bild oder PDF lesen (Schema wie beim Bildlesen). */
    private Outcome callAttach(String prompt, String schema, File file, String mime, Accept accept) {
        if (!Ai.GEMINI.equals(provider) && ai != null && (Ai.JPEG.equals(mime) || Ai.canPdf(provider))) {
            AiClient a = ai;
            return callWith(provider, m -> a.withFile(Ai.request(provider, m, prompt, schema, mime, false), file), accept);
        }
        if (Ai.PDF.equals(mime)) return call(m -> client.pdf(m, prompt, file), accept);
        return call(m -> client.image(m, prompt, file), accept);
    }

    /** Einschätzung: mit Websuche, wenn der Anbieter eine hat. */
    private Outcome callExpert(String prompt, Accept accept) {
        if (!Ai.GEMINI.equals(provider) && ai != null) {
            AiClient a = ai;
            boolean web = Ai.canWeb(provider);
            return callWith(provider, m -> a.json(Ai.request(provider, m, prompt, null, null, web).prefix), accept);
        }
        String body = Gemini.expertRequest(prompt);
        return call(m -> client.json(m, body), accept);
    }

    /**
     * Modelle des Anbieters der Reihe nach (bestes zuerst, gesperrte zuletzt). Fehler 404/408/429/5xx → nächstes
     * Modell, dauerhafte (400/401/403) brechen sofort ab. Höchstens 2 Runden.
     */
    private Outcome callWith(String p, Request req, Accept accept) {
        Outcome o = new Outcome();
        String[] list = Ai.models(p);
        int[] order = Settings.modelOrder(this, p);
        int models = list.length;
        String lastDetail = "";
        boolean quota = false, network = false;
        for (int attempt = 0; attempt < models * 2; attempt++) {
            if (cancelCurrent) { o.error = getString(R.string.canceled); return o; }
            int mi = order[attempt % models];
            String model = list[mi];
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
                Gemini.Reply reply = Ai.reply(p, r.body);
                if (reply != null && !reply.blockReason.isEmpty()) {
                    o.error = getString(R.string.err_blocked, reply.blockReason);
                    return o;
                }
                if (reply != null && accept.accept(reply, o)) {
                    o.model = model;
                    Settings.clearModelCooldown(this, p, mi);
                    return o;
                }
                lastDetail = "200 · " + model + (reply != null && !reply.finishReason.isEmpty() ? " · " + reply.finishReason : "");
                continue;
            }
            String status = Ai.errorStatus(p, r.body);
            lastDetail = r.code + (status.isEmpty() ? "" : " " + status) + " · " + model;
            if (r.code == 429) { quota = true; Settings.setModelCooldown(this, p, mi, 10 * 60_000L); }
            if (r.code == 404) Settings.setModelCooldown(this, p, mi, 24 * 3600_000L);
            if (!Ai.tryNextModel(r.code)) {
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

    private void goForeground(String text) {
        startForeground(NOTIF_WORK, workNotification(null, text, 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
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
        if (ok) TodoistSync.autoPush(this, t.id);
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
