package de.mca.pulsascribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import de.mca.pulsascribe.core.Ai;

/** Eingabe des Gemini-Schlüssels: nativ, ohne Screenshot (FLAG_SECURE), Feld wie ein Passwort. */
final class KeyDialog {
    private KeyDialog() {}

    static void show(Activity a, Runnable onSaved) {
        showFor(a, Ai.GEMINI, onSaved);
    }

    /** Schlüssel des aktiven Anbieters (Einstellungen → KI-Anbieter). */
    static void showActive(Activity a, Runnable onSaved) {
        showFor(a, Settings.provider(a), onSaved);
    }

    static void showFor(Activity a, String provider, Runnable onSaved) {
        String p = Ai.clean(provider);
        boolean gemini = Ai.GEMINI.equals(p);
        LinearLayout col = Ui.column(a);
        Ui.add(col, Ui.body(a, gemini ? a.getString(R.string.key_text) : a.getString(R.string.ai_key_text, Ai.label(p))));
        EditText e = Ui.input(a, "", 300, false);
        e.setHint(gemini ? a.getString(R.string.key_hint) : a.getString(R.string.ai_key_hint));
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        Ui.add(col, e);
        AlertDialog.Builder b = Ui.dialog(a).setTitle(gemini ? a.getString(R.string.key_title) : a.getString(R.string.ai_key_title, Ai.label(p))).setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (AiKeys.has(a, p)) {
            b.setNeutralButton(R.string.key_delete, (d, w) -> {
                AiKeys.delete(a, p);
                Toast.makeText(a, R.string.key_deleted, Toast.LENGTH_SHORT).show();
                if (onSaved != null) onSaved.run();
            });
        } else {
            b.setNeutralButton(R.string.ai_key_get, (d, w) -> {
                try {
                    a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Ai.keyUrl(p)))
                            .addCategory(android.content.Intent.CATEGORY_BROWSABLE));
                } catch (RuntimeException ignored) { }
            });
        }
        AlertDialog d = b.create();
        if (d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = e.getText().toString().trim();
            if (!Ai.validKey(p, k)) {
                e.setError(a.getString(R.string.key_invalid));
                return;
            }
            if (AiKeys.set(a, p, k)) {
                Toast.makeText(a, R.string.key_saved, Toast.LENGTH_SHORT).show();
                e.setText("");
                d.dismiss();
                if (onSaved != null) onSaved.run();
            } else {
                e.setError(a.getString(R.string.key_save_failed));
            }
        }));
        d.show();
    }
}
