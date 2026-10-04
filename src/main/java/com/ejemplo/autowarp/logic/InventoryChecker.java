package com.ejemplo.autowarp.logic;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int THROTTLE_TICKS = 2;

    private int contadorTicks = 0;
    private int throttleCounter = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;
    private int cooldownTicks = 0;

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) return;

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (enviandoComando) return;

        throttleCounter++;
        if (throttleCounter < THROTTLE_TICKS) return;
        throttleCounter = 0;

        boolean inventarioLleno = estaInventarioLleno(player);

        if (inventarioLleno) {
            if (!inventarioLlenoAnterior) {
                contadorTicks = 0;
                inventarioLlenoAnterior = true;
            }

            contadorTicks += THROTTLE_TICKS;

            int segundosEspera = cfg.segundosInventarioLleno;
            int ticksEspera = segundosEspera * TICKS_POR_SEGUNDO;
            int segundoActual = contadorTicks / TICKS_POR_SEGUNDO;
            int tickEnSegundo = contadorTicks % TICKS_POR_SEGUNDO;

            if (cfg.mostrarMensajesOverlay
                    && tickEnSegundo == THROTTLE_TICKS
                    && segundoActual >= 1
                    && segundoActual <= segundosEspera) {
                player.sendOverlayMessage(Component.literal("T" + segundoActual));
            }

            if (contadorTicks >= ticksEspera) {
                ejecutarComando(client, player, cfg);
            }
        } else {
            resetear();
        }
    }

    private boolean estaInventarioLleno(LocalPlayer player) {
        var menu = player.containerMenu;
        Inventory playerInventory = player.getInventory();

        int slotsOcupados = 0;
        int slotsInventario = 0;

        for (Slot slot : menu.slots) {
            if (slot.container != playerInventory) continue;
            int slotIndex = slot.index;
            if (slotIndex < 9 || slotIndex > 44) continue;

            slotsInventario++;
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) slotsOcupados++;
        }

        return slotsInventario == 36 && slotsOcupados == 36;
    }

    private void ejecutarComando(Minecraft client, LocalPlayer player, AutoWarpConfig cfg) {
        enviandoComando = true;

        try {
            if (client.getConnection() != null) {
                // Enviar el comando configurable (sin la barra inicial)
                String cmd = cfg.comandoWarp;
                if (cmd != null && !cmd.isEmpty()) {
                    // Quitar la barra inicial si el usuario la escribió
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    client.getConnection().sendCommand(cmd);
                }
            }
            // Aplicar cooldown configurable en segundos
            cooldownTicks = cfg.segundosCooldown * TICKS_POR_SEGUNDO;
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