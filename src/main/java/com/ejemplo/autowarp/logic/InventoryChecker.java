package com.ejemplo.autowarp.logic;

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

    // === ESTADO DE INTERACCIÓN CON EL CARTEL ===
    private boolean esperandoMensajeError = false;
    private int bloqueObjetivoX, bloqueObjetivoY, bloqueObjetivoZ;
    private int intentosClick = 0;
    private int ticksDesdeUltimoClick = 0;

    /** Cartel que estamos procesando actualmente (para marcarlo como sin stock). */
    private CoordStorage.Coordenada cartelActual = null;

    private final AutoWalker autoWalker = new AutoWalker();

    public InventoryChecker() {
        autoWalker.setLlegadaCallback(this::onLlegadaAlDestino);
    }

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) {
            autoWalker.tick(client);
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

        autoWalker.tick(client);

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
    // CALLBACK DE LLEGADA
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
                    "[AutoWarp] Interactuando con el cartel..."));
        }
    }

    /**
     * Llamado desde el listener de chat cuando se detecta "Error: You do not have".
     * NO es un error: significa que el cartel está vacío. Saltamos al siguiente.
     */
    public void onMensajeErrorDetectado() {
        if (!esperandoMensajeError) return;

        esperandoMensajeError = false;

        Minecraft client = Minecraft.getInstance();
        LocalPlayer player = client.player;

        if (cartelActual != null) {
            // Marcar el cartel como sin stock en esta sesión
            CoordStorage.marcarSinStock(cartelActual);
            if (player != null) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Cartel " + cartelActual + " sin stock. Saltando al siguiente."));
            }
        }

        cartelActual = null;

        // Detener el AutoWalker si sigue activo (por seguridad)
        if (autoWalker.estaActivo()) {
            autoWalker.detener(client);
        }

        // Buscar el siguiente cartel y navegar hacia él
        if (player != null) {
            intentarNavegacion(player);
        }
    }

    // =====================================================
    // INTERACCIÓN CON EL BLOQUE
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
    // RESTO DE LÓGICA
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
            // Silenciar errores
        } finally {
            resetear();
            enviandoComando = false;
        }
    }

    private void intentarNavegacion(LocalPlayer player) {
        List<CoordStorage.Coordenada> lista = CoordStorage.getCoordenadasActuales();

        if (lista.isEmpty()) {
            return;
        }

        CoordStorage.Coordenada elegida = null;

        for (CoordStorage.Coordenada cartel : lista) {
            // Saltar carteles marcados como sin stock en esta sesión
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
                    "[AutoWarp] Ningún cartel cumple las condiciones para navegar."));
            return;
        }

        cartelActual = elegida;

        double dx = elegida.x - player.getX();
        double dz = elegida.z - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Navegando hacia el cartel " + elegida + " ("
                + (int) distancia + " bloques)."));
        autoWalker.iniciar(elegida.x, elegida.y, elegida.z);
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