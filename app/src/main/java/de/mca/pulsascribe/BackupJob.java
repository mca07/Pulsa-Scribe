package de.mca.pulsascribe;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Tägliche automatische Sicherung im Hintergrund (nur mit gemerktem Ordner und Passwort). */
public final class BackupJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        if (!BackupPlace.autoReady(this) || BackupPlace.autoDoneToday(this)) return false;
        new Thread(() -> {
            try {
                // Ist das Limit erreicht und „Älteste automatisch löschen“ aus, fragt die App beim nächsten Öffnen.
                BackupPlace.autoIfDue(getApplicationContext(), false);
            } catch (RuntimeException ignored) { }
            jobFinished(params, false);
        }, "backup-auto").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false; // nicht sofort wiederholen; spätestens beim nächsten Lauf oder App-Start
    }
}
