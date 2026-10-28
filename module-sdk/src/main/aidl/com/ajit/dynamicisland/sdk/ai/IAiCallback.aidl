package com.ajit.dynamicisland.sdk.ai;

import android.os.Bundle;

oneway interface IAiCallback {
    /** The answer so far (whole text, not a delta), throttled by the engine. */
    void onPartial(String requestId, String textSoFar);
    void onDone(String requestId, String text);
    /** [code] is one of AiContract.E_*. */
    void onError(String requestId, int code, String message);
    /**
     * API 2: what the engine is doing (AiContract.P_* keys): waiting behind other requests, loading
     * the model, reading the prompt, writing. Added last so older engines and clients keep working.
     */
    void onProgress(String requestId, in Bundle progress);
}
