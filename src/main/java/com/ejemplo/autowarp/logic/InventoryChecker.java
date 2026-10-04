package com.ejemplo.autowarp.logic;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int THROTTLE_TICKS = 2;
    private static final double DISTANCIA_MAXIMA = 500.0;

    private int contadorTicks = 0;
    private int throttleCounter = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;
    private int cooldownTicks = 0;

    // AutoWalker para la navegación posterior
    private final AutoWalker autoWalker = new AutoWalker();

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) {
            // Aun si el mod está desactivado, seguir procesando el auto-walk si está activo
            autoWalker.tick(client);
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        // AutoWalker siempre se procesa
        autoWalker.tick(client);

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
                String cmd = cfg.comandoWarp;
                if (cmd != null && !cmd.isEmpty()) {
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    client.getConnection().sendCommand(cmd);
                }
            }
            cooldownTicks = cfg.segundosCooldown * TICKS_POR_SEGUNDO;

            // Tras el comando, intentar navegar al cartel más cercano
            intentarNavegacion(player);
        } catch (Exception e) {
            // Silenciar errores
        } finally {
            resetear();
            enviandoComando = false;
        }
    }

    // =====================================================
    // LÓGICA DE NAVEGACIÓN
    // =====================================================

    private void intentarNavegacion(LocalPlayer player) {
        CoordStorage.Coordenada cartel = CoordStorage.getCartelMasCercano();

        if (cartel == null) {
            // No hay carteles guardados en este contexto
            return;
        }

        // 1. Verificar distancia
        double dx = cartel.x - player.getX();
        double dz = cartel.z - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        if (distancia > DISTANCIA_MAXIMA) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El cartel más cercano está a " + (int) distancia +
                    " bloques (máximo " + (int) DISTANCIA_MAXIMA + "). No se navegará."));
            return;
        }

        // 2. Verificar item asociado
        if (cartel.itemId == null || cartel.itemId.isEmpty()) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El cartel no tiene un item asociado. No se navegará."));
            return;
        }

        Identifier id = Identifier.tryParse(cartel.itemId);
        if (id == null) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El ID del item del cartel es inválido: " + cartel.itemId));
            return;
        }

        Item itemObjetivo = BuiltInRegistries.ITEM.get(id);
        if (itemObjetivo == null) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El item del cartel no existe en el registro: " + cartel.itemId));
            return;
        }

        // 3. Verificar que hay al menos 1 stack completo del item en el inventario
        int cantidad = contarItem(player, itemObjetivo);
        if (cantidad < 64) {
            player.sendSystemMessage(Component.literal(
                    "El siguiente item no hay un stack de el: " + cartel.itemId
                    + " (tienes " + cantidad + "/64)"));
            return;
        }

        // 4. Verificar contexto (mundo/servidor)
        // Ya está implícito: getCartelMasCercano() solo devuelve carteles del contexto actual.
        // Si no hay carteles en este contexto, ya habría retornado arriba.

        // Todo OK: iniciar navegación
        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Navegando hacia el cartel " + cartel + " ("
                + (int) distancia + " bloques)."));
        autoWalker.iniciar(cartel.x, cartel.y, cartel.z);
    }

    /**
     * Cuenta cuántos items del tipo indicado tiene el jugador en el inventario.
     */
    private int contarItem(LocalPlayer player, Item item) {
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.getItem() == item) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private void resetear() {
        contadorTicks = 0;
        throttleCounter = 0;
        inventarioLlenoAnterior = false;
    }

    public AutoWalker getAutoWalker() {
        return autoWalker;
    }
}