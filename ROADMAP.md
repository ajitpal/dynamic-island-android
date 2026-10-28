# Roadmap

Dynamic Island grows as a **small core app plus optional add-on modules**. Each module is its own app with its own permissions, so you only grant what you install, and the core stays offline and low-permission.

| Phase | Version | Scope | Status |
|---|---|---|---|
| 1. Foundation & Inbox | 1.4 | Customisable, paged **Island tools** (25+ built-in tools + any app); **Notification Inbox** (encrypted, kept until cleared, search/filters/OTP copy); **duplicate pop-up controls** (system pop-up guide, opt-in "move to island", peek de-duplication); unit tests | ✅ shipped |
| 2. Module platform | 1.5 | Module SDK (AIDL + declarative island cards), discovery, **Modules** screen with per-module approval, signature verification supporting **approved third-party developers** (`signature\|knownSigner` allow-list + certificate pinning + developer mode), first modules: **Automation Bridge** (Tasker/MacroDroid/adb) and **Calendar** | ✅ shipped |
| 3. Voice (English) | 1.6 | **Meeting Notes** module: record from the island, live on-device English transcript (Moonshine streaming, Small or Tiny model, one-time download), island REC timer with live captions + Pause/Stop, timestamped transcript with playback, rule-based "possible action items", share/copy. SDK: tools and card buttons can open a module screen (needed to start the mic) | ✅ shipped |
| 4. Intelligence | 1.7 | Shared **AI Engine** module: on-device LLM (Google LiteRT-LM) with a choice of open models (Qwen3 0.6B / 1.7B, Gemma 4 E2B; pinned, checksum-verified downloads), used by any first-party app. **Meeting Notes AI notes**: summary, decisions, action items, open questions, 5 templates, long meetings handled in parts, auto-title, export. **Smart Inbox** (opt-in): "Catch me up" digest and ✨ reply suggestions in the island | ✅ shipped |
| 4b. Intelligence+ | 1.8 | **Gemini Nano** (ML Kit GenAI Prompt API) as a zero-download option on supported phones, used while Meeting Notes is on screen (Android only allows it for the top foreground app) with automatic fallback to the AI Engine; **Ask about this meeting** (answers with tappable timestamps, saved per meeting); **Catch me up** island tool | ✅ shipped |
| 3b. Voice (Hindi / Hinglish) | 1.8.x | Second speech model for Hindi and code-mixed speech; import audio files | next |
| 5. Ecosystem | 2.0 | Public SDK docs, third-party module catalogue, Scanner / Weather & AQI / Screen Recorder modules, three activities at once, IzzyOnDroid listings | planned |

## Module security model (Phase 2, designed for third parties)
- Modules declare a bound service with the `com.ajit.dynamicisland.action.MODULE` intent filter plus a metadata XML (id, name, API version, tools, settings, permission rationale).
- The core declares `com.ajit.dynamicisland.permission.MODULE_API` with `protectionLevel="signature|knownSigner"` and an allow-list of approved developer certificate digests. Modules from other developers can be approved by adding their certificate.
- The core also verifies each module's signing certificate at bind time and asks the user to **approve every module once**. Approval can be revoked anytime.
- Modules render through **structured templates** (no arbitrary views), so a module can't break or spoof the island.
- Notification data is **never** shared with a module unless the user explicitly allows it for that module.

**Why one Meeting Notes module instead of a separate "AI Engine" + "Recorder" pair (as first planned)?** Streaming raw microphone audio between two apps adds latency, battery cost and a privacy surface for no gain while there is only one model. The shared AI Engine arrives in Phase 4, when a language model for summaries makes sharing worthwhile.

Want to build a module? Read **[docs/MODULES.md](docs/MODULES.md)**.
