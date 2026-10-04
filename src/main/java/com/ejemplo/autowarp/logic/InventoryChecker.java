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

    /** Ticks de espera tras el comando antes de calcular navegación (1 segundo). */
    private static final int ESPERA_POST_COMANDO_TICKS = 20;

    private int contadorTicks = 0;
    private int throttleCounter = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;
    private int cooldownTicks = 0;

    /** Contador para el delay post-comando. -1 significa inactivo. */
    private int esperaPostComandoTicks = -1;

    private final AutoWalker autoWalker = new AutoWalker();

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) {
            autoWalker.tick(client);
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        autoWalker.tick(client);

        // === Fase de espera post-comando ===
        if (esperaPostComandoTicks >= 0) {
            esperaPostComandoTicks--;
            if (esperaPostComandoTicks <= 0) {
                esperaPostComandoTicks = -1;
                // Ya pasó 1 segundo: calcular y navegar con la posición actualizada
                intentarNavegacion(player);
            }
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
                String cmd = cfg.comandoWarp;
                if (cmd != null && !cmd.isEmpty()) {
                    if (cmd.startsWith("/")) cmd = cmd.substring(1);
                    client.getConnection().sendCommand(cmd);
                }
            }
            cooldownTicks = cfg.segundosCooldown * TICKS_POR_SEGUNDO;

            // En lugar de navegar inmediatamente, activar la espera de 1 segundo.
            // La navegación se calculará cuando termine la espera, con la
            // posición ya actualizada por el servidor tras el warp.
            esperaPostComandoTicks = ESPERA_POST_COMANDO_TICKS;
        } catch (Exception e) {
            // Silenciar errores
        } finally {
            resetear();
            enviandoComando = false;
        }
    }

    private void intentarNavegacion(LocalPlayer player) {
        CoordStorage.Coordenada cartel = CoordStorage.getCartelMasCercano();

        if (cartel == null) {
            return;
        }

        // Aquí la posición del jugador ya está actualizada tras el warp.
        double dx = cartel.x - player.getX();
        double dz = cartel.z - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        if (distancia > DISTANCIA_MAXIMA) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El cartel más cercano está a " + (int) distancia +
                    " bloques (máximo " + (int) DISTANCIA_MAXIMA + "). No se navegará."));
            return;
        }

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

        Item itemObjetivo = BuiltInRegistries.ITEM.get(id)
                .map(ref -> ref.value())
                .orElse(null);

        if (itemObjetivo == null) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] El item del cartel no existe en el registro: " + cartel.itemId));
            return;
        }

        int cantidad = contarItem(player, itemObjetivo);
        if (cantidad < 64) {
            player.sendSystemMessage(Component.literal(
                    "El siguiente item no hay un stack de el: " + cartel.itemId
                    + " (tienes " + cantidad + "/64)"));
            return;
        }

        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Navegando hacia el cartel " + cartel + " ("
                + (int) distancia + " bloques)."));
        autoWalker.iniciar(cartel.x, cartel.y, cartel.z);
    }

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