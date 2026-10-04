package com.ejemplo.autowarp;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import com.ejemplo.autowarp.logic.InventoryChecker;
import com.ejemplo.autowarp.screen.AutoWarpScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public class AutoWarpMod implements ClientModInitializer {

    public static final String MOD_ID = "autowarp";
    private final InventoryChecker inventoryChecker = new InventoryChecker();

    private static KeyMapping abrirConfigKey;

    @Override
    public void onInitializeClient() {
        // Cargar configuración y coordenadas
        AutoWarpConfig.load();
        CoordStorage.cargar();

        // Tecla para abrir la pantalla (por defecto: K)
        abrirConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.autowarp.abrir_config",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.autowarp"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Abrir pantalla con la tecla
            while (abrirConfigKey.consumeClick()) {
                if (client.screen == null) {
                    AutoWarpScreen.open();
                }
            }

            // Lógica del checker
            if (client.player != null && client.level != null) {
                inventoryChecker.tick(client);
            }
        });
    }
}