package de.mca.pulsascribe;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/** Gemeinsame Felder für „Datei transkribieren“ und „Aufnahme“: Titel, Sprache, Optionen, Begriffe. */
final class OptionsForm {
    private final EditText title, terms;
    private final Switch diarize, summary, clean;
    private String lang;
    private final String fallbackTitle;

    OptionsForm(Activity a, LinearLayout col, String initialTitle) {
        fallbackTitle = initialTitle;
        Ui.add(col, Ui.label(a, R.string.start_name));
        title = Ui.input(a, initialTitle, Transcript.MAX_TITLE, false);
        Ui.add(col, title);
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
        View gap = new View(a);
        gap.setMinimumHeight(Ui.dp(a, 6));
        Ui.add(col, gap);
        diarize = Ui.switchRow(a, R.string.start_diarize, Settings.diarize(a));
        summary = Ui.switchRow(a, R.string.start_summary, Settings.summarize(a));
        clean = Ui.switchRow(a, R.string.start_clean, Settings.cleanFillers(a));
        Ui.add(col, diarize);
        Ui.add(col, summary);
        Ui.add(col, clean);
        Ui.add(col, Ui.label(a, R.string.start_terms));
        terms = Ui.input(a, "", Transcript.MAX_TERMS, false);
        terms.setHint(R.string.start_terms_hint);
        Ui.add(col, terms);
        TextView sub = Ui.body(a, a.getString(R.string.start_terms_sub));
        sub.setTextSize(12);
        sub.setTextColor(Design.color(a, R.attr.pl_muted));
        Ui.add(col, sub);
    }

    /** Neues Transkript mit den gewählten Optionen (noch nicht gespeichert). */
    Transcript build() {
        Transcript t = new Transcript();
        t.id = Store.newId();
        String tt = Text.line(title.getText().toString(), Transcript.MAX_TITLE);
        t.title = tt.isEmpty() ? fallbackTitle : tt;
        t.createdMs = System.currentTimeMillis();
        t.language = Languages.clean(lang);
        t.diarize = diarize.isChecked();
        t.summarize = summary.isChecked();
        t.cleanFillers = clean.isChecked();
        t.terms = Text.line(terms.getText().toString(), Transcript.MAX_TERMS);
        return t;
    }
}
