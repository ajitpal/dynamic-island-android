// Implemented by the AI Engine module; bound by the island and by other trusted modules.
package com.ajit.dynamicisland.sdk.ai;

import android.os.Bundle;
import com.ajit.dynamicisland.sdk.ai.IAiCallback;

interface IAiEngine {
    int apiVersion();
    /** AiContract.S_* keys: is a model ready, its name, how much text it can take. */
    Bundle status();
    /** AiContract.R_* keys (system, prompt, maxTokens, temperature). Results arrive on [cb]. */
    oneway void generate(String requestId, in Bundle request, IAiCallback cb);
    oneway void cancel(String requestId);
}
