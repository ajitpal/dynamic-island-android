# Privacy policy

_Last updated: 26 October 2026_

Dynamic Island for Android ("the app") is open-source software by Ajit Kumar Pal.

**The app collects no data.** It has no internet permission, so it can't send anything off your device. There are no ads, analytics, crash reporters or accounts.

What the app processes, **only on your device**:
- **Notifications and media sessions** (via Notification access): used to display content in the island, send quick replies you type, and control playback. They are held in memory while shown and are not stored.
- **Foreground app name** (via the Accessibility service, window-state events only): used to hide an app's own activity while you're in that app. The service **can't read screen content** (`canRetrieveWindowContent=false`).
- **Settings** you choose (theme, size, enabled apps, pinned note) are saved in the app's private storage and never leave the device. Uninstalling the app deletes them.

Questions: [open an issue](https://github.com/ajitpal/dynamic-island-android/issues).
