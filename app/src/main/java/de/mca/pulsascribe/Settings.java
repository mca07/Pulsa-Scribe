package de.mca.pulsascribe;

import android.content.Context;
import android.content.SharedPreferences;

import de.mca.pulsascribe.core.Gemini;
import de.mca.pulsascribe.core.Languages;

/** Einstellungen; jeder Wert wird beim Lesen gegen eine Positivliste geprüft. */
final class Settings {
    private Settings() {}

    private static final String PREFS = "pulsa_scribe_settings";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String language(Context c) { return Languages.clean(p(c).getString("language", "de")); }
    static void setLanguage(Context c, String v) { p(c).edit().putString("language", Languages.clean(v)).apply(); }

    static String summaryLanguage(Context c) { return Languages.cleanSummary(p(c).getString("summary_lang", Languages.SUM_SAME)); }
    static void setSummaryLanguage(Context c, String v) { p(c).edit().putString("summary_lang", Languages.cleanSummary(v)).apply(); }

    static boolean diarize(Context c) { return p(c).getBoolean("diarize", true); }
    static void setDiarize(Context c, boolean v) { p(c).edit().putBoolean("diarize", v).apply(); }

    static boolean summarize(Context c) { return p(c).getBoolean("summarize", true); }
    static void setSummarize(Context c, boolean v) { p(c).edit().putBoolean("summarize", v).apply(); }

    static boolean cleanFillers(Context c) { return p(c).getBoolean("clean", true); }
    static void setCleanFillers(Context c, boolean v) { p(c).edit().putBoolean("clean", v).apply(); }

    static boolean keepAudio(Context c) { return p(c).getBoolean("keep_audio", true); }
    static void setKeepAudio(Context c, boolean v) { p(c).edit().putBoolean("keep_audio", v).apply(); }

    static boolean hintAccepted(Context c) { return p(c).getBoolean("hint_v1", false); }
    static void setHintAccepted(Context c) { p(c).edit().putBoolean("hint_v1", true).apply(); }

    static boolean askedNotifications(Context c) { return p(c).getBoolean("asked_notif", false); }
    static void setAskedNotifications(Context c) { p(c).edit().putBoolean("asked_notif", true).apply(); }

    /** Zuletzt erfolgreiches Modell (Index in der festen Liste), damit nicht jedes Mal von vorn probiert wird. */
    static int modelIndex(Context c) {
        int i = p(c).getInt("model_idx", 0);
        return i >= 0 && i < Gemini.MODELS.length ? i : 0;
    }

    static void setModelIndex(Context c, int i) {
        if (i >= 0 && i < Gemini.MODELS.length) p(c).edit().putInt("model_idx", i).apply();
    }
}
