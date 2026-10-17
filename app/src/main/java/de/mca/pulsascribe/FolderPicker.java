package de.mca.pulsascribe;

import android.app.Activity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import de.mca.pulsascribe.core.Folders;

/** Auswahl eines Ordners (Baum mit Einrückung) und die Dialoge zum Anlegen/Umbenennen. */
final class FolderPicker {
    private FolderPicker() {}

    interface Allowed {
        boolean ok(String folderId);
    }

    interface Picked {
        void picked(String folderId);
    }

    static void show(Activity a, String title, Allowed allowed, Picked picked) {
        Folders f = Store.folders(a);
        List<String> ids = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (allowed == null || allowed.ok(Folders.ROOT)) {
            ids.add(Folders.ROOT);
            labels.add(a.getString(R.string.folder_top));
        }
        for (Folders.Folder x : f.tree()) {
            if (allowed != null && !allowed.ok(x.id)) continue;
            StringBuilder b = new StringBuilder();
            int d = f.depth(x.id);
            for (int i = 1; i < d; i++) b.append("      ");
            b.append(d > 1 ? "└ " : "").append(x.name);
            ids.add(x.id);
            labels.add(b.toString());
        }
        Ui.dialog(a).setTitle(title)
                .setItems(labels.toArray(new String[0]), (d, w) -> picked.picked(ids.get(w)))
                .setNeutralButton(R.string.folder_new, (d, w) -> create(a, Folders.ROOT, newId -> {
                    if (newId != null) picked.picked(newId);
                }))
                .setNegativeButton(R.string.cancel, null).show();
    }

    /** Neuen Ordner unter parent anlegen. */
    static void create(Activity a, String parent, Picked done) {
        LinearLayout col = Ui.column(a);
        Ui.add(col, Ui.label(a, R.string.folder_name));
        EditText e = Ui.input(a, "", Folders.MAX_NAME, false);
        Ui.add(col, e);
        android.app.AlertDialog d = Ui.dialog(a).setTitle(R.string.folder_new).setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null).create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String id = Store.newId();
            int r = Store.editFolders(a, fs -> fs.add(id, e.getText().toString(), parent, null));
            if (r == Folders.OK) {
                d.dismiss();
                if (done != null) done.picked(id);
            } else {
                e.setError(a.getString(message(r)));
            }
        }));
        d.show();
    }

    static void rename(Activity a, String id) {
        Folders.Folder cur = Store.folders(a).get(id);
        if (cur == null) return;
        LinearLayout col = Ui.column(a);
        EditText e = Ui.input(a, cur.name, Folders.MAX_NAME, false);
        Ui.add(col, e);
        android.app.AlertDialog d = Ui.dialog(a).setTitle(R.string.folder_rename).setView(col)
                .setPositiveButton(R.string.save, null)
                .setNegativeButton(R.string.cancel, null).create();
        d.setOnShowListener(x -> d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int r = Store.editFolders(a, fs -> fs.rename(id, e.getText().toString()));
            if (r == Folders.OK) d.dismiss();
            else e.setError(a.getString(message(r)));
        }));
        d.show();
    }

    /** Ordner verschieben: nur Ziele, die keine Schleife bilden und die Tiefe einhalten. */
    static void move(Activity a, String id) {
        Folders f = Store.folders(a);
        if (!f.exists(id)) return;
        show(a, a.getString(R.string.folder_move_to), target -> f.canMove(id, target) == Folders.OK, target -> {
            int r = Store.editFolders(a, fs -> fs.move(id, target));
            if (r != Folders.OK) Toast.makeText(a, message(r), Toast.LENGTH_SHORT).show();
        });
    }

    static void delete(Activity a, String id, Runnable after) {
        Folders.Folder cur = Store.folders(a).get(id);
        if (cur == null) return;
        Ui.dialog(a).setTitle(a.getString(R.string.folder_delete_title, cur.name)).setMessage(R.string.folder_delete_text)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    Store.deleteFolder(a, id);
                    if (after != null) after.run();
                })
                .setNegativeButton(R.string.cancel, null).show();
    }

    static int message(int code) {
        switch (code) {
            case Folders.ERR_NAME: return R.string.folder_err_name;
            case Folders.ERR_DUPLICATE: return R.string.folder_err_dup;
            case Folders.ERR_DEPTH: return R.string.folder_err_depth;
            case Folders.ERR_LIMIT: return R.string.folder_err_limit;
            case Folders.ERR_CYCLE: return R.string.folder_err_cycle;
            default: return R.string.error_generic;
        }
    }
}
