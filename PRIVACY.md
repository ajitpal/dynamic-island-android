# Privacy policy

_Last updated: 27 October 2026_

Dynamic Island for Android ("the app") is open-source software by Ajit Kumar Pal.

**The app collects no data.** It has no internet permission, so it can't send anything off your device. There are no ads, analytics, crash reporters or accounts.

What the app processes, **only on your device**:
- **Notifications and media sessions** (via Notification access): used to display content in the island, send quick replies you type, and control playback. They are held in memory while shown. If you use the **Notification Inbox**, notifications are also kept in the app's private storage, **encrypted** with a key held in the Android Keystore, until you clear them or the retention period you choose ends.
- **Foreground app name** (via the Accessibility service, window-state events only): used to hide an app's own activity while you're in that app. The service **can't read screen content** (`canRetrieveWindowContent=false`).
- **Settings** you choose (theme, size, enabled apps, pinned note) are saved in the app's private storage and never leave the device. Uninstalling the app deletes them.

## Add-on modules
The **module catalog** shown in the app (Modules → Get more) is bundled inside the app; looking at it doesn't contact any server. Tapping "Get" opens the module's release page in your browser.

Modules are separate apps you choose to install. Each asks for its own permissions and is covered by this policy when it comes from this repository:
- **Calendar**: reads your calendar on the device to show the next meeting. Nothing is sent anywhere.
- **Automation Bridge**: receives cards from automation apps on your phone (protected by a secret key). Nothing is sent anywhere.
- **Meeting Notes**: uses the **microphone** only while you are recording (Android shows its own mic indicator and a notification). Speech is turned into text **on the device**. Audio files and transcripts stay in the module's private storage until you delete them, and are excluded from backups. It has internet permission **only to download the speech model once** from Moonshine AI's servers (download.moonshine.ai); no audio, text or usage data is sent. Recording other people may require their consent where you live: tell everyone before you start. If the AI Engine is installed, the transcript is passed to it on the phone to write notes, which are saved next to the transcript.
- **Meeting Notes in Hindi / Hinglish**: when your phone's own **on-device** speech recognition supports Hindi, Meeting Notes uses it through Android's on-device recognizer, which Android runs on the phone without sending audio to a server (Meeting Notes asks for the on-device recognizer only). An **imported audio file** is copied into Meeting Notes' private storage, like a recording.
- **Speech Pack**: turns Hindi and Hinglish speech into text **on the device**. It receives audio only from apps signed by the same developer (Meeting Notes, or a file you pick on its own Try It screen), doesn't keep the audio or the text, and remembers only which of those apps used it last. It has internet permission **only to download a speech model** from Hugging Face (huggingface.co and its download servers).
- **AI Engine**: runs a language model on the device. Its Diagnostics (recent runs, timings, its own log lines) stay on the phone; **Copy Report** puts them on your clipboard only when you tap it. It accepts requests only from apps signed by the same developer (the island and its modules) and keeps nothing except which of those apps used it last. It has internet permission **only to download a model** from Hugging Face (huggingface.co and its download servers); prompts and answers are never sent anywhere.
- **Gemini Nano** (Meeting Notes and AI Engine, **off by default**, only on phones that have it): runs inside Android's own AICore service on the device. Google's ML Kit library that connects to it may send anonymous usage and performance statistics to Google; your text is not sent. The island app doesn't include this library.
- **Questions about a meeting** are saved next to that meeting and deleted with it.
- **Updater** (recommended, optional): once a day (if "Check Automatically" is on and the module is on) it downloads a small file from GitHub (github.com and its download servers) listing the newest versions, and downloads an app only when you tap Update. GitHub sees an ordinary download request; nothing about you, your phone or your apps is sent. It can install apps only after you confirm each one in Android's prompt, and only apps signed with the Dynamic Island release key. Turning it off in Modules stops all checks.
- **Smart Inbox** (in the island app, **off by default**): when you turn on "Catch me up" or "Suggest replies", the text of the notifications involved is passed to the AI Engine on your phone. One-time codes are masked first. The island app itself still has no internet permission.

Questions: [open an issue](https://github.com/ajitpal/dynamic-island-android/issues).
