# Buds LE Link

Buds LE Link keeps Samsung's Galaxy Buds4 fully connected to Samsung's Galaxy Wearable app on a Lenovo Googlebook
running GooglebookOS, where the earbuds connect over Bluetooth LE Audio only. It is a small background app that
signals Samsung's Galaxy Buds plugin to open its control link whenever the earbuds connect, checks the link every
15 minutes, and warns me only when the link really fails. This repository also documents the fix that came first:
reinstalling the Galaxy Buds plugin in its ARM version so that Galaxy Wearable stops crashing on the Googlebook's
Intel processor. Since version 2.3, Buds LE Link also keeps the plugin in ARM mode after every restart, because a
GooglebookOS update can switch it back to Intel mode. Since version 2.4, it also restarts the plugin by itself
when the plugin gets stuck and stops connecting. Since version 2.5, it also puts the Galaxy Buds back on LE Audio's
auto-connect list after a restart if the restart took them off it.

## What it does

Buds LE Link keeps my Galaxy Buds4 fully connected to the Galaxy Wearable app on my Googlebook, so the earbuds'
controls, settings and battery widget work without tapping Connect over and over. It runs in the background on its
own:

- A few seconds after the earbuds connect (for example when I take them out of the case), it sends Samsung's Galaxy
  Buds plugin the signal that makes the plugin open its control link to the earbuds. Audio stays on LE Audio the
  whole time.
- Every 15 minutes it checks whether the earbuds are connected over LE Audio and, if they are, sends the signal
  again. This catches anything the instant path missed.
- After each Bluetooth event it verifies that the control link actually came up. If five checks in a row, about a
  minute apart, find the link still down, it restarts Samsung's plugin (when the optional pairing from Part 3 is set
  up). It warns me with a notification only after eight failed checks in a row, so that a slow reconnect never looks
  like a failure.
- It checks its own setup (permissions, battery settings, background limits, and whether the plugin is installed,
  enabled and running in ARM mode) and tells me if anything needs fixing.
- After every restart it checks whether Android has switched the plugin to Intel mode. If it has, and the optional
  automatic ARM repair is set up, it reinstalls the saved plugin in ARM mode by itself and tells me with a
  notification.
- A minute after every restart it checks whether the Galaxy Buds are still on LE Audio's auto-connect list, because
  a restart can take them off it. If they are off it, and the optional pairing from Part 3 is set up, it puts them
  back by itself. It warns me only if that does not work.

A status window shows the link state, the time of the last reconnect signal, the installed plugin version and the
setup checks. It has a **Reconnect now** button and an **Open Galaxy Wearable** button, and every failed setup
check has a **Fix** button where the app can fix it.

The project also includes the fix that stopped Galaxy Wearable from crashing in the first place.

## The problem

### First problem: Galaxy Wearable crashed

Galaxy Wearable crashed the moment the Buds4 were connected, and then on every launch it closed and jumped to
Bluetooth settings. The Buds widget never appeared.

Galaxy Wearable itself was fine. It hands the earbuds over to a separate Galaxy Buds plugin app
(`com.samsung.accessory.budsunitemgr`), and that plugin was the part that crashed. The Googlebook has an x86-64
Intel processor, so Android installed the plugin with its x86-64 native libraries. In version 9.0.10.1720,
Samsung's x86-64 library folder contains only 3 of the plugin's 8 native libraries. The ARM folders contain all
eight. The missing ones are `libFotaServiceInfo.so`, `libDiagMonKey.so`, `libSwPedometer.so`, `libsbc.so` and
`libsbc-libs.so`. The plugin tried to load one of them at startup, failed with an `UnsatisfiedLinkError`, and
crashed.

### Second problem: the plugin showed "disconnected"

Once the crash was fixed, a second problem appeared. The plugin's screens and its widget showed "disconnected" while
music was playing through the earbuds, and I had to tap Connect every time. The link also dropped at random.

The root cause is how GooglebookOS and the plugin each handle Bluetooth:

1. **The Googlebook connects the Buds4 over LE Audio only.** Dual-mode audio is off, and GooglebookOS turns down the
   earbuds' incoming classic audio connections (A2DP for music and HFP for calls) and uses LE Audio for everything.
2. **The plugin only reconnects by itself when a classic audio connection comes up.** It listens for the classic
   A2DP and headset connection broadcasts, and those never arrive on this laptop.
3. **The plugin ignores the LE Audio connection.** It does receive the LE Audio connection broadcasts, but it drops
   them unless a check passes that the Buds4 can never pass here. The check accepts only one other model (the Galaxy
   Buds On), and it also requires a hidden Bluetooth method that exists only in Samsung's own Android builds, plus a
   test file. When it fails, the plugin logs `supportLEconnetion: model is not support` (Samsung's spelling).
4. **The control channel does not come back.** The plugin talks to the earbuds over its own classic Bluetooth
   control channel (an RFCOMM socket), separate from the audio. That channel closes whenever an earbud's link blips,
   and nothing in the plugin reopens it.

So the earbuds played music perfectly over LE Audio, but the plugin, its widget and every setting it controls
believed they were disconnected.

## How I solved it

### Fixing the crash

I reinstalled the same Play Store copy of the plugin, but forced it to use its ARM (`arm64-v8a`) libraries, which
are complete. GooglebookOS translates ARM code so that it runs on the Intel processor. The reinstall keeps the
plugin's data and keeps the Play Store recorded as its installer, as it was before. I then granted the plugin the
Nearby devices permissions and the notification permission it had never been given. The plugin started normally,
and its widget appeared by itself.

Because a Google Play update could put the broken Intel build back, I turned off automatic updates for the
plugin.

### Keeping the control link connected

I wanted to keep LE Audio, so turning it off and falling back to classic Bluetooth audio was not an option. Instead,
I looked for whatever makes the plugin connect on its own. From the plugin's log messages and a study of its code, I
found this:

- At startup, the plugin sends itself an internal "Bluetooth ready" broadcast,
  `com.samsung.accessory.hearablemgr.core.bluetooth.BluetoothManager.ACTION_READY`.
- Its receiver for that broadcast is exported without any permission, so any app may send it.
- Each time it arrives, the plugin first connects to any earbuds that its database marks as connected and whose
  A2DP, headset **or LE Audio** profile is up. Then it refreshes that database flag from the live Bluetooth state.
  This handler, unlike the others, does count LE Audio as connected.

Because the connect step runs before the refresh step, the first broadcast fixes a stale "disconnected" flag and the
second one opens the control channel. Extra broadcasts are harmless: when a connection already exists or is in
progress, the plugin leaves it alone.

Buds LE Link sends that broadcast at the right moments, addressed only to the plugin's package. It works in four
layers:

1. **Instant reconnect (AclReceiver).** Android announces every Bluetooth link that comes up or goes down
   (`ACL_CONNECTED` and `ACL_DISCONNECTED`), and these announcements reach the app even when it is not running. For
   any device whose name contains "Galaxy Buds" (or whose address was seen with that name before), the helper sends
   the broadcast at about 4, 6.5, 12 and 15 seconds after the event. LE Audio finishes connecting a second or two
   after the link itself, so the first signal waits, and the later ones cover earbuds that were still settling. Both
   earbuds and both transports announce themselves at nearly the same moment, so a new series starts only when at
   least 3 seconds have passed since the last one began. Each series also schedules a verify check 30 seconds
   later.
2. **Periodic check (CheckJob, every 15 minutes).** A scheduled job that survives reboots. It re-learns the paired
   Galaxy Buds, refreshes the setup warnings, and asks Android's LE Audio service which devices are connected (with
   a 10-second timeout). If Galaxy Buds are connected over LE Audio, it sends the broadcast twice, 2.5 seconds
   apart. This repairs anything the instant path missed, including a future Android change that stops delivering
   the link announcements.
