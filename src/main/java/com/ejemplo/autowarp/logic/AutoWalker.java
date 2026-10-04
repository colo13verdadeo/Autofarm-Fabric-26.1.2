package com.ejemplo.autowarp.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AutoWalker {

    private static final Logger LOGGER = LoggerFactory.getLogger("AutoWarp");

    private static final double DISTANCIA_LLEGADA = 0.5;
    private static final double VELOCIDAD_ROTACION = 15.0;
    private static final int TIMEOUT_MAX = 20 * 120;
    private static final double DISTANCIA_MIRA = 1.5;
    private static final int CAIDA_MAXIMA = 1;

    private static final double ALTURA_JUGADOR = 1.8;
    private static final double ANCHO_JUGADOR = 0.6;
    private static final int DURACION_SALTO_TICKS = 8;
    private static final double ALTURA_PISABLE = 0.5;

    private static final double MARGEN_LATERAL = 0.15;

    private static final int DURACION_DESVIO_TICKS = 15;
    private static final int MAX_INTENTOS_DESVIO = 6;

    private static final int DURACION_RETROCESO_TICKS = 6;

    private static final double DISTANCIA_SIMULACION = 0.5;

    private static final int MUESTRAS_TRAYECTORIA = 5;

    private static final int MAX_PRUEBAS_FALLIDAS = 11;

    private static final int ESPERA_ENTRE_PRUEBAS_TICKS = 10;

    private static final float[] ANGULOS_EXPLORACION = {
            30.0f, 60.0f, 90.0f, 120.0f, 150.0f
    };

    public interface LlegadaCallback {
        void onLlegada(double x, double y, double z);
    }

    private LlegadaCallback llegadaCallback;

    private boolean activo = false;
    private double targetX, targetY, targetZ;
    private int timeoutTicks = 0;
    private int saltoTicks = 0;

    private int ticksIgnorandoSuelo = 0;

    private int estadoDesvio = 0;
    private int desvioTicks = 0;
    private int intentosDesvio = 0;
    private int ultimaDireccionDesvio = 1;

    private float anguloDesvioActual = 0f;

    private int retrocesoTicks = 0;

    private int pruebasFallidas = 0;

    private int esperaEntrePruebasTicks = 0;

    private boolean zonaSegura = true;

    private void debug(String mensaje) {
        LOGGER.info("[DEBUG] " + mensaje);
    }

    public void setLlegadaCallback(LlegadaCallback callback) {
        this.llegadaCallback = callback;
    }

    public void setZonaSegura(boolean valor) {
        this.zonaSegura = valor;
    }

    public void iniciar(double x, double y, double z) {
        this.activo = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.timeoutTicks = 0;
        this.saltoTicks = 0;
        this.ticksIgnorandoSuelo = 0;
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
        this.ticksIgnorandoSuelo = 0;
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

        if (ticksIgnorandoSuelo > 0) {
            ticksIgnorandoSuelo--;
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

        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double distanciaHorizontal = Math.sqrt(dx * dx + dz * dz);

        if (distanciaHorizontal <= DISTANCIA_LLEGADA) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Destino alcanzado."));
            double fx = targetX, fy = targetY, fz = targetZ;
            detener(client);
            if (llegadaCallback != null) {
                llegadaCallback.onLlegada(fx, fy, fz);
            }
            return;
        }

        if (!zonaSegura) {
            float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
            float yawActual = player.getYRot();
            float diferencia = normalizarAngulo(yawObjetivo - yawActual);
            float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
            player.setYRot(yawActual + paso);

            if (saltoTicks > 0) {
                client.options.keyJump.setDown(true);
                saltoTicks--;
                if (saltoTicks == 0) {
                    client.options.keyJump.setDown(false);
                }
            }

            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }

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

        if (estadoDesvio != 0) {
            desvioTicks--;

            if (desvioTicks <= 0) {
                estadoDesvio = 0;
                anguloDesvioActual = 0f;
                pruebasFallidas = 0;
            } else {
                aplicarRotacionDesvio(client, player, dx, dz);

                if (!esDireccionSegura(client, player)) {
                    debug("DESVÍO PELIGROSO activado. Yaw=" + String.format("%.1f", player.getYRot())
                            + " EstadoDesvio=" + estadoDesvio
                            + " ÁnguloDesvio=" + String.format("%.1f", anguloDesvioActual)
                            + " Pos=(" + String.format("%.2f", player.getX()) + ", "
                            + String.format("%.2f", player.getY()) + ", "
                            + String.format("%.2f", player.getZ()) + ")");
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    anguloDesvioActual = 0f;
                    retrocesoTicks = DURACION_RETROCESO_TICKS;
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Desvío peligroso. Retrocediendo."));
                    return;
                }

                if (!simularAvanceSeguro(client, player)) {
                    debug("DESVÍO BLOQUEADO activado. Yaw=" + String.format("%.1f", player.getYRot())
                            + " EstadoDesvio=" + estadoDesvio
                            + " ÁnguloDesvio=" + String.format("%.1f", anguloDesvioActual)
                            + " Pos=(" + String.format("%.2f", player.getX()) + ", "
                            + String.format("%.2f", player.getY()) + ", "
                            + String.format("%.2f", player.getZ()) + ")");
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

        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        if (!simularAvanceSeguro(client, player)) {
            if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                if (!hayHuecoSuficiente(client, player)) {
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Pasillo estrecho detectado. No se puede pasar."));
                }
                registrarPruebaFallida(client, player);
            }
            return;
        }

        if (saltoTicks > 0) {
            client.options.keyJump.setDown(true);
            saltoTicks--;
            if (saltoTicks == 0) {
                client.options.keyJump.setDown(false);
            }
            ticksIgnorandoSuelo = Math.max(ticksIgnorandoSuelo, DURACION_SALTO_TICKS);
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(true);
            return;
        }

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
                    ticksIgnorandoSuelo = DURACION_SALTO_TICKS;
                }
            } else {
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    registrarPruebaFallida(client, player);
                }
                return;
            }
        }

        if (ticksIgnorandoSuelo == 0) {
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

        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double distanciaTotal = DISTANCIA_SIMULACION;

        for (int i = 1; i <= MUESTRAS_TRAYECTORIA; i++) {
            double factor = (double) i / MUESTRAS_TRAYECTORIA;
            double distanciaIntermedia = distanciaTotal * factor;

            double checkX = player.getX() + forwardX * distanciaIntermedia;
            double checkY = player.getY();
            double checkZ = player.getZ() + forwardZ * distanciaIntermedia;

            double margen = (i == MUESTRAS_TRAYECTORIA) ? MARGEN_LATERAL : 0.0;

            AABB hitboxIntermedia = new AABB(
                    checkX - ANCHO_JUGADOR / 2 - margen,
                    checkY,
                    checkZ - ANCHO_JUGADOR / 2 - margen,
                    checkX + ANCHO_JUGADOR / 2 + margen,
                    checkY + ALTURA_JUGADOR,
                    checkZ + ANCHO_JUGADOR / 2 + margen
            );

            if (hayColisionEnHitbox(client, hitboxIntermedia)) {
                debug("simularAvanceSeguro FALSA: colisión en muestra " + i + "/"
                        + MUESTRAS_TRAYECTORIA + " a " + String.format("%.2f", distanciaIntermedia)
                        + " bloques. Yaw=" + String.format("%.1f", player.getYRot())
                        + " Pos=(" + String.format("%.2f", checkX) + ", "
                        + String.format("%.2f", checkY) + ", "
                        + String.format("%.2f", checkZ) + ")");
                return false;
            }
        }

        if (ticksIgnorandoSuelo == 0) {
            double destinoX = player.getX() + forwardX * distanciaTotal;
            double destinoZ = player.getZ() + forwardZ * distanciaTotal;
            double destinoY = player.getY();

            BlockPos sueloNuevo = BlockPos.containing(destinoX, destinoY - 0.1, destinoZ);
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
                    debug("simularAvanceSeguro FALSA: precipicio al destino. Caída=" + caida
                            + " Pos=(" + String.format("%.2f", destinoX) + ", "
                            + String.format("%.2f", destinoY) + ", "
                            + String.format("%.2f", destinoZ) + ")");
                    return false;
                }
            }
        }

        return true;
    }

    private boolean hayColisionEnHitbox(Minecraft client, AABB hitbox) {
        int minX = (int) Math.floor(hitbox.minX);
        int maxX = (int) Math.floor(hitbox.maxX);
        int minY = (int) Math.floor(hitbox.minY);
        int maxY = (int) Math.floor(hitbox.maxY);
        int minZ = (int) Math.floor(hitbox.minZ);
        int maxZ = (int) Math.floor(hitbox.maxZ);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    BlockPos bpos = new BlockPos(bx, by, bz);
                    BlockState estado = client.level.getBlockState(bpos);

                    if (estado.isAir()) continue;
                    if (esBloquePisable(client, bpos, estado)) continue;
                    if (esBloqueNoSolido(estado)) continue;

                    VoxelShape forma = estado.getCollisionShape(client.level, bpos);
                    if (forma.isEmpty()) continue;

                    for (AABB cajaBloque : forma.toAabbs()) {
                        AABB cajaReal = cajaBloque.move(bx, by, bz);
                        if (cajaReal.intersects(hitbox)) {
                            return true;
                        }
                    }
                }
            }
        }

        return false;
    }

    private boolean hayHuecoSuficiente(Minecraft client, LocalPlayer player) {
        double yawRad = Math.toRadians(player.getYRot());

        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double lateralX = forwardZ;
        double lateralZ = -forwardX;

        double distanciaComprobacion = DISTANCIA_SIMULACION;
        double centerX = player.getX() + forwardX * distanciaComprobacion;
        double centerZ = player.getZ() + forwardZ * distanciaComprobacion;

        double[] alturas = {0.0, 0.5, 1.0, 1.5};

        for (double alturaRelativa : alturas) {
            double y = player.getY() + alturaRelativa;

            double huecoIzquierda = buscarBloqueLateral(client, centerX, centerZ, y,
                    -lateralX, -lateralZ);
            double huecoDerecha = buscarBloqueLateral(client, centerX, centerZ, y,
                    lateralX, lateralZ);

            double huecoTotal = huecoIzquierda + huecoDerecha;

            if (huecoTotal >= ANCHO_JUGADOR + MARGEN_LATERAL) {
                return true;
            }
        }

        return false;
    }

    private double buscarBloqueLateral(Minecraft client, double centerX, double centerZ,
                                        double y, double dirX, double dirZ) {
        double maxBusqueda = 1.5;
        double paso = 0.1;

        for (double d = 0.3; d <= maxBusqueda; d += paso) {
            double x = centerX + dirX * d;
            double z = centerZ + dirZ * d;

            BlockPos pos = BlockPos.containing(x, y, z);
            BlockState estado = client.level.getBlockState(pos);

            if (estado.isAir()) continue;
            if (esBloqueNoSolido(estado)) continue;
            if (esBloquePisable(client, pos, estado)) continue;

            VoxelShape forma = estado.getCollisionShape(client.level, pos);
            if (forma.isEmpty()) continue;

            AABB cajaBloque = forma.bounds().move(pos.getX(), pos.getY(), pos.getZ());
            if (cajaBloque.minX <= x && cajaBloque.maxX >= x
                    && cajaBloque.minZ <= z && cajaBloque.maxZ >= z) {
                return d;
            }
        }

        return maxBusqueda;
    }

    private boolean esDireccionSegura(Minecraft client, LocalPlayer player) {
        double yawRad = Math.toRadians(player.getYRot());

        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double distanciaTotal = DISTANCIA_MIRA;

        for (int i = 1; i <= MUESTRAS_TRAYECTORIA; i++) {
            double factor = (double) i / MUESTRAS_TRAYECTORIA;
            double distanciaIntermedia = distanciaTotal * factor;

            double checkX = player.getX() + forwardX * distanciaIntermedia;
            double checkZ = player.getZ() + forwardZ * distanciaIntermedia;
            double checkY = player.getY();

            AABB hitbox = new AABB(
                    checkX - ANCHO_JUGADOR / 2 - MARGEN_LATERAL,
                    checkY,
                    checkZ - ANCHO_JUGADOR / 2 - MARGEN_LATERAL,
                    checkX + ANCHO_JUGADOR / 2 + MARGEN_LATERAL,
                    checkY + ALTURA_JUGADOR,
                    checkZ + ANCHO_JUGADOR / 2 + MARGEN_LATERAL
            );

            if (hayColisionEnHitbox(client, hitbox)) {
                debug("esDireccionSegura FALSA: colisión en muestra " + i + "/"
                        + MUESTRAS_TRAYECTORIA + " a " + String.format("%.2f", distanciaIntermedia)
                        + " bloques. Yaw=" + String.format("%.1f", player.getYRot())
                        + " Pos=(" + String.format("%.2f", checkX) + ", "
                        + String.format("%.2f", checkY) + ", "
                        + String.format("%.2f", checkZ) + ")");
                return false;
            }

            if (zonaSegura && ticksIgnorandoSuelo == 0) {
                BlockPos sueloCheck = BlockPos.containing(checkX, checkY - 0.1, checkZ);
                BlockState estadoSuelo = client.level.getBlockState(sueloCheck);
                boolean haySuelo = esBloqueCaminable(client, sueloCheck, estadoSuelo);

                if (!haySuelo) {
                    int caida = 0;
                    BlockPos check = sueloCheck;
                    while (caida <= CAIDA_MAXIMA + 1) {
                        BlockState st = client.level.getBlockState(check);
                        if (esBloqueCaminable(client, check, st)) break;
                        check = check.below();
                        caida++;
                    }
                    if (caida > CAIDA_MAXIMA) {
                        debug("esDireccionSegura FALSA: precipicio en muestra " + i + "/"
                                + MUESTRAS_TRAYECTORIA + " a " + String.format("%.2f", distanciaIntermedia)
                                + " bloques. Caída=" + caida
                                + " Pos=(" + String.format("%.2f", checkX) + ", "
                                + String.format("%.2f", checkY) + ", "
                                + String.format("%.2f", checkZ) + ")");
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean iniciarDesvioSeguro(Minecraft client, LocalPlayer player, double dx, double dz) {
        if (intentosDesvio >= MAX_INTENTOS_DESVIO) {
            return false;
        }

        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));

        int ladoPrimero = ultimaDireccionDesvio;
        int ladoSegundo = -ultimaDireccionDesvio;

        if (probarAngulosEnLado(client, player, yawObjetivo, ladoPrimero)) {
            return true;
        }
        if (probarAngulosEnLado(client, player, yawObjetivo, ladoSegundo)) {
            return true;
        }

        return false;
    }

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

    private boolean esBloqueNoSolido(BlockState estado) {
        if (estado.is(Blocks.BARRIER)) return false;

        if (estado.getBlock() instanceof SignBlock) return true;
        if (estado.is(BlockTags.BANNERS)) return true;
        if (estado.is(BlockTags.ALL_SIGNS)) return true;
        if (estado.is(BlockTags.FENCE_GATES)) return true;
        if (estado.is(BlockTags.CANDLES)) return true;
        if (estado.is(BlockTags.CANDLE_CAKES)) return true;
        return false;
    }

    private boolean esEscalable(Minecraft client, BlockPos piesDelante, BlockPos cabezaDelante) {
        BlockState estadoCabeza = client.level.getBlockState(cabezaDelante);
        if (!esBloqueNoSolido(estadoCabeza)
                && !estadoCabeza.getCollisionShape(client.level, cabezaDelante).isEmpty()) {
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

        if (esBloqueNoSolido(estado)) return false;

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