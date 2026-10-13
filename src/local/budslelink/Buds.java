package local.budslelink;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Recognizes the Galaxy Buds and checks the Samsung apps the helper depends on. */
final class Buds {
    static final String PLUGIN = "com.samsung.accessory.budsunitemgr";
    static final String WEARABLE = "com.samsung.android.app.watchmanager";
    // The plugin build whose behavior this helper was written against (behavior studied 2026-10-08).
    static final long KNOWN_GOOD_PLUGIN_VERSION = 901001720L;

    private Buds() {}

    static boolean hasBluetoothPermission(Context context) {
        return context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    /**
     * True for Galaxy Buds, including ones renamed after the helper first saw them.
     * Every other device (other earbuds, headphones, speakers, keyboards...) is ignored
     * outright. The helper never connects or disconnects any Bluetooth device anyway; it
     * only signals Samsung's plugin, which acts solely on the Buds it manages.
     */
    static boolean isBuds(Context context, BluetoothDevice device) {
        if (device == null) {
            return false;
        }
        String name = null;
        try {
            name = device.getName();
        } catch (SecurityException ignored) {
            // Fall back to the remembered addresses.
        }
        if (name != null && name.toLowerCase(Locale.ROOT).contains("galaxy buds")) {
            remember(context, device.getAddress());
            return true;
        }
        return knownAddresses(context).contains(device.getAddress());
    }

    /** Learns the addresses of already-paired Galaxy Buds. */
    static void learnBonded(Context context) {
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null || !hasBluetoothPermission(context)) {
            return;
        }
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                isBuds(context, device);
            }
        } catch (SecurityException ignored) {
            // Permission revoked between the check and the call.
        }
    }

    /**
     * The paired Galaxy Buds to show in Bluetooth settings: the dual-mode earbud (the
     * pair's main address, the one Settings lists) when known, else any paired Buds.
     */
    static BluetoothDevice mainBuds(Context context) {
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null || !hasBluetoothPermission(context)) {
            return null;
        }
        BluetoothDevice fallback = null;
        try {
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                if (!isBuds(context, device)) {
                    continue;
                }
                if (device.getType() == BluetoothDevice.DEVICE_TYPE_DUAL) {
                    return device;
                }
                if (fallback == null) {
                    fallback = device;
                }
            }
        } catch (SecurityException ignored) {
            // Permission revoked between the check and the call.
        }
        return fallback;
    }

    private static Set<String> knownAddresses(Context context) {
        return State.prefs(context).getStringSet(State.BUDS_ADDRESSES, new HashSet<>());
    }

    private static void remember(Context context, String address) {
        Set<String> known = knownAddresses(context);
        if (!known.contains(address)) {
            Set<String> updated = new HashSet<>(known);
            updated.add(address);
            State.prefs(context).edit().putStringSet(State.BUDS_ADDRESSES, updated).apply();
        }
    }

    /** Problems with Samsung's apps that would stop the Buds app working, or null. */
    static String pluginProblem(Context context) {
        PackageManager pm = context.getPackageManager();
        PackageInfo plugin;
        try {
            plugin = pm.getPackageInfo(PLUGIN, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return context.getString(R.string.problem_plugin_missing);
        }
        ApplicationInfo info = plugin.applicationInfo;
        if (info == null || !info.enabled) {
            return context.getString(R.string.problem_plugin_disabled);
        }
        // Samsung ships an incomplete x86_64 library set; installed that way the plugin
        // crashes on Intel Googlebooks. It must run as arm64 (lib dir ".../lib/arm64").
        String libDir = info.nativeLibraryDir;
        if (libDir != null && libDir.endsWith("x86_64")) {
            return context.getString(R.string.problem_plugin_x86);
        }
        return null;
    }

    static String pluginVersion(Context context) {
        try {
            PackageInfo plugin = context.getPackageManager().getPackageInfo(PLUGIN, 0);
            return plugin.versionName + (plugin.getLongVersionCode() == KNOWN_GOOD_PLUGIN_VERSION
                    ? "" : " " + context.getString(R.string.plugin_version_changed));
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }
}
