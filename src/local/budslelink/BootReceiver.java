package local.budslelink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Re-arms the checks after a reboot or a helper update, and checks the plugin's mode after a reboot. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            // Every Bluetooth link is gone after a reboot, whatever was last recorded.
            State.setClassic(context, State.CLASSIC_DOWN);
            // A restart (especially into a system update) can switch Samsung's plugin to Intel mode.
            ArmJob.schedule(context, 5_000, false);
        } else if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        CheckJob.schedulePeriodic(context);
        CheckJob.scheduleVerify(context, 20_000);
    }
}
