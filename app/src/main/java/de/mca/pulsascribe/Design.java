package de.mca.pulsascribe;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

/** Design-Auswahl (Pulsa / Academy). Wert aus dem Speicher nur per Positivliste. */
final class Design {
    private Design() {}

    static final String PULSA = "pulsa", ACADEMY = "academy";
    private static final String PREFS = "pulsa_scribe_ui", KEY = "design";

    static String get(Context c) {
        String v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, PULSA);
        return ACADEMY.equals(v) ? ACADEMY : PULSA;
    }

    static void set(Context c, String v) {
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        p.edit().putString(KEY, ACADEMY.equals(v) ? ACADEMY : PULSA).apply();
    }

    /** Vor setContentView aufrufen. */
    static void apply(Activity a) {
        a.setTheme(ACADEMY.equals(get(a)) ? R.style.Theme_Scribe_Academy : R.style.Theme_Scribe);
    }

    static int color(Context c, int attr) {
        TypedValue tv = new TypedValue();
        if (c.getTheme().resolveAttribute(attr, tv, true)) return tv.data;
        return 0xff808080;
    }
}
