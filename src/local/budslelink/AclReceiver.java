package local.budslelink;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

/**
 * Layer 1: nudges the Buds plugin the moment a Galaxy Buds link comes up or goes down.
 *
 * Up: an earbud just (re)connected over LE Audio, so the plugin needs to open its
 * control link. Down: either the plugin's classic control link dropped while LE Audio
 * is still up (it should reconnect), or the Buds went away (the nudge is a no-op).
 * A verify check follows each series to confirm the control link actually came up.
 */
public class AclReceiver extends BroadcastReceiver {
    // LE Audio finishes connecting a second or two after the ACL link, so wait before
    // the first nudge, then retry in case the earbuds were still settling.
    private static final long[] DELAYS_MS = {4000, 6500, 12000, 15000};
    // Both earbuds and both transports fire events at nearly the same moment.
    private static final long DEBOUNCE_MS = 3000;

    private static long lastSeriesAt = -DEBOUNCE_MS;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        if (!connected && !BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action)) {
            return;
        }
        BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (!Buds.isBuds(context, device)) {
            return;
        }

        int transport = intent.getIntExtra(BluetoothDevice.EXTRA_TRANSPORT, -1);
        if (transport == BluetoothDevice.TRANSPORT_BREDR) {
            State.setClassic(context, connected ? State.CLASSIC_UP : State.CLASSIC_DOWN);
            if (connected) {
                Checks.reconnected(context);
            }
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastSeriesAt < DEBOUNCE_MS) {
            return;
        }
        lastSeriesAt = now;
        Log.i(Nudge.TAG, action + " (transport " + transport + ") for Galaxy Buds, nudging plugin");

        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        Handler handler = new Handler(Looper.getMainLooper());
        for (int i = 0; i < DELAYS_MS.length; i++) {
            boolean last = i == DELAYS_MS.length - 1;
            handler.postDelayed(() -> {
                Nudge.send(app);
                if (last) {
                    pending.finish();
                }
            }, DELAYS_MS[i]);
        }
        CheckJob.scheduleVerify(app, 30_000);
    }
}
