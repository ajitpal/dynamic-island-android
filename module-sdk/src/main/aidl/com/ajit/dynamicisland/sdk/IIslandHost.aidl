// Implemented by the Dynamic Island app; handed to a module in attach().
// New methods are only ever added at the END, so older modules and hosts keep working.
package com.ajit.dynamicisland.sdk;

import android.os.Bundle;

interface IIslandHost {
    int apiVersion();
    /** Show or update a card. See IslandContract for the keys. */
    oneway void postCard(in Bundle card);
    oneway void removeCard(String cardId);
    /** API 2: show or update this module's tile on the app's Island tab (one per module). */
    oneway void postTile(in Bundle tile);
    oneway void removeTile();
}
