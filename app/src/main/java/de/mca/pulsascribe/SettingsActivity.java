package de.mca.pulsascribe;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Transcript;

/** Schlüssel, Standardwerte, Design, Datenschutz. */
public final class SettingsActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        Design.apply(this);
        super.onCreate(state);
        setContentView(R.layout.activity_settings);
        findViewById(R.id.settings_back).setOnClickListener(v -> finish());

        updateKey();
        findViewById(R.id.row_key).setOnClickListener(v -> KeyDialog.show(this, this::updateKey));
        findViewById(R.id.row_key_get).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/app/apikey"))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(i);
            } catch (RuntimeException ignored) { }
        });

        TextView lang = findViewById(R.id.lang_value);
        lang.setText(Languages.label(Settings.language(this)));
        findViewById(R.id.row_lang).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.set_lang)
                .setSingleChoiceItems(Languages.LABELS, Languages.index(Settings.language(this)), (d, w) -> {
                    Settings.setLanguage(this, Languages.CODES[w]);
                    lang.setText(Languages.LABELS[w]);
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());

        TextView sumLang = findViewById(R.id.sum_lang_value);
        sumLang.setText(Languages.SUM_LABELS[Languages.summaryIndex(Settings.summaryLanguage(this))]);
        findViewById(R.id.row_sum_lang).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.set_sum_lang)
                .setSingleChoiceItems(Languages.SUM_LABELS, Languages.summaryIndex(Settings.summaryLanguage(this)), (d, w) -> {
                    Settings.setSummaryLanguage(this, Languages.SUM_CODES[w]);
                    sumLang.setText(Languages.SUM_LABELS[w]);
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());

        TextView tpl = findViewById(R.id.template_value);
        tpl.setText(de.mca.pulsascribe.core.Templates.label(Settings.template(this)));
        findViewById(R.id.row_template).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.start_template)
                .setSingleChoiceItems(de.mca.pulsascribe.core.Templates.LABELS,
                        de.mca.pulsascribe.core.Templates.index(Settings.template(this)), (d, w) -> {
                    Settings.setTemplate(this, de.mca.pulsascribe.core.Templates.IDS[w]);
                    tpl.setText(de.mca.pulsascribe.core.Templates.LABELS[w]);
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());

        TextView rate = findViewById(R.id.rate_value);
        rate.setText(Settings.SPEECH_LABELS[rateIndex()]);
        findViewById(R.id.row_rate).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.set_rate)
                .setSingleChoiceItems(Settings.SPEECH_LABELS, rateIndex(), (d, w) -> {
                    Settings.setSpeechRate(this, Settings.SPEECH_RATES[w]);
                    rate.setText(Settings.SPEECH_LABELS[w]);
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());
        findViewById(R.id.row_tts).setOnClickListener(v -> {
            try {
                startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
            } catch (RuntimeException e) {
                Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
            }
        });

        bindSwitch(R.id.row_diarize, R.id.sw_diarize, Settings.diarize(this), on -> Settings.setDiarize(this, on));
        bindSwitch(R.id.row_clean, R.id.sw_clean, Settings.cleanFillers(this), on -> Settings.setCleanFillers(this, on));
        bindSwitch(R.id.row_summary, R.id.sw_summary, Settings.summarize(this), on -> Settings.setSummarize(this, on));
        bindSwitch(R.id.row_keep, R.id.sw_keep, Settings.keepAudio(this), on -> Settings.setKeepAudio(this, on));

        TextView design = findViewById(R.id.design_value);
        design.setText(Design.ACADEMY.equals(Design.get(this)) ? R.string.design_academy : R.string.design_pulsa);
        findViewById(R.id.row_design).setOnClickListener(v -> {
            String[] labels = {getString(R.string.design_pulsa), getString(R.string.design_academy)};
            int sel = Design.ACADEMY.equals(Design.get(this)) ? 1 : 0;
            Ui.dialog(this).setTitle(R.string.design_title)
                    .setSingleChoiceItems(labels, sel, (d, w) -> {
                        d.dismiss();
                        String val = w == 1 ? Design.ACADEMY : Design.PULSA;
                        if (!val.equals(Design.get(this))) {
                            Design.set(this, val);
                            recreate();
                        }
                    }).setNegativeButton(R.string.cancel, null).show();
        });

        findViewById(R.id.row_privacy).setOnClickListener(v -> MainActivity.showHint(this));
        findViewById(R.id.row_delete_all).setOnClickListener(v -> {
            for (Transcript t : Store.all(this)) {
                if (t.isWorking()) {
                    Toast.makeText(this, R.string.busy_delete_all, Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Ui.dialog(this).setTitle(R.string.delete_all_title).setMessage(R.string.delete_all_text)
                    .setPositiveButton(R.string.delete, (d, w) -> {
                        for (Transcript t : Store.all(this)) Store.delete(this, t.id);
                        Toast.makeText(this, R.string.deleted_all, Toast.LENGTH_SHORT).show();
                    }).setNegativeButton(R.string.cancel, null).show();
        });

        findViewById(R.id.row_backup).setOnClickListener(v -> backupDialog());
        findViewById(R.id.row_restore).setOnClickListener(v -> {
            if (Store.anyWorking(this) || RecordService.isRecording()) {
                Toast.makeText(this, R.string.backup_busy, Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, REQ_RESTORE); } catch (RuntimeException e) { Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show(); }
        });

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.version_value)).setText(version);
    }

    private static final int REQ_BACKUP = 21, REQ_RESTORE = 22;
    private char[] pendingPw;
    private boolean pendingMedia;
    private android.app.AlertDialog progressDialog;

    private android.widget.EditText pwField(int hint) {
        android.widget.EditText e = Ui.input(this, "", 128, false);
        e.setHint(hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        return e;
    }

    private void backupDialog() {
        if (Store.anyWorking(this) || RecordService.isRecording()) {
            Toast.makeText(this, R.string.backup_busy, Toast.LENGTH_LONG).show();
            return;
        }
        android.widget.LinearLayout col = Ui.column(this);
        android.widget.EditText p1 = pwField(R.string.backup_pw), p2 = pwField(R.string.backup_pw2);
        Ui.add(col, p1);
        android.view.View gap = new android.view.View(this);
        gap.setMinimumHeight(Ui.dp(this, 8));
        Ui.add(col, gap);
        Ui.add(col, p2);
        Switch media = Ui.switchRow(this, R.string.backup_media, false);
        Ui.add(col, media);
        TextView warn = Ui.body(this, getString(R.string.backup_warn));
        warn.setTextSize(13);
        warn.setTextColor(Design.color(this, R.attr.pl_muted));
        Ui.add(col, warn);
        android.app.AlertDialog d = Ui.dialog(this).setTitle(R.string.backup_create).setView(col)
                .setPositiveButton(R.string.save, null).setNegativeButton(R.string.cancel, null).create();
        if (d.getWindow() != null) d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < de.mca.pulsascribe.core.Backup.MIN_PASSWORD) { p1.setError(getString(R.string.backup_pw_short)); return; }
            if (!a.equals(b)) { p2.setError(getString(R.string.backup_pw_mismatch)); return; }
            pendingPw = a.toCharArray();
            pendingMedia = media.isChecked();
            p1.setText("");
            p2.setText("");
            d.dismiss();
            String date = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(new java.util.Date());
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, getString(R.string.backup_file_name, date));
            try { startActivityForResult(i, REQ_BACKUP); } catch (RuntimeException e) { clearPw(); }
        }));
        d.show();
    }

    private void clearPw() {
        if (pendingPw != null) java.util.Arrays.fill(pendingPw, '\0');
        pendingPw = null;
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) { clearPw(); return; }
        Uri uri = data.getData();
        if (req == REQ_BACKUP && pendingPw != null) {
            char[] pw = pendingPw;
            pendingPw = null;
            boolean media = pendingMedia;
            runTask(R.string.backup_running, () -> {
                BackupManager.create(this, uri, pw, media, (dn, tot) -> { });
                return getString(R.string.backup_done);
            }, pw);
        } else if (req == REQ_RESTORE) {
            android.widget.LinearLayout col = Ui.column(this);
            android.widget.EditText p1 = pwField(R.string.backup_pw);
            Ui.add(col, p1);
            android.app.AlertDialog d = Ui.dialog(this).setTitle(R.string.backup_restore).setView(col)
                    .setPositiveButton(R.string.ok, (dd, w) -> {
                        char[] pw = p1.getText().toString().toCharArray();
                        p1.setText("");
                        runTask(R.string.restore_running, () -> {
                            BackupManager.Result r = BackupManager.restore(this, uri, pw, (dn, tot) -> { });
                            return getString(R.string.restore_done, r.added, r.skipped, r.rejected);
                        }, pw);
                    })
                    .setNegativeButton(R.string.cancel, null).create();
            if (d.getWindow() != null) d.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
            d.show();
        }
    }

    private interface Task {
        String run() throws Exception;
    }

    /** Läuft im Hintergrund mit einfachem Fortschrittsfenster; Passwort wird danach überschrieben. */
    private void runTask(int msg, Task task, char[] pw) {
        android.widget.LinearLayout col = Ui.column(this);
        android.widget.ProgressBar pb = new android.widget.ProgressBar(this);
        pb.setIndeterminate(true);
        Ui.add(col, pb);
        Ui.add(col, Ui.body(this, getString(msg)));
        progressDialog = Ui.dialog(this).setView(col).setCancelable(false).show();
        new Thread(() -> {
            String result;
            try {
                result = task.run();
            } catch (de.mca.pulsascribe.core.Backup.BadPasswordException e) {
                result = getString(R.string.restore_bad_pw);
            } catch (de.mca.pulsascribe.core.Backup.FormatException e) {
                result = getString(R.string.restore_bad_file);
            } catch (Exception e) {
                result = getString(R.string.backup_failed) + " (" + e.getClass().getSimpleName() + ")";
            } finally {
                java.util.Arrays.fill(pw, '\0');
            }
            String r = result;
            runOnUiThread(() -> {
                if (progressDialog != null) progressDialog.dismiss();
                progressDialog = null;
                if (!isFinishing()) Ui.dialog(this).setMessage(r).setPositiveButton(R.string.ok, null).show();
            });
        }, "backup").start();
    }

    private int rateIndex() {
        float r = Settings.speechRate(this);
        for (int i = 0; i < Settings.SPEECH_RATES.length; i++) if (Settings.SPEECH_RATES[i] == r) return i;
        return 1;
    }

    private interface OnToggle {
        void set(boolean on);
    }

    private void bindSwitch(int rowId, int switchId, boolean value, OnToggle t) {
        Switch s = findViewById(switchId);
        s.setChecked(value);
        s.setOnCheckedChangeListener((b, on) -> t.set(on));
        findViewById(rowId).setOnClickListener(v -> s.toggle());
    }

    private void updateKey() {
        ((TextView) findViewById(R.id.key_status)).setText(GeminiKey.has(this) ? R.string.set_key_set : R.string.set_key_missing);
    }
}
