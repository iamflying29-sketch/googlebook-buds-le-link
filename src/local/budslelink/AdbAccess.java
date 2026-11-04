package local.budslelink;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.util.Log;

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
 * Buds LE Link's own connection to Wireless debugging on this Googlebook (127.0.0.1), shared by the ARM
 * repair after restarts and the restart of a stuck plugin. It finds the current port (it moves after every
 * Wi-Fi reconnection), connects with the app's own paired key, and proves it reached Android's real adb
 * service before anything else is sent.
 */
final class AdbAccess {
    private AdbAccess() {}

    /**
     * A connection to this Googlebook's Wireless debugging with Buds LE Link's own paired key, already proven to
     * reach Android's adb service, or null. Blocking (up to about 30 s): never call it on the main thread.
     */
    static AdbClient open(Context ctx) throws Exception {
        if (!ArmJob.provisioned(ctx)) return null;
        byte[] p12 = Files.readAllBytes(ArmJob.keyFile(ctx).toPath());
        char[] pass = State.prefs(ctx).getString(ArmJob.P12_PASS, "").toCharArray();
        return connect(ctx, p12, pass);
    }

    static int userId() {
        return android.os.Process.myUid() / 100000;
    }

    private static AdbClient connect(Context ctx, byte[] p12, char[] pass) throws InterruptedException {
        int saved = State.prefs(ctx).getInt(ArmJob.PORT, -1);
        if (saved > 0) {
            AdbClient c = proven(ctx, saved, p12, pass);
            if (c != null) return c;
        }
        for (int port : discoverOwnPorts(ctx, 15_000)) {
            AdbClient c = proven(ctx, port, p12, pass);
            if (c != null) {
                State.prefs(ctx).edit().putInt(ArmJob.PORT, port).apply();
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
