package local.budslelink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Commands that only the adb shell (or the system) can send: the manifest requires
 * android.permission.DUMP from the sender.
 *   PROVISION: stores Buds LE Link's own paired adb key (PKCS#12, base64) in private storage.
 *   PROVE:     answers a random nonce, so the app knows an adb connection really reaches
 *              Android's adb service and not something imitating it.
 *   ARMTEST:   runs the after-restart check now. By default it forces the whole repair even though
 *              the plugin is in ARM mode (a test that changes nothing); with --ez force false it
 *              runs exactly the check a restart runs.
 */
public class ControlReceiver extends BroadcastReceiver {
    static final Set<String> PROVEN = ConcurrentHashMap.newKeySet();

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ("local.budslelink.PROVE".equals(action)) {
            String nonce = intent.getStringExtra("nonce");
            if (nonce != null) PROVEN.add(nonce);
            setResultData("proven");
        } else if ("local.budslelink.PROVISION".equals(action)) {
            String p12 = intent.getStringExtra("p12");
            String pass = intent.getStringExtra("pass");
            if (p12 == null || pass == null) {
                setResultData("missing p12 or pass");
                return;
            }
            try {
                byte[] key = Base64.decode(p12, Base64.DEFAULT);
                File tmp = new File(context.getFilesDir(), "adb.p12.new");
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    out.write(key);
                    out.getFD().sync();
                }
                if (!tmp.renameTo(ArmJob.keyFile(context))) throw new java.io.IOException("rename failed");
                State.prefs(context).edit().putString(ArmJob.P12_PASS, pass).remove(ArmJob.PORT).commit();
                setResultData("provisioned");
                Log.i(Nudge.TAG, "adb key provisioned");
            } catch (Exception e) {
                setResultData("failed: " + e);
            }
        } else if ("local.budslelink.ARMTEST".equals(action)) {
            ArmJob.schedule(context, 0, intent.getBooleanExtra("force", true));
            setResultData("scheduled");
        }
    }
}
