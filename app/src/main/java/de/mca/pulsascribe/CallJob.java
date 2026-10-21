package de.mca.pulsascribe;

import android.app.job.JobParameters;
import android.app.job.JobService;

/** Hintergrundprüfung des freigegebenen Anruf-Ordners (von Android ausgelöst). */
public final class CallJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            try {
                CallImport.scan(getApplicationContext());
            } catch (RuntimeException ignored) { }
            // Inhalts-Auslöser gelten nur einmal: neu anmelden
            CallImport.schedule(getApplicationContext());
            jobFinished(params, false);
        }, "call-scan").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }
}
