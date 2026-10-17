package de.mca.pulsascribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import de.mca.pulsascribe.core.Gemini;

/** Eingabe des Gemini-Schlüssels: nativ, ohne Screenshot (FLAG_SECURE), Feld wie ein Passwort. */
final class KeyDialog {
    private KeyDialog() {}

    static void show(Activity a, Runnable onSaved) {
        LinearLayout col = Ui.column(a);
        Ui.add(col, Ui.body(a, a.getString(R.string.key_text)));
        EditText e = Ui.input(a, "", 240, false);
        e.setHint(R.string.key_hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        Ui.add(col, e);
        AlertDialog.Builder b = Ui.dialog(a).setTitle(R.string.key_title).setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (GeminiKey.has(a)) {
            b.setNeutralButton(R.string.key_delete, (d, w) -> {
                GeminiKey.delete(a);
                Toast.makeText(a, R.string.key_deleted, Toast.LENGTH_SHORT).show();
                if (onSaved != null) onSaved.run();
            });
        }
        AlertDialog d = b.create();
        if (d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = e.getText().toString().trim();
            if (!Gemini.validKey(k)) {
                e.setError(a.getString(R.string.key_invalid));
                return;
            }
            if (GeminiKey.set(a, k)) {
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
