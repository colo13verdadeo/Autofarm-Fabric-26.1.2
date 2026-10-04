package com.ejemplo.autowarp.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.StairsShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public class AutoWalker {

    private static final double DISTANCIA_LLEGADA = 2.0;
    private static final double VELOCIDAD_ROTACION = 15.0;
    private static final int TIMEOUT_MAX = 20 * 120;
    private static final double DISTANCIA_MIRA = 1.5;
    private static final int CAIDA_MAXIMA = 1;

    private static final double ALTURA_JUGADOR = 1.8;
    private static final double ANCHO_JUGADOR = 0.6;

    /** Duración del salto forzado en ticks. */
    private static final int DURACION_SALTO_TICKS = 8;

    private boolean activo = false;
    private int targetX, targetY, targetZ;
    private int timeoutTicks = 0;

    /** Contador de salto en curso. 0 = no saltando. */
    private int saltoTicks = 0;

    public void iniciar(int x, int y, int z) {
        this.activo = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.timeoutTicks = 0;
        this.saltoTicks = 0;
    }

    public void detener(Minecraft client) {
        if (!activo) return;
        this.activo = false;
        this.saltoTicks = 0;
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
        }
    }

    public boolean estaActivo() {
        return activo;
    }

    public void tick(Minecraft client) {
        if (!activo) return;

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            detener(client);
            return;
        }

        // Cancelar si el jugador pulsa movimiento manual
        if (client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Movimiento manual detectado. Cancelando navegación."));
            detener(client);
            return;
        }

        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_MAX) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Tiempo de navegación agotado."));
            detener(client);
            return;
        }

        double dx = targetX + 0.5 - player.getX();
        double dz = targetZ + 0.5 - player.getZ();
        double distanciaHorizontal = Math.sqrt(dx * dx + dz * dz);

        if (distanciaHorizontal <= DISTANCIA_LLEGADA) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Destino alcanzado."));
            detener(client);
            return;
        }

        // Rotar suavemente hacia el objetivo
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        // === COMPROBACIÓN DE SEGURIDAD ===
        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad) * DISTANCIA_MIRA;
        double forwardZ = Math.cos(yawRad) * DISTANCIA_MIRA;

        BlockPos piesDelante = BlockPos.containing(
                player.getX() + forwardX,
                player.getY(),
                player.getZ() + forwardZ
        );
        BlockPos cabezaDelante = piesDelante.above();
        BlockPos sueloDelante = piesDelante.below();

        // Hitbox del jugador en la posición delantera
        AABB hitboxDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        // 1. Colisión a la altura de los pies
        boolean colisionPies = colisionaConBloque(client, piesDelante, hitboxDelante);

        // 2. Colisión a la altura de la cabeza
        boolean colisionCabeza = colisionaConBloque(client, cabezaDelante, hitboxDelante);

        // Si hay colisión en la cabeza, siempre detenerse (no se puede saltar)
        if (colisionCabeza) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Obstrucción a la altura de la cabeza. Deteniendo navegación."));
            detener(client);
            return;
        }

        // Si hay colisión en los pies, intentar saltar si es un obstáculo escalable
        if (colisionPies) {
            if (esEscalable(client, piesDelante, cabezaDelante)) {
                // Iniciar salto si no estamos ya saltando
                if (saltoTicks == 0) {
                    saltoTicks = DURACION_SALTO_TICKS;
                }
            } else {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Pared no escalable delante. Deteniendo navegación."));
                detener(client);
                return;
            }
        }

        // === GESTIÓN DEL SALTO ===
        if (saltoTicks > 0) {
            client.options.keyJump.setDown(true);
            saltoTicks--;
            if (saltoTicks == 0) {
                client.options.keyJump.setDown(false);
            }
            // Durante el salto, mantener el avance
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }

        // === COMPROBAR PRECIPICIO ===
        BlockState bloqueSueloDelante = client.level.getBlockState(sueloDelante);
        boolean haySuelo = esBloqueCaminable(client, sueloDelante, bloqueSueloDelante);

        if (!haySuelo) {
            int caida = 0;
            BlockPos check = sueloDelante;
            while (caida <= CAIDA_MAXIMA + 1) {
                BlockState st = client.level.getBlockState(check);
                if (esBloqueCaminable(client, check, st)) break;
                check = check.below();
                caida++;
            }

            if (caida > CAIDA_MAXIMA) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Precipicio detectado delante (caída de "
                        + caida + "+ bloques). Deteniendo navegación."));
                detener(client);
                return;
            }
        }

        // Todo despejado: avanzar
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
    }

    /**
     * Determina si un obstáculo a la altura de los pies es escalable.
     * Escalables: losas, escaleras (no invertidas), y bloques de altura <= 1.0
     * con el hueco de la cabeza libre.
     */
    private boolean esEscalable(Minecraft client, BlockPos piesDelante, BlockPos cabezaDelante) {
        BlockState estadoPies = client.level.getBlockState(piesDelante);

        // El bloque de la cabeza debe estar libre para poder saltar
        BlockState estadoCabeza = client.level.getBlockState(cabezaDelante);
        if (!estadoCabeza.getCollisionShape(client.level, cabezaDelante).isEmpty()) {
            return false;
        }

        // Las losas son escalables (altura 0.5)
        if (estadoPies.is(BlockTags.SLABS)) return true;

        // Las escaleras normales (no invertidas) son escalables
        if (estadoPies.getBlock() instanceof StairBlock) {
            // Verificar que no sea una escalera invertida (half=top)
            Half half = estadoPies.getValue(StairBlock.HALF);
            return half == Half.BOTTOM;
        }

        // Cualquier bloque con altura de colisión <= 1.0 y > 0.5 es escalable
        VoxelShape forma = estadoPies.getCollisionShape(client.level, piesDelante);
        if (forma.isEmpty()) return false;

        double altura = forma.max(Direction.Axis.Y);
        return altura <= 1.0 && altura > 0.5;
    }

    private boolean colisionaConBloque(Minecraft client, BlockPos pos, AABB hitboxJugador) {
        BlockState estado = client.level.getBlockState(pos);

        if (estado.isAir()) return false;

        VoxelShape forma = estado.getCollisionShape(client.level, pos);
        if (forma.isEmpty()) return false;

        for (AABB cajaBloque : forma.toAabbs()) {
            AABB cajaReal = cajaBloque.move(pos.getX(), pos.getY(), pos.getZ());
            if (cajaReal.intersects(hitboxJugador)) {
                return true;
            }
        }

        return false;
    }

    private boolean esBloqueCaminable(Minecraft client, BlockPos pos, BlockState estado) {
        if (estado.isAir()) return false;

        if (estado.is(BlockTags.SLABS)) return true;

        VoxelShape shape = estado.getCollisionShape(client.level, pos);
        if (shape.isEmpty()) return false;

        double altura = shape.max(Direction.Axis.Y);
        return altura >= 0.5;
    }

    private float normalizarAngulo(float angulo) {
        while (angulo > 180) angulo -= 360;
        while (angulo < -180) angulo += 360;
        return angulo;
    }
}