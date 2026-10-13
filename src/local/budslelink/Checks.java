package local.budslelink;

import android.Manifest;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import java.util.ArrayList;
import java.util.List;

/**
 * Layer 4: finds anything that would stop the helper or the Buds app from working and
 * posts a notification, instead of failing silently.
 */
final class Checks {
    private static final String CHANNEL = "alerts";
    private static final int SETUP_NOTIFICATION = 1;
    private static final int RECONNECT_NOTIFICATION = 2;

    private Checks() {}

    /** Human-readable problems, empty when everything is set up correctly. */
    static List<String> setupProblems(Context context) {
        List<String> problems = new ArrayList<>();
        if (!Buds.hasBluetoothPermission(context)) {
            problems.add(context.getString(R.string.problem_bluetooth_permission));
        }
        if (!context.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(context.getPackageName())) {
            problems.add(context.getString(R.string.problem_battery));
        }
        if (context.getSystemService(ActivityManager.class).isBackgroundRestricted()) {
            problems.add(context.getString(R.string.problem_background_restricted));
        }
        if (!context.getPackageManager().isAutoRevokeWhitelisted()) {
            problems.add(context.getString(R.string.problem_hibernation));
        }
        String plugin = Buds.pluginProblem(context);
        if (plugin != null) {
            problems.add(plugin);
        }
        return problems;
    }

    static boolean canNotify(Context context) {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static void refreshSetupWarning(Context context) {
        List<String> problems = setupProblems(context);
        if (problems.isEmpty()) {
            cancel(context, SETUP_NOTIFICATION);
        } else {
            notify(context, SETUP_NOTIFICATION, context.getString(R.string.alert_setup_title),
                    String.join("\n", problems));
        }
    }

    /** The plugin's control link came up: clear failure tracking. */
    static void reconnected(Context context) {
        State.prefs(context).edit().putInt(State.FAILED_CHECKS, 0).apply();
        cancel(context, RECONNECT_NOTIFICATION);
    }

    /** No Buds on LE Audio, so there is nothing to reconnect. */
    static void budsGone(Context context) {
        reconnected(context);
    }

    /** Records a failed verify check and alerts once there have been enough. */
    static int verifyFailed(Context context, int failuresBeforeAlert) {
        int failures = State.prefs(context).getInt(State.FAILED_CHECKS, 0) + 1;
        State.prefs(context).edit().putInt(State.FAILED_CHECKS, failures).apply();
        if (failures >= failuresBeforeAlert) {
            notify(context, RECONNECT_NOTIFICATION, context.getString(R.string.alert_reconnect_title),
                    context.getString(R.string.alert_reconnect_text));
        }
        return failures;
    }

    private static void notify(Context context, int id, String title, String text) {
        if (!canNotify(context)) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL, context.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(context, id,
                new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(id, new Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    private static void cancel(Context context, int id) {
        context.getSystemService(NotificationManager.class).cancel(id);
    }
}
