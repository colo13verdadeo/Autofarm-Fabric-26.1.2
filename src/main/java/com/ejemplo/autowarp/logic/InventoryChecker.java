package com.ejemplo.autowarp.logic;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public class InventoryChecker {

    private static final int TICKS_POR_SEGUNDO = 20;
    private static final int THROTTLE_TICKS = 2;
    private static final double DISTANCIA_MAXIMA = 500.0;

    private static final int ESPERA_POST_COMANDO_TICKS = 20;
    private static final int ESPERA_CARGA_CHUNKS_TICKS = 10;
    private static final int ESPERA_CARGA_MAXIMA_TICKS = 100;

    private static final int MAX_INTENTOS_CLICK = 30;
    private static final int TICKS_ENTRE_CLICKS = 10;

    private int contadorTicks = 0;
    private int throttleCounter = 0;
    private boolean inventarioLlenoAnterior = false;
    private boolean enviandoComando = false;
    private int cooldownTicks = 0;

    private int esperaPostComandoTicks = -1;
    private int esperaCargaChunksTicks = -1;
    private int esperaCargaTotalTicks = 0;

    private boolean esperandoMensajeError = false;
    private int bloqueObjetivoX, bloqueObjetivoY, bloqueObjetivoZ;
    private int intentosClick = 0;
    private int ticksDesdeUltimoClick = 0;

    private CoordStorage.Coordenada cartelActual = null;

    /** Flag para saber si tras la espera post-comando hay que ir a autofarm. */
    private boolean irAAutofarmTrasEspera = false;

    public InventoryChecker() {
        // No necesitamos callback de AutoWalker, Baritone gestiona la navegación
    }

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) {
            if (esperandoMensajeError && client.player != null) {
                procesarInteraccion(client, client.player);
            }
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        if (esperandoMensajeError) {
            procesarInteraccion(client, player);
            return;
        }

        if (esperaPostComandoTicks >= 0) {
            esperaPostComandoTicks--;
            if (esperaPostComandoTicks <= 0) {
                esperaPostComandoTicks = -1;
                esperaCargaChunksTicks = ESPERA_CARGA_CHUNKS_TICKS;
                esperaCargaTotalTicks = 0;
            }
            return;
        }

        if (esperaCargaChunksTicks >= 0) {
            esperaCargaChunksTicks--;
            esperaCargaTotalTicks++;

            boolean chunksListos = chunksCargados(client, player);
            boolean tiempoAgotado = esperaCargaTotalTicks > ESPERA_CARGA_MAXIMA_TICKS;

            if (esperaCargaChunksTicks <= 0 || chunksListos || tiempoAgotado) {
                esperaCargaChunksTicks = -1;
                esperaCargaTotalTicks = 0;
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

    // =====================================================
    // CALLBACKS DE BARITONE
    // =====================================================

    private void onLlegadaAlDestino(int x, int y, int z) {
        this.esperandoMensajeError = true;
        this.bloqueObjetivoX = x;
        this.bloqueObjetivoY = y;
        this.bloqueObjetivoZ = z;
        this.intentosClick = 0;
        this.ticksDesdeUltimoClick = 0;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Baritone ha llegado al destino. Interactuando con el cartel..."));
        }
    }

    private void onLlegadaAutofarm(int x, int y, int z) {
        Minecraft client = Minecraft.getInstance();
        AutoWarpConfig cfg = AutoWarpConfig.get();

        if (client.player != null) {
            if (cfg != null && cfg.autofarmCapturado) {
                client.player.setYRot(cfg.autofarmYaw);
                client.player.setXRot(cfg.autofarmPitch);
            }
            client.player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Baritone ha llegado a autofarm. Navegación finalizada."));
        }
    }

    public void onMensajeErrorDetectado() {
        if (!esperandoMensajeError) return;

        esperandoMensajeError = false;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        if (cartelActual != null) {
            CoordStorage.marcarSinStock(cartelActual);
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartelActual + " sin stock. Saltando al siguiente."));
            }
        }

        cartelActual = null;

        // Cancelar Baritone si está activo
        cancelarBaritone();

        if (player != null) {
            intentarNavegacion(player);
        }
    }

    // =====================================================
    // BARITONE
    // =====================================================

    /**
     * Inicia la navegación con Baritone hacia las coordenadas dadas.
     */
    private void navegarConBaritone(int x, int z) {
        try {
            BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getCustomGoalProcess()
                    .setGoalAndPath(new GoalXZ(x, z));

            // Configurar Baritone para que corra
            BaritoneAPI.getSettings().allowSprint.value = true;

            debug("Baritone: navegando hacia (" + x + ", " + z + ")");
        } catch (Exception e) {
            debug("Error al iniciar Baritone: " + e.getMessage());
        }
    }

    /**
     * Cancela cualquier navegación de Baritone en curso.
     */
    private void cancelarBaritone() {
        try {
            BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getPathingBehavior()
                    .cancelEverything();
            debug("Baritone: navegación cancelada");
        } catch (Exception e) {
            debug("Error al cancelar Baritone: " + e.getMessage());
        }
    }

    /**
     * Comprueba si Baritone sigue navegando.
     */
    private boolean baritoneNavegando() {
        try {
            return BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getPathingBehavior()
                    .isPathing();
        } catch (Exception e) {
            return false;
        }
    }

    private void debug(String mensaje) {
        System.out.println("[AutoWarp DEBUG] " + mensaje);
    }

    // =====================================================
    // INTERACCIÓN
    // =====================================================

    private void procesarInteraccion(Minecraft client, LocalPlayer player) {
        if (intentosClick >= MAX_INTENTOS_CLICK) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] No se detectó respuesta tras " + MAX_INTENTOS_CLICK + " intentos. Saltando cartel."));
            esperandoMensajeError = false;

            if (cartelActual != null) {
                CoordStorage.marcarSinStock(cartelActual);
                cartelActual = null;
            }

            intentarNavegacion(player);
            return;
        }

        ticksDesdeUltimoClick++;
        if (ticksDesdeUltimoClick < TICKS_ENTRE_CLICKS) {
            return;
        }
        ticksDesdeUltimoClick = 0;
        intentosClick++;

        BlockPos pos = new BlockPos(bloqueObjetivoX, bloqueObjetivoY, bloqueObjetivoZ);

        Vec3 playerEye = player.getEyePosition();
        Vec3 blockCenter = Vec3.atCenterOf(pos);
        Vec3 direction = blockCenter.subtract(playerEye).normalize();

        Direction face = getCaraMasCercana(direction);

        double offsetX = face.getStepX() * 0.5;
        double offsetY = face.getStepY() * 0.5;
        double offsetZ = face.getStepZ() * 0.5;

        Vec3 hitVec = blockCenter.add(offsetX, offsetY, offsetZ);

        BlockHitResult hitResult = new BlockHitResult(hitVec, face, pos, false);

        if (client.gameMode != null) {
            client.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hitResult);
        }
    }

    private Direction getCaraMasCercana(Vec3 dir) {
        double ax = Math.abs(dir.x);
        double ay = Math.abs(dir.y);
        double az = Math.abs(dir.z);

        if (ax >= ay && ax >= az) {
            return dir.x > 0 ? Direction.EAST : Direction.WEST;
        }
        if (ay >= ax && ay >= az) {
            return dir.y > 0 ? Direction.UP : Direction.DOWN;
        }
        return dir.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // =====================================================
    // RESTO
    // =====================================================

    private boolean chunksCargados(Minecraft client, LocalPlayer player) {
        if (client.level == null) return false;

        int chunkX = player.getBlockX() >> 4;
        int chunkZ = player.getBlockZ() >> 4;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                var chunk = client.level.getChunkSource()
                        .getChunk(chunkX + dx, chunkZ + dz, false);
                if (chunk == null) {
                    return false;
                }
            }
        }
        return true;
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
            esperaPostComandoTicks = ESPERA_POST_COMANDO_TICKS;
        } catch (Exception e) {
            // Silenciar
        } finally {
            resetear();
            enviandoComando = false;
        }
    }

    private void ejecutarComandoPostCarteles(Minecraft client, LocalPlayer player) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null) return;

        String cmd = cfg.comandoPostCarteles;
        if (cmd == null || cmd.isEmpty()) return;

        if (cmd.startsWith("/")) cmd = cmd.substring(1);

        if (client.getConnection() != null) {
            client.getConnection().sendCommand(cmd);
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] No quedan carteles. Ejecutando: /" + cmd));

            CoordStorage.limpiarMarcasSesion();
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Carteles reiniciados. Disponibles para la siguiente vuelta."));

            if (cfg.autofarmActivado && cfg.autofarmCapturado) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Autofarm activado. Navegando a la ubicación capturada."));
                irAAutofarmTrasEspera = true;
                esperaPostComandoTicks = ESPERA_POST_COMANDO_TICKS;
            }
        }
    }

    private void intentarNavegacion(LocalPlayer player) {
        AutoWarpConfig cfg = AutoWarpConfig.get();

        if (irAAutofarmTrasEspera) {
            irAAutofarmTrasEspera = false;

            if (cfg != null && cfg.autofarmActivado && cfg.autofarmCapturado) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Navegando a autofarm con Baritone ("
                        + (int) cfg.autofarmX + ", " + (int) cfg.autofarmZ + ")."));

                // Navegar con Baritone (solo XZ, Y lo gestiona Baritone)
                navegarConBaritone((int) cfg.autofarmX, (int) cfg.autofarmZ);

                // Nota: Baritone no tiene callback nativo fácil, así que usamos un timer
                // Para simplificar, el usuario tendrá que esperar a que Baritone termine
                // y luego el código detectará que está cerca
                return;
            }
        }

        List<CoordStorage.Coordenada> lista = CoordStorage.getCoordenadasActuales();

        if (lista.isEmpty()) {
            ejecutarComandoPostCarteles(Minecraft.getInstance(), player);
            return;
        }

        CoordStorage.Coordenada elegida = null;

        for (CoordStorage.Coordenada cartel : lista) {
            if (CoordStorage.estaSinStock(cartel)) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartel + " marcado sin stock. Saltando."));
                continue;
            }

            double dx = cartel.x - player.getX();
            double dz = cartel.z - player.getZ();
            double distancia = Math.sqrt(dx * dx + dz * dz);

            if (distancia > DISTANCIA_MAXIMA) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartel + " a " + (int) distancia +
                        " bloques (máximo " + (int) DISTANCIA_MAXIMA + "). Saltando."));
                continue;
            }

            if (cartel.itemId == null || cartel.itemId.isEmpty()) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartel + " sin item asociado. Saltando."));
                continue;
            }

            Identifier id = Identifier.tryParse(cartel.itemId);
            if (id == null) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartel + " con ID de item inválido: "
                        + cartel.itemId + ". Saltando."));
                continue;
            }

            Item itemObjetivo = BuiltInRegistries.ITEM.get(id)
                    .map(ref -> ref.value())
                    .orElse(null);

            if (itemObjetivo == null) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartel + " con item inexistente: "
                        + cartel.itemId + ". Saltando."));
                continue;
            }

            int cantidad = contarItem(player, itemObjetivo);
            if (cantidad < 64) {
                player.sendSystemMessage(Component.literal(
                        "El siguiente item no hay un stack de el: " + cartel.itemId
                        + " (tienes " + cantidad + "/64). Saltando cartel " + cartel + "."));
                continue;
            }

            elegida = cartel;
            break;
        }

        if (elegida == null) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Ningún cartel cumple las condiciones. Ejecutando comando post-carteles."));
            ejecutarComandoPostCarteles(Minecraft.getInstance(), player);
            return;
        }

        cartelActual = elegida;

        double dx = elegida.x - player.getX();
        double dz = elegida.z - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Navegando con Baritone hacia el cartel " + elegida + " ("
                + (int) distancia + " bloques)."));

        // Iniciar navegación con Baritone
        navegarConBaritone((int) elegida.x, (int) elegida.z);

        // Programar la llegada al destino (simplificado)
        // Baritone no tiene un callback directo fácil, así que usamos un timer
        // o comprobamos periódicamente la distancia
        programarLlegadaBaritone(elegida.x, elegida.y, elegida.z);
    }

    /**
     * Programa la detección de llegada cuando Baritone termine.
     * Como Baritone no tiene callback fácil, usamos un contador y comprobamos
     * si ya no está navegando.
     */
    private int esperaLlegadaBaritone = -1;
    private double targetLlegadaX, targetLlegadaY, targetLlegadaZ;

    private void programarLlegadaBaritone(double x, double y, double z) {
        this.esperaLlegadaBaritone = 0;
        this.targetLlegadaX = x;
        this.targetLlegadaY = y;
        this.targetLlegadaZ = z;
    }

    /**
     * Comprueba si Baritone ha terminado de navegar.
     * Se llama cada tick desde el método tick() principal.
     */
    private void comprobarLlegadaBaritone(Minecraft client, LocalPlayer player) {
        if (esperaLlegadaBaritone < 0) return;

        esperaLlegadaBaritone++;

        // Comprobar si Baritone ya no está navegando O si estamos cerca del destino
        double dx = targetLlegadaX - player.getX();
        double dz = targetLlegadaZ - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        if (!baritoneNavegando() || distancia < 2.0) {
            debug("Baritone ha llegado o terminado. Distancia: " + distancia);
            esperaLlegadaBaritone = -1;

            // Determinar si era un cartel o autofarm
            // (simplificado: asumimos que si hay cartelActual, es un cartel)
            if (cartelActual != null) {
                onLlegadaAlDestino((int) targetLlegadaX, (int) targetLlegadaY, (int) targetLlegadaZ);
            } else {
                onLlegadaAutofarm((int) targetLlegadaX, (int) targetLlegadaY, (int) targetLlegadaZ);
            }
        }
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
}