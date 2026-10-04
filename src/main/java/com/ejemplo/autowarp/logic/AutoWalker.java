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
import net.minecraft.world.phys.Vec3;
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

    private static final int DURACION_RETROCESO_TICKS = 6;

    private static final double DISTANCIA_SIMULACION = 0.5;

    private static final int MAX_PRUEBAS_FALLIDAS = 11;

    private static final int ESPERA_ENTRE_PRUEBAS_TICKS = 10;

    /**
     * Ángulos de exploración lateral en grados respecto a la dirección al objetivo.
     * Se prueban en orden: primero los más suaves, luego los más pronunciados.
     * Cubre hasta 150° en cada lado para permitir rodear obstáculos grandes.
     */
    private static final float[] ANGULOS_EXPLORACION = {
            30.0f, 60.0f, 90.0f, 120.0f, 150.0f
    };

    public interface LlegadaCallback {
        void onLlegada(int x, int y, int z);
    }

    private LlegadaCallback llegadaCallback;

    private boolean activo = false;
    private int targetX, targetY, targetZ;
    private int timeoutTicks = 0;
    private int saltoTicks = 0;

    private int estadoDesvio = 0;
    private int desvioTicks = 0;
    private int intentosDesvio = 0;
    private int ultimaDireccionDesvio = 1;

    /** Ángulo actual del desvío en curso. */
    private float anguloDesvioActual = 0f;

    private int retrocesoTicks = 0;

    private int pruebasFallidas = 0;

    private int esperaEntrePruebasTicks = 0;

    public void setLlegadaCallback(LlegadaCallback callback) {
        this.llegadaCallback = callback;
    }

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
        this.anguloDesvioActual = 0f;
        this.retrocesoTicks = 0;
        this.pruebasFallidas = 0;
        this.esperaEntrePruebasTicks = 0;
    }

    public void detener(Minecraft client) {
        if (!activo) return;
        this.activo = false;
        this.saltoTicks = 0;
        this.estadoDesvio = 0;
        this.desvioTicks = 0;
        this.retrocesoTicks = 0;
        this.pruebasFallidas = 0;
        this.esperaEntrePruebasTicks = 0;
        this.anguloDesvioActual = 0f;
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyDown.setDown(false);
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

        boolean movimientoManual = false;

        if (client.options.keyLeft.isDown() || client.options.keyRight.isDown()) {
            movimientoManual = true;
        }
        if (retrocesoTicks == 0
                && esperaEntrePruebasTicks == 0
                && client.options.keyDown.isDown()) {
            movimientoManual = true;
        }

        if (movimientoManual) {
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
            int fx = targetX, fy = targetY, fz = targetZ;
            detener(client);
            if (llegadaCallback != null) {
                llegadaCallback.onLlegada(fx, fy, fz);
            }
            return;
        }

        // === ESPERA ENTRE PRUEBAS ===
        if (esperaEntrePruebasTicks > 0) {
            esperaEntrePruebasTicks--;
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyJump.setDown(false);

            if (esperaEntrePruebasTicks <= 0) {
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    registrarPruebaFallida(client, player);
                }
            }
            return;
        }

        // === RETROCESO ===
        if (retrocesoTicks > 0) {
            retrocesoTicks--;
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(true);

            if (retrocesoTicks <= 0) {
                client.options.keyDown.setDown(false);
                esperaEntrePruebasTicks = ESPERA_ENTRE_PRUEBAS_TICKS;
            }
            return;
        }

        // === DESVÍO ACTIVO ===
        if (estadoDesvio != 0) {
            desvioTicks--;

            if (desvioTicks <= 0) {
                estadoDesvio = 0;
                anguloDesvioActual = 0f;
                pruebasFallidas = 0;
            } else {
                aplicarRotacionDesvio(client, player, dx, dz);

                if (!esDireccionSegura(client, player)) {
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    anguloDesvioActual = 0f;
                    retrocesoTicks = DURACION_RETROCESO_TICKS;
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Desvío peligroso. Retrocediendo."));
                    return;
                }

                if (!simularAvanceSeguro(client, player)) {
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    anguloDesvioActual = 0f;
                    retrocesoTicks = DURACION_RETROCESO_TICKS;
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Desvío bloqueado. Retrocediendo."));
                    return;
                }

                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
                return;
            }
        }

        // === DIRECCIÓN HACIA EL OBJETIVO ===
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        // === SIMULACIÓN ===
        if (!simularAvanceSeguro(client, player)) {
            if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                registrarPruebaFallida(client, player);
            }
            return;
        }

        // === SALTO ===
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

        // === COMPROBAR SI NECESITA SALTAR ===
        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad) * DISTANCIA_MIRA;
        double forwardZ = Math.cos(yawRad) * DISTANCIA_MIRA;

        BlockPos piesDelante = BlockPos.containing(
                player.getX() + forwardX,
                player.getY(),
                player.getZ() + forwardZ
        );
        BlockPos cabezaDelante = piesDelante.above();

        BlockState estadoPies = client.level.getBlockState(piesDelante);
        boolean esPisable = esBloquePisable(client, piesDelante, estadoPies);

        AABB hitboxDelante = new AABB(
                piesDelante.getX() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getY(),
                piesDelante.getZ() + 0.5 - ANCHO_JUGADOR / 2,
                piesDelante.getX() + 0.5 + ANCHO_JUGADOR / 2,
                piesDelante.getY() + ALTURA_JUGADOR,
                piesDelante.getZ() + 0.5 + ANCHO_JUGADOR / 2
        );

        if (!esPisable && colisionaConBloque(client, piesDelante, hitboxDelante)) {
            if (esEscalable(client, piesDelante, cabezaDelante)) {
                if (saltoTicks == 0) {
                    saltoTicks = DURACION_SALTO_TICKS;
                }
            } else {
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    registrarPruebaFallida(client, player);
                }
                return;
            }
        }

        // === PRECIPICIO ===
        BlockPos sueloDelante = piesDelante.below();
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
                retrocesoTicks = DURACION_RETROCESO_TICKS;
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Precipicio detectado. Retrocediendo para buscar ruta."));
                return;
            }
        }

        pruebasFallidas = 0;
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
    }

    private void registrarPruebaFallida(Minecraft client, LocalPlayer player) {
        pruebasFallidas++;

        if (pruebasFallidas >= MAX_PRUEBAS_FALLIDAS) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] " + MAX_PRUEBAS_FALLIDAS + " pruebas fallidas. No se encontró ruta. Deteniendo navegación."));
            detener(client);
            return;
        }

        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Prueba fallida " + pruebasFallidas + "/" + MAX_PRUEBAS_FALLIDAS
                + ". Retrocediendo para reintentar."));

        retrocesoTicks = DURACION_RETROCESO_TICKS;
        intentosDesvio = 0;
    }

    // =====================================================
    // SIMULACIÓN
    // =====================================================

    private boolean simularAvanceSeguro(Minecraft client, LocalPlayer player) {
        double yawRad = Math.toRadians(player.getYRot());

        double forwardX = -Math.sin(yawRad) * DISTANCIA_SIMULACION;
        double forwardZ = Math.cos(yawRad) * DISTANCIA_SIMULACION;

        double nuevaX = player.getX() + forwardX;
        double nuevaY = player.getY();
        double nuevaZ = player.getZ() + forwardZ;

        AABB hitboxNueva = new AABB(
                nuevaX - ANCHO_JUGADOR / 2,
                nuevaY,
                nuevaZ - ANCHO_JUGADOR / 2,
                nuevaX + ANCHO_JUGADOR / 2,
                nuevaY + ALTURA_JUGADOR,
                nuevaZ + ANCHO_JUGADOR / 2
        );

        int minX = (int) Math.floor(hitboxNueva.minX);
        int maxX = (int) Math.floor(hitboxNueva.maxX);
        int minY = (int) Math.floor(hitboxNueva.minY);
        int maxY = (int) Math.floor(hitboxNueva.maxY);
        int minZ = (int) Math.floor(hitboxNueva.minZ);
        int maxZ = (int) Math.floor(hitboxNueva.maxZ);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos bpos = new BlockPos(bx, by, bz);
                    BlockState estado = client.level.getBlockState(bpos);

                    if (estado.isAir()) continue;
                    if (esBloquePisable(client, bpos, estado)) continue;

                    VoxelShape forma = estado.getCollisionShape(client.level, bpos);
                    if (forma.isEmpty()) continue;

                    for (AABB cajaBloque : forma.toAabbs()) {
                        AABB cajaReal = cajaBloque.move(bx, by, bz);
                        if (cajaReal.intersects(hitboxNueva)) {
                            return false;
                        }
                    }
                }
            }
        }

        BlockPos sueloNuevo = BlockPos.containing(nuevaX, nuevaY - 0.1, nuevaZ);
        BlockState estadoSuelo = client.level.getBlockState(sueloNuevo);
        boolean haySuelo = esBloqueCaminable(client, sueloNuevo, estadoSuelo);

        if (!haySuelo) {
            int caida = 0;
            BlockPos check = sueloNuevo;
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

        if (colisionaConBloque(client, cabezaDelante, hitboxDelante)) {
            return false;
        }

        BlockState estadoPies = client.level.getBlockState(piesDelante);
        boolean esPisable = esBloquePisable(client, piesDelante, estadoPies);
        if (!esPisable && colisionaConBloque(client, piesDelante, hitboxDelante)) {
            if (!esEscalable(client, piesDelante, cabezaDelante)) {
                return false;
            }
        }

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
     * Explora múltiples ángulos a ambos lados hasta encontrar uno seguro.
     * Prioriza los ángulos suaves primero. Alterna el lado inicial.
     */
    private boolean iniciarDesvioSeguro(Minecraft client, LocalPlayer player, double dx, double dz) {
        if (intentosDesvio >= MAX_INTENTOS_DESVIO) {
            return false;
        }

        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

        int ladoPrimero = ultimaDireccionDesvio;
        int ladoSegundo = -ultimaDireccionDesvio;

        // Probar primero el lado alternado, luego el otro
        if (probarAngulosEnLado(client, player, yawObjetivo, ladoPrimero)) {
            return true;
        }
        if (probarAngulosEnLado(client, player, yawObjetivo, ladoSegundo)) {
            return true;
        }

        return false;
    }

    /**
     * Prueba todos los ángulos de exploración en un lado concreto.
     * Si encuentra uno seguro, inicia el desvío.
     */
    private boolean probarAngulosEnLado(Minecraft client, LocalPlayer player,
                                          float yawObjetivo, int lado) {
        for (float angulo : ANGULOS_EXPLORACION) {
            float yawDesviado = normalizarAngulo(yawObjetivo + (angulo * lado));
            if (esDireccionSeguraParaYaw(client, player, yawDesviado)) {
                intentosDesvio++;
                estadoDesvio = lado;
                desvioTicks = DURACION_DESVIO_TICKS;
                anguloDesvioActual = angulo;
                ultimaDireccionDesvio = -lado;
                return true;
            }
        }
        return false;
    }

    /**
     * Comprueba si la dirección indicada por yawDesviado es segura.
     */
    private boolean esDireccionSeguraParaYaw(Minecraft client, LocalPlayer player, float yawDesviado) {
        float yawOriginal = player.getYRot();
        player.setYRot(yawDesviado);

        boolean seguro = simularAvanceSeguro(client, player);

        player.setYRot(yawOriginal);
        return seguro;
    }

    private void aplicarRotacionDesvio(Minecraft client, LocalPlayer player, double dx, double dz) {
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawDesviado = normalizarAngulo(yawObjetivo + (anguloDesvioActual * estadoDesvio));

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