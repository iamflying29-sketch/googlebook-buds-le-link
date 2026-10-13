# Buds LE Link

Buds LE Link keeps Samsung's Galaxy Buds4 fully connected to Samsung's Galaxy Wearable app on a Lenovo Googlebook
running GooglebookOS, where the earbuds connect over Bluetooth LE Audio only. It is a small background app that
signals Samsung's Galaxy Buds plugin to open its control link whenever the earbuds connect, checks the link every
15 minutes, and warns me only when the link really fails. This repository also documents the fix that came first:
reinstalling the Galaxy Buds plugin in its ARM version so that Galaxy Wearable stops crashing on the Googlebook's
Intel processor.

## What it does

Buds LE Link keeps my Galaxy Buds4 fully connected to the Galaxy Wearable app on my Googlebook, so the earbuds'
controls, settings and battery widget work without tapping Connect over and over. It runs in the background on its
own:

- A few seconds after the earbuds connect (for example when I take them out of the case), it sends Samsung's Galaxy
  Buds plugin the signal that makes the plugin open its control link to the earbuds. Audio stays on LE Audio the
  whole time.
- Every 15 minutes it checks whether the earbuds are connected over LE Audio and, if they are, sends the signal
  again. This catches anything the instant path missed.
- After each Bluetooth event it verifies that the control link actually came up. It warns me with a notification
  only after five failed checks in a row, about a minute apart, so that a slow reconnect never looks like a failure.
- It checks its own setup (permissions, battery settings, background limits, and whether the plugin is installed,
  enabled and running in ARM mode) and tells me if anything needs fixing.

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
   sends the broadcast twice, and tries again about a minute later. After 5 failures in a row it
   posts a notification, and after 8 it stops retrying (the 15-minute check keeps going). An unknown state, such as
   right after installation, is never counted as a failure.
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
window that closes cleanly without affecting the background part.

## Requirements

- **Device:** a Lenovo Googlebook 15 (x86-64 Intel), or another x86-64 GooglebookOS device whose Galaxy Buds plugin
  has the same problem. I built and tested this on GooglebookOS build CL3B.260622.271.R1 (Android 17).
- **Earbuds:** Samsung Galaxy Buds4, paired in Bluetooth settings and connected over LE Audio. The helper reacts to
  any device whose name contains "Galaxy Buds", but I only tested the Buds4.
- **Samsung apps:** Galaxy Wearable (`com.samsung.android.app.watchmanager`) and its Galaxy Buds plugin
  (`com.samsung.accessory.budsunitemgr`) version 9.0.10.1720, both installed from Google Play. The helper was
  written against that exact plugin version.
