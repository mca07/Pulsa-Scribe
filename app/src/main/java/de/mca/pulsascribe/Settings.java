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

    /** Standardtitel geteilter Bilder ("Geteiltes Bild …"): wird durch den erkannten Titel ersetzt. */
    static boolean isDefaultImageTitle(Context c, String title) {
        return title != null && title.startsWith(c.getString(R.string.share_image_prefix));
    }

    static float speechRate(Context c) {
        float r = p(c).getFloat("tts_rate", 1.0f);
        for (float x : SPEECH_RATES) if (x == r) return r;
        return 1.0f;
    }

    static void setSpeechRate(Context c, float r) {
        for (float x : SPEECH_RATES) if (x == r) { p(c).edit().putFloat("tts_rate", r).apply(); return; }
    }

    static final float[] SPEECH_RATES = {0.8f, 1.0f, 1.2f, 1.5f, 1.8f};
    static final String[] SPEECH_LABELS = {"Langsam (0,8×)", "Normal", "Etwas schneller (1,2×)", "Schnell (1,5×)", "Sehr schnell (1,8×)"};

    static String template(Context c) { return de.mca.pulsascribe.core.Templates.clean(p(c).getString("template", "standard")); }
    static void setTemplate(Context c, String v) { p(c).edit().putString("template", de.mca.pulsascribe.core.Templates.clean(v)).apply(); }

    static String summaryLength(Context c) { return de.mca.pulsascribe.core.Templates.cleanLength(p(c).getString("sum_len", "medium")); }
    static void setSummaryLength(Context c, String v) { p(c).edit().putString("sum_len", de.mca.pulsascribe.core.Templates.cleanLength(v)).apply(); }

    static String engine(Context c) {
        return "whisper".equals(p(c).getString("engine", "gemini")) ? "whisper" : "gemini";
    }

    static void setEngine(Context c, String e) {
        p(c).edit().putString("engine", "whisper".equals(e) ? "whisper" : "gemini").apply();
    }

    /** Zuletzt erfolgreiches Modell (Index in der festen Liste), damit nicht jedes Mal von vorn probiert wird. */
    static int modelIndex(Context c) {
        int i = p(c).getInt("model_idx", 0);
        return i >= 0 && i < Gemini.MODELS.length ? i : 0;
    }

    static void setModelIndex(Context c, int i) {
        if (i >= 0 && i < Gemini.MODELS.length) p(c).edit().putInt("model_idx", i).apply();
    }

    static boolean todoistAuto(Context c) { return p(c).getBoolean("todoist_auto", false); }
    static void setTodoistAuto(Context c, boolean v) { p(c).edit().putBoolean("todoist_auto", v).apply(); }

    /** Papierkorb automatisch leeren nach … Tagen (0 = nie). */
    static final int[] TRASH_DAYS = {7, 30, 90, 0};
    static int trashDays(Context c) {
        int d = p(c).getInt("trash_days", 30);
        for (int x : TRASH_DAYS) if (x == d) return d;
        return 30;
    }
    static void setTrashDays(Context c, int d) { p(c).edit().putInt("trash_days", d).apply(); }

    /** Natürliche Gemini-Stimme (Standard an) oder Handy-Stimme. */
    static boolean naturalVoice(Context c) { return p(c).getBoolean("natural_voice", true); }
    static void setNaturalVoice(Context c, boolean v) { p(c).edit().putBoolean("natural_voice", v).apply(); }
    static String voice(Context c) { return de.mca.pulsascribe.core.Speech.cleanVoice(p(c).getString("voice", "")); }
    static void setVoice(Context c, String v) { p(c).edit().putString("voice", de.mca.pulsascribe.core.Speech.cleanVoice(v)).apply(); }
    /** Name der gewählten Handy-Stimme, "" = automatisch die beste. */
    static String deviceVoice(Context c) {
        String v = p(c).getString("device_voice", "");
        return v.length() <= 120 && v.matches("[A-Za-z0-9_#.\\-]*") ? v : "";
    }
    static void setDeviceVoice(Context c, String v) { p(c).edit().putString("device_voice", v == null ? "" : v).apply(); }

    /** CRM-Eintrag standardmäßig in Ich-Form, eigener Name zum Erkennen des eigenen Sprechers. */
    static boolean crmFirstPerson(Context c) { return p(c).getBoolean("crm_first_person", false); }
    static void setCrmFirstPerson(Context c, boolean v) { p(c).edit().putBoolean("crm_first_person", v).apply(); }
    static String myName(Context c) { return de.mca.pulsascribe.core.Text.line(p(c).getString("my_name", ""), 40); }
    static void setMyName(Context c, String v) { p(c).edit().putString("my_name", de.mca.pulsascribe.core.Text.line(v, 40)).apply(); }

    /** Modelle in Qualitätsreihenfolge; solche in der Sperrzeit (Kontingent, nicht gefunden) kommen zuletzt. */
    static int[] modelOrder(Context c) {
        int n = Gemini.MODELS.length;
        long now = System.currentTimeMillis();
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < n; i++) if (p(c).getLong("model_cd_" + i, 0) <= now) out[k++] = i;
        for (int i = 0; i < n; i++) if (p(c).getLong("model_cd_" + i, 0) > now) out[k++] = i;
        return out;
    }
    static void setModelCooldown(Context c, int i, long ms) { p(c).edit().putLong("model_cd_" + i, System.currentTimeMillis() + ms).apply(); }
    static void clearModelCooldown(Context c, int i) { p(c).edit().remove("model_cd_" + i).apply(); }

    /** Berichtsarten ein-/ausschalten (Vorlagenliste und Knöpfe in der Zusammenfassung). */
    static boolean crmEnabled(Context c) { return p(c).getBoolean("rep_crm", true); }
    static void setCrmEnabled(Context c, boolean v) { p(c).edit().putBoolean("rep_crm", v).apply(); }
    static boolean serviceEnabled(Context c) { return p(c).getBoolean("rep_service", true); }
    static void setServiceEnabled(Context c, boolean v) { p(c).edit().putBoolean("rep_service", v).apply(); }

    static java.util.List<String> templateIds(Context c, String current) {
        return de.mca.pulsascribe.core.Templates.available(crmEnabled(c), serviceEnabled(c), current);
    }

    static String[] templateLabels(java.util.List<String> ids) {
        String[] l = new String[ids.size()];
        for (int i = 0; i < l.length; i++) l[i] = de.mca.pulsascribe.core.Templates.label(ids.get(i));
        return l;
    }

    /** Zuletzt geöffneter Ordner = Ziel für neue Einträge (Aufnahme, Datei, Link, Browser, Kachel, Widget). */
    static String lastFolder(Context c) {
        String f = p(c).getString("last_folder", de.mca.pulsascribe.core.Folders.ROOT);
        return Store.folders(c).clean(f);
    }
    static void setLastFolder(Context c, String f) { p(c).edit().putString("last_folder", f == null ? "" : f).apply(); }
}
