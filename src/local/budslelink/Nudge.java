package local.budslelink;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Wakes Samsung's Buds plugin (com.samsung.accessory.budsunitemgr) so it opens its
 * control link to Buds that are connected over LE Audio only.
 *
 * The plugin only auto-connects on classic A2DP/HFP events, which never arrive when
 * the host blocks classic profiles for an LE Audio device (non-Samsung Android without
 * dual-mode audio). But its "Bluetooth ready" broadcast is registered RECEIVER_EXPORTED
 * with no permission, and each one:
 *   1. checks its device DB and connects any device marked connected whose A2DP,
 *      headset OR LE Audio profile is up (Application.connectToBudsPlugin), then
 *   2. refreshes that DB flag from the live profile states (updateDeviceConnectionState).
 * Step 1 runs before step 2, so the first broadcast fixes a stale flag and the next
 * one connects. Extra broadcasts are harmless: an existing or in-progress connection
 * is left alone (the plugin just refreshes its widget).
 */
final class Nudge {
    static final String TAG = "BudsLELink";
    static final String ACTION_READY =
            "com.samsung.accessory.hearablemgr.core.bluetooth.BluetoothManager.ACTION_READY";

    private Nudge() {}

    static void send(Context context) {
        Log.i(TAG, "Sending ACTION_READY to " + Buds.PLUGIN);
        context.sendBroadcast(new Intent(ACTION_READY).setPackage(Buds.PLUGIN));
        State.prefs(context).edit().putLong(State.LAST_NUDGE_AT, System.currentTimeMillis()).apply();
    }
}
