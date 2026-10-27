package com.ajit.dynamicisland.sdk.ai;

oneway interface IAiCallback {
    /** The answer so far (whole text, not a delta), throttled by the engine. */
    void onPartial(String requestId, String textSoFar);
    void onDone(String requestId, String text);
    /** [code] is one of AiContract.E_*. */
    void onError(String requestId, int code, String message);
}
