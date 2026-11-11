package local.budslelink;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Puts the Galaxy Buds back on LE Audio's auto-connect list after a Googlebook restart, if a restart
 * removed them. It runs as the adb shell user (which has Bluetooth's privileged permission), started by
 * AutoConnectJob through Buds LE Link's own adb pairing:
 *   CLASSPATH=<this app's APK> app_process /system/bin local.budslelink.LeArmTool <mode>
 *
 * Why (seen 2026-11-10 on build CL3B.260622.286.R2): when GooglebookOS starts, Bluetooth starts first for
 * Android's user 0, whose Bluetooth database has LE Audio's connection policy 0 for the Buds, so it switches
 * their LE Audio auto-connect off (and that is saved to the shared Bluetooth config). Then Bluetooth starts
 * for the user's own Android user, and its LeAudioService switches it back on, but only if the devices are
 * already loaded when it checks. On one restart the check ran 2 ms too early, and the Buds stayed off the
 * auto-connect list. Re-applying the user's own LE Audio setting ALLOWED (100), which it already is, makes
 * LeAudioService call setEnabledState(true) and connect(), which puts the whole group back on the list.
 * The same step is proven for the Pixel Buds in Buds Battery Fix.
 *
 * Modes:
 *   check      read only: prints what it sees and changes nothing.
 *   arm        re-applies the allowed setting only if the log shows the Buds are not on the list (and they
 *              are not connected, and their LE Audio setting is ALLOWED), then checks the result 35 s later.
 *   logtest    a test of the log reader only: a normal read, one that hits the size cap and one that hits
 *              the deadline. Changes nothing.
 *   arm-force  a test: re-applies the allowed setting even if the log shows the Buds are already on the list
 *              (still only when the setting is ALLOWED and they are not connected), then checks the result.
 *
 * Output: lines starting with "RESULT ok|info|attention: " are the outcome, and a line starting with
 * "RETRY: " asks the job to try again later (for example while Bluetooth is still starting); other lines
 * are details.
 * A watchdog ends the tool after 105 s whatever happens (the job wraps it in a 110 s limit, inside the adb
 * connection's own 120 s limit). Every log read has a deadline and a size cap, and
 * the pipe is read while logcat writes.
 */
public final class LeArmTool {
    private static final int LE_AUDIO = 22;
    private static final int POLICY_ALLOWED = 100;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;
    private static final long BT_SETTLE_MS = 10_000;
    private static final long PROXY_WAIT_MS = 15_000;
    private static final long VERIFY_AFTER_MS = 35_000;
    private static final long LOG_DEADLINE_MS = 10_000;
    private static final int LOG_CAP_BYTES = 256 * 1024;
    private static final long WATCHDOG_MS = 105_000;
    private static final int BT_APP_ID = 1002;

    private LeArmTool() {}

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "check";
        Thread watchdog = new Thread(() -> {
            SystemClock.sleep(WATCHDOG_MS);
            say("RESULT attention: the auto-connect check did not finish within " + (WATCHDOG_MS / 1000) + " s");
            Runtime.getRuntime().halt(2);
        }, "learm-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try {
            run(mode);
        } catch (Throwable e) {
            fail(e);
        }
        finish();
    }

    private static void fail(Throwable e) {
        Throwable c = e.getCause();
        say("RESULT attention: the auto-connect check failed: " + e + (c != null ? " / " + c : ""));
    }

    private static void finish() {
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static void say(String s) {
        System.out.println(s);
        System.out.flush();
    }

    private static void run(String mode) throws Exception {
        if (mode.equals("logtest")) {
            logTest();
            return;
        }
        boolean change = mode.equals("arm") || mode.equals("arm-force");
        boolean force = mode.equals("arm-force");
        Looper.prepareMainLooper();
        try {
            Class.forName("android.app.ActivityThread").getMethod("initializeMainlineModules").invoke(null);
        } catch (Throwable ignored) {
        }
        AttributionSource shell = new AttributionSource.Builder(android.os.Process.myUid())
                .setPackageName("com.android.shell").build();
        Context ctx = new ShellContext(systemContext(), shell);
        BluetoothAdapter adapter = shellAdapter(ctx, shell);
        if (!adapter.isEnabled()) {
            say("RESULT info: Bluetooth is off, so there is nothing to check");
            return;
        }

        // Wait until Bluetooth has run for a few seconds, so its own start-up work is finished.
        long[] bt = bluetoothProcess();
        if (bt == null) {
            // None, or two during the switch from user 0 to the user's own Bluetooth at startup.
            say("RETRY: Bluetooth's process is not settled yet");
            return;
        }
        if (bt[1] < BT_SETTLE_MS) SystemClock.sleep(BT_SETTLE_MS - bt[1]);
        int btPid = (int) bt[0];
        say("Bluetooth process " + btPid + ", running " + (Math.max(bt[1], BT_SETTLE_MS) / 1000) + " s");

        List<BluetoothDevice> buds = new ArrayList<>();
        for (BluetoothDevice d : adapter.getBondedDevices()) {
            String name = d.getName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains("galaxy buds")) buds.add(d);
        }
        if (buds.isEmpty()) {
            say("RESULT info: no paired Galaxy Buds, so there is nothing to check");
            return;
        }

        // The LE Audio service, as the shell user. Its answer arrives on the main looper, which keeps running
        // (Android does not let the main looper stop), so the rest runs on a worker thread that ends the tool.
        BluetoothProfile[] proxy = {null};
        CountDownLatch ready = new CountDownLatch(1);
        boolean asked = adapter.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile p) {
                proxy[0] = p;
                ready.countDown();
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, LE_AUDIO);
        if (!asked) {
            say("RESULT info: LE Audio is not available on this device");
            return;
        }
        Thread worker = new Thread(() -> {
            try {
                if (!awaitQuietly(ready, PROXY_WAIT_MS) || proxy[0] == null) {
                    say("RESULT attention: LE Audio did not answer within " + (PROXY_WAIT_MS / 1000) + " s");
                } else {
                    checkAndArm(proxy[0], buds, btPid, change, force);
                }
            } catch (Throwable e) {
                fail(e);
            }
            finish();
        }, "learm-worker");
        worker.start();
        Looper.loop(); // never returns: the worker or the watchdog ends the tool
    }

    private static void checkAndArm(BluetoothProfile le, List<BluetoothDevice> buds, int btPid, boolean change,
            boolean force) throws Exception {
        Method getPolicy = le.getClass().getMethod("getConnectionPolicy", BluetoothDevice.class);
        Method setPolicy = le.getClass().getMethod("setConnectionPolicy", BluetoothDevice.class, int.class);

        // Look at every Galaxy Buds device (the Buds 4 are a group of two, one per earbud).
        BluetoothDevice toApply = null;
        String why = null;
        boolean anyConnected = false;
        for (BluetoothDevice d : buds) {
            int policy = (Integer) getPolicy.invoke(le, d);
            int state = le.getConnectionState(d);
            String logged = nativeLeState(btPid, d);
            say(label(d) + ": LE Audio setting " + policy + ", state " + state + ", log says " + logged);
            if (state == STATE_CONNECTED || state == STATE_CONNECTING) anyConnected = true;
            if (policy != POLICY_ALLOWED) continue;
            if (!force && armedOrConnected(logged)) continue;
            if (toApply == null) {
                toApply = d;
                why = logged == null ? "the system log could not be read"
                        : logged.equals("NONE") ? "the system log has no record of them being put back on LE Audio's auto-connect list"
                        : "the system log shows them as " + logged;
            }
        }
        if (anyConnected) {
            say("RESULT ok: the Galaxy Buds are already connected over LE Audio; nothing changed");
            return;
        }
        if (toApply == null) {
            say("RESULT ok: the Galaxy Buds are on LE Audio's auto-connect list (or their LE Audio setting is off, which was left alone); nothing changed");
            return;
        }
        if (!change) {
            say("RESULT info: the Galaxy Buds are not on LE Audio's auto-connect list (" + why + "); check mode, so nothing was changed");
            return;
        }
        // One call is enough: LeAudioService applies a connection policy to every device in the group.
        boolean applied = (Boolean) setPolicy.invoke(le, toApply, POLICY_ALLOWED);
        if (!applied) {
            say("RESULT attention: re-applying the Galaxy Buds' LE Audio setting was refused (" + why
                    + "); they may not connect by themselves until you connect them once in Bluetooth settings");
            return;
        }
        say("re-applied the allowed LE Audio setting on " + label(toApply) + " (" + why + ")" + (force ? " (test run)" : ""));

        // Confirm the result after LE Audio has had time to act. Re-applying the setting also starts a connect
        // attempt ("CONNECTING_BY_USER") that ends after about 30 s when the Buds are not in range; while one is
        // still running, look again once more 30 s later, as the Pixel Buds fix does.
        SystemClock.sleep(VERIFY_AFTER_MS);
        boolean userConnectRunning = false;
        for (BluetoothDevice d : buds) {
            if ("CONNECTING_BY_USER".equals(nativeLeState(btPid, d))) userConnectRunning = true;
        }
        if (userConnectRunning) {
            say("a connect attempt is still running; looking again in 30 s");
            SystemClock.sleep(30_000);
        }
        int armed = 0;
        int counted = 0;
        boolean connectedNow = false;
        StringBuilder states = new StringBuilder();
        for (BluetoothDevice d : buds) {
            if ((Integer) getPolicy.invoke(le, d) != POLICY_ALLOWED) continue;
            counted++;
            int state = le.getConnectionState(d);
            String logged = nativeLeState(btPid, d);
            if (state == STATE_CONNECTED) connectedNow = true;
            if (state == STATE_CONNECTED || armedOrConnected(logged)) armed++;
            states.append(states.length() == 0 ? "" : ", ").append(label(d)).append(" ").append(logged);
        }
        if (connectedNow) {
            say("RESULT ok: re-applied the Galaxy Buds' LE Audio setting; confirmed connected over LE Audio");
        } else if (armed == counted) {
            say("RESULT ok: re-applied the Galaxy Buds' LE Audio setting; confirmed back on LE Audio's auto-connect list");
        } else {
            say("RESULT attention: after re-applying their LE Audio setting, the Galaxy Buds are not all on LE Audio's"
                    + " auto-connect list (log: " + states + "); they may not connect by themselves until you connect"
                    + " them once in Bluetooth settings");
        }
    }

    private static void logTest() {
        String[][] tests = {
            {"normal read (expect done)", "262144", "10000", "/system/bin/logcat", "-d", "-b", "main", "-t", "5"},
            {"big read with a 4 KB cap (expect cap)", "4096", "10000", "/system/bin/logcat", "-d", "-b", "main,system"},
            {"endless read with a 2 s deadline (expect deadline)", "1048576", "2000", "/system/bin/logcat", "-b", "main", "-T", "1"},
        };
        for (String[] t : tests) {
            long start = SystemClock.uptimeMillis();
            String[] cmd = java.util.Arrays.copyOfRange(t, 3, t.length);
            String[] r = runBounded(cmd, Integer.parseInt(t[1]), Long.parseLong(t[2]));
            say(t[0] + ": ended by " + (r == null ? "error" : r[1]) + " after " + (SystemClock.uptimeMillis() - start)
                    + " ms with " + (r == null ? 0 : r[0].length()) + " characters");
        }
        say("RESULT info: log reader test finished");
    }

    private static boolean awaitQuietly(CountDownLatch latch, long ms) {
        try {
            return latch.await(ms, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            return false;
        }
    }

    private static String label(BluetoothDevice d) {
        String a = d.getAddress();
        return d.getName() + " (" + a.substring(Math.max(0, a.length() - 5)) + ")";
    }

    private static boolean armedOrConnected(String state) {
        return "CONNECTING_AUTOCONNECT".equals(state) || "CONNECTED".equals(state)
                || "CONNECTED_AUTOCONNECT_GETTING_READY".equals(state);
    }

    /**
     * The device's latest LE Audio connection state in Bluetooth's own log ("CONNECTING_AUTOCONNECT" means on
     * the auto-connect list), "NONE" if the log has no state change for it, or null if the log could not be
     * read completely. Only lines of the given Bluetooth process are read.
     */
    private static String nativeLeState(int btPid, BluetoothDevice d) {
        String a = d.getAddress().toLowerCase(Locale.ROOT);
        String tail = a.substring(Math.max(0, a.length() - 5));
        String[] r = runBounded(new String[] {"/system/bin/logcat", "-d", "-b", "main,system", "--pid=" + btPid,
                "-e", "SetConnectionState: [0-9a-fx:]*" + tail + ","}, LOG_CAP_BYTES, LOG_DEADLINE_MS);
        if (r == null || !r[1].equals("done")) return null; // cut off: the latest line may be missing
        String last = null;
        for (String line : r[0].split("\n")) {
            if (line.contains("SetConnectionState:") && line.contains(" --> ")) last = line;
        }
        if (last == null) return "NONE";
        String after = last.substring(last.indexOf(" --> ") + 5).trim();
        int space = after.indexOf(' ');
        return space < 0 ? after : after.substring(0, space);
    }

    /**
     * Runs a command with a total deadline and a size cap, reading its output while it writes. At the cap the
     * read end is closed, so the command gets an error instead of waiting on a full pipe; at the deadline the
     * pipe is closed and the command is stopped. Returns {text, "done"|"cap"|"deadline"}, or null.
     */
    private static String[] runBounded(String[] cmd, int capBytes, long deadlineMs) {
        Process p;
        try {
            p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        } catch (Exception e) {
            return null;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AtomicBoolean capped = new AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            try (InputStream s = p.getInputStream()) {
                byte[] buf = new byte[8192];
                while (true) {
                    int n = s.read(buf);
                    if (n < 0) break;
                    synchronized (out) {
                        if (out.size() + n > capBytes) {
                            capped.set(true);
                            break;
                        }
                        out.write(buf, 0, n);
                    }
                }
            } catch (Exception ignored) {
            }
        }, "learm-reader");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = p.waitFor(deadlineMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            finished = false;
        }
        if (!finished || capped.get()) {
            try {
                p.getInputStream().close();
            } catch (Exception ignored) {
            }
            p.destroyForcibly();
        }
        try {
            reader.join(2_000);
        } catch (InterruptedException ignored) {
        }
        String ended = capped.get() ? "cap" : !finished ? "deadline" : "done";
        synchronized (out) {
            return new String[] {out.toString(), ended};
        }
    }

    /** {pid, milliseconds running} of the one Bluetooth process, or null if there is none or more than one. */
    private static long[] bluetoothProcess() {
        List<String> found = new ArrayList<>();
        String[] list = new File("/proc").list();
        if (list == null) return null;
        for (String p : list) {
            if (p.isEmpty() || !p.chars().allMatch(Character::isDigit)) continue;
            String name;
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(new File("/proc/" + p + "/cmdline").toPath());
                int end = 0;
                while (end < raw.length && raw[end] != 0) end++;
                name = new String(raw, 0, end);
            } catch (Exception e) {
                continue;
            }
            if (name.equals("com.google.android.bluetooth") || name.equals("com.android.bluetooth")) {
                found.add(p);
                continue;
            }
            // A renamed Bluetooth app: a package-like name ending in "bluetooth" that runs as Bluetooth's user id.
            if (name.matches("^[a-z0-9_.]+\\.bluetooth$")) {
                try {
                    for (String line : java.nio.file.Files.readAllLines(new File("/proc/" + p + "/status").toPath())) {
                        if (line.startsWith("Uid:")) {
                            int uid = Integer.parseInt(line.split("\\s+")[1]);
                            if (uid % 100_000 == BT_APP_ID) found.add(p);
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
        if (found.size() != 1) return null;
        String pid = found.get(0);
        try {
            String stat = new String(java.nio.file.Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()));
            // Field 22 (start time, in clock ticks since boot), counted after the ")" that ends the name.
            String[] f = stat.substring(stat.lastIndexOf(')') + 1).trim().split(" ");
            long start = Long.parseLong(f[19]);
            long ticks = android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK);
            if (ticks <= 0) ticks = 100;
            return new long[] {Long.parseLong(pid), SystemClock.elapsedRealtime() - start * 1000 / ticks};
        } catch (Exception e) {
            return null;
        }
    }

    private static Context systemContext() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> ctor = at.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object thread = ctor.newInstance();
        java.lang.reflect.Field current = at.getDeclaredField("sCurrentActivityThread");
        current.setAccessible(true);
        current.set(null, thread);
        java.lang.reflect.Field system = at.getDeclaredField("mSystemThread");
        system.setAccessible(true);
        system.setBoolean(thread, true);
        Method getSystemContext = at.getDeclaredMethod("getSystemContext");
        getSystemContext.setAccessible(true);
        return (Context) getSystemContext.invoke(thread);
    }

    /** A Bluetooth adapter that presents the adb shell user's identity. */
    private static BluetoothAdapter shellAdapter(Context ctx, AttributionSource shell) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "bluetooth_manager");
        if (binder == null) throw new IllegalStateException("the Bluetooth manager service is not running");
        Class<?> mgrClass = Class.forName("android.bluetooth.IBluetoothManager");
        Object mgr = Class.forName("android.bluetooth.IBluetoothManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
        try {
            Constructor<BluetoothAdapter> c = BluetoothAdapter.class.getDeclaredConstructor(mgrClass, Context.class, AttributionSource.class);
            c.setAccessible(true);
            return c.newInstance(mgr, ctx, shell);
        } catch (NoSuchMethodException e) {
            Constructor<BluetoothAdapter> c = BluetoothAdapter.class.getDeclaredConstructor(mgrClass, AttributionSource.class);
            c.setAccessible(true);
            return c.newInstance(mgr, shell);
        }
    }

    private static final class ShellContext extends ContextWrapper {
        private final AttributionSource source;

        ShellContext(Context base, AttributionSource source) {
            super(base);
            this.source = source;
        }

        @Override
        public String getPackageName() {
            return "com.android.shell";
        }

        @Override
        public String getOpPackageName() {
            return "com.android.shell";
        }

        @Override
        public AttributionSource getAttributionSource() {
            return source;
        }

        @Override
        public Context getApplicationContext() {
            return this;
        }
    }
}
