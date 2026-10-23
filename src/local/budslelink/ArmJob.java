package local.budslelink;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.PersistableBundle;
import android.util.Log;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        byte[] p12 = Files.readAllBytes(keyFile(ctx).toPath());
        char[] pass = State.prefs(ctx).getString(P12_PASS, "").toCharArray();
        AdbClient adb = connect(ctx, p12, pass);
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

    private static AdbClient connect(Context ctx, byte[] p12, char[] pass) throws InterruptedException {
        int saved = State.prefs(ctx).getInt(PORT, -1);
        if (saved > 0) {
            AdbClient c = proven(ctx, saved, p12, pass);
            if (c != null) return c;
        }
        for (int port : discoverOwnPorts(ctx, 15_000)) {
            AdbClient c = proven(ctx, port, p12, pass);
            if (c != null) {
                State.prefs(ctx).edit().putInt(PORT, port).apply();
                return c;
            }
        }
        return null;
    }

    /** Connects and proves it is Android's adb service before anything else is sent. */
    private static AdbClient proven(Context ctx, int port, byte[] p12, char[] pass) {
        AdbClient c;
        try {
            c = AdbClient.connect("127.0.0.1", port, p12, pass, 5000);
        } catch (Exception e) {
            Log.i(Nudge.TAG, "port " + port + ": " + e);
            return null;
        }
        String nonce = hex(16);
        try {
            int user = android.os.Process.myUid() / 100000;
            c.exec("am broadcast --user " + user + " -n " + ctx.getPackageName()
                    + "/.ControlReceiver -a local.budslelink.PROVE --es nonce " + nonce);
        } catch (Exception e) {
            c.close();
            return null;
        }
        long until = android.os.SystemClock.uptimeMillis() + 3000;
        while (android.os.SystemClock.uptimeMillis() < until) {
            if (ControlReceiver.PROVEN.remove(nonce)) return c;
            android.os.SystemClock.sleep(50);
        }
        Log.w(Nudge.TAG, "port " + port + " did not prove to be Android's adb service; ignored");
        c.close();
        return null;
    }

    private static String hex(int bytes) {
        byte[] b = new byte[bytes];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /** Wireless debugging ports advertised by this device itself (not by phones on the Wi-Fi). */
    private static List<Integer> discoverOwnPorts(Context ctx, long timeoutMs) throws InterruptedException {
        Set<String> own = new java.util.HashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses())) own.add(a.getHostAddress());
        } catch (Exception ignored) {
        }
        NsdManager nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        final boolean[] closed = {false};
        List<Integer> found = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch gotOne = new CountDownLatch(1);
        java.util.concurrent.Executor direct = Runnable::run;
        List<NsdManager.ServiceInfoCallback> callbacks = Collections.synchronizedList(new ArrayList<>());
        NsdManager.DiscoveryListener listener = new NsdManager.DiscoveryListener() {
            public void onStartDiscoveryFailed(String t, int e) { gotOne.countDown(); }
            public void onStopDiscoveryFailed(String t, int e) { }
            public void onDiscoveryStarted(String t) { }
            public void onDiscoveryStopped(String t) { }
            public void onServiceLost(NsdServiceInfo i) { }
            public void onServiceFound(NsdServiceInfo info) {
                NsdManager.ServiceInfoCallback cb = new NsdManager.ServiceInfoCallback() {
                    public void onServiceInfoCallbackRegistrationFailed(int e) { }
                    public void onServiceLost() { }
                    public void onServiceInfoCallbackUnregistered() { }
                    public void onServiceUpdated(NsdServiceInfo s) {
                        boolean mine = false;
                        for (InetAddress a : s.getHostAddresses()) if (own.contains(a.getHostAddress())) mine = true;
                        if (mine && s.getPort() > 0) {
                            found.add(s.getPort());
                            gotOne.countDown();
                        }
                    }
                };
                synchronized (callbacks) {
                    if (closed[0]) return;
                    try {
                        nsd.registerServiceInfoCallback(info, direct, cb);
                        callbacks.add(cb);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
        };
        nsd.discoverServices("_adb-tls-connect._tcp", NsdManager.PROTOCOL_DNS_SD, listener);
        try {
            gotOne.await(timeoutMs, TimeUnit.MILLISECONDS);
            Thread.sleep(500);
        } finally { // never leave a discovery session open
            try { nsd.stopServiceDiscovery(listener); } catch (RuntimeException ignored) { }
            synchronized (callbacks) {
                closed[0] = true;
                for (NsdManager.ServiceInfoCallback cb : callbacks) {
                    try { nsd.unregisterServiceInfoCallback(cb); } catch (RuntimeException ignored) { }
                }
            }
        }
        synchronized (found) {
            return new ArrayList<>(new LinkedHashSet<>(found));
        }
    }
}