- **Android version:** Buds LE Link needs Android 13 (API level 33) or later. It targets API level 35.
- **Permissions used by Buds LE Link:** Nearby devices (`BLUETOOTH_CONNECT`), notifications (`POST_NOTIFICATIONS`),
  starting at boot (`RECEIVE_BOOT_COMPLETED`), and permission to ask for unrestricted battery use
  (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`). It has no internet permission.
- **Tools:** the Linux Terminal with adb connected to GooglebookOS over Wireless debugging, `unzip`, and, for
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
| Samsung Buds plugin installed and running in ARM mode | No button. A warning line explains the problem. Use Part 1 of the installation to fix it. |

Close the window with its close button at any time. Closing it never affects the background part.

### Notifications

Buds LE Link posts notifications only when something is wrong, in a channel called **Problems**:

- **Buds LE Link needs attention** lists every setup problem it found. It disappears by itself once everything is
  fixed. Notifications permission is the one problem it cannot report this way, because without it no notification
  can be shown. The status window still shows it.
- **Galaxy Buds app couldn't reconnect** appears when 5 verify checks in a row, about a minute apart, have found
  the earbuds on LE Audio but the plugin's control channel down. It disappears by itself when the control channel
  comes up or the earbuds disconnect.

Tapping either notification opens the status window.

### Logs

The helper writes short log lines under the tag `BudsLELink`. To see them:

```bash
adb logcat -d -s BudsLELink
```

You will see lines such as `Sending ACTION_READY to com.samsung.accessory.budsunitemgr` and, when the control link
does not come up, `Buds on LE Audio but no control link yet (check 1)`.

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

Nothing to do. A boot receiver re-creates the 15-minute check, marks the classic link as down, and runs a verify
check 20 seconds after startup.

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

Open the status window and look at the setup checks and the plugin line. If a future GooglebookOS build changes how
the Buds4 connect, for example by allowing the classic audio connections next to LE Audio, the plugin may behave
differently, and you should test the case-out reconnect from step 7 again.

## Uninstalling

Remove the battery exemption and uninstall Buds LE Link:

```bash
adb shell cmd deviceidle whitelist -local.budslelink
adb shell pm uninstall --user <USER_ID> local.budslelink
```

Uninstalling also removes its scheduled checks, its saved state and its permissions.

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

**"Galaxy Buds app couldn't reconnect" notification.** Five verify checks in a row, about a minute apart, found the
earbuds on LE Audio but the control channel down. Tap the notification to open the status window, then tap
**Reconnect now**. If that does not help, open Galaxy Wearable and tap Connect, and go through the previous item.

**"Buds LE Link needs attention" notification.** Open the status window and use the **Fix** button next to each
check with a warning sign, or repeat step 4 of Part 2. If the plugin line has the warning, repeat Part 1.

**`adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.** The new APK was signed with a different keystore
than the installed copy, for example after you built it on another computer. Uninstall the old copy first, then
install the new one and repeat steps 4 to 6 of Part 2.

**The earbuds reconnect by themselves after you disconnect them in Bluetooth settings.** If you disconnect the Buds4
in Bluetooth settings while you are still wearing them, the earbuds connect again on their own. That is the
earbuds' own behavior. I confirmed it with Buds LE Link disabled. Put them in the case instead.

## Safety and privacy notes

- **No internet.** Buds LE Link has no internet permission, so it cannot send anything off the device.
- **It never connects or disconnects a Bluetooth device itself.** It only sends one broadcast, addressed to the
  Galaxy Buds plugin's package, and the plugin acts only on the earbuds it manages. The plugin also has a
  disconnect command that disconnects the system's own Bluetooth profiles. Buds LE Link never sends it.
- **It ignores every other device.** Other earbuds, headphones, speakers and keyboards are ignored outright. Only
  devices whose name contains "Galaxy Buds", or whose address was seen with such a name, trigger anything.
- **What it stores.** A small private settings file on the device holds the Bluetooth addresses of Galaxy Buds it
  has seen (so renamed earbuds still match), the classic link state and when it changed, the time of the last
  signal, the last time the earbuds were seen on LE Audio, and the count of failed verify checks. Backup is turned
  off for the app.
- **It never blocks the system.** It uses only public Android Bluetooth and job scheduling interfaces, and it asks
  the LE Audio service one targeted question with a 10-second timeout. It acts only on Bluetooth link events for
  Galaxy Buds, at boot, in its 15-minute check and in short verify checks. The app itself never uses adb and never
  reads system service dumps.
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
- **Tested on one setup.** I tested this with Galaxy Buds4 on one Lenovo Googlebook 15 with GooglebookOS build
  CL3B.260622.271.R1. Other Galaxy Buds models match by name, but I have not tested them.

## Files in this repository

| File | Purpose |
|---|---|
| `AndroidManifest.xml` | App manifest: package `local.budslelink`, permissions, the status window, the Bluetooth and boot receivers, and the check job |
| `build.sh` | Builds and signs `BudsLELink.apk` with plain SDK tools (version 2.2, version code 4, API levels 33 to 35) |
| `src/local/budslelink/AclReceiver.java` | Reacts to Galaxy Buds links coming up or going down and sends the timed reconnect signals |
| `src/local/budslelink/Nudge.java` | Sends the "Bluetooth ready" broadcast to the plugin |
| `src/local/budslelink/CheckJob.java` | The 15-minute check and the verify checks |
| `src/local/budslelink/Checks.java` | Setup checks and the problem notifications |
| `src/local/budslelink/BootReceiver.java` | Re-creates the checks after a reboot or an update of the helper |
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
