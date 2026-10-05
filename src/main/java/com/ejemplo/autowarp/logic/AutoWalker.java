package com.ejemplo.autowarp.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Entity;
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
    private static final double VELOCIDAD_ROTACION = 30.0;
    private static final int TIMEOUT_MAX = 20 * 120;
    private static final double DISTANCIA_MIRA = 1.5;
    private static final int CAIDA_MAXIMA = 1;

    private static final double ALTURA_JUGADOR = 1.8;
    private static final double ANCHO_JUGADOR = 0.6;
    private static final int DURACION_SALTO_TICKS = 8;
    private static final double ALTURA_PISABLE = 0.5;

    private static final double MARGEN_LATERAL = 0.15;

    private static final int DURACION_DESVIO_TICKS = 25;
    private static final int MAX_INTENTOS_DESVIO = 6;

    private static final int DURACION_RETROCESO_TICKS = 6;

    private static final double DISTANCIA_SIMULACION = 0.5;

    private static final int MUESTRAS_TRAYECTORIA = 5;

    private static final int MAX_PRUEBAS_FALLIDAS = 11;

    private static final int ESPERA_ENTRE_PRUEBAS_TICKS = 3;

    private static final float UMBRAL_ALINEACION_DESVIO = 15.0f;

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

    /** Contador de ticks para no spamear logs de velocidad. */
    private int tickCounter = 0;

    private void debug(String mensaje) {
        LOGGER.info("[DEBUG] " + mensaje);
    }

    /**
     * Log de velocidad del jugador. Se llama cada tick para ver si avanza.
     */
    private void logVelocidad(LocalPlayer player, String contexto) {
        double vx = player.getX() - player.xOld;
        double vz = player.getZ() - player.zOld;
        double velocidad = Math.sqrt(vx * vx + vz * vz);
        debug("VELOCIDAD [" + contexto + "]: " + String.format("%.3f", velocidad)
                + " bloques/tick | vx=" + String.format("%.3f", vx)
                + " vz=" + String.format("%.3f", vz)
                + " | Pos=(" + String.format("%.2f", player.getX()) + ", "
                + String.format("%.2f", player.getY()) + ", "
                + String.format("%.2f", player.getZ()) + ")");
    }

    private void logTeclas(Minecraft client) {
        debug("TECLAS: keyUp=" + client.options.keyUp.isDown()
                + " keySprint=" + client.options.keySprint.isDown()
                + " keyJump=" + client.options.keyJump.isDown()
                + " keyDown=" + client.options.keyDown.isDown()
                + " keyLeft=" + client.options.keyLeft.isDown()
                + " keyRight=" + client.options.keyRight.isDown());
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
        this.tickCounter = 0;
        debug("INICIAR navegación hacia (" + String.format("%.2f", x) + ", "
                + String.format("%.2f", y) + ", " + String.format("%.2f", z) + ")");
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
        debug("DETENER navegación");
        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
        }
    }

    public boolean estaActivo() {
        return activo;
    }

    private boolean haySueloEn(Minecraft client, double x, double y, double z) {
        BlockPos pos = BlockPos.containing(x, y - 0.1, z);
        BlockState estado = client.level.getBlockState(pos);
        return esBloqueCaminable(client, pos, estado);
    }

    private boolean retrocesoEsSeguro(Minecraft client, LocalPlayer player) {
        double yawRad = Math.toRadians(player.getYRot());
        double backX = Math.sin(yawRad);
        double backZ = -Math.cos(yawRad);

        double checkX = player.getX() + backX * 0.8;
        double checkZ = player.getZ() + backZ * 0.8;
        double checkY = player.getY();

        return haySueloEn(client, checkX, checkY, checkZ);
    }

    private boolean desvioLateralEsSeguro(Minecraft client, LocalPlayer player, int lado) {
        double yawRad = Math.toRadians(player.getYRot() + 90 * lado);
        double sideX = -Math.sin(yawRad);
        double sideZ = Math.cos(yawRad);

        double checkX = player.getX() + sideX * 0.8;
        double checkZ = player.getZ() + sideZ * 0.8;
        double checkY = player.getY();

        return haySueloEn(client, checkX, checkY, checkZ);
    }

    private boolean alBordeDePrecipicio(Minecraft client, LocalPlayer player) {
        BlockPos pies = BlockPos.containing(player.getX(), player.getY() - 0.1, player.getZ());
        BlockState estadoPies = client.level.getBlockState(pies);

        if (esBloqueCaminable(client, pies, estadoPies)) {
            return false;
        }

        int caida = 0;
        BlockPos check = pies;
        while (caida <= CAIDA_MAXIMA + 1) {
            BlockState st = client.level.getBlockState(check);
            if (esBloqueCaminable(client, check, st)) return false;
            check = check.below();
            caida++;
        }
        return caida > CAIDA_MAXIMA;
    }

    private boolean direccionApuntaAPrecipicio(Minecraft client, LocalPlayer player, float yaw) {
        double yawRad = Math.toRadians(yaw);
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        double distancia = 1.5;
        double checkX = player.getX() + forwardX * distancia;
        double checkZ = player.getZ() + forwardZ * distancia;
        double checkY = player.getY();

        BlockPos pos = BlockPos.containing(checkX, checkY - 0.1, checkZ);
        BlockState estado = client.level.getBlockState(pos);

        if (!estado.isAir()) return false;

        int caida = 0;
        BlockPos check = pos;
        while (caida <= CAIDA_MAXIMA + 2) {
            BlockState st = client.level.getBlockState(check);
            if (esBloqueCaminable(client, check, st)) return false;
            check = check.below();
            caida++;
        }
        return true;
    }

    private void manejarBordeDePrecipicio(Minecraft client, LocalPlayer player) {
        debug("MANEJAR BORDE: retrocesoEsSeguro=" + retrocesoEsSeguro(client, player)
                + " desvioIzqSeguro=" + desvioLateralEsSeguro(client, player, 1)
                + " desvioDerSeguro=" + desvioLateralEsSeguro(client, player, -1));

        if (retrocesoEsSeguro(client, player)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyDown.setDown(true);
            retrocesoTicks = DURACION_RETROCESO_TICKS;
            debug("BORDE: retrocediendo");
            return;
        }

        if (desvioLateralEsSeguro(client, player, 1)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyLeft.setDown(true);
            retrocesoTicks = DURACION_RETROCESO_TICKS;
            debug("BORDE: desviando izquierda");
            return;
        }

        if (desvioLateralEsSeguro(client, player, -1)) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(true);
            retrocesoTicks = DURACION_RETROCESO_TICKS;
            debug("BORDE: desviando derecha");
            return;
        }

        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyDown.setDown(false);
        client.options.keyLeft.setDown(false);
        client.options.keyRight.setDown(false);
        esperaEntrePruebasTicks = ESPERA_ENTRE_PRUEBAS_TICKS;
        debug("BORDE: bloqueado, esperando " + ESPERA_ENTRE_PRUEBAS_TICKS + " ticks");
    }

    public void tick(Minecraft client) {
        if (!activo) return;

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            detener(client);
            return;
        }

        tickCounter++;

        if (ticksIgnorandoSuelo > 0) {
            ticksIgnorandoSuelo--;
        }

        // Log cada 20 ticks para no spamear
        if (tickCounter % 20 == 0) {
            debug("=== TICK " + tickCounter + " === Estado: desvio=" + estadoDesvio
                    + " desvioTicks=" + desvioTicks
                    + " retroceso=" + retrocesoTicks
                    + " espera=" + esperaEntrePruebasTicks
                    + " pruebasFallidas=" + pruebasFallidas
                    + " salto=" + saltoTicks
                    + " ticksIgnorandoSuelo=" + ticksIgnorandoSuelo);
            logTeclas(client);
        }

        boolean movimientoManual = false;

        if (client.options.keyLeft.isDown() || client.options.keyRight.isDown()) {
            movimientoManual = true;
            if (tickCounter % 20 == 0) debug("Movimiento manual por keyLeft/keyRight");
        }
        if (retrocesoTicks == 0
                && esperaEntrePruebasTicks == 0
                && client.options.keyDown.isDown()) {
            movimientoManual = true;
            if (tickCounter % 20 == 0) debug("Movimiento manual por keyDown");
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

        if (!player.onGround() && saltoTicks == 0 && ticksIgnorandoSuelo == 0) {
            if (tickCounter % 20 == 0) debug("En el aire, esperando a tocar suelo");
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            return;
        }

        if (esperaEntrePruebasTicks > 0) {
            esperaEntrePruebasTicks--;
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
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
            if (!client.options.keyDown.isDown()
                    && !client.options.keyLeft.isDown()
                    && !client.options.keyRight.isDown()) {
                client.options.keyDown.setDown(true);
            }

            if (retrocesoTicks <= 0) {
                client.options.keyDown.setDown(false);
                client.options.keyLeft.setDown(false);
                client.options.keyRight.setDown(false);
                esperaEntrePruebasTicks = ESPERA_ENTRE_PRUEBAS_TICKS;
                debug("Retroceso terminado, esperando " + ESPERA_ENTRE_PRUEBAS_TICKS + " ticks");
            }
            return;
        }

        if (alBordeDePrecipicio(client, player)) {
            debug("AL BORDE DE PRECIPICIO detectado");
            manejarBordeDePrecipicio(client, player);
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
            if (tickCounter % 20 == 0) logVelocidad(player, "sinZonaSegura");
            return;
        }

        if (estadoDesvio != 0) {
            desvioTicks--;

            if (desvioTicks <= 0) {
                estadoDesvio = 0;
                anguloDesvioActual = 0f;
                pruebasFallidas = 0;
                debug("Desvío terminado");
            } else {
                aplicarRotacionDesvio(client, player, dx, dz);

                float yawObjetivoBase = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
                float yawObjetivoDesvio = normalizarAngulo(yawObjetivoBase + (anguloDesvioActual * estadoDesvio));
                float diferenciaRotacion = Math.abs(normalizarAngulo(yawObjetivoDesvio - player.getYRot()));

                if (diferenciaRotacion > UMBRAL_ALINEACION_DESVIO) {
                    if (tickCounter % 20 == 0) {
                        debug("Desvío: rotando. Dif=" + String.format("%.1f", diferenciaRotacion)
                                + " yawActual=" + String.format("%.1f", player.getYRot())
                                + " yawObjetivoDesvio=" + String.format("%.1f", yawObjetivoDesvio));
                    }
                    client.options.keyUp.setDown(false);
                    client.options.keySprint.setDown(false);
                    return;
                }

                if (!esDireccionSegura(client, player)) {
                    debug("DESVÍO PELIGROSO activado. Yaw=" + String.format("%.1f", player.getYRot())
                            + " EstadoDesvio=" + estadoDesvio
                            + " ÁnguloDesvio=" + String.format("%.1f", anguloDesvioActual));
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    anguloDesvioActual = 0f;
                    manejarBordeDePrecipicio(client, player);
                    return;
                }

                if (!simularAvanceSeguro(client, player)) {
                    debug("DESVÍO BLOQUEADO activado. Yaw=" + String.format("%.1f", player.getYRot())
                            + " EstadoDesvio=" + estadoDesvio
                            + " ÁnguloDesvio=" + String.format("%.1f", anguloDesvioActual));
                    estadoDesvio = 0;
                    desvioTicks = 0;
                    anguloDesvioActual = 0f;
                    manejarBordeDePrecipicio(client, player);
                    return;
                }

                client.options.keyUp.setDown(true);
                client.options.keySprint.setDown(true);
                if (tickCounter % 20 == 0) logVelocidad(player, "desvio");
                return;
            }
        }

        // === AVANCE NORMAL ===
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        if (!simularAvanceSeguro(client, player)) {
            debug("Avance bloqueado por simulación. Buscando desvío...");
            if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                debug("No hay desvío posible. Registrando prueba fallida.");
                if (!hayHuecoSuficiente(client, player)) {
                    player.sendSystemMessage(Component.literal(
                            "[AutoWarp] Pasillo estrecho detectado. No se puede pasar."));
                }
                registrarPruebaFallida(client, player);
            } else {
                debug("Desvío iniciado: estadoDesvio=" + estadoDesvio
                        + " angulo=" + String.format("%.1f", anguloDesvioActual));
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
            if (tickCounter % 20 == 0) logVelocidad(player, "salto");
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
                debug("Bloque escalable detectado. Iniciando salto.");
                if (saltoTicks == 0) {
                    saltoTicks = DURACION_SALTO_TICKS;
                    ticksIgnorandoSuelo = DURACION_SALTO_TICKS;
                }
            } else {
                debug("Bloque NO escalable. Buscando desvío...");
                if (!iniciarDesvioSeguro(client, player, dx, dz)) {
                    debug("No hay desvío posible desde bloque no escalable.");
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
                    debug("Precipicio detectado en avance normal. Manejar borde.");
                    manejarBordeDePrecipicio(client, player);
                    return;
                }
            }
        }

        pruebasFallidas = 0;
        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(true);
        if (tickCounter % 20 == 0) logVelocidad(player, "avanceNormal");
    }

    private void registrarPruebaFallida(Minecraft client, LocalPlayer player) {
        pruebasFallidas++;

        debug("PRUEBA FALLIDA " + pruebasFallidas + "/" + MAX_PRUEBAS_FALLIDAS);

        if (pruebasFallidas >= MAX_PRUEBAS_FALLIDAS) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] " + MAX_PRUEBAS_FALLIDAS + " pruebas fallidas. No se encontró ruta. Deteniendo navegación."));
            detener(client);
            return;
        }

        player.sendSystemMessage(Component.literal(
                "[AutoWarp] Prueba fallida " + pruebasFallidas + "/" + MAX_PRUEBAS_FALLIDAS
                + ". Reintentando."));

        intentosDesvio = 0;
        if (pruebasFallidas >= 3) {
            esperaEntrePruebasTicks = ESPERA_ENTRE_PRUEBAS_TICKS;
            debug("Esperando " + ESPERA_ENTRE_PRUEBAS_TICKS + " ticks antes de reintentar");
        }
    }

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

            if (hayColisionNoEscalable(client, checkX, checkY, checkZ, margen)) {
                if (i <= 3) {
                    if (tickCounter % 20 == 0) {
                        debug("Simulación FALSA: colisión en muestra " + i + "/5 a "
                                + String.format("%.2f", distanciaIntermedia) + " bloques");
                    }
                    return false;
                }
                break;
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
                    if (tickCounter % 20 == 0) {
                        debug("Simulación FALSA: precipicio al destino. Caída=" + caida);
                    }
                    return false;
                }
            }
        }

        return true;
    }

    private boolean hayColisionNoEscalable(Minecraft client, double checkX, double checkY,
                                             double checkZ, double margen) {
        AABB hitbox = new AABB(
                checkX - ANCHO_JUGADOR / 2 - margen,
                checkY,
                checkZ - ANCHO_JUGADOR / 2 - margen,
                checkX + ANCHO_JUGADOR / 2 + margen,
                checkY + ALTURA_JUGADOR,
                checkZ + ANCHO_JUGADOR / 2 + margen
        );

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
                            double alturaBloque = forma.max(Direction.Axis.Y);
                            
                            if (alturaBloque > ALTURA_PISABLE && alturaBloque <= 1.0) {
                                BlockPos cabezaPos = bpos.above();
                                BlockState estadoCabeza = client.level.getBlockState(cabezaPos);
                                
                                boolean cabezaLibre = esBloqueNoSolido(estadoCabeza)
                                        || estadoCabeza.getCollisionShape(client.level, cabezaPos).isEmpty();
                                
                                if (cabezaLibre) {
                                    continue;
                                }
                            }
                            
                            return true;
                        }
                    }
                }
            }
        }

        if (hayEntidadBloqueando(client, hitbox)) {
            return true;
        }

        return false;
    }

    private boolean hayEntidadBloqueando(Minecraft client, AABB hitbox) {
        if (client.level == null) return false;

        for (Entity entidad : client.level.entitiesForRendering()) {
            if (entidad == client.player) continue;
            if (entidad.isSpectator()) continue;
            if (entidad.noPhysics) continue;

            AABB cajaEntidad = entidad.getBoundingBox();
            if (cajaEntidad.intersects(hitbox)) {
                return true;
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

            if (hayColisionNoEscalable(client, checkX, checkY, checkZ, MARGEN_LATERAL)) {
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
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private boolean iniciarDesvioSeguro(Minecraft client, LocalPlayer player, double dx, double dz) {
        if (intentosDesvio >= MAX_INTENTOS_DESVIO) {
            debug("iniciarDesvioSeguro: agotados intentos (" + intentosDesvio + "/" + MAX_INTENTOS_DESVIO + ")");
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

        debug("iniciarDesvioSeguro: ningún ángulo seguro en ningún lado");
        return false;
    }

    private boolean probarAngulosEnLado(Minecraft client, LocalPlayer player,
                                          float yawObjetivo, int lado) {
        for (float angulo : ANGULOS_EXPLORACION) {
            float yawDesviado = normalizarAngulo(yawObjetivo + (angulo * lado));

            if (direccionApuntaAPrecipicio(client, player, yawDesviado)) {
                debug("  Ángulo " + String.format("%.0f", angulo) + "° lado " + lado
                        + ": apunta a precipicio, descartado");
                continue;
            }

            if (esDireccionSeguraParaYaw(client, player, yawDesviado)) {
                intentosDesvio++;
                estadoDesvio = lado;
                desvioTicks = DURACION_DESVIO_TICKS;
                anguloDesvioActual = angulo;
                ultimaDireccionDesvio = -lado;
                debug("  Ángulo " + String.format("%.0f", angulo) + "° lado " + lado + ": SEGURO");
                return true;
            } else {
                debug("  Ángulo " + String.format("%.0f", angulo) + "° lado " + lado
                        + ": no seguro");
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