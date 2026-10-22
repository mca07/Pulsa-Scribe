package de.mca.pulsascribe;

import android.app.Activity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import de.mca.pulsascribe.core.Languages;
import de.mca.pulsascribe.core.Templates;
import de.mca.pulsascribe.core.Text;
import de.mca.pulsascribe.core.Transcript;

/** „Neu transkribieren …“: Optionen ändern und Text + Zusammenfassung neu erstellen (Ton bzw. Quelle bleibt). */
final class Retranscribe {
    private Retranscribe() {}

    /** Geht nur, wenn Ton bzw. Quelle noch da ist (nicht bei geteiltem Text). */
    static boolean possible(Activity a, Transcript t) {
        if (t.isWorking() || t.aggregate) return false;
        if (t.isAudio()) return (t.audioReady && Store.audioFile(a, t.id).isFile()) || !t.sourceUri.isEmpty() || t.parts.size() > 1;
        if (Transcript.KIND_IMAGE.equals(t.kind)) return Store.imageFile(a, t.id).isFile();
        if (Transcript.KIND_PDF.equals(t.kind)) return Store.pdfFile(a, t.id).isFile();
        return Transcript.KIND_WEB.equals(t.kind) && !t.url.isEmpty();
    }

    static void show(Activity a, String id) {
        Transcript t = Store.get(a, id);
        if (t == null) return;
        if (!possible(a, t)) {
            Toast.makeText(a, R.string.retr_impossible, Toast.LENGTH_LONG).show();
            return;
        }
        boolean audio = t.isAudio();
        boolean waiting = Transcript.WAITING.equals(t.status);
        LinearLayout col = Ui.column(a);
        TextView info = Ui.body(a, a.getString(waiting ? R.string.call_decide_info : R.string.retr_info));
        info.setTextSize(13);
        info.setTextColor(Design.color(a, R.attr.pl_muted));
        Ui.add(col, info);

        final String[] lang = {t.language};
        Ui.add(col, Ui.label(a, R.string.set_lang));
        TextView langRow = Ui.choiceRow(a, Languages.label(lang[0]));
        langRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.set_lang)
                .setSingleChoiceItems(Languages.LABELS, Languages.index(lang[0]), (d, w) -> {
                    lang[0] = Languages.CODES[w];
                    langRow.setText(Languages.LABELS[w]);
                    d.dismiss();
                }).show());
        Ui.add(col, langRow);

        Switch diarize = null, clean = null;
        EditText terms = null;
        final String[] engine = {t.engine};
        if (audio) {
            diarize = Ui.switchRow(a, R.string.start_diarize, t.diarize);
            Ui.add(col, diarize);
            clean = Ui.switchRow(a, R.string.start_clean, t.cleanFillers);
            Ui.add(col, clean);
            Ui.add(col, Ui.label(a, R.string.start_terms));
            terms = Ui.input(a, t.terms, Transcript.MAX_TERMS, false);
            terms.setHint(R.string.start_terms_hint);
            Ui.add(col, terms);
            if (OfflineModels.anyInstalled(a)) {
                String[] labels = {a.getString(R.string.off_engine_gemini), a.getString(R.string.off_engine_whisper)};
                Ui.add(col, Ui.label(a, R.string.off_engine));
                TextView engRow = Ui.choiceRow(a, labels[Transcript.ENGINE_WHISPER.equals(engine[0]) ? 1 : 0]);
                engRow.setOnClickListener(v -> Ui.dialog(a).setTitle(R.string.off_engine)
                        .setSingleChoiceItems(labels, Transcript.ENGINE_WHISPER.equals(engine[0]) ? 1 : 0, (d, w) -> {
                            engine[0] = w == 1 ? Transcript.ENGINE_WHISPER : Transcript.ENGINE_GEMINI;
                            engRow.setText(labels[w]);
                            d.dismiss();
                        }).show());
                Ui.add(col, engRow);
            }
        }

        final String[] tpl = {Templates.clean(t.template)};
        Ui.add(col, Ui.label(a, R.string.start_template));
        TextView tplRow = Ui.choiceRow(a, Templates.label(tpl[0]));
        tplRow.setOnClickListener(v -> {
            List<String> ids = Settings.templateIds(a, tpl[0]);
            Ui.dialog(a).setTitle(R.string.start_template)
                    .setSingleChoiceItems(Settings.templateLabels(ids), ids.indexOf(tpl[0]), (d, w) -> {
                        tpl[0] = ids.get(w);
                        tplRow.setText(Templates.label(tpl[0]));
                        d.dismiss();
                    }).show();
        });
        Ui.add(col, tplRow);

        android.widget.ScrollView sc = new android.widget.ScrollView(a);
        sc.addView(col);
        final Switch fd = diarize, fc = clean;
        final EditText ft = terms;
        android.app.AlertDialog.Builder b = Ui.dialog(a).setTitle(waiting ? R.string.call_decide_title : R.string.retr_title).setView(sc);
        if (waiting) {
            // Telefonat nicht gebraucht: in den Papierkorb (dort noch wiederherstellbar)
            b.setNeutralButton(R.string.call_discard, (d, w) -> {
                if (Store.trash(a, id)) Toast.makeText(a, R.string.call_discarded, Toast.LENGTH_SHORT).show();
                android.app.NotificationManager nm = a.getSystemService(android.app.NotificationManager.class);
                if (nm != null) nm.cancel(id.hashCode());
                if (a instanceof TranscriptActivity) a.finish();
            });
        }
        b.setPositiveButton(waiting ? R.string.call_transcribe : R.string.retr_go, (d, w) -> {
                    if (!Transcript.ENGINE_WHISPER.equals(engine[0]) && !AiKeys.ready(a)) {
                        KeyDialog.showActive(a, null);
                        return;
                    }
                    Transcript cur = Store.get(a, id);
                    if (cur == null || cur.isWorking()) return;
                    Transcript n = Store.copy(cur);
                    n.language = Languages.clean(lang[0]);
                    n.template = Templates.clean(tpl[0]);
                    if (fd != null) n.diarize = fd.isChecked();
                    if (fc != null) n.cleanFillers = fc.isChecked();
                    if (ft != null) n.terms = Text.line(ft.getText().toString(), Transcript.MAX_TERMS);
                    n.engine = engine[0];
                    // Text und alles, was daran hängt, neu; Ton, Teile und Zuschnitt bleiben
                    n.segments.clear();
                    n.chunksDone = 0;
                    n.summary = null;
                    n.sumModel = "";
                    n.translations.clear();
                    n.speakerNames.clear();
                    n.incomplete = false;
                    n.summarize = waiting ? cur.summarize : true;
                    n.error = "";
                    n.status = Transcript.QUEUED;
                    Store.save(a, n);
                    if (waiting) {
                        android.app.NotificationManager nm = a.getSystemService(android.app.NotificationManager.class);
                        if (nm != null) nm.cancel(id.hashCode());
                    }
                    try {
                        TranscribeService.start(a, id, null);
                    } catch (RuntimeException e) {
                        Transcript x = Store.copy(n);
                        x.status = Transcript.ERROR;
                        x.error = a.getString(R.string.err_unexpected, e.getClass().getSimpleName());
                        Store.save(a, x);
                    }
                })
                .setNegativeButton(R.string.cancel, null).show();
    }
}
