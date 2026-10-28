package com.ajit.dynamicisland.sdk.speech;

import android.os.Bundle;

oneway interface ISpeechCallback {
    /** One transcribed utterance: where it starts in the audio, and its text. */
    void onLine(String requestId, long startMs, long endMs, String text);
    /** SpeechContract.P_* keys: stage, fraction done (0..1). */
    void onProgress(String requestId, in Bundle progress);
    void onDone(String requestId);
    /** [code] is one of SpeechContract.E_*. */
    void onError(String requestId, int code, String message);
}
