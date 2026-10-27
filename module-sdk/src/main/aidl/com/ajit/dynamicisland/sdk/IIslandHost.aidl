// Implemented by the Dynamic Island app; handed to a module in attach().
package com.ajit.dynamicisland.sdk;

import android.os.Bundle;

interface IIslandHost {
    int apiVersion();
    /** Show or update a card. See IslandContract for the keys. */
    oneway void postCard(in Bundle card);
    oneway void removeCard(String cardId);
}
