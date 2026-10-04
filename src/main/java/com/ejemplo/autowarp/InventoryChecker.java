package com.ejemplo.autowarp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int TIEMPO_ESPERA = 3 * TICKS_POR_SEGUNDO;

    private int contadorTicks = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null) {
            resetear();
            return;
        }

        if (enviandoComando) {
            return;
        }

        boolean inventarioLleno = estaInventarioLleno(player);

        if (inventarioLleno) {
            if (!inventarioLlenoAnterior) {
                contadorTicks = 0;
                inventarioLlenoAnterior = true;
            }

            contadorTicks++;

            int segundoActual = contadorTicks / TICKS_POR_SEGUNDO;
            int tickEnSegundo = contadorTicks % TICKS_POR_SEGUNDO;

            if (tickEnSegundo == 1 && segundoActual >= 1 && segundoActual <= 3) {
                player.displayClientMessage(Component.literal("T" + segundoActual), false);
            }

            if (contadorTicks >= TIEMPO_ESPERA) {
                ejecutarComando(client, player);
            }
        } else {
            resetear();
        }
    }

    private boolean estaInventarioLleno(LocalPlayer player) {
        var inventario = player.getInventory();
        var slotsPrincipales = inventario.items; // En Mojang Mappings: "items" en vez de "main"

        for (var stack : slotsPrincipales) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private void ejecutarComando(Minecraft client, LocalPlayer player) {
        enviandoComando = true;

        try {
            if (client.getConnection() != null) {
                // En Mojang Mappings el método cambió de sendCommand a sendCommand (verificar)
                client.getConnection().sendCommand("warp shop");
            }
        } catch (Exception e) {
            // Silenciar errores
        } finally {
            resetear();
            enviandoComando = false;
        }
    }

    private void resetear() {
        contadorTicks = 0;
        inventarioLlenoAnterior = false;
    }
}