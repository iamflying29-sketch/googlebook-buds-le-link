package local.budslelink;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Status window: reconnects the Buds app right away and shows whether everything is set up. It is just a
 * window. Closing it (X) never affects the background part (AclReceiver, CheckJob, BootReceiver).
 */
public class MainActivity extends Activity {
    private static final long REFRESH_MS = 2000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, REFRESH_MS);
        }
    };
    private TextView status;
    private LinearLayout checks;
    private int pad;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pad = (int) (16 * getResources().getDisplayMetrics().density);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = text(getString(R.string.app_name), 22);
        root.addView(title);
        TextView about = text(getString(R.string.about), 14);
        about.setPadding(0, pad / 2, 0, pad);
        root.addView(about);
        status = text("", 16);
        root.addView(status);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(0, pad, 0, pad);
        Button reconnect = new Button(this);
        reconnect.setText(R.string.button_reconnect);
        reconnect.setOnClickListener(v -> reconnect());
        row.addView(reconnect);
        Button wearable = new Button(this);
        wearable.setText(R.string.button_open_wearable);
        wearable.setOnClickListener(v -> openWearable());
        row.addView(wearable);
        root.addView(row);

        TextView checksTitle = text(getString(R.string.checks_title), 16);
        checksTitle.setTypeface(Typeface.DEFAULT_BOLD);
        checksTitle.setPadding(0, pad, 0, pad / 4);
        root.addView(checksTitle);
        checks = new LinearLayout(this);
        checks.setOrientation(LinearLayout.VERTICAL);
        root.addView(checks);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);

        requestMissingPermissions();
        CheckJob.schedulePeriodic(this);
        Buds.learnBonded(this);
        reconnect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        Buds.learnBonded(this);
        reconnect();
    }

    private void reconnect() {
        // Same two-step as the automatic path: refresh the plugin's state, then connect.
        Nudge.send(this);
        handler.postDelayed(() -> Nudge.send(getApplicationContext()), 2500);
        CheckJob.scheduleVerify(this, 30_000);
        refresh();
    }

    private void openWearable() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(Buds.WEARABLE);
        if (launch != null) {
            startActivity(launch);
        }
    }

    private void requestMissingPermissions() {
        List<String> missing = new ArrayList<>();
        for (String permission : new String[] {Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS}) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), 1);
        }
    }

    private void refresh() {
        StringBuilder text = new StringBuilder();
        int classic = State.classic(this);
        long changedAt = State.prefs(this).getLong(State.CLASSIC_CHANGED_AT, 0);
        text.append(getString(classic == State.CLASSIC_UP ? R.string.status_link_up
                : classic == State.CLASSIC_DOWN ? R.string.status_link_down : R.string.status_link_unknown));
        if (changedAt > 0 && classic != State.CLASSIC_UNKNOWN) {
            text.append(' ').append(getString(R.string.status_since, time(changedAt)));
        }
        long nudgedAt = State.prefs(this).getLong(State.LAST_NUDGE_AT, 0);
        if (nudgedAt > 0) {
            text.append('\n').append(getString(R.string.status_last_signal, time(nudgedAt)));
        }
        String version = Buds.pluginVersion(this);
        text.append('\n').append(version == null ? getString(R.string.problem_plugin_missing)
                : getString(R.string.status_plugin, version));
        status.setText(text);

        checks.removeAllViews();
        String pkg = getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        row(R.string.check_bluetooth, Buds.hasBluetoothPermission(this), v -> requestMissingPermissions());
        row(R.string.check_notifications, Checks.canNotify(this), v -> requestMissingPermissions());
        row(R.string.check_battery, getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg),
                v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri)));
        row(R.string.check_background, !getSystemService(ActivityManager.class).isBackgroundRestricted(),
                v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri)));
        row(R.string.check_hibernation, getPackageManager().isAutoRevokeWhitelisted(),
                v -> startActivity(new Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, pkgUri)));
        String pluginProblem = Buds.pluginProblem(this);
        row(R.string.check_plugin, pluginProblem == null, null);
        if (pluginProblem != null) {
            checks.addView(text(pluginProblem, 14));
        }
        Checks.refreshSetupWarning(this);
    }

    private void row(int label, boolean ok, View.OnClickListener fix) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text((ok ? "✓  " : "⚠  ") + getString(label), 15);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!ok && fix != null) {
            Button button = new Button(this);
            button.setText(R.string.button_fix);
            button.setOnClickListener(fix);
            row.addView(button);
        }
        checks.addView(row);
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        return view;
    }

    private String time(long millis) {
        return DateFormat.getTimeFormat(this).format(millis);
    }
}
