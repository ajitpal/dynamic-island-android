// Implemented by a module (IslandModuleService); bound by the Dynamic Island app.
package com.ajit.dynamicisland.sdk;

import android.os.Bundle;
import com.ajit.dynamicisland.sdk.IIslandHost;

interface IIslandModule {
    int apiVersion();
    /** Tools this module adds to the island's tools panel. */
    Bundle describe();
    oneway void attach(IIslandHost host);
    oneway void detach();
    oneway void onToolTapped(String toolId);
    oneway void onAction(String cardId, String actionId, String input);
}
