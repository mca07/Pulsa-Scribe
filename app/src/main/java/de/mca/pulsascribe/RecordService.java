package de.mca.pulsascribe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import de.mca.pulsascribe.core.Adts;
import de.mca.pulsascribe.core.Chunker;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Nimmt über das Mikrofon auf (Vordergrund-Dienst, Typ „microphone“, sichtbare Benachrichtigung).
 * Der Ton wird direkt als AAC (16 kHz, Mono) geschrieben und regelmäßig gesichert, damit bei einem
 * Absturz nur die letzten Sekunden fehlen. Gestoppt und transkribiert wird über die Aufnahme-Ansicht.
 */
public final class RecordService extends Service {
    static final String ACTION_START = "de.mca.pulsascribe.REC_START";
    static final String ACTION_PAUSE = "de.mca.pulsascribe.REC_PAUSE";
    static final String ACTION_RESUME = "de.mca.pulsascribe.REC_RESUME";
    static final String ACTION_STOP = "de.mca.pulsascribe.REC_STOP";
    static final String ACTION_DISCARD = "de.mca.pulsascribe.REC_DISCARD";

    static final int RATE = 16000;
    static final long MAX_MS = 6L * 3600 * 1000;       // 6 Stunden
    static final long MIN_FREE_START = 60L * 1024 * 1024;
    static final long MIN_FREE_RUN = 20L * 1024 * 1024;
    private static final String CH = "record";
    private static final int NOTIF = 3;

    // Zustand für die Anzeige (ein Prozess, eine Aufnahme)
    static volatile String currentId;
    static volatile boolean paused;
    static volatile long recordedMs;
    static volatile float level;         // 0..1
    static volatile boolean finishing;   // Stopp läuft
    static volatile int stopReason;      // Res-ID, wenn automatisch gestoppt

    private volatile boolean stopRequested, discardRequested;
    private Thread thread;
    private PowerManager.WakeLock wake;

    static boolean isRecording() {
        return currentId != null;
    }

    static void start(Context c, String id) {
        c.startForegroundService(new Intent(c, RecordService.class).setAction(ACTION_START)
                .putExtra(TranscribeService.EXTRA_ID, id));
    }

    static void command(Context c, String action) {
        c.startService(new Intent(c, RecordService.class).setAction(action));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CH, getString(R.string.ch_record), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_START.equals(action)) {
            String id = intent.getStringExtra(TranscribeService.EXTRA_ID);
            try {
                startForeground(NOTIF, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } catch (RuntimeException e) {
                // z. B. Mikrofon-Berechtigung fehlt oder Start aus dem Hintergrund
                failStart(id);
                return START_NOT_STICKY;
            }
            if (thread != null || !Text.isId(id) || Store.get(this, id) == null
                    || checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                if (thread == null) failStart(id);
                return START_NOT_STICKY;
            }
            currentId = id;
            paused = false;
            recordedMs = 0;
            level = 0;
            finishing = false;
            stopReason = 0;
            stopRequested = false;
            discardRequested = false;
            thread = new Thread(() -> run(id), "record");
            thread.start();
        } else if (ACTION_PAUSE.equals(action)) {
            if (thread != null) { paused = true; updateNotification(); }
        } else if (ACTION_RESUME.equals(action)) {
            if (thread != null) { paused = false; updateNotification(); }
        } else if (ACTION_STOP.equals(action)) {
            if (thread != null) { finishing = true; stopRequested = true; }
        } else if (ACTION_DISCARD.equals(action)) {
            if (thread != null) { finishing = true; discardRequested = true; stopRequested = true; }
        }
        if (thread == null) stopSelf();
        return START_NOT_STICKY;
    }

    private void failStart(String id) {
        if (Text.isId(id) && Store.get(this, id) != null) Store.delete(this, id);
        currentId = null;
        stopReason = R.string.rec_failed;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Store.progress();
    }

