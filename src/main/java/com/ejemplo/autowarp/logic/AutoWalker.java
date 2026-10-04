package com.ejemplo.autowarp.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

public class AutoWalker {

    private static final double DISTANCIA_LLEGADA = 2.0;
    private static final double VELOCIDAD_ROTACION = 15.0;
    private static final int TIMEOUT_MAX = 20 * 120;
    private static final double DISTANCIA_MIRA = 1.5;
    private static final int CAIDA_MAXIMA = 1;

    /** Altura del jugador en bloques (pies a cabeza). */
    private static final double ALTURA_JUGADOR = 1.8;
    /** Ancho del jugador (hitbox de 0.6). */
    private static final double ANCHO_JUGADOR = 0.6;

    private boolean activo = false;
    private int targetX, targetY, targetZ;
    private int timeoutTicks = 0;

    public void iniciar(int x, int y, int z) {
        this.activo = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.timeoutTicks = 0;
    }

    public void detener(Minecraft client) {
        if (!activo) return;
        this.activo = false;
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
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

        // === COMPROBACIÓN DE SEGURIDAD CON COLISIONES REALES ===
        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad) * DISTANCIA_MIRA;
        double forwardZ = Math.cos(yawRad) * DISTANCIA_MIRA;

        // Posición del bloque delante a la altura de los pies
        BlockPos piesDelante = BlockPos.containing(
                player.getX() + forwardX,
                player.getY(),
                player.getZ() + forwardZ
        );

        // === 1. COMPROBAR SI EL JUGADOR CABE (pies + cabeza) ===
        // El jugador ocupa 2 bloques de altura (pies y cabeza)
        BlockPos cabezaDelante = piesDelante.above();

        // Crear la hitbox del jugador en la posición delantera
        AABB hitboxJugadorDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        // Comprobar colisión con el bloque de los pies
        if (colisionaConBloque(client, piesDelante, hitboxJugadorDelante)) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Obstrucción a la altura de los pies delante. Deteniendo navegación."));
            detener(client);
            return;
        }

        // Comprobar colisión con el bloque de la cabeza
        if (colisionaConBloque(client, cabezaDelante, hitboxJugadorDelante)) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Obstrucción a la altura de la cabeza delante. Deteniendo navegación."));
            detener(client);
            return;
        }

        // === 2. COMPROBAR PRECIPICIO ===
        BlockPos debajoDelante = piesDelante.below();
        BlockState bloqueDebajoDelante = client.level.getBlockState(debajoDelante);

        boolean haySuelo = esBloqueCaminable(client, debajoDelante, bloqueDebajoDelante);

        if (!haySuelo) {
            int caida = 0;
            BlockPos check = debajoDelante;
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
     * Comprueba si la hitbox del jugador intersecta con la forma de colisión
     * real del bloque. Esto detecta paneles de vidrio, losas, escaleras, vallas,
     * y cualquier bloque con forma incompleta.
     */
    private boolean colisionaConBloque(Minecraft client, BlockPos pos, AABB hitboxJugador) {
        BlockState estado = client.level.getBlockState(pos);

        // Los bloques de aire no colisionan
        if (estado.isAir()) return false;

        // Obtener la forma de colisión real del bloque
        VoxelShape forma = estado.getCollisionShape(client.level, pos);

        // Si la forma está vacía, el bloque es atravesable
        if (forma.isEmpty()) return false;

        // Comprobar si alguna de las cajas de colisión del bloque intersecta con la hitbox del jugador
        for (AABB cajaBloque : forma.toAabbs()) {
            // Las cajas de VoxelShape son relativas a 0,0,0; hay que desplazarlas a la posición real
            AABB cajaReal = cajaBloque.move(pos.getX(), pos.getY(), pos.getZ());
            if (cajaReal.intersects(hitboxJugador)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Determina si un bloque es caminable (suelo firme o losa inferior).
     * Usa la altura de colisión para detectar losas y bloques parciales.
     */
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