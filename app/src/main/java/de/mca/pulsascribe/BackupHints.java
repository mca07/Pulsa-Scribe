package de.mca.pulsascribe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.List;

/**
 * Beim Öffnen der App: Willkommens-Angebot nach Neuinstallation, fällige automatische Sicherung
 * (mit Rückfrage, wenn die Aufbewahrung voll ist) und Erinnerung nach 30 Tagen – höchstens ein Hinweis am Tag.
 */
final class BackupHints {
    private BackupHints() {}

    private static volatile boolean checking;

    static void onAppOpen(Activity a) {
        if (checking) return;
        checking = true;
        Context c = a.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                boolean hasData = !Store.all(c).isEmpty();
                if (BackupPlace.offerRestore(c, hasData)) {
                    main.post(() -> welcome(a));
                    return;
                }
                BackupPlace.schedule(c);
                if (BackupPlace.auto(c) && BackupPlace.lost(c) && BackupPlace.hintAllowed(c)) {
                    main.post(() -> toast(a, R.string.bk_place_lost));
                    return;
                }
                if (!Store.anyWorking(c) && !RecordService.isRecording()) {
                    BackupPlace.Outcome o = BackupPlace.autoIfDue(c, false);
                    if (o != null) {
                        if (o.needConfirm != null && !o.needConfirm.isEmpty()) {
                            if (!BackupPlace.hintAllowed(c)) return;
                            List<BackupPlace.Entry> old = o.needConfirm;
                            main.post(() -> askDeleteOldest(a, old));
                            return;
                        }
                        if (!o.ok && BackupPlace.hintAllowed(c)) {
                            main.post(() -> toast(a, R.string.bk_auto_failed));
                            return;
                        }
                        if (o.ok) return;
                    }
                }
                if (BackupPlace.remindDue(c, hasData) && BackupPlace.hintAllowed(c)) {
                    boolean never = BackupPlace.lastBackup(c) == 0;
                    main.post(() -> remind(a, never));
                }
            } catch (RuntimeException ignored) {
            } finally {
                checking = false;
            }
        }, "backup-check").start();
    }

    private static boolean gone(Activity a) {
        return a.isFinishing() || a.isDestroyed();
    }

    private static void toast(Activity a, int res) {
        if (!gone(a)) Toast.makeText(a, res, Toast.LENGTH_LONG).show();
    }

    private static void welcome(Activity a) {
        if (gone(a)) return;
        Ui.dialog(a).setTitle(R.string.bk_welcome_title).setMessage(R.string.bk_welcome_text)
                .setPositiveButton(R.string.bk_welcome_restore, (d, w) ->
                        a.startActivity(new Intent(a, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_RESTORE, true)))
                .setNegativeButton(R.string.bk_welcome_new, null).show();
    }

    private static void remind(Activity a, boolean never) {
        if (gone(a)) return;
        Ui.dialog(a).setTitle(R.string.bk_remind_title).setMessage(never ? R.string.bk_remind_never : R.string.bk_remind_text)
                .setPositiveButton(R.string.bk_now, (d, w) ->
                        a.startActivity(new Intent(a, SettingsActivity.class).putExtra(SettingsActivity.EXTRA_BACKUP_NOW, true)))
                .setNegativeButton(R.string.bk_later, null).show();
    }

    private static void askDeleteOldest(Activity a, List<BackupPlace.Entry> old) {
        if (gone(a)) return;
        Context c = a.getApplicationContext();
        String oldest = old.get(0).day();
        Ui.dialog(a).setTitle(R.string.bk_full_title)
                .setMessage(a.getString(R.string.bk_full_text, BackupPlace.keep(c), oldest))
                .setPositiveButton(R.string.bk_full_ok, (d, w) -> new Thread(() -> {
                    BackupPlace.Outcome o = BackupPlace.autoIfDue(c, true);
                    new Handler(Looper.getMainLooper()).post(() ->
                            toast(a, o != null && o.ok ? R.string.bk_auto_done : R.string.bk_auto_failed));
                }, "backup-auto").start())
                .setNegativeButton(R.string.bk_full_skip, (d, w) -> BackupPlace.skipToday(c))
                .setCancelable(false).show();
    }
}
