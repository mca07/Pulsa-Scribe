package de.mca.pulsascribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.mca.pulsascribe.core.Times;
import de.mca.pulsascribe.core.Transcript;

/** Kleine Bausteine für Dialoge und Listen. Fremde Texte immer nur per setText (nie als HTML). */
final class Ui {
    private Ui() {}

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 22);
        l.setPadding(p, dp(c, 8), p, 0);
        return l;
    }

    static TextView label(Context c, int res) {
        TextView t = new TextView(c);
        t.setText(res);
        t.setTextSize(13);
        t.setTextColor(Design.color(c, R.attr.pl_muted));
        t.setPadding(0, dp(c, 12), 0, dp(c, 4));
        return t;
    }

    static TextView body(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Design.color(c, R.attr.pl_text));
        t.setLineSpacing(0, 1.15f);
        t.setPadding(0, dp(c, 6), 0, dp(c, 6));
        return t;
    }

    static EditText input(Context c, String value, int maxLen, boolean multiLine) {
        EditText e = new EditText(c);
        e.setText(value);
        e.setTextSize(16);
        e.setTextColor(Design.color(c, R.attr.pl_text));
        e.setHintTextColor(Design.color(c, R.attr.pl_muted));
        e.setBackgroundResource(R.drawable.input_bg);
        int p = dp(c, 12);
        e.setPadding(p, p, p, p);
        e.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maxLen)});
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | (multiLine ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (!multiLine) e.setSingleLine(true);
        e.setMinHeight(dp(c, 48));
        return e;
    }

    static Switch switchRow(Context c, int res, boolean checked) {
        Switch s = new Switch(c);
        s.setText(res);
        s.setTextSize(15);
        s.setTextColor(Design.color(c, R.attr.pl_text));
        s.setChecked(checked);
        s.setMinHeight(dp(c, 48));
        s.setGravity(Gravity.CENTER_VERTICAL);
        return s;
    }

    /** Auswahlzeile: Titel links, Wert rechts in Akzentfarbe. */
    static TextView choiceRow(Context c, String value) {
        TextView t = new TextView(c);
        t.setText(value);
        t.setTextSize(16);
        t.setTextColor(Design.color(c, R.attr.pl_accent));
        t.setMinHeight(dp(c, 48));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setBackgroundResource(R.drawable.input_bg);
        int p = dp(c, 12);
        t.setPadding(p, 0, p, 0);
        t.setClickable(true);
        t.setFocusable(true);
        return t;
    }

    static void add(LinearLayout parent, View v) {
        parent.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    static AlertDialog.Builder dialog(Activity a) {
        return new AlertDialog.Builder(a);
    }

    static String date(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy, HH:mm", Locale.GERMANY).format(new Date(ms));
    }

    /** "17.10.2026, 14:05 · 42 Min · 3 Sprecher" */
    static String meta(Context c, Transcript t) {
        StringBuilder b = new StringBuilder(date(t.createdMs));
        if (t.durationMs > 0) b.append(" · ").append(Times.duration(t.durationMs));
        int sp = t.speakers().size();
        if (sp > 0) b.append(" · ").append(c.getString(R.string.speakers_n, sp));
        return b.toString();
    }

    private static final int[] SPEAKER_COLORS = {R.color.spk1, R.color.spk2, R.color.spk3, R.color.spk4, R.color.spk5, R.color.spk6};

    static int speakerColor(Context c, int index) {
        return c.getColor(SPEAKER_COLORS[Math.floorMod(index, SPEAKER_COLORS.length)]);
    }

    /** Statuszeile für Liste und Transkript. Null = nichts anzeigen. */
    static String status(Context c, Transcript t) {
        int[] live = TranscribeService.liveProgress(t.id);
        switch (t.status) {
            case Transcript.RECORDING:
                return c.getString(R.string.st_recording, Times.clock(t.id.equals(RecordService.currentId) ? RecordService.recordedMs : 0));
            case Transcript.QUEUED: return c.getString(R.string.st_queued);
            case Transcript.PREPARE:
                return c.getString(R.string.st_prepare) + (live != null ? " · " + live[1] + " %" : " …");
            case Transcript.TRANSCRIBE: {
                int n = t.chunks();
                String s = n > 1 ? c.getString(R.string.st_transcribe_part, Math.min(t.chunksDone + 1, n), n)
                        : c.getString(R.string.st_transcribe);
                return s + " …";
            }
            case Transcript.SUMMARY: return c.getString(R.string.st_summary) + " …";
            case Transcript.CANCELED: return c.getString(R.string.st_canceled);
            case Transcript.ERROR: return t.error.isEmpty() ? c.getString(R.string.st_error) : t.error;
            default: return t.error.isEmpty() ? null : t.error;
        }
    }

    /** Fortschritt 0–100 oder -1 (unbestimmt). */
    static int progress(Transcript t) {
        int[] live = TranscribeService.liveProgress(t.id);
        if (Transcript.PREPARE.equals(t.status)) return live != null ? live[1] : -1;
        if (Transcript.TRANSCRIBE.equals(t.status)) {
            int n = t.chunks();
            return Math.min(99, t.chunksDone * 100 / Math.max(1, n));
        }
        return -1;
    }
}
