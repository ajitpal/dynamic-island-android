# Install & setup guide

Works on **Android 12 or newer** phones. It looks best on phones with a centred punch-hole camera; notches and side punch-holes work too (use the offsets).

## 1. Install the APK
1. Download `DynamicIsland-vX.Y.Z.apk` from the [latest release](https://github.com/ajitpal/dynamic-island-android/releases/latest).
2. Open it. Allow your browser or file manager to "install unknown apps" if asked.
3. **Play Protect** may warn that the developer is unknown, because the app isn't on the Play Store. Tap **More details → Install anyway**. The full source is in this repo.

## 2. Unlock "restricted settings" (Android 13+)
Android blocks *Notification access* and *Accessibility* for apps installed from a file until you allow them. This is a system safety feature and no app can bypass it.

1. Open Dynamic Island → **Step 1 → Notification access** and try to switch it on. You'll see *"Controlled by restricted setting"*. That's expected.
2. Go back, tap **Open App info**, then **⋮ (top-right) → Allow restricted settings**, and confirm with your PIN or fingerprint.
3. Now switch on **Notification access** and **Accessibility → Downloaded apps → Dynamic Island**.

**No "Allow restricted settings" in the menu?** Some skins hide it. With USB debugging on, run this from a computer:
```bash
adb shell appops set com.ajit.dynamicisland ACCESS_RESTRICTED_SETTINGS allow
```
To enable everything from ADB in one go:
```bash
adb shell cmd notification allow_listener com.ajit.dynamicisland/com.ajit.dynamicisland.service.IslandNotificationListener
adb shell settings put secure enabled_accessibility_services com.ajit.dynamicisland/com.ajit.dynamicisland.service.IslandAccessibilityService
adb shell settings put secure accessibility_enabled 1
```
> ⚠️ The second command **replaces** your list of enabled accessibility services. If you use others (e.g. a password manager), add them separated by `:`.

## 3. Keep it running (per brand)
Phone makers stop background apps to save battery, and when that happens the island disappears. The app detects your brand and shows the right buttons under **Step 2**. The full community guides are at [dontkillmyapp.com](https://dontkillmyapp.com).

| Brand | What to do |
|---|---|
| **Xiaomi / Redmi / POCO** (HyperOS, MIUI) | Autostart → on · Battery saver → *No restrictions* · Other permissions → *Show on lock screen* and *Display pop-up windows while running in background* · lock the app in Recents |
| **Samsung** (One UI) | Settings → Apps → Dynamic Island → Battery → *Unrestricted* · Battery → Background usage limits → add to *Never sleeping apps* |
| **OnePlus / OPPO / realme** (OxygenOS, ColorOS) | App info → Battery → *Allow background activity* and *Allow auto launch* · lock in Recents |
| **vivo / iQOO** | i Manager → App manager → Autostart → on · Battery → *High background power consumption* → allow |
| **Motorola / Nothing / Pixel** | Usually fine. Set App info → Battery → *Unrestricted* if the island stops after a while |
| **Huawei / Honor** | App launch → Dynamic Island → *Manage manually* → enable all three toggles |

## 4. Fit it to your camera
In **7 · Look & fit**:
1. Turn on **Calibrate**. The resting island turns red.
2. Adjust **Camera hole size fine-tune**, **Horizontal offset** and **Vertical offset** until the red circle just covers the camera.
3. Turn **Calibrate** off. At rest the island is now invisible.

Choose a theme, width, height, roundness, opacity and animation speed to taste. The real island at the top updates live.

## Troubleshooting
| Symptom | Fix |
|---|---|
| Nothing appears | Check **Diagnostics** (bottom of the app). *Accessibility running* and *Listener connected* must both be `true`. |
| Island disappears after a while | Redo step 3 for your brand |
| Tapping the island doesn't open apps (Xiaomi) | Allow *Display pop-up windows while running in background* |
| Island hidden during videos/games | That's "Hide in full-screen apps" (off by default). Turn it off if your skin misreports full screen. |
| Wrong position or size | Use Calibrate + offsets, then **Reset look & position** if needed |
| Still stuck | [Open an issue](https://github.com/ajitpal/dynamic-island-android/issues/new/choose) with your phone model + a Diagnostics screenshot |
