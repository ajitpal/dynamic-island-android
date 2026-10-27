# Privacy policy

_Last updated: 27 October 2026_

Dynamic Island for Android ("the app") is open-source software by Ajit Kumar Pal.

**The app collects no data.** It has no internet permission, so it can't send anything off your device. There are no ads, analytics, crash reporters or accounts.

What the app processes, **only on your device**:
- **Notifications and media sessions** (via Notification access): used to display content in the island, send quick replies you type, and control playback. They are held in memory while shown. If you use the **Notification Inbox**, notifications are also kept in the app's private storage, **encrypted** with a key held in the Android Keystore, until you clear them or the retention period you choose ends.
- **Foreground app name** (via the Accessibility service, window-state events only): used to hide an app's own activity while you're in that app. The service **can't read screen content** (`canRetrieveWindowContent=false`).
- **Settings** you choose (theme, size, enabled apps, pinned note) are saved in the app's private storage and never leave the device. Uninstalling the app deletes them.

## Add-on modules
Modules are separate apps you choose to install. Each asks for its own permissions and is covered by this policy when it comes from this repository:
- **Calendar**: reads your calendar on the device to show the next meeting. Nothing is sent anywhere.
- **Automation Bridge**: receives cards from automation apps on your phone (protected by a secret key). Nothing is sent anywhere.
- **Meeting Notes**: uses the **microphone** only while you are recording (Android shows its own mic indicator and a notification). Speech is turned into text **on the device**. Audio files and transcripts stay in the module's private storage until you delete them, and are excluded from backups. It has internet permission **only to download the speech model once** from Moonshine AI's servers (download.moonshine.ai); no audio, text or usage data is sent. Recording other people may require their consent where you live: tell everyone before you start.

Questions: [open an issue](https://github.com/ajitpal/dynamic-island-android/issues).
