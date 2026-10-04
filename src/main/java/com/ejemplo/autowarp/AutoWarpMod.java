package com.ejemplo.autowarp;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import com.ejemplo.autowarp.logic.InventoryChecker;
import com.ejemplo.autowarp.screen.AutoWarpScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

public class AutoWarpMod implements ClientModInitializer {

    public static final String MOD_ID = "autowarp";
    private final InventoryChecker inventoryChecker = new InventoryChecker();

    private static KeyMapping abrirConfigKey;

    // Categoría de tecla
    private static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "autowarp")
    );

    @Override
    public void onInitializeClient() {
        AutoWarpConfig.load();
        CoordStorage.cargar();

        // Tecla para abrir la pantalla: U
        abrirConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.autowarp.abrir_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_U,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (abrirConfigKey.consumeClick()) {
                if (client.screen == null) {
                    AutoWarpScreen.open();
                }
            }

            if (client.player != null && client.level != null) {
                inventoryChecker.tick(client);
            }
        });
    }
}