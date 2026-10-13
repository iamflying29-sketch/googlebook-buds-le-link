package local.budslelink;

import android.content.Context;
import android.content.SharedPreferences;

/** What the helper has observed, kept across process deaths and reboots. */
final class State {
    // Addresses of earbuds seen with a "Galaxy Buds" name, so renamed Buds still match.
    static final String BUDS_ADDRESSES = "buds_addresses";
    // Classic (BR/EDR) link to the Buds: CLASSIC_UNKNOWN until an event is seen. Over LE
    // Audio only the plugin's control link uses classic Bluetooth, so this being up
    // means the plugin is (almost always) connected.
    static final String CLASSIC = "classic";
    static final int CLASSIC_UNKNOWN = -1;
    static final int CLASSIC_DOWN = 0;
    static final int CLASSIC_UP = 1;
    static final String CLASSIC_CHANGED_AT = "classic_changed_at";
    static final String LAST_NUDGE_AT = "last_nudge_at";
    static final String LAST_LE_AUDIO_SEEN_AT = "last_le_audio_seen_at";
    // Consecutive verify checks that found LE Audio up but no control link.
    static final String FAILED_CHECKS = "failed_checks";

    private State() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    static void setClassic(Context context, int classic) {
        prefs(context).edit()
                .putInt(CLASSIC, classic)
                .putLong(CLASSIC_CHANGED_AT, System.currentTimeMillis())
                .apply();
    }

    static int classic(Context context) {
        return prefs(context).getInt(CLASSIC, CLASSIC_UNKNOWN);
    }
}
