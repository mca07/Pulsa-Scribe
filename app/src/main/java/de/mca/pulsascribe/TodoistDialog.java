package de.mca.pulsascribe;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import de.mca.pulsascribe.core.Todoist;

/** Todoist-Token eintragen: wie der Gemini-Schlüssel ohne Screenshot, Passwortfeld; wird vor dem Speichern geprüft. */
final class TodoistDialog {
    private TodoistDialog() {}

    static void show(Activity a, Runnable onChanged) {
        LinearLayout col = Ui.column(a);
        Ui.add(col, Ui.body(a, a.getString(R.string.td_dialog_text)));
        EditText e = Ui.input(a, "", 120, false);
        e.setHint(R.string.td_token_hint);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setImportantForAutofill(android.view.View.IMPORTANT_FOR_AUTOFILL_NO);
        Ui.add(col, e);
        AlertDialog.Builder b = Ui.dialog(a).setTitle(R.string.td_token).setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null);
        if (TodoistToken.has(a)) {
            b.setNeutralButton(R.string.td_remove, (d, w) -> {
                TodoistToken.delete(a);
                Toast.makeText(a, R.string.td_removed, Toast.LENGTH_SHORT).show();
                if (onChanged != null) onChanged.run();
            });
        }
        AlertDialog d = b.create();
        if (d.getWindow() != null) d.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        d.setOnShowListener(x -> d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String k = e.getText().toString().trim();
            if (!Todoist.validToken(k)) {
                e.setError(a.getString(R.string.td_token_invalid));
                return;
            }
            v.setEnabled(false);
            TodoistSync.test(k, res -> {
                if (a.isFinishing() || !d.isShowing()) return;
                v.setEnabled(true);
                if (res != R.string.td_test_ok) {
                    e.setError(a.getString(res));
                    return;
                }
                if (!TodoistToken.set(a, k)) {
                    e.setError(a.getString(R.string.key_save_failed));
                    return;
                }
                Toast.makeText(a, R.string.td_test_ok, Toast.LENGTH_LONG).show();
                e.setText("");
                d.dismiss();
                if (onChanged != null) onChanged.run();
            });
        }));
        d.show();
    }
}
