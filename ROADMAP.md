# Roadmap

Dynamic Island grows as a **small core app plus optional add-on modules**. Each module is its own app with its own permissions, so you only grant what you install, and the core stays offline and low-permission.

| Phase | Version | Scope | Status |
|---|---|---|---|
| 1. Foundation & Inbox | 1.4 | Customisable, paged **Island tools** (25+ built-in tools + any app); **Notification Inbox** (encrypted, kept until cleared, search/filters/OTP copy); **duplicate pop-up controls** (system pop-up guide, opt-in "move to island", peek de-duplication); unit tests | ✅ shipped |
| 2. Module platform | 1.5 | Module SDK (AIDL + declarative island templates), discovery, **Modules** screen with per-module approval, signature verification that supports **approved third-party developers from day one** (`signature\|knownSigner` allow-list + cert pinning), sample modules: **Automation Bridge** (Tasker/MacroDroid) and **Calendar** | next |
| 3. Voice | 1.6 | **AI Engine** module (on-device speech-to-text: Moonshine v2 streaming for live English, Whisper for Hindi/Hinglish; model download or file import) + **Meeting Recorder** module (mic, background recording, island REC + live captions) | planned |
| 4. Intelligence | 1.7 | On-device notes (Gemini Nano via ML Kit where supported, Gemma 3n E2B fallback): summary, decisions, action items; templates; export; **Smart Inbox** digest & quick replies (opt-in data sharing) | planned |
| 5. Ecosystem | 2.0 | Public SDK docs, third-party module catalogue, Scanner / Weather & AQI / Screen Recorder modules, three activities at once, IzzyOnDroid listings | planned |

## Module security model (Phase 2, designed for third parties)
- Modules declare a bound service with the `com.ajit.dynamicisland.action.MODULE` intent filter plus a metadata XML (id, name, API version, tools, settings, permission rationale).
- The core declares `com.ajit.dynamicisland.permission.MODULE_API` with `protectionLevel="signature|knownSigner"` and an allow-list of approved developer certificate digests. Modules from other developers can be approved by adding their certificate.
- The core also verifies each module's signing certificate at bind time and asks the user to **approve every module once**. Approval can be revoked anytime.
- Modules render through **structured templates** (no arbitrary views), so a module can't break or spoof the island.
- Notification data is **never** shared with a module unless the user explicitly allows it for that module.

Want to build a module? Watch this repo. The SDK lands in v1.5.
