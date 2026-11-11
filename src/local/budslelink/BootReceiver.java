package local.budslelink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Re-arms the checks after a reboot or a helper update, and checks the plugin's mode after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Every Bluetooth link is gone after a reboot, whatever was last recorded.
            State.setClassic(context, State.CLASSIC_DOWN);
            // A restart (especially into a system update) can switch Samsung's plugin to Intel mode.
            // Each job is scheduled on its own, so a problem with one never stops the others.
            try {
                ArmJob.schedule(context, 5_000, false);
            } catch (RuntimeException e) {
                Log.w(Nudge.TAG, "could not schedule the ARM check", e);
            }
            // A restart can also take the Galaxy Buds off LE Audio's auto-connect list. Bluetooth starts
            // first for Android's user 0 and then for this user, so the check waits a minute.
            try {
                AutoConnectJob.schedule(context, 60_000, "arm");
            } catch (RuntimeException e) {
                Log.w(Nudge.TAG, "could not schedule the auto-connect check", e);
            }
        } else if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        CheckJob.schedulePeriodic(context);
        CheckJob.scheduleVerify(context, 20_000);
    }
}
