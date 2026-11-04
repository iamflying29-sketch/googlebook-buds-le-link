package local.budslelink;

import android.content.Context;
import android.util.Log;

/**
 * Restarts Samsung's Buds plugin when it is stuck.
 *
 * Seen 2026-11-04: after days of running, the plugin kept an internal plugin object for the Buds whose
 * connection was empty. On every "Bluetooth ready" signal it logged "Plugin already exists ... connection =
 * null" and "keeping plugin alive", then never tried to open its control link. Restarting the plugin (a
 * force-stop, the same as closing and reopening the app) cleared it, and the control link was up 4 seconds
 * later. The verify check calls this only after confirming for several minutes that the Buds are on LE Audio
 * while the control link is down, and never more than once in MIN_GAP_MS.
 */
final class PluginRestart {
    static final String LAST_RESTART_AT = "lastPluginRestartAt";
    private static final long MIN_GAP_MS = 30 * 60 * 1000L;

    private PluginRestart() {}

    /** True when the app has its own adb key and has not restarted the plugin in the last 30 minutes. */
    static boolean allowed(Context context) {
        long last = State.prefs(context).getLong(LAST_RESTART_AT, 0);
        long now = System.currentTimeMillis();
        return ArmJob.provisioned(context) && (now - last >= MIN_GAP_MS || now < last);
    }

    /** Force-stops the plugin. Blocking (up to about 30 s): call it off the main thread. */
    static boolean run(Context context) {
        // Counted before trying, so that even a failed attempt keeps the 30-minute gap.
        State.prefs(context).edit().putLong(LAST_RESTART_AT, System.currentTimeMillis()).commit();
        try (AdbClient adb = AdbAccess.open(context)) {
            if (adb == null) {
                Log.w(Nudge.TAG, "plugin restart: Wireless debugging not reachable");
                return false;
            }
            String out = adb.exec("am force-stop --user " + AdbAccess.userId() + " " + Buds.PLUGIN + " 2>&1").trim();
            Log.i(Nudge.TAG, "plugin restart: restarted Samsung's plugin" + (out.isEmpty() ? "" : " (" + out + ")"));
            return true;
        } catch (Exception e) {
            Log.w(Nudge.TAG, "plugin restart failed", e);
            return false;
        }
    }
}
