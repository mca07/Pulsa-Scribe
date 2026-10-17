package de.mca.pulsascribe;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import de.mca.pulsascribe.core.Folders;
import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Templates;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/**
 * Gemeinsame Felder für „Datei transkribieren“, „Aufnahme“ und „Teilen → Zusammenfassen“:
 * Titel, Ordner, Sprache, Vorlage, Optionen, Begriffe.
 */
final class OptionsForm {
    private final EditText title, terms;
    private final Switch diarize, summary, clean, readAfter;
    private String lang, template, folder, engine, length;
    private final String fallbackTitle;
    private final boolean audio;

    OptionsForm(Activity a, LinearLayout col, String initialTitle, String initialFolder, boolean audio) {
        this.audio = audio;
        fallbackTitle = initialTitle;
        Folders folders = Store.folders(a);
        folder = folders.clean(initialFolder);

        Ui.add(col, Ui.label(a, R.string.start_name));
        title = Ui.input(a, initialTitle, Transcript.MAX_TITLE, false);
        Ui.add(col, title);

        Ui.add(col, Ui.label(a, R.string.folder_move_to));
        TextView folderRow = Ui.choiceRow(a, folderLabel(a, folders, folder));
        folderRow.setOnClickListener(v -> FolderPicker.show(a, a.getString(R.string.folder_move_to), null, picked -> {
            folder = picked;
            folderRow.setText(folderLabel(a, Store.folders(a), picked));
        }));
        Ui.add(col, folderRow);

        Ui.add(col, Ui.label(a, R.string.start_lang));
        lang = Settings.language(a);
        TextView langRow = Ui.choiceRow(a, Languages.label(lang));
        langRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.start_lang)
                .setSingleChoiceItems(Languages.LABELS, Languages.index(lang), (d, w) -> {
                    lang = Languages.CODES[w];
                    langRow.setText(Languages.LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, langRow);

        engine = Settings.engine(a);
        if (audio) {
            Ui.add(col, Ui.label(a, R.string.off_engine));
            String[] engLabels = {a.getString(R.string.off_engine_gemini), a.getString(R.string.off_engine_whisper)};
            TextView engRow = Ui.choiceRow(a, engLabels["whisper".equals(engine) ? 1 : 0]);
            engRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.off_engine)
                    .setSingleChoiceItems(engLabels, "whisper".equals(engine) ? 1 : 0, (d, w) -> {
                        d.dismiss();
                        if (w == 1 && !OfflineModels.anyInstalled(a)) {
                            Ui.dialog(a).setMessage(R.string.off_need_model)
                                    .setPositiveButton(R.string.settings, (d2, w2) -> a.startActivity(new android.content.Intent(a, SettingsActivity.class)))
                                    .setNegativeButton(R.string.cancel, null).show();
                            return;
                        }
                        engine = w == 1 ? "whisper" : "gemini";
                        engRow.setText(engLabels[w]);
                        diarizeRowState();
                    }).show());
            Ui.add(col, engRow);
            if ("whisper".equals(engine) && !OfflineModels.anyInstalled(a)) {
                engine = "gemini";
                engRow.setText(engLabels[0]);
            }
        }

        Ui.add(col, Ui.label(a, R.string.start_template));
        template = audio ? Settings.template(a) : Templates.POST;
        TextView tplRow = Ui.choiceRow(a, Templates.label(template));
        tplRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.start_template)
                .setSingleChoiceItems(Templates.LABELS, Templates.index(template), (d, w) -> {
                    template = Templates.IDS[w];
                    tplRow.setText(Templates.LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, tplRow);

        Ui.add(col, Ui.label(a, R.string.sum_length));
        length = Settings.summaryLength(a);
        TextView lenRow = Ui.choiceRow(a, Templates.LENGTH_LABELS[Templates.lengthIndex(length)]);
        lenRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.sum_length)
                .setSingleChoiceItems(Templates.LENGTH_LABELS, Templates.lengthIndex(length), (d, w) -> {
                    length = Templates.LENGTHS[w];
                    lenRow.setText(Templates.LENGTH_LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, lenRow);

        View gap = new View(a);
        gap.setMinimumHeight(Ui.dp(a, 6));
        Ui.add(col, gap);
        diarize = Ui.switchRow(a, R.string.start_diarize, Settings.diarize(a));
        summary = Ui.switchRow(a, R.string.start_summary, audio ? Settings.summarize(a) : true);
        clean = Ui.switchRow(a, R.string.start_clean, Settings.cleanFillers(a));
        readAfter = Ui.switchRow(a, R.string.share_read_after, !audio);
        diarizeRowState();
        if (audio) {
            Ui.add(col, diarize);
            Ui.add(col, summary);
            Ui.add(col, clean);
        } else {
            Ui.add(col, readAfter);
        }
        if (audio) {
            Ui.add(col, Ui.label(a, R.string.start_terms));
            terms = Ui.input(a, "", Transcript.MAX_TERMS, false);
            terms.setHint(R.string.start_terms_hint);
            Ui.add(col, terms);
            TextView sub = Ui.body(a, a.getString(R.string.start_terms_sub));
            sub.setTextSize(12);
            sub.setTextColor(Design.color(a, R.attr.pl_muted));
            Ui.add(col, sub);
        } else {
            terms = null;
        }
    }

    /** Offline gibt es keine Sprechererkennung. */
    private void diarizeRowState() {
        if (diarize == null) return;
        boolean off = "whisper".equals(engine);
        diarize.setEnabled(!off);
        if (off) diarize.setChecked(false);
    }

    static String folderLabel(Activity a, Folders f, String id) {
        return Folders.ROOT.equals(id) || !f.exists(id) ? a.getString(R.string.folder_top) : f.pathText(id);
    }

    boolean readAfter() {
        return !audio && readAfter.isChecked();
    }

    /** Neues Transkript mit den gewählten Optionen (noch nicht gespeichert). */
    Transcript build() {
        Transcript t = new Transcript();
        t.id = Store.newId();
        String tt = Text.line(title.getText().toString(), Transcript.MAX_TITLE);
        t.title = tt.isEmpty() ? fallbackTitle : tt;
        t.createdMs = System.currentTimeMillis();
        t.language = Languages.clean(lang);
        t.template = Templates.clean(template);
        t.summaryLength = Templates.cleanLength(length);
        t.folder = folder;
        t.engine = audio && "whisper".equals(engine) ? Transcript.ENGINE_WHISPER : Transcript.ENGINE_GEMINI;
        t.diarize = audio && diarize.isChecked() && !Transcript.ENGINE_WHISPER.equals(t.engine);
        t.summarize = !audio || summary.isChecked();
        t.cleanFillers = clean.isChecked();
        t.terms = terms == null ? "" : Text.line(terms.getText().toString(), Transcript.MAX_TERMS);
        return t;
    }
}
