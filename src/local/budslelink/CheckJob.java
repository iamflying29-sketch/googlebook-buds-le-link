package local.budslelink;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothLeAudio;
import android.bluetooth.BluetoothProfile;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.util.Log;
import java.util.List;

/**
 * Layers 2 and 3: a check that runs every 15 minutes (surviving reboots) and shortly
 * after every Buds event.
 *
 * Every check re-learns the paired Buds, refreshes setup warnings, and, if Galaxy Buds
 * are connected over LE Audio, nudges the plugin. That repairs anything the instant
 * path missed, including a future Android change that stops delivering the Bluetooth
 * events. A verify check also confirms the plugin's control link came up and, after
 * repeated failures, posts a notification instead of failing silently.
 */
public class CheckJob extends JobService {
    private static final int PERIODIC_ID = 1;
    private static final int VERIFY_ID = 2;
    private static final String EXTRA_VERIFY = "verify";
    private static final long PERIODIC_MS = 15 * 60 * 1000L;
    private static final long RETRY_MS = 60_000;
    private static final long PROXY_TIMEOUT_MS = 10_000;
    private static final long SECOND_NUDGE_MS = 2500;
    // Failed verify checks (about a minute apart, each confirming the Buds are on LE Audio
    // and the control link is down) before a notification is posted: 5 minutes, so no false alarms.
    private static final int FAILURES_BEFORE_ALERT = 5;
    // Stop retrying after this many failures; the periodic check keeps trying.
    private static final int MAX_VERIFY_FAILURES = 8;

    private final Handler handler = new Handler(Looper.getMainLooper());

    static void schedulePeriodic(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(PERIODIC_ID) != null) {
            return;
        }
        scheduler.schedule(new JobInfo.Builder(PERIODIC_ID, new ComponentName(context, CheckJob.class))
                .setPeriodic(PERIODIC_MS)
                .setPersisted(true)
                .build());
    }

    static void scheduleVerify(Context context, long delayMs) {
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(EXTRA_VERIFY, true);
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(VERIFY_ID, new ComponentName(context, CheckJob.class))
                        .setMinimumLatency(delayMs)
                        .setOverrideDeadline(delayMs + 30_000)
                        .setPersisted(true)
                        .setExtras(extras)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        boolean verify = params.getExtras().getBoolean(EXTRA_VERIFY, false);
        Context context = getApplicationContext();
        schedulePeriodic(context);
        Buds.learnBonded(context);
        Checks.refreshSetupWarning(context);

        BluetoothAdapter adapter = Buds.adapter(context);
        if (adapter == null || !Buds.hasBluetoothPermission(context) || !adapter.isEnabled()) {
            Checks.budsGone(context);
            return false;
        }

        boolean[] done = {false};
        Runnable timeout = () -> {
            if (!done[0]) {
                done[0] = true;
                Log.w(Nudge.TAG, "LE Audio status unavailable");
                jobFinished(params, false);
            }
        };
        handler.postDelayed(timeout, PROXY_TIMEOUT_MS);
        boolean requested = adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                if (done[0]) {
                    adapter.closeProfileProxy(profile, proxy);
                    return;
                }
                done[0] = true;
                handler.removeCallbacks(timeout);
                boolean budsOnLeAudio = budsConnected(context, proxy);
                adapter.closeProfileProxy(profile, proxy);
                onLeAudioStatus(params, verify, budsOnLeAudio);
            }

            @Override
            public void onServiceDisconnected(int profile) {}
        }, BluetoothProfile.LE_AUDIO);
        if (!requested) {
            done[0] = true;
            handler.removeCallbacks(timeout);
            return false;
        }
        return true;
    }

    private static boolean budsConnected(Context context, BluetoothProfile proxy) {
        try {
            List<BluetoothDevice> devices = ((BluetoothLeAudio) proxy).getConnectedDevices();
            for (BluetoothDevice device : devices) {
                if (Buds.isBuds(context, device)) {
                    return true;
                }
            }
        } catch (SecurityException e) {
            Log.w(Nudge.TAG, "Nearby devices permission missing", e);
        }
        return false;
    }

    private void onLeAudioStatus(JobParameters params, boolean verify, boolean budsOnLeAudio) {
        Context context = getApplicationContext();
        if (!budsOnLeAudio) {
            Checks.budsGone(context);
            jobFinished(params, false);
            return;
        }
        State.prefs(context).edit().putLong(State.LAST_LE_AUDIO_SEEN_AT, System.currentTimeMillis()).apply();

        boolean retry = false;
        if (verify) {
            int classic = State.classic(context);
            if (classic == State.CLASSIC_UP) {
                Checks.reconnected(context);
                jobFinished(params, false);
                return;
            }
            // Unknown (e.g. right after an update) is not evidence of failure: just nudge.
            if (classic == State.CLASSIC_DOWN) {
                int failures = Checks.verifyFailed(context, FAILURES_BEFORE_ALERT);
                Log.w(Nudge.TAG, "Buds on LE Audio but no control link yet (check " + failures + ")");
                retry = failures < MAX_VERIFY_FAILURES;
            }
        }

        // Two nudges: the first refreshes the plugin's stale state, the second connects.
        Nudge.send(context);
        boolean scheduleRetry = retry;
        handler.postDelayed(() -> {
            Nudge.send(context);
            jobFinished(params, false);
            // Only after finishing: rescheduling a running job's ID stops that job.
            if (scheduleRetry) {
                scheduleVerify(context, RETRY_MS);
            }
        }, SECOND_NUDGE_MS);
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        handler.removeCallbacksAndMessages(null);
        return true;
    }
}
