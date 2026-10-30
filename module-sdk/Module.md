# Module Dynamic Island module SDK

Build add-on modules for [Dynamic Island for Android](https://ajitpal.github.io/dynamic-island-android/sdk/).
Start with `IslandModuleService`, post `IslandCard`s and an `IslandTile`, and see the
[guide](https://ajitpal.github.io/dynamic-island-android/sdk/modules.html) for manifests, security and listing.

# Package com.ajit.dynamicisland.sdk

Everything a module needs: the service base class, cards, tiles, the contract constants and the signed catalogue format.

# Package com.ajit.dynamicisland.sdk.ai

Client for the on-device AI Engine module (first-party apps signed with the Dynamic Island key).

# Package com.ajit.dynamicisland.sdk.speech

Client for the Speech Pack module (Hindi / Hinglish speech-to-text, first-party apps).
