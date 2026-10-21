package de.mca.pulsascribe;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;

/** Gespräch aufnehmen: Einverständnis bestätigen, Optionen wählen, aufnehmen, beenden → Transkription. */
public final class RecordActivity extends Activity {
    static final String EXTRA_STOP = "stop";
    private static final int REQ_MIC = 7;

    private View setup, live, secondaryRow, dot;
    private CheckBox consent;
    private OptionsForm form;
    private Button primary, pause;
    private TextView timer, state, titleText;
    private ProgressBar level;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private boolean wasRecording;
    private String startedId;

    @Override
    protected void onCreate(Bundle state0) {
        Design.apply(this);
        super.onCreate(state0);
        setContentView(R.layout.activity_record);
        setup = findViewById(R.id.rec_setup);
        live = findViewById(R.id.rec_live);
        secondaryRow = findViewById(R.id.rec_secondary_row);
        dot = findViewById(R.id.rec_dot);
        consent = findViewById(R.id.rec_consent);
        primary = findViewById(R.id.rec_primary);
        pause = findViewById(R.id.rec_pause);
        timer = findViewById(R.id.rec_timer);
        state = findViewById(R.id.rec_state);
        titleText = findViewById(R.id.rec_title_text);
        level = findViewById(R.id.rec_level);
        LinearLayout col = findViewById(R.id.rec_form);
        String date = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(new Date());
        String folder = getIntent().getStringExtra(MainActivity.EXTRA_FOLDER);
        if (folder == null) folder = Settings.lastFolder(this); // Kachel, Widget: zuletzt geöffneter Ordner
        form = new OptionsForm(this, col, getString(R.string.rec_default_title, date), folder, true);

        findViewById(R.id.rec_back).setOnClickListener(v -> finish());
        primary.setOnClickListener(v -> {
            if (RecordService.isRecording()) stop();
            else begin();
        });
        pause.setOnClickListener(v -> RecordService.command(this,
                RecordService.paused ? RecordService.ACTION_RESUME : RecordService.ACTION_PAUSE));
        findViewById(R.id.rec_discard).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.rec_discard_title)
                .setMessage(R.string.rec_discard_text)
                .setPositiveButton(R.string.rec_discard, (d, w) -> {
                    RecordService.command(this, RecordService.ACTION_DISCARD);
                    startedId = null;
                })
                .setNegativeButton(R.string.cancel, null).show());
        handleStopExtra(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleStopExtra(intent);
    }

    private void handleStopExtra(Intent i) {
        if (i != null && i.getBooleanExtra(EXTRA_STOP, false) && RecordService.isRecording()) {
            i.removeExtra(EXTRA_STOP);
            stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (RecordService.isRecording()) startedId = RecordService.currentId;
        tick();
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    private void begin() {
        if (!consent.isChecked()) {
            Toast.makeText(this, R.string.rec_need_consent, Toast.LENGTH_SHORT).show();
            return;
        }
        if (getFilesDir().getUsableSpace() < RecordService.MIN_FREE_START) {
            Toast.makeText(this, R.string.rec_low_space, Toast.LENGTH_LONG).show();
            return;
        }
        if (!Settings.hintAccepted(this)) {
            Ui.dialog(this).setTitle(R.string.hint_title).setMessage(R.string.hint_text)
                    .setPositiveButton(R.string.hint_ok, (d, w) -> { Settings.setHintAccepted(this); begin(); })
                    .setNegativeButton(R.string.cancel, null).show();
            return;
        }
        if (!AiKeys.ready(this)) {
            Ui.dialog(this).setTitle(R.string.key_title).setMessage(R.string.start_need_key)
                    .setPositiveButton(R.string.start_set_key, (d, w) -> KeyDialog.showActive(this, null))
                    .setNegativeButton(R.string.cancel, null).show();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !Settings.askedNotifications(this)
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Settings.setAskedNotifications(this);
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_MIC + 1);
            return;
        }
        if (RecordService.isRecording()) {
            Toast.makeText(this, R.string.rec_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        Transcript t = form.build();
        t.recorded = true;
        t.source = getString(R.string.rec_title);
        t.status = Transcript.RECORDING;
        Store.save(this, t);
        startedId = t.id;
        try {
            RecordService.start(this, t.id);
        } catch (RuntimeException e) {
            Store.delete(this, t.id);
            startedId = null;
            Toast.makeText(this, R.string.rec_failed, Toast.LENGTH_LONG).show();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        handler.postDelayed(tick, 150);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        if (req == REQ_MIC) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) begin();
            else Toast.makeText(this, R.string.rec_need_mic, Toast.LENGTH_LONG).show();
        } else if (req == REQ_MIC + 1) {
            begin(); // Benachrichtigungen sind optional
        }
    }

    private void stop() {
        RecordService.command(this, RecordService.ACTION_STOP);
        tick();
    }

    private void tick() {
        handler.removeCallbacks(tick);
        boolean rec = RecordService.isRecording();
        setup.setVisibility(rec ? View.GONE : View.VISIBLE);
        live.setVisibility(rec ? View.VISIBLE : View.GONE);
        secondaryRow.setVisibility(rec && !RecordService.finishing ? View.VISIBLE : View.GONE);
        if (rec) {
            wasRecording = true;
            boolean p = RecordService.paused, fin = RecordService.finishing;
            timer.setText(Times.clock(RecordService.recordedMs));
            state.setText(fin ? R.string.rec_finishing : p ? R.string.rec_paused : R.string.rec_running);
            dot.setAlpha(p || fin ? 0.3f : (System.currentTimeMillis() / 600 % 2 == 0 ? 1f : 0.45f));
            level.setProgress(p ? 0 : Math.round(RecordService.level * 100));
            pause.setText(p ? R.string.rec_resume : R.string.rec_pause);
            primary.setText(R.string.rec_stop_transcribe);
            primary.setEnabled(!fin);
            Transcript t = Store.get(this, RecordService.currentId);
            titleText.setText(t == null ? "" : t.title);
            timer.setContentDescription(getString(R.string.st_recording, Times.clock(RecordService.recordedMs)));
            handler.postDelayed(tick, 200);
        } else {
            primary.setText(R.string.rec_start);
            primary.setEnabled(true);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            if (wasRecording) {
                wasRecording = false;
                onFinished();
            } else if (startedId != null) {
                Transcript t = Store.get(this, startedId);
                if (t != null && Transcript.RECORDING.equals(t.status)) {
                    handler.postDelayed(tick, 200); // Dienst startet noch
                } else if (t == null && RecordService.stopReason != 0) {
                    Toast.makeText(this, RecordService.stopReason, Toast.LENGTH_LONG).show();
                    RecordService.stopReason = 0;
                    startedId = null;
                }
            }
        }
    }

    /** Aufnahme beendet: Transkription starten und das Transkript öffnen. */
    private void onFinished() {
        int reason = RecordService.stopReason;
        if (reason != 0) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show();
            RecordService.stopReason = 0;
        }
        String id = RecordService.Pending.take();
        if (id != null) {
            Transcript t = Store.get(this, id);
            if (t != null && Transcript.QUEUED.equals(t.status)) {
                try {
                    TranscribeService.start(this, id, null);
                } catch (RuntimeException e) {
                    RecordService.Pending.set(id);
                }
                startActivity(new Intent(this, TranscriptActivity.class).putExtra(TranscribeService.EXTRA_ID, id));
            }
            finish();
        } else if (startedId != null && Store.get(this, startedId) != null) {
            // Fehler beim Speichern: Eintrag mit Fehlermeldung bleibt in der Liste
            finish();
        }
        startedId = null;
    }
}
