package com.kobosh.koboshmapartaddon.client;

import net.fabricmc.api.ClientModInitializer;

public class WurstaddonClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        // Addon provider is registered via Java ServiceLoader metadata.
        // No need for manual registration here
    }
}