    @SuppressWarnings("MissingPermission") // geprüft in onStartCommand
    private void run(String id) {
        File file = Store.audioFile(this, id);
        AudioRecord ar = null;
        AudioPrep.Encoder enc = null;
        boolean ok = false;
        wake = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PulsaScribe:record");
        wake.setReferenceCounted(false);
        wake.acquire(MAX_MS + 600_000);
        try {
            int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            int size = Math.max(min, RATE / 2 * 2); // ≥ 0,5 s Puffer
            ar = new AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) throw new IOException("mic");
            enc = new AudioPrep.Encoder(file);
            enc.setInput(AudioPrep.Encoder.pcmFormat(RATE));
            ByteBuffer buf = ByteBuffer.allocateDirect(2048).order(ByteOrder.LITTLE_ENDIAN);
            ar.startRecording();
            boolean running = true;
            long samples = 0, lastFlush = 0, lastSpaceCheck = 0, lastNotif = 0;
            while (!stopRequested) {
                if (paused) {
                    if (running) { ar.stop(); running = false; }
                    level = 0;
                    SystemClock.sleep(80);
                    continue;
                }
                if (!running) { ar.startRecording(); running = true; }
                buf.clear();
                int n = ar.read(buf, 2048, AudioRecord.READ_BLOCKING);
                if (n < 0) throw new IOException("read " + n);
                if (n == 0) continue;
                buf.position(0);
                buf.limit(n);
                double sq = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    short v = buf.getShort(i);
                    sq += (double) v * v;
                }
                level = (float) Math.min(1.0, Math.sqrt(sq / Math.max(1, n / 2)) / 8000.0);
                enc.addPcm(buf);
                enc.pump(false);
                samples += n / 2;
                recordedMs = samples * 1000L / RATE;
                if (samples - lastFlush >= RATE * 5L) { enc.flush(); lastFlush = samples; }
                if (samples - lastNotif >= RATE * 10L) { lastNotif = samples; updateNotification(); }
                if (samples - lastSpaceCheck >= RATE * 30L) {
                    lastSpaceCheck = samples;
                    if (getFilesDir().getUsableSpace() < MIN_FREE_RUN) { stopReason = R.string.rec_stopped_space; break; }
                }
                if (recordedMs >= MAX_MS) { stopReason = R.string.rec_stopped_max; break; }
            }
            finishing = true;
            try { ar.stop(); } catch (RuntimeException ignored) { }
            if (discardRequested) return;
            // Encoder leeren
            enc.endOfInput();
            long guard = SystemClock.elapsedRealtime() + 10_000;
            while (!enc.done && SystemClock.elapsedRealtime() < guard) enc.pump(true);
            enc.close();
            int frames = enc.frames;
            if (frames == 0) throw new IOException("empty");
            Transcript base = Store.get(this, id);
            if (base == null) return; // inzwischen gelöscht
            Transcript t = Store.copy(base);
            t.durationMs = frames * (long) Adts.SAMPLES_PER_FRAME * 1000L / enc.outRate;
            t.cuts = Chunker.cuts(frames, enc.energy(), enc.outRate / (double) Adts.SAMPLES_PER_FRAME,
                    TranscribeService.CHUNK_SEC, TranscribeService.CHUNK_WINDOW_SEC);
            t.chunksDone = 0;
            t.audioReady = true;
            t.status = Transcript.QUEUED;
            t.error = "";
            Store.save(this, t);
            ok = true;
        } catch (IOException | RuntimeException e) {
            stopReason = R.string.rec_failed;
        } finally {
            if (ar != null) {
                try { ar.release(); } catch (RuntimeException ignored) { }
            }
            if (enc != null) enc.close();
            if (!ok) {
                Transcript cur = Store.get(this, id);
                if (discardRequested || cur == null || !file.isFile() || file.length() < 1024) {
                    if (cur != null) Store.delete(this, id);
                    else //noinspection ResultOfMethodCallIgnored
                        file.delete();
                } else {
                    // Fehler mitten in der Aufnahme: bisheriger Ton bleibt zum erneuten Versuch
                    Transcript n = Store.copy(cur);
                    n.audioReady = true;
                    n.cuts = new int[0];
                    n.status = Transcript.ERROR;
                    n.error = getString(R.string.err_rec_interrupted);
                    Store.save(this, n);
                }
            }
            if (wake != null && wake.isHeld()) wake.release();
            String done = currentId;
            if (ok && done != null) Pending.set(done); // vor dem Zurücksetzen, damit die Ansicht es sicher sieht
            currentId = null;
            paused = false;
            level = 0;
            finishing = false;
            Store.progress();
            new android.os.Handler(getMainLooper()).post(() -> {
                thread = null;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            });
        }
    }

    /** Merkt sich eine fertige Aufnahme, bis eine sichtbare Ansicht die Transkription startet. */
    static final class Pending {
        private static String id;

        static synchronized void set(String v) { id = v; }

        static synchronized String take() {
            String v = id;
            id = null;
            return v;
        }
    }

    private void updateNotification() {
        getSystemService(NotificationManager.class).notify(NOTIF, notification());
    }

    private Notification notification() {
        Intent open = new Intent(this, RecordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 30, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, RecordActivity.class).putExtra(RecordActivity.EXTRA_STOP, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent ps = PendingIntent.getActivity(this, 31, stop, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent toggle = new Intent(this, RecordService.class).setAction(paused ? ACTION_RESUME : ACTION_PAUSE);
        PendingIntent pt = PendingIntent.getService(this, 32, toggle, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = (paused ? getString(R.string.rec_paused) : getString(R.string.rec_running))
                + " · " + de.mca.pulsascribe.core.Times.clock(recordedMs);
        return new Notification.Builder(this, CH)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle(getString(R.string.rec_notif_title))
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, getString(paused ? R.string.rec_resume : R.string.rec_pause), pt).build())
                .addAction(new Notification.Action.Builder(null, getString(R.string.rec_stop), ps).build())
                .build();
    }
}