3. **Verify check (CheckJob).** Over LE Audio only, the plugin's control channel is the only classic Bluetooth
   (BR/EDR) link to the earbuds, so the helper records whether a classic link to the Buds is up. A verify check
   first confirms the earbuds are on LE Audio. If they are not, there is nothing to reconnect, and it clears any
   earlier failures. If the classic link is up, all is well. If it is confirmed down, the check counts a failure,
   sends the broadcast twice, and tries again about a minute later. After 5 failures in a row it restarts the
   plugin (see [Restarting a stuck plugin](#restarting-a-stuck-plugin)), after 8 it posts a notification, and after
   10 it stops retrying (the 15-minute check keeps going). An unknown state, such as right after installation, is
   never counted as a failure.
4. **Setup checks (Checks and MainActivity).** The helper looks for anything that would stop it or the plugin from
   working: a missing Nearby devices permission, battery optimization turned back on, restricted background
   activity, the risk of being paused as an unused app, and a plugin that is missing, disabled or installed in Intel
   mode. If it finds a problem, it posts a notification instead of failing silently.

After a reboot, a boot receiver marks the classic link as down (every Bluetooth link is gone after a reboot),
re-creates the 15-minute check and schedules a verify check 20 seconds later. After the helper itself is updated,
it re-creates the check and schedules the verify check the same way.

In the first test, the plugin's link came back about 4 seconds after the earbuds came out of the case.

I wanted it to be foolproof, so version 2 added the 15-minute check, the setup checks with a Fix button for every
permission and battery setting it needs, and the status window. Version 2.2 turned that window into a normal app
window that closes cleanly without affecting the background part. Version 2.3 added the automatic ARM repair,
version 2.4 added the restart of a stuck plugin, and version 2.5 added the auto-connect check after restarts. All
three are described next.

### Keeping the plugin in ARM mode after restarts

A GooglebookOS system update broke the crash fix. After the Googlebook restarted into the update, the plugin was in
Intel mode again (`primaryCpuAbi=x86_64`), although nothing had reinstalled it: its version and its install time were
unchanged. Buds LE Link's setup check caught it and warned me, and I reinstalled the plugin in ARM mode by hand.

The ARM choice that `pm install --abi arm64-v8a` makes is not a lasting setting. Right after the reinstall, the plugin
lists `cpuAbiOverride=arm64-v8a`, but after a plain restart that entry is empty again. In my test a plain restart
kept the plugin in ARM mode anyway. The system update is the one time I saw it switch to Intel, and I have not
proven exactly why the update decided the mode again.

Fixing that needs the rights of adb, which an ordinary app does not have. So version 2.3 gives Buds LE Link its own
Wireless debugging pairing, with its own key that is separate from every other pairing:

1. **After every restart** (and only then), a boot receiver schedules a check 5 seconds later. The check asks
   Android which library folder the plugin uses. That needs no adb and takes a moment.
2. **ARM mode:** the check does nothing more.
3. **Intel mode:** the app finds the Googlebook's current Wireless debugging port (it moves after every Wi-Fi
   reconnection), connects to it on this device itself (`127.0.0.1`) with its own key, and proves that it reached
   Android's real adb service. It sends a random code that only a command from the adb shell can deliver back to the
   app. Then it checks the fingerprint (SHA-256) of the saved plugin copy and runs
   `pm install -r --abi arm64-v8a -i com.android.vending` with that copy, which keeps the plugin's data. Afterwards it
   confirms that the plugin is in ARM mode, posts **Samsung Buds plugin repaired**, and schedules a reconnect check.
4. **If it cannot repair it** (no Wi-Fi yet, or Wireless debugging is off), it tries again with a growing delay that
   starts at 30 seconds, for up to 20 attempts. The **Buds LE Link needs attention** warning stays up until the plugin
   is in ARM mode again, so a failure is never silent.

I tested it three ways on my Googlebook. A forced test ran the whole repair while the plugin was already in ARM mode,
and the reinstall succeeded in about a second and a half. For a real test I put the plugin into Intel mode myself,
the way the update had, and ran the check: the plugin was back in ARM mode 1.1 seconds later, and the Galaxy Buds app
reconnected about 4 seconds after that without crashing. Finally I restarted the Googlebook, and Android's job history
showed the check running about 15 seconds after startup and leaving the plugin, which was in ARM mode, untouched.

### Restarting a stuck plugin

After the plugin had been running for several days, the Galaxy Buds app stayed disconnected although the earbuds
played audio. The plugin was in ARM mode and had not crashed. Buds LE Link had logged ten failed checks and posted its
warning. The plugin's own log showed what happened on every reconnect signal: it found its existing plugin object for
the earbuds, reported `Plugin already exists ... connection = null` and `Plugin connection not yet ready (time: 2ms),
keeping plugin alive`, called `connectToDevice()`, and then never tried to open its control channel. Its log had no
connection attempt at all for more than a day. I have not found out how the plugin got into that state.

Restarting the plugin (a force-stop, the same as closing and reopening the app) cleared it. The plugin started
fresh, opened its control channel, and was connected 4 seconds later.

Version 2.4 does that by itself. When five verify checks in a row, about a minute apart, have confirmed that the
earbuds are on LE Audio while the control channel is down, Buds LE Link connects to Wireless debugging with the same
pairing as the ARM repair, proves it reached Android's adb service, and runs `am force-stop` for the plugin. Then it
sends the reconnect signal. It restarts the plugin at most once every 30 minutes, so it can never loop. The
**Galaxy Buds app couldn't reconnect** warning now comes only after eight failed checks, which gives the restart
about three minutes to work. Without the pairing from Part 3, the helper cannot restart the plugin and only warns
you.

To test it, I made the next check act as if the plugin had been stuck for five minutes. The helper counted the fifth
failure, found the current Wireless debugging port by itself because the saved one had moved, restarted the plugin,
and the control channel was connected 3 seconds later. The next check found the link up and posted no warning. I
have not yet seen the 30-minute limit act in a live test.

### Keeping LE Audio auto-connect after restarts

My Pixel Buds stopped connecting by themselves after some restarts of the Googlebook, and Bluetooth's log showed
why. When GooglebookOS starts, Bluetooth first starts for Android's user 0, whose Bluetooth database has LE Audio
turned off for the earbuds. It takes them off LE Audio's auto-connect list, and that is saved. Then Bluetooth
starts again for my own Android user, where LE Audio is allowed, and puts them back on the list, but only if the
earbuds are already loaded when it checks. On one restart that check ran 2 milliseconds too early, and the earbuds
stayed off the list.

The same log showed both of my Galaxy Buds4 earbuds being taken off the list in the same way on that restart. On
the next restart they were put back in time. I did not use the Buds4 after the first restart, so I have not seen
them fail to connect myself. Since the outcome is a race, I believe it can happen on any restart.

My [Buds Battery Fix](https://github.com/iamflying29-sketch/googlebook-buds-battery-fix) found the step that puts
earbuds back: applying their LE Audio setting ALLOWED again, which it already is, makes Bluetooth add them back to
the auto-connect list. Version 2.5 of Buds LE Link does the same for the Galaxy Buds:

1. **A minute after every restart** (and only then), a job connects to Wireless debugging with the pairing from
   Part 3, proves it reached Android's adb service, and runs a small tool from Buds LE Link's own APK as the adb
   shell user, because an ordinary app may not change Bluetooth connection settings and the adb shell user may. The tool waits until Bluetooth has
   run for 10 seconds, so it never acts while Bluetooth is still starting.
2. **It checks both earbuds.** For every paired device whose name contains "Galaxy Buds", it reads the LE Audio
   setting, the connection state and the latest auto-connect entry in Bluetooth's own log lines. The log read stops
   after 10 seconds or 256 KB, whichever comes first.
3. **It changes nothing when nothing is wrong.** If the earbuds are connected, are on the auto-connect list, or
   have their LE Audio setting turned off, it leaves them alone.
4. **Otherwise it re-applies the ALLOWED setting once.** Bluetooth applies it to both earbuds as a group.
5. **It confirms the result.** 35 seconds later it reads the log again. If Bluetooth is still trying to connect, it
   looks once more 30 seconds after that. It posts **Galaxy Buds auto-connect after the restart** only if the
   earbuds are not back on the list. The status window shows the result of the last check.

I tested it on my Googlebook with GooglebookOS build CL3B.260622.286.R2. A read-only run found both earbuds and
changed nothing. A test of the log reader showed that the size cap and the deadline each stop it, and that no log
reader is left running. A forced run with the earbuds in their case re-applied the setting once, Bluetooth's log
showed both earbuds put back on the auto-connect list from that one call, and the tool reported them back on the
list after its connection attempt had timed out. Running the restart check again right after that correctly found
them on the list and changed nothing. I have not yet tested it with a real restart, or while the earbuds were
connected.

## Requirements

- **Device:** a Lenovo Googlebook 15 (x86-64 Intel), or another x86-64 GooglebookOS device whose Galaxy Buds plugin
  has the same problem. I built and tested this on GooglebookOS builds CL3B.260622.271.R1 and CL3B.260622.276.R4
  (Android 17), and the auto-connect check on CL3B.260622.286.R2.
- **Earbuds:** Samsung Galaxy Buds4, paired in Bluetooth settings and connected over LE Audio. The helper reacts to
  any device whose name contains "Galaxy Buds", but I only tested the Buds4.
- **Samsung apps:** Galaxy Wearable (`com.samsung.android.app.watchmanager`) and its Galaxy Buds plugin
  (`com.samsung.accessory.budsunitemgr`) version 9.0.10.1720, both installed from Google Play. The helper was
  written against that exact plugin version.
- **Android version:** Buds LE Link needs Android 13 (API level 33) or later. It targets API level 35.
- **Permissions used by Buds LE Link:** Nearby devices (`BLUETOOTH_CONNECT`), notifications (`POST_NOTIFICATIONS`),
  starting at boot (`RECEIVE_BOOT_COMPLETED`), and permission to ask for unrestricted battery use
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). For the automatic ARM repair, the plugin restart and the
  auto-connect check it also has `INTERNET`,
  `ACCESS_NETWORK_STATE` and `ACCESS_LOCAL_NETWORK`. It uses them only to find and reach the Googlebook's own
  Wireless debugging service at `127.0.0.1`. See [Safety and privacy notes](#safety-and-privacy-notes).
- **Tools:** the Linux Terminal with adb connected to GooglebookOS over Wireless debugging, `unzip`, `openssl` for
  the optional automatic ARM repair, and, for
  building the app, a JDK plus the Android SDK build tools 35.0.0 and platform android-35. The shared
  [getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md)
  explains how to set all of this up and how to find your Android user ID.

## Installation

First follow the
[getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md) to
turn on the Linux Terminal, install adb, connect to Wireless debugging, find your Android user ID and install the
SDK build tools. Run every command below in the Linux Terminal. Wherever you see `<USER_ID>`, use the number that
`adb shell am get-current-user` printed.

### Part 1: Fix the Galaxy Wearable crash

Do this part only if Galaxy Wearable crashes or jumps to Bluetooth settings when you open it with your Buds
connected.

1. Install Galaxy Wearable from Google Play and pair your Galaxy Buds4. Galaxy Wearable then installs its Galaxy
   Buds plugin through Google Play.

2. Check the plugin's version and the processor type it was installed for:

   ```bash
   adb shell dumpsys package com.samsung.accessory.budsunitemgr | grep -E 'versionCode|versionName|primaryCpuAbi'
   ```

   The version I fixed is `versionCode=901001720` (`versionName=9.0.10.1720`). Installed for the Intel processor,
   it shows `primaryCpuAbi=x86_64`. If your version is different, read
   [Limitations and known issues](#limitations-and-known-issues) before you continue.

3. Optionally, confirm that the crash is the missing-library crash:

   ```bash
   adb logcat -d -b crash | grep -m 5 UnsatisfiedLinkError
   ```

4. Copy the plugin that Google Play already installed on your own Googlebook into the Linux Terminal. This uses
   your own legally installed copy, so you never need to download Samsung's app from anywhere else. First list its
   files:

   ```bash
   mkdir -p ~/apks/budsunitemgr
   cd ~/apks/budsunitemgr
   adb shell pm path --user <USER_ID> com.samsung.accessory.budsunitemgr
   ```

   Each line of output is one APK file, such as `package:/data/app/.../base.apk`. The copy I used was a single APK
   that contains the libraries for every processor type. Some apps are installed as a `base.apk` plus several
   `split_*.apk` files instead. Pull every file the command listed:

   ```bash
   for apk in $(adb shell pm path --user <USER_ID> com.samsung.accessory.budsunitemgr | tr -d '\r' | sed 's/^package://'); do
       adb pull "$apk"
   done
   ls -l
   ```

5. Check that your copy contains the complete ARM libraries:

   ```bash
   unzip -l base.apk 'lib/arm64-v8a/*'
   ```

   The list should show eight `.so` files, including `libFotaServiceInfo.so`, `libDiagMonKey.so`,
   `libSwPedometer.so`, `libsbc.so` and `libsbc-libs.so`. If you pulled split files and `base.apk` has no `lib/`
   folder, check them too:

   ```bash
   for f in split_*.apk; do unzip -l "$f" 'lib/*'; done
   ```

   If none of your files contain `lib/arm64-v8a/`, Google Play gave your device only the Intel libraries, and this
   fix cannot be done with that copy. Stop here.

6. Reinstall the same plugin, forced to its ARM libraries. `-r` keeps the plugin's data, `--abi arm64-v8a` selects
   the ARM libraries, and `-i com.android.vending` keeps the Play Store recorded as the installer.

   If you pulled a single `base.apk`:

   ```bash
   adb install -r --abi arm64-v8a --user <USER_ID> -i com.android.vending base.apk
   ```

   If you pulled a `base.apk` and split files, install them together in one command:

   ```bash
   adb install-multiple -r --abi arm64-v8a --user <USER_ID> -i com.android.vending base.apk split_*.apk
   ```

   Both commands print `Success` when they work. I only tested the single-file case.

7. Confirm that the plugin now runs in ARM mode:

   ```bash
   adb shell dumpsys package com.samsung.accessory.budsunitemgr | grep primaryCpuAbi
   ```

   It should now show `primaryCpuAbi=arm64-v8a`.

8. Give the plugin the Nearby devices and notification permissions it needs:

   ```bash
   for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN BLUETOOTH_ADVERTISE POST_NOTIFICATIONS; do
       adb shell pm grant --user <USER_ID> com.samsung.accessory.budsunitemgr android.permission.$p
   done
   ```

9. Turn off automatic updates for the plugin, so that Google Play does not put the broken Intel build back. Open
   the plugin's Play Store page:

   ```bash
   adb shell am start --user <USER_ID> -a android.intent.action.VIEW -d 'market://details?id=com.samsung.accessory.budsunitemgr'
   ```

   On that page, open the three-dot menu at the top and clear **Enable auto update**.

10. Open Galaxy Wearable. It should now open the Buds4 screens without crashing, and the Buds widget becomes
    available to add.

Keep the files in `~/apks/budsunitemgr`. You need them again if the plugin is ever reinstalled in Intel mode. They
are Samsung's software, so do not share them.

### Part 2: Build and install Buds LE Link

1. Get the source code. If `git` is not installed, install it first with `sudo apt install git`.

   ```bash
   git clone https://github.com/iamflying29-sketch/googlebook-buds-le-link.git ~/googlebook-buds-le-link
   cd ~/googlebook-buds-le-link
   ```

2. Build the app. The first build creates your own signing keystore, `keystore.jks`, protected by the password in
   `KS_PASS`. Choose your own password, and keep the keystore and the password, because you need both to install
   later updates over this copy.

   ```bash
   KS_PASS='your-own-password' ./build.sh
   ```

   The script expects the SDK in `~/tools/android-sdk`. If yours is somewhere else, also set `ANDROID_SDK`, for
   example `ANDROID_SDK=/path/to/sdk KS_PASS='your-own-password' ./build.sh`. It writes `BudsLELink.apk` in the
   repository folder. Each build uses a new timestamped folder under `build/`, and the script never deletes
   anything, so you can remove old build folders yourself whenever you like.

3. Install it for your Android user:

   ```bash
   adb install --user <USER_ID> BudsLELink.apk
   ```

4. Grant its permissions and protect it from the system's battery and cleanup features:

   ```bash
   adb shell pm grant --user <USER_ID> local.budslelink android.permission.BLUETOOTH_CONNECT
   adb shell pm grant --user <USER_ID> local.budslelink android.permission.POST_NOTIFICATIONS
   adb shell cmd deviceidle whitelist +local.budslelink
   adb shell cmd appops set --user <USER_ID> local.budslelink RUN_ANY_IN_BACKGROUND allow
   adb shell cmd appops set --user <USER_ID> local.budslelink AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore
   ```

   In order, these allow it to see Bluetooth devices, allow its problem notifications, exempt it from battery
   optimization, allow background activity, and stop Android from pausing it and removing its permissions when you
   have not opened it for a few months. You can also do each of these later with the **Fix** buttons in the app.

5. Open **Buds LE Link** once from the app launcher, or with this command:

   ```bash
   adb shell am start --user <USER_ID> -n local.budslelink/.MainActivity
   ```

   This step matters. Android does not deliver Bluetooth announcements to a newly installed app until it has been
   opened once. Opening it also schedules the 15-minute check and sends a first reconnect signal. If you skipped
   step 4, it asks for the Nearby devices and notification permissions now.

6. Look at the **Setup checks** in the window. Every line should have a check mark. Any line with a warning sign
   has a **Fix** button, except the plugin line, which points you back to Part 1.

7. Test it: put both earbuds in the case, close it for a few seconds, then take them out. Within a few seconds the
   window should show **Galaxy Buds app: connected**. You can now close the window. The background part keeps
   working.

### Part 3 (optional): Set up the automatic ARM repair, plugin restart and auto-connect check

Without this part, Buds LE Link still warns you when the plugin is in Intel mode or stuck, and you fix it yourself
(repeat Part 1, or restart the plugin as described in [Troubleshooting](#troubleshooting)). It also cannot check the
Galaxy Buds' auto-connect after a restart. With it, Buds LE Link repairs the ARM mode by itself after a restart,
restarts a stuck plugin by itself, and puts the Galaxy Buds back on LE Audio's auto-connect list after a restart when
needed. All three need Wireless debugging to be on. Steps 4 and 5 are only needed for the ARM repair.

1. Make an adb key just for Buds LE Link and pair it with Wireless debugging. The key gets its own folder and its own
   adb server port (5041 here), so it never mixes with the adb you normally use. On the Googlebook, open
   **Settings > System > Developer options > Wireless debugging > Pair device with pairing code**, and use the
   address, port and code it shows:

   ```bash
   K=~/.config/buds-le-link
   mkdir -p $K/adbhome/.android && chmod 700 $K $K/adbhome $K/adbhome/.android
   HOME=$K/adbhome adb keygen $K/adbhome/.android/adbkey
   sed -i 's/ [^ ]*$/ BudsLELink@googlebook/' $K/adbhome/.android/adbkey.pub
   HOME=$K/adbhome ANDROID_ADB_SERVER_PORT=5041 adb pair <IP>:<PAIRING_PORT> <PAIRING_CODE>
   HOME=$K/adbhome ANDROID_ADB_SERVER_PORT=5041 adb kill-server
   ```

   `adb pair` prints `Successfully paired` when it works.

2. Turn the key into the key file the app reads (PKCS#12, protected by a random password):

   ```bash
   (umask 077; head -c 24 /dev/urandom | base64 | tr -d '/+=' > $K/p12.pass)
   openssl req -new -x509 -key $K/adbhome/.android/adbkey -out $K/cert.pem -days 36500 -subj /CN=BudsLELink
   openssl pkcs12 -export -inkey $K/adbhome/.android/adbkey -in $K/cert.pem -out $K/key.p12 -passout "pass:$(cat $K/p12.pass)"
   chmod 600 $K/key.p12 $K/cert.pem $K/p12.pass
   ```

3. Hand the key file to Buds LE Link and allow it to reach Wireless debugging. Only the adb shell can deliver this
   command, because the receiver requires a system permission from the sender:

   ```bash
   adb shell am broadcast --user <USER_ID> -n local.budslelink/.ControlReceiver -a local.budslelink.PROVISION \
       --es p12 $(base64 -w0 $K/key.p12) --es pass $(cat $K/p12.pass)
   adb shell pm grant --user <USER_ID> local.budslelink android.permission.ACCESS_LOCAL_NETWORK
   ```

   The first command prints `data="provisioned"`.

4. Put your saved plugin copy from Part 1 where the adb shell can read it, under the exact name the app expects, and
   compare its fingerprint with the one the app checks:

   ```bash
   adb shell mkdir -p /data/local/tmp/budslelink
   adb push ~/apks/budsunitemgr/base.apk /data/local/tmp/budslelink/plugin-9.0.10.1720.apk
   adb shell sha256sum /data/local/tmp/budslelink/plugin-9.0.10.1720.apk
   ```

   The repair only runs with the exact file it was written for. Its fingerprint must be
   `3a8c0cf868c85a6c1a82ebb059004b6da591ce95a4051d6521503027f48c2efe`, which is version 9.0.10.1720 as Google Play
   installed it on my Googlebook as a single APK. If yours is different (another version, or a copy split into
   several files), the repair refuses to run and the Intel-mode warning stays, so you repeat Part 1 by hand
   instead.

5. Test the whole repair once. This command makes the app run it even though the plugin is already in ARM mode, so
   it changes nothing:

   ```bash
   adb shell am broadcast --user <USER_ID> -n local.budslelink/.ControlReceiver -a local.budslelink.ARMTEST
   adb logcat -d -s BudsLELink | grep 'ARM repair'
   ```

   After a few seconds the log should show `ARM repair: pm install said: Success`. To run exactly the check that a
   restart runs (it does nothing while the plugin is in ARM mode), add `--ez force false` to the first command.

6. Open the status window. The setup check **Automatic ARM repair after restarts (own pairing installed)** should
   have a check mark.

7. Optionally, test the restart of a stuck plugin while your earbuds are connected. This command makes the next
   check act as if the plugin had been stuck for five minutes, so the helper really restarts the plugin. The
   plugin's control channel drops for a few seconds, and the audio is not affected:

   ```bash
   adb shell am broadcast --user <USER_ID> -n local.budslelink/.ControlReceiver -a local.budslelink.STUCKTEST
   adb logcat -d -s BudsLELink | grep 'plugin restart'
   ```

   After a few seconds the log should show `plugin restart: restarted Samsung's plugin`, and the status window
   shows the time next to **Last restart of a stuck Samsung plugin**.

8. Optionally, test the auto-connect check. This command runs it in read-only mode, which changes nothing:

   ```bash
   adb shell am broadcast --user <USER_ID> -n local.budslelink/.ControlReceiver -a local.budslelink.AUTOTEST
   adb logcat -d -s BudsLELink | grep 'auto-connect check'
   ```

   Within a minute the log shows what the tool found for each earbud and a line starting with
   `auto-connect check: RESULT`. To run exactly the check that a restart runs, add `--es mode arm` to the first
   command. It changes something only if the earbuds are off the auto-connect list, and it takes up to about
   two minutes to finish.

## Using it

Day to day there is nothing to do. When the earbuds connect, Buds LE Link signals the plugin, and the Galaxy
Wearable screens and the Buds widget show the earbuds as connected. When the earbuds go back in the case, the
helper's checks see that they are gone and stay quiet.

### The status window

Open **Buds LE Link** from the app launcher. Opening the window also sends a reconnect signal, the same as
**Reconnect now**. While the window is open, it refreshes every 2 seconds and shows:

- **Galaxy Buds app: connected**, **Galaxy Buds app: not connected**, or **Galaxy Buds app: waiting to see the next
  Buds connection**, followed by the time the state last changed. "Connected" means a classic Bluetooth link to the
  earbuds is up, which on the Googlebook means the plugin's control channel is open. "Waiting" means the helper has
  not seen a classic link event yet since it was installed.
- **Last reconnect signal:** the time of the last signal it sent to the plugin.
- **Last restart of a stuck Samsung plugin:** the time of the last automatic plugin restart. This line appears only
  after the first one.
- **Auto-connect check after the last restart:** the time and result of the last auto-connect check. This line
  appears only after the first one.
- **Samsung Buds plugin:** the installed plugin version. If it is not the version this helper was written for
  (9.0.10.1720), the line adds a note that it has changed.

Buttons:

- **Reconnect now** sends the signal twice, 2.5 seconds apart, and schedules a verify check 30 seconds later.
- **Open Galaxy Wearable** opens Galaxy Wearable.

Below the buttons, the **Setup checks** list shows a check mark or a warning sign for each item:

| Check | What the Fix button does |
|---|---|
| Nearby devices permission | Asks for the permission |
| Notifications allowed (for problem alerts) | Asks for the permission |
| Battery: unrestricted | Opens the system dialog that turns off battery optimization for the app |
| Background activity allowed | Opens the app's App info page |
| Never paused when unused | Opens the system setting that controls pausing the app when unused |
| Samsung Buds plugin installed and running in ARM mode | No button. A warning line explains the problem. After a restart the automatic ARM repair fixes it if it is set up. Otherwise use Part 1 of the installation. |
| Automatic ARM repair after restarts (own pairing installed) | No button. A check mark means the app has its own adb key. Set it up with Part 3 of the installation. |

Close the window with its close button at any time. Closing it never affects the background part.

### Notifications

Buds LE Link posts notifications only when something is wrong or has just been repaired, in a channel called
**Problems**:

- **Buds LE Link needs attention** lists every setup problem it found. It disappears by itself once everything is
  fixed. Notifications permission is the one problem it cannot report this way, because without it no notification
  can be shown. The status window still shows it.
- **Galaxy Buds app couldn't reconnect** appears when 8 verify checks in a row, about a minute apart, have found
  the earbuds on LE Audio but the plugin's control channel down. If the plugin restart is set up, the helper has
  already restarted the plugin by then. The notification disappears by itself when the control channel comes up or
  the earbuds disconnect.
- **Samsung Buds plugin repaired** appears once when the automatic ARM repair has switched the plugin back to ARM
  mode after a restart.
- **Galaxy Buds auto-connect after the restart** appears when the auto-connect check could not confirm that the
  earbuds are back on LE Audio's auto-connect list, or could not finish after 20 tries, which take about an hour and a half after the restart.
  The text says what it found.

Tapping any of these notifications opens the status window.

### Logs

The helper writes short log lines under the tag `BudsLELink`. To see them:

```bash
adb logcat -d -s BudsLELink
```

You will see lines such as `Sending ACTION_READY to com.samsung.accessory.budsunitemgr` and, when the control link
does not come up, `Buds on LE Audio but no control link yet (check 1)`. The automatic ARM repair writes lines that
start with `ARM repair:` and, when there is nothing to do, `plugin is in ARM mode: nothing to do`. A restart of a
stuck plugin writes `plugin restart: restarted Samsung's plugin`. The auto-connect check writes lines that start with
`auto-connect check:`.

Right after a restart, the check's log line was missing once on my Googlebook. Android's job history shows
whether it ran (job 3 is the ARM check, and "onStartJob returned false" means the plugin was in ARM mode, and job 4 is the
auto-connect check):

```bash
adb shell dumpsys jobscheduler | grep budslelink
```

## Updating, and what happens after updates

### Updating Buds LE Link

Get the new source, rebuild it with the same keystore and password, and install it over the old copy:

```bash
cd ~/googlebook-buds-le-link
git pull
KS_PASS='your-own-password' ./build.sh
adb install -r --user <USER_ID> BudsLELink.apk
```

When the helper is updated, Android tells it so, and it re-creates its 15-minute check and runs a verify check 20
seconds later. Its permissions and protections carry over. If you lost your keystore, uninstall the old copy first
(see [Uninstalling](#uninstalling)) and then repeat Part 2 of the installation.

### After a reboot

Nothing to do. A boot receiver re-creates the 15-minute check, marks the classic link as down, runs a verify check
20 seconds after startup, runs the ARM check 5 seconds after startup, and runs the auto-connect check a minute
after startup.

### After a Galaxy Buds plugin update

If Google Play updates the plugin (because automatic updates were turned back on, or because you updated it by
hand), two things can happen:

- Google Play installs it for the Intel processor again. If Samsung's Intel libraries are still incomplete, Galaxy
  Wearable crashes again. Buds LE Link shows **Samsung Buds plugin installed and running in ARM mode** with a
  warning sign and posts **Buds LE Link needs attention**. Repeat Part 1 with your copy of the new version, after
  checking with step 5 that its ARM libraries are complete.
- The new version may change the behavior Buds LE Link depends on. The status window then marks the plugin version
  as changed. If the plugin stops connecting, Buds LE Link cannot fix that by itself.

### After a GooglebookOS update

A system update can switch the plugin to Intel mode. With the automatic ARM repair set up (Part 3), Buds LE Link
switches it back after the restart and posts **Samsung Buds plugin repaired**. Without it, the setup check warns
you, and you repeat Part 1.

Then open the status window and look at the setup checks and the plugin line. If a future GooglebookOS build changes
how the Buds4 connect, for example by allowing the classic audio connections next to LE Audio, the plugin may behave
differently, and you should test the case-out reconnect from step 7 again.

## Uninstalling

Remove the battery exemption and uninstall Buds LE Link:

```bash
adb shell cmd deviceidle whitelist -local.budslelink
adb shell pm uninstall --user <USER_ID> local.budslelink
```

Uninstalling also removes its scheduled checks, its saved state, its copy of the adb key and its permissions.

If you set up the automatic ARM repair, also remove its pairing and its plugin copy. Remove the pairing in
**Settings > System > Developer options > Wireless debugging** (the pairing you made for Buds LE Link), and delete the
plugin copy and the key folder:

```bash
adb shell rm -r /data/local/tmp/budslelink
rm -r ~/.config/buds-le-link
```

The crash fix does not need to be undone. Reverting it brings the crash back as long as Samsung's Intel build is
incomplete. If you want Google Play to manage the plugin normally again, open its Play Store page (step 9 of Part
1) and select **Enable auto update** again. The next plugin update from Google Play then replaces your
reinstalled copy.

You can delete your saved copy of the plugin whenever you no longer need it:

```bash
rm -r ~/apks/budsunitemgr
```

## Troubleshooting

**Galaxy Wearable crashes or jumps to Bluetooth settings.** The plugin is probably installed in Intel mode. Check with
`adb shell dumpsys package com.samsung.accessory.budsunitemgr | grep primaryCpuAbi`. If it shows `x86_64`, follow
Part 1 of the installation.

**Nothing happens when the earbuds connect, right after installing.** Open Buds LE Link once from the launcher.
Android does not deliver Bluetooth announcements to a new app until it has been opened. Also check that **Nearby
devices permission** has a check mark.

**The window says "waiting to see the next Buds connection".** The helper has not yet seen a classic Bluetooth link
event since it was installed. Put the earbuds in the case, take them out again, or tap **Reconnect now**. The state
changes as soon as the plugin opens or closes its control channel.

**The window says "not connected" while the earbuds are playing.** Tap **Reconnect now** and wait about 30 seconds.
If it stays "not connected":

- Check the plugin line. A changed plugin version may no longer react to the signal.
- Check that no other app holds the earbuds' control channel. The unofficial Galaxy Buds Client app, for example,
  competes with Samsung's plugin for the same classic RFCOMM channel. Close it and try again.
- Put the earbuds in the case for a few seconds and take them out again.

**"Galaxy Buds app couldn't reconnect" notification.** Eight verify checks in a row, about a minute apart, found the
earbuds on LE Audio but the control channel down. Tap the notification to open the status window, then tap
**Reconnect now**. If that does not help, the plugin may be stuck (see
[Restarting a stuck plugin](#restarting-a-stuck-plugin)). Restart it yourself and send the reconnect signal:

```bash
adb shell am force-stop --user <USER_ID> com.samsung.accessory.budsunitemgr
adb shell am broadcast --user <USER_ID> -a com.samsung.accessory.hearablemgr.core.bluetooth.BluetoothManager.ACTION_READY -p com.samsung.accessory.budsunitemgr
```

Then tap **Reconnect now** once more. If it still does not connect, go through the previous item.

**"Buds LE Link needs attention" notification.** Open the status window and use the **Fix** button next to each
check with a warning sign, or repeat step 4 of Part 2. If the plugin line has the warning, repeat Part 1.

**The plugin was in Intel mode after a restart, and the automatic repair did not fix it.** Check that the setup check
**Automatic ARM repair after restarts** has a check mark, that Wireless debugging is on, and that the plugin copy is
in place with the right fingerprint (step 4 of Part 3). Then run the test from step 5 of Part 3 and read the
`ARM repair:` log lines. `Wireless debugging not reachable yet` means it could not connect, and `saved plugin copy
missing or changed` means the copy is missing or is a different file. If the pairing was removed from the Wireless
debugging list, pair a new key with Part 3 again.

**`adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The new APK was signed with a different keystore
than the installed copy, for example after you built it on another computer. Uninstall the old copy first, then
install the new one and repeat steps 4 to 6 of Part 2.

**The earbuds reconnect by themselves after you disconnect them in Bluetooth settings.** If you disconnect the Buds4
in Bluetooth settings while you are still wearing them, the earbuds connect again on their own. That is the
earbuds' own behavior. I confirmed it with Buds LE Link disabled. Put them in the case instead.

## Safety and privacy notes

- **It never contacts anything outside the Googlebook.** The internet permission exists only for the automatic ARM
  repair, the plugin restart and the auto-connect check, which connect to `127.0.0.1`, the Googlebook's own Wireless debugging service. To find that service's
  current port it listens for the local network announcements of Wireless debugging and accepts only ports that
  this Googlebook itself announces. Before it sends any command, it proves that it reached Android's real adb
  service.
- **What the pairing is used for.** Its key has the rights of adb on the Googlebook, like any Wireless debugging
  pairing. The app uses it in only three situations. After a restart, only when the plugin
  is in Intel mode, it sends a broadcast back to itself for the proof, runs `sha256sum` on the saved plugin copy, and
  runs the `pm install --abi arm64-v8a` reinstall of that copy. When the plugin is stuck (five failed checks in a row,
  at most once every 30 minutes), it sends the proof broadcast and runs `am force-stop` for Samsung's plugin. A minute after a
  restart, it sends the proof broadcast, runs `pm path` to find Buds LE Link's own APK, and runs the auto-connect tool
  from that APK with `app_process`. The tool reads Bluetooth's own log lines about the earbuds' auto-connect state
  and, only when the Galaxy Buds are off the auto-connect list, re-applies their existing LE Audio setting. The key
  file lives in the app's private storage, and the app's backup is turned off.
- **It never disconnects a Bluetooth device, and it never connects one directly.** To keep the Galaxy Buds app connected
  it only sends one broadcast, addressed to the Galaxy Buds plugin's package, and the plugin acts only on the earbuds
  it manages. The one other thing it does to Bluetooth is the auto-connect check after a restart: when the Galaxy
  Buds are off LE Audio's auto-connect list, it applies their LE Audio setting ALLOWED again, which they already have.
  Bluetooth then puts them back on the list and tries to connect them, the same as after a restart that goes right.
  It never changes the setting of a device whose LE Audio is turned off. The plugin also has a
  disconnect command that disconnects the system's own Bluetooth profiles. Buds LE Link never sends it.
- **It ignores every other device.** Other earbuds, headphones, speakers and keyboards are ignored outright. Only
  devices whose name contains "Galaxy Buds", or whose address was seen with such a name, trigger anything.
- **What it stores.** A small private settings file on the device holds the Bluetooth addresses of Galaxy Buds it
  has seen (so renamed earbuds still match), the classic link state and when it changed, the time of the last
  signal, the last time the earbuds were seen on LE Audio, the count of failed verify checks, and the time and result of
  the last auto-connect check. Backup is turned
  off for the app.
- **It never blocks the system.** It uses only public Android Bluetooth and job scheduling interfaces, and it asks
  the LE Audio service one targeted question with a 10-second timeout. It acts only on Bluetooth link events for
  Galaxy Buds, at boot, in its 15-minute check and in short verify checks. It never reads system service dumps. Its
  adb connection exists only during the automatic ARM repair, a plugin restart or the auto-connect check, and every
  command has a 2-minute limit and a 1 MB output limit. The auto-connect tool also stops itself after 105 seconds
  whatever happens, and each of its log reads stops after 10 seconds or 256 KB.
- **Samsung's app is not modified.** The crash fix reinstalls Samsung's own unmodified APK, copied from your own
  device. The helper only sends a broadcast that the plugin already listens for. This repository contains none of
  Samsung's code or files.
- **Permissions on the plugin.** The crash fix grants the plugin only the Nearby devices and notification
  permissions that its own manifest asks for and that it had never been given on the Googlebook.

## Limitations and known issues

- **It depends on undocumented behavior of plugin version 9.0.10.1720.** The "Bluetooth ready" broadcast, its
  export without a permission, and the way its handler counts LE Audio are internal details of Samsung's plugin. A
  Samsung update can change any of them and break the helper. The status window marks a different plugin version
  as changed, but it cannot tell whether the new version still works.
- **The crash fix depends on that version too.** A later plugin version may have complete Intel libraries, or
  incomplete ARM ones. Check with step 5 before reinstalling any other version. The helper's ARM-mode check only
  looks at which library folder the plugin uses, so it would also warn about a future Intel build that works.
- **Turning off automatic updates** means you do not get Samsung's plugin updates until you update by hand.
- **The "connected" state is inferred.** The helper treats a classic Bluetooth link to the earbuds as the plugin's
  control channel, which is true on the Googlebook because audio uses LE Audio only. It does not ask the plugin
  directly.
- **Competing apps.** The unofficial Galaxy Buds Client app competes for the same RFCOMM control channel as
  Samsung's plugin. While it holds the channel, the plugin may not be able to connect.
- **Extra signals every 15 minutes.** While the earbuds are on LE Audio, the periodic check sends the signal twice
  even when the plugin is already connected. The plugin leaves an existing connection alone, so this is harmless.
- **Setup needs adb.** The crash fix and the protections in Part 2 need adb over Wireless debugging. The helper
  itself needs Android 13 or later.
- **The automatic ARM repair needs Wireless debugging.** It only runs when Wireless debugging is on after the
  restart. It works only with the exact plugin file it checks for (version 9.0.10.1720, the fingerprint in Part 3),
  and it only checks after a restart, not at other times.
- **The auto-connect check needs Wireless debugging too,** and it also runs only after a restart. It reads the
  earbuds' auto-connect state from Bluetooth's log. If the log no longer holds that entry, the check treats the
  earbuds as off the list and re-applies the setting, which does no harm when they were on it. It looks at every
  paired device whose name contains "Galaxy Buds", and I have only tested it with the Buds4, without a real restart
  so far.
- **Tested on one setup.** I tested this with Galaxy Buds4 on one Lenovo Googlebook 15 with GooglebookOS build
  CL3B.260622.271.R1, CL3B.260622.276.R4 and CL3B.260622.286.R2. Other Galaxy Buds models match by name, but I have not tested them.

## Files in this repository

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | App manifest: package `local.budslelink`, permissions, the status window, the Bluetooth, boot and control receivers, and the jobs |
| `build.sh` | Builds and signs `BudsLELink.apk` with plain SDK tools (version 2.5, version code 7, API levels 33 to 35) |
| `src/local/budslelink/AclReceiver.java` | Reacts to Galaxy Buds links coming up or going down and sends the timed reconnect signals |
| `src/local/budslelink/Nudge.java` | Sends the "Bluetooth ready" broadcast to the plugin |
| `src/local/budslelink/CheckJob.java` | The 15-minute check and the verify checks |
| `src/local/budslelink/Checks.java` | Setup checks and the problem notifications |
| `src/local/budslelink/BootReceiver.java` | Re-creates the checks after a reboot or an update of the helper, and schedules the ARM check and the auto-connect check after a reboot |
| `src/local/budslelink/ArmJob.java` | The ARM check after a restart and the automatic repair |
| `src/local/budslelink/AutoConnectJob.java` | The auto-connect check after a restart: runs the tool through the app's own pairing and records the result |
| `src/local/budslelink/LeArmTool.java` | The auto-connect tool that runs as the adb shell user: reads the earbuds' auto-connect state and re-applies their LE Audio setting when needed |
| `src/local/budslelink/PluginRestart.java` | Restarts a stuck plugin, at most once every 30 minutes |
| `src/local/budslelink/AdbAccess.java` | Finds the current Wireless debugging port, connects with the app's own key and proves it reached Android's adb service |
| `src/local/budslelink/AdbClient.java` | A minimal adb client for Wireless debugging (TLS with the app's own paired key) |
| `src/local/budslelink/ControlReceiver.java` | Commands only the adb shell can send: install the key, the proof code, the repair test, the stuck-plugin test and the auto-connect test |
| `src/local/budslelink/Buds.java` | Recognizes Galaxy Buds and checks the plugin's installation and version |
| `src/local/budslelink/State.java` | The small saved state (known addresses, link state, times, failure count) |
| `src/local/budslelink/MainActivity.java` | The status window |
| `res/values/strings.xml` | All text shown by the app |
| `res/drawable/ic_launcher.xml`, `res/drawable/ic_stat.xml` | App icon and notification icon |
| `LICENSE` | MIT License |

## Related projects

- [Googlebook Projects](https://github.com/iamflying29-sketch/googlebook-projects): the overview of all my
  Googlebook projects and the shared
  [getting-started guide](https://github.com/iamflying29-sketch/googlebook-projects/blob/main/GETTING-STARTED.md).
- [Battery Widget](https://github.com/iamflying29-sketch/googlebook-battery-widget): makes Google's built-in
  Batteries widget show the battery level of Bluetooth earbuds such as the Galaxy Buds4.
- [Sleep Guard](https://github.com/iamflying29-sketch/googlebook-sleep-guard): another background helper of mine
  for the Googlebook, with the same kind of status window that closes without affecting the background part.

## Disclaimer and license

This is an independent, unofficial project. It is not affiliated with, endorsed by, or sponsored by Google, Lenovo
or any other company named here. All product names belong to their owners. Released under the MIT License.
Copyright (c) 2026 Thomas Liviakis.
