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

        updateTodoist();
        findViewById(R.id.row_td_token).setOnClickListener(v -> TodoistDialog.show(this, this::updateTodoist));
        findViewById(R.id.row_td_get).setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(de.mca.pulsascribe.core.Todoist.TOKEN_URL))
                    .addCategory(Intent.CATEGORY_BROWSABLE);
            try {
                startActivity(i);
            } catch (RuntimeException ignored) { }
        });
        bindSwitch(R.id.row_td_auto, R.id.sw_td_auto, Settings.todoistAuto(this), on -> Settings.setTodoistAuto(this, on));
        findViewById(R.id.row_td_sync).setOnClickListener(v -> {
            if (!TodoistToken.has(this)) { TodoistDialog.show(this, this::updateTodoist); return; }
            Toast.makeText(this, R.string.td_syncing, Toast.LENGTH_SHORT).show();
            TodoistSync.pull(this, true, res -> { if (!isFinishing()) Toast.makeText(this, res, Toast.LENGTH_LONG).show(); });
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

        TextView lenV = findViewById(R.id.sum_len_value);
        lenV.setText(de.mca.pulsascribe.core.Templates.LENGTH_LABELS[de.mca.pulsascribe.core.Templates.lengthIndex(Settings.summaryLength(this))]);
        findViewById(R.id.row_sum_len).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.sum_length)
                .setSingleChoiceItems(de.mca.pulsascribe.core.Templates.LENGTH_LABELS,
                        de.mca.pulsascribe.core.Templates.lengthIndex(Settings.summaryLength(this)), (d, w) -> {
                    Settings.setSummaryLength(this, de.mca.pulsascribe.core.Templates.LENGTHS[w]);
                    lenV.setText(de.mca.pulsascribe.core.Templates.LENGTH_LABELS[w]);
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());

        sampleReader = new Reader(this, new Reader.Listener() {
            @Override
            public void onState(boolean speaking, int index) {
                ((TextView) findViewById(R.id.voice_test_title)).setText(speaking ? R.string.voice_test_stop : R.string.voice_test);
            }

            @Override
            public void onError(int messageRes) {
                Toast.makeText(SettingsActivity.this, messageRes, Toast.LENGTH_LONG).show();
            }
        });
        updateVoice();
        findViewById(R.id.row_voice_engine).setOnClickListener(v -> {
            String[] labels = {getString(R.string.voice_natural), getString(R.string.voice_device)};
            Ui.dialog(this).setTitle(R.string.voice_engine)
                    .setSingleChoiceItems(labels, Settings.naturalVoice(this) ? 0 : 1, (d, w) -> {
                        d.dismiss();
                        if (w == 0 && !GeminiKey.has(this)) { KeyDialog.show(this, this::updateVoice); }
                        Settings.setNaturalVoice(this, w == 0);
                        updateVoice();
                        playSample();
                    }).setNegativeButton(R.string.cancel, null).show();
        });
        findViewById(R.id.row_voice).setOnClickListener(v -> pickVoice());
        findViewById(R.id.row_voice_test).setOnClickListener(v -> {
            if (sampleReader.isSpeaking()) sampleReader.stop();
            else playSample();
        });

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
                        Store.emptyTrash(this);
                        android.webkit.CookieManager.getInstance().removeAllCookies(null);
                        android.webkit.WebStorage.getInstance().deleteAllData();
                        Toast.makeText(this, R.string.deleted_all, Toast.LENGTH_SHORT).show();
                    }).setNegativeButton(R.string.cancel, null).show();
        });

        findViewById(R.id.row_browser_clear).setOnClickListener(v -> BrowserActivity.clearLogins(this, null));
        findViewById(R.id.row_trash).setOnClickListener(v -> startActivity(new Intent(this, TrashActivity.class)));
        TextView trashDays = findViewById(R.id.trash_days_value);
        trashDays.setText(trashDaysLabels()[trashDaysIndex()]);
        findViewById(R.id.row_trash_days).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.trash_days)
                .setSingleChoiceItems(trashDaysLabels(), trashDaysIndex(), (d, w) -> {
                    Settings.setTrashDays(this, Settings.TRASH_DAYS[w]);
                    trashDays.setText(trashDaysLabels()[w]);
                    Store.purgeOld(this, Settings.TRASH_DAYS[w]);
                    updateTrash();
                    d.dismiss();
                }).setNegativeButton(R.string.cancel, null).show());

        findViewById(R.id.row_backup).setOnClickListener(v -> backupDialog());
        findViewById(R.id.row_restore).setOnClickListener(v -> {
            if (Store.anyWorking(this) || RecordService.isRecording()) {
                Toast.makeText(this, R.string.backup_busy, Toast.LENGTH_LONG).show();
                return;
            }
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, REQ_RESTORE); } catch (RuntimeException e) { Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show(); }
        });

        TextView engine = findViewById(R.id.engine_value);
        String[] engLabels = {getString(R.string.off_engine_gemini), getString(R.string.off_engine_whisper)};
        engine.setText(engLabels["whisper".equals(Settings.engine(this)) ? 1 : 0]);
        findViewById(R.id.row_engine).setOnClickListener(v -> Ui.dialog(this).setTitle(R.string.off_engine)
                .setSingleChoiceItems(engLabels, "whisper".equals(Settings.engine(this)) ? 1 : 0, (d, w) -> {
                    d.dismiss();
                    if (w == 1 && !OfflineModels.anyInstalled(this)) {
                        Toast.makeText(this, R.string.off_need_model, Toast.LENGTH_LONG).show();
                        return;
                    }
                    Settings.setEngine(this, w == 1 ? "whisper" : "gemini");
                    engine.setText(engLabels[w]);
                }).setNegativeButton(R.string.cancel, null).show());
        buildModels();

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

    // ------------------------------------------------------------------ Offline-Modelle

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Set<String> verifying = new java.util.HashSet<>();
    private final Runnable modelTick = new Runnable() {
        @Override
        public void run() {
            buildModels();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        updateTrash();
        handler.post(modelTick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(modelTick);
        if (sampleReader != null) sampleReader.stop();
        super.onPause();
    }

    private void buildModels() {
        android.widget.LinearLayout box = findViewById(R.id.off_models);
        if (box == null) return;
        box.removeAllViews();
        String selected = OfflineModels.selected(this);
        for (de.mca.pulsascribe.core.Offline.Model m : de.mca.pulsascribe.core.Offline.MODELS) {
            android.widget.LinearLayout row = new android.widget.LinearLayout(this);
            row.setOrientation(android.widget.LinearLayout.VERTICAL);
            int p = Ui.dp(this, 16);
            row.setPadding(p, Ui.dp(this, 12), p, Ui.dp(this, 12));
            row.setBackgroundResource(R.drawable.settings_row);
            row.setClickable(true);
            row.setFocusable(true);
            TextView title = new TextView(this);
            title.setText(m.label);
            title.setTextSize(16);
            title.setTextColor(Design.color(this, R.attr.pl_text));
            TextView sub = new TextView(this);
            sub.setTextSize(13);
            sub.setTextColor(Design.color(this, R.attr.pl_muted));
            int st = OfflineModels.downloadState(this, m);
            boolean inst = OfflineModels.installed(this, m);
            String state;
            if (verifying.contains(m.id)) state = getString(R.string.off_verifying);
            else if (inst) state = getString(m.id.equals(selected) ? R.string.off_selected : R.string.off_installed);
            else if (st == 101) { state = getString(R.string.off_verifying); verify(m); }
            else if (st == -2) state = getString(R.string.off_failed);
            else if (st >= 0) state = getString(R.string.off_downloading, st);
            else state = getString(R.string.off_download);
            sub.setText(state);
            row.addView(title);
            row.addView(sub);
            row.setOnClickListener(v -> modelMenu(v, m));
            box.addView(row);
        }
    }

    private void verify(de.mca.pulsascribe.core.Offline.Model m) {
        if (!verifying.add(m.id)) return;
        new Thread(() -> {
            boolean ok = OfflineModels.verifyAndInstall(this, m);
            runOnUiThread(() -> {
                verifying.remove(m.id);
                if (ok) {
                    if (!OfflineModels.installed(this, de.mca.pulsascribe.core.Offline.model(OfflineModels.selected(this)))) {
                        OfflineModels.setSelected(this, m.id);
                    }
                } else if (!isFinishing()) {
                    Toast.makeText(this, R.string.off_bad_hash, Toast.LENGTH_LONG).show();
                }
                buildModels();
            });
        }, "verify").start();
    }

    private void modelMenu(android.view.View anchor, de.mca.pulsascribe.core.Offline.Model m) {
        boolean inst = OfflineModels.installed(this, m);
        int st = OfflineModels.downloadState(this, m);
        if (!inst && (st < 0) && !verifying.contains(m.id)) {
            Ui.dialog(this).setMessage(getString(R.string.off_dl_confirm, m.label))
                    .setPositiveButton(R.string.off_download, (d, w) -> {
                        if (OfflineModels.download(this, m) < 0) Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
                        buildModels();
                    })
                    .setNegativeButton(R.string.cancel, null).show();
            return;
        }
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        if (inst) {
            pm.getMenu().add(0, 1, 0, R.string.off_model_menu_use);
            pm.getMenu().add(0, 2, 0, R.string.off_model_menu_delete);
        } else if (st >= 0 && st <= 100) {
            pm.getMenu().add(0, 3, 0, R.string.off_model_menu_cancel);
        }
        pm.setOnMenuItemClickListener(mi -> {
            if (mi.getItemId() == 1) OfflineModels.setSelected(this, m.id);
            else if (mi.getItemId() == 2) OfflineModels.delete(this, m);
            else OfflineModels.cancelDownload(this, m);
            if (!OfflineModels.anyInstalled(this)) Settings.setEngine(this, "gemini");
            buildModels();
            return true;
        });
        pm.show();
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

    private String[] trashDaysLabels() {
        String[] l = new String[Settings.TRASH_DAYS.length];
        for (int i = 0; i < l.length; i++) {
            int d = Settings.TRASH_DAYS[i];
            l[i] = d == 0 ? getString(R.string.trash_never) : getResources().getQuantityString(R.plurals.trash_after_days, d, d);
        }
        return l;
    }

    private int trashDaysIndex() {
        int d = Settings.trashDays(this);
        for (int i = 0; i < Settings.TRASH_DAYS.length; i++) if (Settings.TRASH_DAYS[i] == d) return i;
        return 1;
    }

    private void updateTrash() {
        int n = Store.trashList(this).size();
        ((TextView) findViewById(R.id.trash_status)).setText(n == 0 ? getString(R.string.trash_empty_short)
                : getResources().getQuantityString(R.plurals.trash_summary, n, n,
                android.text.format.Formatter.formatShortFileSize(this, Store.trashBytes(this))));
    }

    private Reader sampleReader;

    @Override
    protected void onDestroy() {
        if (sampleReader != null) sampleReader.release();
        super.onDestroy();
    }


    private void playSample() {
        sampleReader.stop();
        sampleReader.speak(Reader.of(getString(R.string.voice_sample)), "de");
    }

    private java.util.Locale sampleLocale() {
        return java.util.Locale.GERMANY;
    }

    private void updateVoice() {
        boolean nat = Settings.naturalVoice(this) && GeminiKey.has(this);
        ((TextView) findViewById(R.id.voice_engine_value)).setText(nat ? R.string.voice_natural_short : R.string.voice_device_short);
        ((TextView) findViewById(R.id.voice_engine_sub)).setText(nat ? R.string.voice_natural_sub : R.string.voice_device_sub);
        String dv = Settings.deviceVoice(this);
        ((TextView) findViewById(R.id.voice_value)).setText(nat
                ? de.mca.pulsascribe.core.Speech.VOICE_LABELS[de.mca.pulsascribe.core.Speech.voiceIndex(Settings.voice(this))]
                : dv.isEmpty() ? getString(R.string.voice_auto) : dv);
    }

    private void pickVoice() {
        if (Settings.naturalVoice(this) && GeminiKey.has(this)) {
            Ui.dialog(this).setTitle(R.string.voice_pick)
                    .setSingleChoiceItems(de.mca.pulsascribe.core.Speech.VOICE_LABELS,
                            de.mca.pulsascribe.core.Speech.voiceIndex(Settings.voice(this)), (d, w) -> {
                        Settings.setVoice(this, de.mca.pulsascribe.core.Speech.VOICES[w]);
                        updateVoice();
                        playSample(); // Dialog bleibt offen: weitere Stimmen anhören
                    })
                    .setPositiveButton(R.string.ok, null).show();
            return;
        }
        if (!sampleReader.isReady()) {
            Toast.makeText(this, R.string.read_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        java.util.List<android.speech.tts.Voice> vs = sampleReader.voices(sampleLocale());
        String[] labels = new String[vs.size() + 1];
        labels[0] = getString(R.string.voice_auto);
        int sel = 0;
        String cur = Settings.deviceVoice(this);
        for (int i = 0; i < vs.size(); i++) {
            android.speech.tts.Voice x = vs.get(i);
            String q = x.getQuality() >= android.speech.tts.Voice.QUALITY_VERY_HIGH ? getString(R.string.voice_q_very_high)
                    : x.getQuality() >= android.speech.tts.Voice.QUALITY_HIGH ? getString(R.string.voice_q_high) : getString(R.string.voice_q_normal);
            labels[i + 1] = getString(R.string.voice_device_item, i + 1, q,
                    getString(x.isNetworkConnectionRequired() ? R.string.voice_online : R.string.voice_offline), x.getLocale().toLanguageTag());
            if (x.getName().equals(cur)) sel = i + 1;
        }
        Ui.dialog(this).setTitle(R.string.voice_pick)
                .setSingleChoiceItems(labels, sel, (d, w) -> {
                    Settings.setDeviceVoice(this, w == 0 ? "" : vs.get(w - 1).getName());
                    updateVoice();
                    playSample();
                })
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.voice_more, (d, w) -> {
                    try {
                        startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
                    } catch (RuntimeException e) {
                        Toast.makeText(this, R.string.no_app, Toast.LENGTH_SHORT).show();
                    }
                }).show();
    }

    private void updateTodoist() {
        ((TextView) findViewById(R.id.td_status)).setText(TodoistToken.has(this) ? R.string.td_status_on : R.string.td_status_off);
    }

    private void updateKey() {
        ((TextView) findViewById(R.id.key_status)).setText(GeminiKey.has(this) ? R.string.set_key_set : R.string.set_key_missing);
    }
}
