package com.ejemplo.autowarp;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int TIEMPO_ESPERA = 3 * TICKS_POR_SEGUNDO;
    private static final int THROTTLE_TICKS = 2; // revisar cada 2 ticks para precisión

    private int contadorTicks = 0;
    private int throttleCounter = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;

    public void tick(Minecraft client) {
        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        if (enviandoComando) {
            return;
        }

        // Throttle: no revisar cada tick, sino cada N ticks
        throttleCounter++;
        if (throttleCounter < THROTTLE_TICKS) {
            return;
        }
        throttleCounter = 0;

        boolean inventarioLleno = estaInventarioLleno(player);

        if (inventarioLleno) {
            if (!inventarioLlenoAnterior) {
                contadorTicks = 0;
                inventarioLlenoAnterior = true;
            }

            contadorTicks += THROTTLE_TICKS;

            int segundoActual = contadorTicks / TICKS_POR_SEGUNDO;
            int tickEnSegundo = contadorTicks % TICKS_POR_SEGUNDO;

            if (tickEnSegundo == THROTTLE_TICKS && segundoActual >= 1 && segundoActual <= 3) {
                player.displayClientMessage(Component.literal("T" + segundoActual), false);
            }

            if (contadorTicks >= TIEMPO_ESPERA) {
                ejecutarComando(client, player);
            }
        } else {
            resetear();
        }
    }

    /**
     * Verifica si los 36 slots del inventario principal están ocupados.
     * Usa menu.slots filtrando por container == playerInventory, igual que ItemDropper.
     */
    private boolean estaInventarioLleno(LocalPlayer player) {
        var menu = player.containerMenu;
        Inventory playerInventory = player.getInventory();

        int slotsOcupados = 0;
        int slotsInventario = 0;

        for (Slot slot : menu.slots) {
            if (slot.container != playerInventory) continue;

            int slotIndex = slot.index;
            // Los 36 slots del inventario principal (hotbar + mochila) van del 9 al 44
            if (slotIndex < 9 || slotIndex > 44) continue;

            slotsInventario++;

            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) {
                slotsOcupados++;
            }
        }

        // Si los 36 slots están ocupados, el inventario está lleno
        return slotsInventario == 36 && slotsOcupados == 36;
    }

    /**
     * Envía /warp shop. Para comandos de chat se usa getConnection().sendCommand().
     */
    private void ejecutarComando(Minecraft client, LocalPlayer player) {
        enviandoComando = true;

        try {
            if (client.getConnection() != null) {
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
        throttleCounter = 0;
        inventarioLlenoAnterior = false;
    }
}