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

        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.version_value)).setText(version);
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
