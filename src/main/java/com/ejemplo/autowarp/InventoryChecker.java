package com.ejemplo.autowarp;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int TIEMPO_ESPERA = 3 * TICKS_POR_SEGUNDO; // 3 segundos = 60 ticks
    private static final int SLOTS_INVENTARIO_PRINCIPAL = 36; // hotbar + mochila

    private int contadorTicks = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;

    public void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) {
            resetear();
            return;
        }

        // Si estamos en medio de la secuencia de envío, no interferir
        if (enviandoComando) {
            return;
        }

        boolean inventarioLleno = estaInventarioLleno(player);

        if (inventarioLleno) {
            // Si acaba de llenarse o seguimos en cuenta regresiva
            if (!inventarioLlenoAnterior) {
                // Inventario acaba de llenarse: iniciar cuenta
                contadorTicks = 0;
                inventarioLlenoAnterior = true;
            }

            // Avanzar contador
            contadorTicks++;

            // Calcular segundo actual para mostrar el log
            int segundoActual = contadorTicks / TICKS_POR_SEGUNDO;
            int tickEnSegundo = contadorTicks % TICKS_POR_SEGUNDO;

            // Mostrar log al INICIO de cada segundo (cuando tickEnSegundo == 1)
            if (tickEnSegundo == 1 && segundoActual >= 1 && segundoActual <= 3) {
                player.sendMessage(Text.literal("T" + segundoActual), false);
            }

            // Ejecutar comando al alcanzar los 3 segundos
            if (contadorTicks >= TIEMPO_ESPERA) {
                ejecutarComando(client, player);
            }
        } else {
            // Inventario ya no está lleno: resetear todo
            resetear();
        }
    }

    /**
     * Verifica si los 36 slots del inventario principal están ocupados.
     * Los slots de armadura y mano secundaria se ignoran intencionalmente.
     */
    private boolean estaInventarioLleno(ClientPlayerEntity player) {
        var inventario = player.getInventory();
        // El método main contiene los slots 0-35 (hotbar + mochila)
        var slotsPrincipales = inventario.main;

        if (slotsPrincipales.size() != SLOTS_INVENTARIO_PRINCIPAL) {
            return false;
        }

        for (var stack : slotsPrincipales) {
            if (stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Envía el comando /warp shop al servidor como si el jugador lo hubiera escrito.
     */
    private void ejecutarComando(MinecraftClient client, ClientPlayerEntity player) {
        enviandoComando = true;

        try {
            // Enviar comando al servidor
            if (client.getNetworkHandler() != null) {
                // El comando sin la barra inicial se pasa a sendCommand
                client.getNetworkHandler().sendCommand("warp shop");
            }
        } catch (Exception e) {
            // Silenciar errores para evitar spam
        } finally {
            // Resetear después de enviar
            resetear();
            enviandoComando = false;
        }
    }

    /**
     * Resetea el estado del checker.
     */
    private void resetear() {
        contadorTicks = 0;
        inventarioLlenoAnterior = false;
    }
}