package local.budslelink;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

import java.io.File;

/**
 * Keeps Samsung's Buds plugin in ARM mode across restarts.
 *
 * Samsung's plugin ships an incomplete x86_64 library set, so on this Intel Googlebook it must run
 * as arm64. A system update (and the restart into it) can make Android re-derive the plugin's ABI
 * and switch it back to x86_64 without reinstalling anything (seen 2026-10-23). After every restart
 * this job checks the plugin's mode (no adb needed, the app can see the library folder). Only if it
 * is in Intel mode does it connect to Wireless debugging with Buds LE Link's OWN paired key and
 * reinstall the saved, fingerprint-checked copy with "pm install --abi arm64-v8a". Your data and
 * pairing in Samsung's app are kept (it is an in-place update of the same version).
 */
public class ArmJob extends JobService {
    static final int JOB_ID = 3;
    static final String P12_PASS = "p12pass";
    static final String PORT = "adbPort";
    private static final String ATTEMPTS = "armAttempts";
    private static final String EXTRA_FORCE = "force";
    // The saved copy on the device, readable by the adb shell, and its fingerprint (the APK Samsung
    // published through the Play Store as version 9.0.10.1720).
    static final String APK_ON_DEVICE = "/data/local/tmp/budslelink/plugin-9.0.10.1720.apk";
    static final String APK_SHA256 = "3a8c0cf868c85a6c1a82ebb059004b6da591ce95a4051d6521503027f48c2efe";
    private static final int MAX_ATTEMPTS = 20; // about 15 minutes of retries with the backoff below

    static File keyFile(Context c) {
        return new File(c.getFilesDir(), "adb.p12");
    }

    static boolean provisioned(Context c) {
        return keyFile(c).exists() && State.prefs(c).contains(P12_PASS);
    }

    /** force: run the whole repair even if the plugin is already in ARM mode (a test). */
    static void schedule(Context context, long delayMs, boolean force) {
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_FORCE, force);
        State.prefs(context).edit().putInt(ATTEMPTS, 0).apply();
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_ID, new ComponentName(context, ArmJob.class))
                        .setMinimumLatency(delayMs)
                        .setOverrideDeadline(delayMs + 60_000)
                        .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_LINEAR)
                        .setPersisted(true)
                        .setExtras(extras)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Context ctx = getApplicationContext();
        boolean force = params.getExtras().getBoolean(EXTRA_FORCE, false);
        if (!force && !Buds.pluginInIntelMode(ctx)) {
            Log.i(Nudge.TAG, "plugin is in ARM mode: nothing to do");
            return false;
        }
        new Thread(() -> {
            boolean retry;
            try {
                retry = !repair(ctx, force);
            } catch (Exception e) {
                Log.w(Nudge.TAG, "ARM repair failed", e);
                retry = true;
            }
            int attempts = State.prefs(ctx).getInt(ATTEMPTS, 0) + 1;
            State.prefs(ctx).edit().putInt(ATTEMPTS, attempts).apply();
            if (retry && attempts >= MAX_ATTEMPTS) {
                Log.w(Nudge.TAG, "ARM repair gave up after " + attempts + " attempts");
                retry = false;
            }
            Checks.refreshSetupWarning(ctx); // shows the Intel-mode warning if it is still not fixed
            jobFinished(params, retry);
        }, "arm-repair").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** True when finished (repaired, or nothing more can be done); false to retry later. */
    private static boolean repair(Context ctx, boolean force) throws Exception {
        if (!provisioned(ctx)) {
            Log.w(Nudge.TAG, "ARM repair: no adb key installed");
            return true;
        }
        AdbClient adb = AdbAccess.open(ctx);
        if (adb == null) {
            Log.w(Nudge.TAG, "ARM repair: Wireless debugging not reachable yet");
            return false;
        }
        try {
            String sum = adb.exec("sha256sum " + APK_ON_DEVICE + " 2>&1").trim();
            if (!sum.startsWith(APK_SHA256)) {
                Log.w(Nudge.TAG, "ARM repair: saved plugin copy missing or changed: " + sum);
                return true;
            }
            int user = android.os.Process.myUid() / 100000;
            String out = adb.exec("pm install -r --abi arm64-v8a --user " + user
                    + " -i com.android.vending " + APK_ON_DEVICE + " 2>&1").trim();
            Log.i(Nudge.TAG, "ARM repair: pm install said: " + out);
            if (!out.contains("Success")) return false;
        } finally {
            adb.close();
        }
        if (Buds.pluginInIntelMode(ctx)) {
            Log.w(Nudge.TAG, "ARM repair: still in Intel mode after installing");
            return false;
        }
        Log.i(Nudge.TAG, "ARM repair: plugin is in ARM mode" + (force ? " (test run)" : ""));
        if (!force) Checks.armRepaired(ctx);
        CheckJob.scheduleVerify(ctx, 10_000); // reconnect the Buds app if the Buds are connected
        return true;
    }
}
