package local.budslelink;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

/**
 * After every restart: makes sure the Galaxy Buds are still on LE Audio's auto-connect list (see LeArmTool,
 * which does the work as the adb shell user). This job only starts the tool through Buds LE Link's own
 * Wireless debugging pairing, records its result, and posts a notification when the result needs attention.
 */
public class AutoConnectJob extends JobService {
    static final int JOB_ID = 4;
    static final String LAST_RESULT = "autoConnectResult";
    static final String LAST_RESULT_AT = "autoConnectResultAt";
    private static final String ATTEMPTS = "autoConnectAttempts";
    private static final String EXTRA_MODE = "mode";
    private static final int MAX_ATTEMPTS = 20; // about 95 minutes of retries with the linear 30 s backoff below

    /** mode: "arm" after a restart; "check" or "arm-force" for tests. */
    static void schedule(Context context, long delayMs, String mode) {
        PersistableBundle extras = new PersistableBundle();
        extras.putString(EXTRA_MODE, mode);
        State.prefs(context).edit().putInt(ATTEMPTS, 0).apply();
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_ID, new ComponentName(context, AutoConnectJob.class))
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
        String mode = params.getExtras().getString(EXTRA_MODE, "arm");
        if (!ArmJob.provisioned(ctx)) {
            Log.w(Nudge.TAG, "auto-connect check: no adb key installed");
            return false;
        }
        new Thread(() -> {
            boolean retry;
            try {
                retry = !runTool(ctx, mode);
            } catch (Exception e) {
                Log.w(Nudge.TAG, "auto-connect check failed", e);
                retry = true;
            }
            int attempts = State.prefs(ctx).getInt(ATTEMPTS, 0) + 1;
            State.prefs(ctx).edit().putInt(ATTEMPTS, attempts).apply();
            if (retry && attempts >= MAX_ATTEMPTS) {
                Log.w(Nudge.TAG, "auto-connect check gave up after " + attempts + " attempts");
                record(ctx, "attention", "the auto-connect check could not finish after the restart, because Wireless "
                        + "debugging could not be reached or Bluetooth had not finished starting");
                retry = false;
            }
            jobFinished(params, retry);
        }, "auto-connect").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true;
    }

    /** True when finished (whatever the outcome), false when Wireless debugging or Bluetooth was not ready yet. */
    private static boolean runTool(Context ctx, String mode) throws Exception {
        try (AdbClient adb = AdbAccess.open(ctx)) {
            if (adb == null) {
                Log.w(Nudge.TAG, "auto-connect check: Wireless debugging not reachable yet");
                return false;
            }
            // The tool is part of this app's own APK, and the shell runs it from there. It has its own 105 s
            // watchdog, and "timeout" (110 s) is a second limit around it, inside AdbClient's 120 s limit.
            String cmd = "p=$(pm path --user " + AdbAccess.userId() + " " + ctx.getPackageName()
                    + " | head -n 1 | cut -d: -f2); CLASSPATH=$p timeout 110 app_process /system/bin "
                    + LeArmTool.class.getName() + " " + mode + " 2>&1";
            String out = adb.exec(cmd);
            for (String line : out.split("\n")) {
                if (!line.isEmpty()) Log.i(Nudge.TAG, "auto-connect check: " + line.trim());
            }
            if (out.contains("RETRY: ")) return false;
            String level = null;
            String text = null;
            for (String line : out.split("\n")) {
                String t = line.trim();
                for (String l : new String[] {"ok", "info", "attention"}) {
                    if (t.startsWith("RESULT " + l + ": ")) {
                        level = l;
                        text = t.substring(("RESULT " + l + ": ").length());
                    }
                }
            }
            if (level == null) {
                record(ctx, "attention", "the auto-connect check ended without a result");
            } else {
                record(ctx, level, text);
            }
            return true;
        }
    }

    private static void record(Context ctx, String level, String text) {
        State.prefs(ctx).edit().putString(LAST_RESULT, level + ": " + text)
                .putLong(LAST_RESULT_AT, System.currentTimeMillis()).apply();
        if (level.equals("attention")) Checks.autoConnectProblem(ctx, text);
    }
}
