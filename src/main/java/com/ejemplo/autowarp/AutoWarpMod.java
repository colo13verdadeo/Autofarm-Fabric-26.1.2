package com.ejemplo.autowarp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;

public class AutoWarpMod implements ClientModInitializer {

    public static final String MOD_ID = "autowarp";
    private final InventoryChecker inventoryChecker = new InventoryChecker();

    @Override
    public void onInitializeClient() {
        // Registrar evento que se ejecuta al final de cada tick del cliente
        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        // Solo actuar si hay un jugador en el mundo
        if (client.player != null && client.world != null) {
            inventoryChecker.tick(client);
        }
    }
}