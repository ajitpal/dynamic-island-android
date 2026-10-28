// Implemented by the Speech Pack module; bound by Meeting Notes and other trusted first-party modules.
package com.ajit.dynamicisland.sdk.speech;

import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import com.ajit.dynamicisland.sdk.speech.ISpeechCallback;

interface ISpeechEngine {
    int apiVersion();
    /** SpeechContract.S_* keys: is a model ready, which, which languages. */
    Bundle status();
    /** Transcribe an audio file (any format Android can decode). Options: SpeechContract.R_* keys. */
    oneway void transcribe(String requestId, in ParcelFileDescriptor audio, in Bundle options, ISpeechCallback cb);
    oneway void cancel(String requestId);
}
