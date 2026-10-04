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
    private static final int DURACION_SALTO_TICKS = 8;
    private static final double ALTURA_PISABLE = 0.5;

    private static final int DURACION_DESVIO_TICKS = 15;
    private static final int MAX_INTENTOS_DESVIO = 6;
    private static final float ANGULO_DESVIO = 60.0f;

    private boolean activo = false;
    private int targetX, targetY, targetZ;
    private int timeoutTicks = 0;
    private int saltoTicks = 0;

    private int estadoDesvio = 0;
    private int desvioTicks = 0;
    private int intentosDesvio = 0;
    private int ultimaDireccionDesvio = 1;

    public void iniciar(int x, int y, int z) {
        this.activo = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.timeoutTicks = 0;
        this.saltoTicks = 0;
        this.estadoDesvio = 0;
        this.desvioTicks = 0;
        this.intentosDesvio = 0;
        this.ultimaDireccionDesvio = 1;
    }

    public void detener(Minecraft client) {
        if (!activo) return;
        this.activo = false;
        this.saltoTicks = 0;
        this.estadoDesvio = 0;
        this.desvioTicks = 0;
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

        // === GESTIÓN DEL DESVÍO ACTIVO ===
        if (estadoDesvio != 0) {
            desvioTicks--;

            if (desvioTicks <= 0) {
                estadoDesvio = 0;
            } else {
                // Aplicar rotación de desvío
                aplicarRotacionDesvio(client, player, dx, dz);

                // COMPROBAR SEGURIDAD en la dirección desviada ANTES de avanzar
                if (!esDireccionSegura(client, player)) {
                    // El desvío nos lleva a un peligro: abortar el desvío inmediatamente
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Desvío bloqueado o peligroso. Reintentando."));
                    return;
                }

                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
                return;
            }
        }

        // === CÁLCULO DE DIRECCIÓN HACIA EL OBJETIVO ===
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        // === COMPROBACIÓN DE SEGURIDAD HACIA EL OBJETIVO ===
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

        AABB hitboxDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        // 1. Colisión cabeza
        if (colisionaConBloque(client, cabezaDelante, hitboxDelante)) {
            if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Obstrucción en la cabeza sin ruta alternativa. Deteniendo."));
                detener(client);
            }
            return;
        }

        // 2. Colisión pies
        BlockState estadoPies = client.level.getBlockState(piesDelante);
        boolean esPisable = esBloquePisable(client, piesDelante, estadoPies);

        if (!esPisable && colisionaConBloque(client, piesDelante, hitboxDelante)) {
            if (esEscalable(client, piesDelante, cabezaDelante)) {
                if (saltoTicks == 0) {
                    saltoTicks = DURACION_SALTO_TICKS;
                }
            } else {
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Pared sin ruta alternativa. Deteniendo navegación."));
                    detener(client);
                }
                return;
            }
        }

        // 3. Gestión del salto
        if (saltoTicks > 0) {
            client.options.keyJump.setDown(true);
            saltoTicks--;
            if (saltoTicks == 0) {
                client.options.keyJump.setDown(false);
            }
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }

        // 4. Precipicio
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
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Precipicio sin ruta alternativa. Deteniendo navegación."));
                    detener(client);
                }
                return;
            }
        }

        // Todo despejado: avanzar
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
    }

    /**
     * Comprueba si la dirección actual del jugador es segura para avanzar.
     * Es decir: no hay precipicio, no hay pared no escalable, no hay obstrucción en cabeza.
     */
    private boolean esDireccionSegura(Minecraft client, LocalPlayer player) {
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

        AABB hitboxDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        // Cabeza bloqueada
        if (colisionaConBloque(client, cabezaDelante, hitboxDelante)) {
            return false;
        }

        // Pies bloqueados por algo no escalable
        BlockState estadoPies = client.level.getBlockState(piesDelante);
        boolean esPisable = esBloquePisable(client, piesDelante, estadoPies);
        if (!esPisable && colisionaConBloque(client, piesDelante, hitboxDelante)) {
            if (!esEscalable(client, piesDelante, cabezaDelante)) {
                return false;
            }
        }

        // Precipicio
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
                return false;
            }
        }

        return true;
    }

    /**
     * Inicia un desvío SOLO si la dirección desviada es segura.
     * Prueba primero un lado y, si no es seguro, el otro.
     */
    private boolean iniciarDesvioSeguro(Minecraft client, LocalPlayer player, double dx, double dz) {
        if (intentosDesvio >= MAX_INTENTOS_DESVIO) {
            return false;
        }

        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

        // Probar primero el lado que toca según la alternancia
        int ladoPrimero = ultimaDireccionDesvio;
        int ladoSegundo = -ultimaDireccionDesvio;

        if (esDireccionDesvioSegura(client, player, yawObjetivo, ladoPrimero)) {
            intentosDesvio++;
            estadoDesvio = ladoPrimero;
            desvioTicks = DURACION_DESVIO_TICKS;
            ultimaDireccionDesvio = -ladoPrimero;
            return true;
        }

        if (esDireccionDesvioSegura(client, player, yawObjetivo, ladoSegundo)) {
            intentosDesvio++;
            estadoDesvio = ladoSegundo;
            desvioTicks = DURACION_DESVIO_TICKS;
            ultimaDireccionDesvio = -ladoSegundo;
            return true;
        }

        // Ningún lado es seguro
        return false;
    }

    /**
     * Comprueba si la dirección de desvío (yaw objetivo + ángulo * lado) es segura.
     */
    private boolean esDireccionDesvioSegura(Minecraft client, LocalPlayer player,
                                             float yawObjetivo, int lado) {
        float yawDesviado = normalizarAngulo(yawObjetivo + (ANGULO_DESVIO * lado));
        double yawRad = Math.toRadians(yawDesviado);

        double forwardX = -Math.sin(yawRad) * DISTANCIA_MIRA;
        double forwardZ = Math.cos(yawRad) * DISTANCIA_MIRA;

        BlockPos piesDelante = BlockPos.containing(
                player.getX() + forwardX,
                player.getY(),
                player.getZ() + forwardZ
        );
        BlockPos cabezaDelante = piesDelante.above();
        BlockPos sueloDelante = piesDelante.below();

        AABB hitboxDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        // Cabeza bloqueada
        if (colisionaConBloque(client, cabezaDelante, hitboxDelante)) {
            return false;
        }

        // Pies bloqueados por algo no escalable ni pisable
        BlockState estadoPies = client.level.getBlockState(piesDelante);
        boolean esPisable = esBloquePisable(client, piesDelante, estadoPies);
        if (!esPisable && colisionaConBloque(client, piesDelante, hitboxDelante)) {
            if (!esEscalable(client, piesDelante, cabezaDelante)) {
                return false;
            }
        }

        // Precipicio
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
                return false;
            }
        }

        return true;
    }

    private void aplicarRotacionDesvio(Minecraft client, LocalPlayer player, double dx, double dz) {
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawDesviado = normalizarAngulo(yawObjetivo + (ANGULO_DESVIO * estadoDesvio));

        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawDesviado - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);
    }

    private boolean esBloquePisable(Minecraft client, BlockPos pos, BlockState estado) {
        if (estado.isAir()) return true;

        VoxelShape forma = estado.getCollisionShape(client.level, pos);
        if (forma.isEmpty()) return true;

        double altura = forma.max(Direction.Axis.Y);
        return altura < ALTURA_PISABLE;
    }

    private boolean esEscalable(Minecraft client, BlockPos piesDelante, BlockPos cabezaDelante) {
        BlockState estadoCabeza = client.level.getBlockState(cabezaDelante);
        if (!estadoCabeza.getCollisionShape(client.level, cabezaDelante).isEmpty()) {
            return false;
        }

        BlockState estadoPies = client.level.getBlockState(piesDelante);

        if (estadoPies.is(BlockTags.SLABS)) return true;

        if (estadoPies.getBlock() instanceof StairBlock) {
            Half half = estadoPies.getValue(StairBlock.HALF);
            return half == Half.BOTTOM;
        }

        VoxelShape forma = estadoPies.getCollisionShape(client.level, piesDelante);
        if (forma.isEmpty()) return false;

        double altura = forma.max(Direction.Axis.Y);
        return altura <= 1.0 && altura > ALTURA_PISABLE;
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
        return altura >= ALTURA_PISABLE;
    }

    private float normalizarAngulo(float angulo) {
        while (angulo > 180) angulo -= 360;
        while (angulo < -180) angulo += 360;
        return angulo;
    }
}