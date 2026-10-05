package com.ejemplo.autowarp.logic;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.GoalXZ;
import com.ejemplo.autowarp.compat.KillAuraCompat;
import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

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

    private static final double DISTANCIA_LLEGADA = 0.1;
    private static final double DISTANCIA_AJUSTE_FINO = 1.5;
    private static final double DISTANCIA_WARP_COMPLETADO = 50.0;
    private static final int ESPERA_WARP_MAXIMA_TICKS = 200;
    private static final int MAX_TICKS_CAMINANDO = 20 * 60;

    private static final float UMBRAL_ALINEACION = 10.0f;
    private static final float UMBRAL_ALINEACION_CERCA = 3.0f;
    private static final float VELOCIDAD_ROTACION = 30.0f;
    private static final float VELOCIDAD_ROTACION_CERCA = 15.0f;
    private static final int DURACION_SALTO_TICKS = 8;

    private static final int DURACION_DESVIO_TICKS = 20;
    private static final int ESPERA_TRAS_DESVIO_TICKS = 5;
    private static final int MAX_TICKS_SIN_PROGRESO = 20 * 15;

    private static final double DISTANCIA_LLEGADA_BARITONE = 2.0;
    private static final int GRACIA_BARITONE_TICKS = 40;
    private static final int MAX_TICKS_ESPERANDO_BARITONE = 20 * 120;

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

    private boolean irAAutofarmTrasEspera = false;

    private int esperandoWarpAutofarm = -1;

    private double destinoX, destinoY, destinoZ;
    private boolean caminando = false;
    private int ticksCaminando = 0;
    private int saltoTicks = 0;
    private boolean destinoEsAutofarm = false;

    private boolean usandoBaritone = false;

    private int estadoDesvio = 0;
    private int desvioTicks = 0;
    private int esperaTrasDesvioTicks = 0;
    private int ultimaDireccionDesvio = 1;

    private double distanciaAnterior = Double.MAX_VALUE;
    private int ticksSinProgreso = 0;

    private double distanciaAnteriorAjuste = Double.MAX_VALUE;
    private int ticksAlejandose = 0;

    private double targetBaritoneX, targetBaritoneY, targetBaritoneZ;

    private int graciaBaritone = 0;

    private int ticksEsperandoBaritone = 0;

    /** Indica si KillAura estaba activado antes de desactivarlo. */
    private boolean killauraEstabaActivo = false;

    public void tick(Minecraft client) {
        AutoWarpConfig cfg = AutoWarpConfig.get();
        if (cfg == null || !cfg.modActivado || !cfg.checkeoActivo) {
            if (esperandoMensajeError && client.player != null) {
                procesarInteraccion(client, client.player);
            }
            if (usandoBaritone) {
                comprobarLlegadaBaritone(client, client.player);
            } else {
                procesarCaminata(client);
            }
            return;
        }

        LocalPlayer player = client.player;
        if (player == null || client.gameMode == null) {
            resetear();
            return;
        }

        if (usandoBaritone) {
            comprobarLlegadaBaritone(client, player);
            return;
        }

        procesarCaminata(client);

        if (esperandoWarpAutofarm >= 0) {
            esperandoWarpAutofarm++;

            double dx = destinoX - player.getX();
            double dz = destinoZ - player.getZ();
            double distancia = Math.sqrt(dx * dx + dz * dz);

            if (distancia < DISTANCIA_WARP_COMPLETADO) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Warp completado. Iniciando navegación..."));
                esperandoWarpAutofarm = -1;
                iniciarNavegacion(destinoX, destinoY, destinoZ, true);
                return;
            }

            if (esperandoWarpAutofarm >= ESPERA_WARP_MAXIMA_TICKS) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Timeout esperando warp. Iniciando navegación igualmente."));
                esperandoWarpAutofarm = -1;
                iniciarNavegacion(destinoX, destinoY, destinoZ, true);
            }
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
    // INICIAR NAVEGACIÓN
    // =====================================================

    private void iniciarNavegacion(double x, double y, double z, boolean esAutofarm) {
        AutoWarpConfig cfg = AutoWarpConfig.get();

        boolean zonaSegura;
        if (esAutofarm) {
            zonaSegura = cfg != null && cfg.zonaSeguraPostCarteles;
        } else {
            zonaSegura = cfg != null && cfg.zonaSeguraCarteles;
        }

        this.destinoX = x;
        this.destinoY = y;
        this.destinoZ = z;
        this.destinoEsAutofarm = esAutofarm;

        if (zonaSegura) {
            this.usandoBaritone = true;
            this.caminando = false;
            iniciarBaritone(x, y, z, esAutofarm);
        } else {
            this.usandoBaritone = false;
            iniciarCaminata(x, y, z, esAutofarm);
        }
    }

    // =====================================================
    // BARITONE
    // =====================================================

    private void iniciarBaritone(double x, double y, double z, boolean esAutofarm) {
        this.targetBaritoneX = x;
        this.targetBaritoneY = y;
        this.targetBaritoneZ = z;
        this.graciaBaritone = GRACIA_BARITONE_TICKS;
        this.ticksEsperandoBaritone = 0;

        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            baritone.getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) x, (int) z));
            BaritoneAPI.getSettings().allowSprint.value = true;

            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "[AutoWarp] (Modo seguro) Baritone navegando hacia ("
                        + (int) x + ", " + (int) z + ")"));
            }
        } catch (Exception e) {
            System.err.println("[AutoWarp] Error al iniciar Baritone: " + e.getMessage());
            this.usandoBaritone = false;
            iniciarCaminata(x, y, z, esAutofarm);
        }
    }

    private void comprobarLlegadaBaritone(Minecraft client, LocalPlayer player) {
        if (!usandoBaritone || player == null) return;

        if (graciaBaritone > 0) {
            graciaBaritone--;
            return;
        }

        ticksEsperandoBaritone++;

        if (ticksEsperandoBaritone > MAX_TICKS_ESPERANDO_BARITONE) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Baritone tardó demasiado. Cambiando a caminata recta."));
            cancelarBaritone();
            this.usandoBaritone = false;
            iniciarCaminata(targetBaritoneX, targetBaritoneY, targetBaritoneZ, destinoEsAutofarm);
            return;
        }

        double dx = targetBaritoneX - player.getX();
        double dz = targetBaritoneZ - player.getZ();
        double distancia = Math.sqrt(dx * dx + dz * dz);

        boolean baritoneActivo = baritoneNavegando();

        if (!baritoneActivo && distancia < DISTANCIA_LLEGADA_BARITONE) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Baritone llegó al destino. Distancia: "
                    + String.format("%.2f", distancia)));
            this.usandoBaritone = false;

            if (destinoEsAutofarm) {
                onLlegadaAutofarm((int) targetBaritoneX, (int) targetBaritoneY, (int) targetBaritoneZ);
            } else {
                onLlegadaAlDestino((int) targetBaritoneX, (int) targetBaritoneY, (int) targetBaritoneZ);
            }
        }
    }

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

    private void cancelarBaritone() {
        try {
            BaritoneAPI.getProvider()
                    .getPrimaryBaritone()
                    .getPathingBehavior()
                    .cancelEverything();
        } catch (Exception e) {
            // Silenciar
        }
    }

    // =====================================================
    // CAMINATA RECTA (MODO INSEGURO)
    // =====================================================

    private void iniciarCaminata(double x, double y, double z, boolean esAutofarm) {
        this.destinoX = x;
        this.destinoY = y;
        this.destinoZ = z;
        this.destinoEsAutofarm = esAutofarm;
        this.caminando = true;
        this.ticksCaminando = 0;
        this.saltoTicks = 0;
        this.estadoDesvio = 0;
        this.desvioTicks = 0;
        this.esperaTrasDesvioTicks = 0;
        this.ultimaDireccionDesvio = 1;
        this.distanciaAnterior = Double.MAX_VALUE;
        this.ticksSinProgreso = 0;
        this.distanciaAnteriorAjuste = Double.MAX_VALUE;
        this.ticksAlejandose = 0;

        Minecraft client = Minecraft.getInstance();
        if (client.player != null) {
            client.player.sendSystemMessage(Component.literal(
                    "[AutoWarp] (Modo inseguro) Caminando recto hacia ("
                    + (int) x + ", " + (int) z + ")"));
        }
    }

    private void procesarCaminata(Minecraft client) {
        if (!caminando) return;

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            detenerCaminata(client);
            return;
        }

        ticksCaminando++;

        if (ticksCaminando > MAX_TICKS_CAMINANDO) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Timeout caminando. Deteniendo."));
            detenerCaminata(client);
            return;
        }

        double dx = destinoX - player.getX();
        double dz = destinoZ - player.getZ();
        double distanciaHorizontal = Math.sqrt(dx * dx + dz * dz);

        if (distanciaHorizontal <= DISTANCIA_LLEGADA) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Destino alcanzado. Distancia: "
                    + String.format("%.3f", distanciaHorizontal)));
            detenerCaminata(client);

            if (destinoEsAutofarm) {
                onLlegadaAutofarm((int) destinoX, (int) destinoY, (int) destinoZ);
            } else {
                onLlegadaAlDestino((int) destinoX, (int) destinoY, (int) destinoZ);
            }
            return;
        }

        if (distanciaHorizontal < distanciaAnterior - 0.05) {
            ticksSinProgreso = 0;
            distanciaAnterior = distanciaHorizontal;
        } else {
            ticksSinProgreso++;
        }

        if (ticksSinProgreso > MAX_TICKS_SIN_PROGRESO) {
            player.sendSystemMessage(Component.literal(
                    "[AutoWarp] Sin progreso durante demasiado tiempo. Deteniendo caminata."));
            detenerCaminata(client);
            return;
        }

        if (distanciaHorizontal < DISTANCIA_AJUSTE_FINO) {
            if (distanciaHorizontal > distanciaAnteriorAjuste + 0.005) {
                ticksAlejandose++;
            } else {
                ticksAlejandose = 0;
            }

            if (ticksAlejandose >= 3) {
                client.options.keyUp.setDown(false);
                client.options.keySprint.setDown(false);
                client.options.keyDown.setDown(true);
                client.options.keyJump.setDown(false);

                if (ticksAlejandose >= 5) {
                    client.options.keyDown.setDown(false);
                    ticksAlejandose = 0;
                }
                distanciaAnteriorAjuste = distanciaHorizontal;
                return;
            }
        } else {
            ticksAlejandose = 0;
        }
        distanciaAnteriorAjuste = distanciaHorizontal;

        if (esperaTrasDesvioTicks > 0) {
            esperaTrasDesvioTicks--;
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            client.options.keyJump.setDown(false);
            return;
        }

        if (estadoDesvio != 0) {
            procesarDesvio(client, player, dx, dz);
            return;
        }

        float yawObjetivo = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawActual = player.getYRot();
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);

        boolean cerca = distanciaHorizontal < DISTANCIA_AJUSTE_FINO;
        float velocidadRotacion = cerca ? VELOCIDAD_ROTACION_CERCA : VELOCIDAD_ROTACION;

        if (Math.abs(diferencia) > velocidadRotacion) {
            yawActual += (diferencia > 0 ? velocidadRotacion : -velocidadRotacion);
        } else {
            yawActual = yawObjetivo;
        }
        player.setYRot(yawActual);

        float umbralAlineacion = cerca ? UMBRAL_ALINEACION_CERCA : UMBRAL_ALINEACION;

        if (Math.abs(normalizarAngulo(yawObjetivo - player.getYRot())) > umbralAlineacion) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            return;
        }

        if (saltoTicks > 0) {
            saltoTicks--;
            client.options.keyJump.setDown(true);
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(!cerca);
            if (saltoTicks == 0) {
                client.options.keyJump.setDown(false);
            }
            return;
        }

        double yawRad = Math.toRadians(player.getYRot());
        double forwardX = -Math.sin(yawRad);
        double forwardZ = Math.cos(yawRad);

        BlockPos piesDelante = BlockPos.containing(
                player.getX() + forwardX * 0.8,
                player.getY(),
                player.getZ() + forwardZ * 0.8
        );
        BlockPos cabezaDelante = piesDelante.above();

        BlockState estadoPies = client.level.getBlockState(piesDelante);
        BlockState estadoCabeza = client.level.getBlockState(cabezaDelante);

        boolean piesBloqueado = esBloqueObstaculo(client, piesDelante, estadoPies);
        boolean cabezaBloqueada = esBloqueObstaculo(client, cabezaDelante, estadoCabeza);

        if (cabezaBloqueada) {
            iniciarDesvio(client, player, dx, dz);
            return;
        }

        if (piesBloqueado) {
            saltoTicks = DURACION_SALTO_TICKS;
            client.options.keyJump.setDown(true);
            client.options.keyUp.setDown(true);
            client.options.keySprint.setDown(!cerca);
            return;
        }

        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(!cerca);
    }

    private boolean esBloqueObstaculo(Minecraft client, BlockPos pos, BlockState estado) {
        if (estado.isAir()) return false;
        if (esBloqueNoSolido(estado)) return false;

        VoxelShape forma = estado.getCollisionShape(client.level, pos);
        if (forma.isEmpty()) return false;

        double altura = forma.max(Direction.Axis.Y);
        if (altura < 0.5) return false;

        if (estado.is(BlockTags.SLABS)) return false;

        return true;
    }

    private void iniciarDesvio(Minecraft client, LocalPlayer player, double dx, double dz) {
        int ladoElegido = elegirMejorLadoDesvio(client, player, dx, dz);

        estadoDesvio = ladoElegido;
        desvioTicks = DURACION_DESVIO_TICKS;
        ultimaDireccionDesvio = -ladoElegido;

        client.options.keyUp.setDown(false);
        client.options.keySprint.setDown(false);
        client.options.keyJump.setDown(false);
        client.options.keyDown.setDown(false);

        if (ladoElegido > 0) {
            client.options.keyLeft.setDown(true);
            client.options.keyRight.setDown(false);
        } else {
            client.options.keyRight.setDown(true);
            client.options.keyLeft.setDown(false);
        }
    }

    private int elegirMejorLadoDesvio(Minecraft client, LocalPlayer player, double dx, double dz) {
        int ladoPrimero = ultimaDireccionDesvio;
        int ladoSegundo = -ultimaDireccionDesvio;

        if (ladoDesvioEsSeguro(client, player, ladoPrimero)) {
            return ladoPrimero;
        }
        if (ladoDesvioEsSeguro(client, player, ladoSegundo)) {
            return ladoSegundo;
        }
        return ladoPrimero;
    }

    private boolean ladoDesvioEsSeguro(Minecraft client, LocalPlayer player, int lado) {
        double yawRad = Math.toRadians(player.getYRot() + 90 * lado);
        double sideX = -Math.sin(yawRad);
        double sideZ = Math.cos(yawRad);

        double checkX = player.getX() + sideX * 1.0;
        double checkZ = player.getZ() + sideZ * 1.0;
        double checkY = player.getY();

        BlockPos pos = BlockPos.containing(checkX, checkY - 0.1, checkZ);
        BlockState estado = client.level.getBlockState(pos);

        return !estado.isAir();
    }

    private void procesarDesvio(Minecraft client, LocalPlayer player, double dx, double dz) {
        desvioTicks--;

        if (desvioTicks <= 0) {
            estadoDesvio = 0;
            esperaTrasDesvioTicks = ESPERA_TRAS_DESVIO_TICKS;
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
            return;
        }

        if (estadoDesvio > 0) {
            client.options.keyLeft.setDown(true);
            client.options.keyRight.setDown(false);
        } else {
            client.options.keyRight.setDown(true);
            client.options.keyLeft.setDown(false);
        }

        client.options.keyUp.setDown(true);
        client.options.keySprint.setDown(false);
    }

    private void detenerCaminata(Minecraft client) {
        caminando = false;
        saltoTicks = 0;
        ticksCaminando = 0;
        estadoDesvio = 0;
        desvioTicks = 0;
        esperaTrasDesvioTicks = 0;
        ticksSinProgreso = 0;
        ticksAlejandose = 0;

        if (client != null && client.options != null) {
            client.options.keyUp.setDown(false);
            client.options.keySprint.setDown(false);
            client.options.keyJump.setDown(false);
            client.options.keyDown.setDown(false);
            client.options.keyLeft.setDown(false);
            client.options.keyRight.setDown(false);
        }
    }

    private float normalizarAngulo(float angulo) {
        while (angulo > 180) angulo -= 360;
        while (angulo < -180) angulo += 360;
        return angulo;
    }

    private boolean esBloqueNoSolido(BlockState estado) {
        if (estado.getBlock() instanceof SignBlock) return true;
        if (estado.is(BlockTags.BANNERS)) return true;
        if (estado.is(BlockTags.ALL_SIGNS)) return true;
        if (estado.is(BlockTags.CANDLES)) return true;
        if (estado.is(BlockTags.CANDLE_CAKES)) return true;
        if (estado.is(BlockTags.FENCE_GATES)) return true;
        return false;
    }

    // =====================================================
    // CALLBACKS
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

        // Reactivar KillAura al llegar al cartel
        reactivarKillAura();
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
                    "[AutoWarp] Autofarm alcanzado. Navegación finalizada."));
        }

        // Reactivar KillAura al llegar
        reactivarKillAura();
    }

    /**
     * Reactiva KillAura si estaba activado antes de desactivarlo.
     */
    private void reactivarKillAura() {
        if (killauraEstabaActivo) {
            KillAuraCompat.setEnabled(true);
            killauraEstabaActivo = false;

            Minecraft client = Minecraft.getInstance();
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal(
                        "[AutoWarp] KillAura reactivado."));
            }
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

        cancelarBaritone();
        usandoBaritone = false;

        if (player != null) {
            intentarNavegacion(player);
        }
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
                        "[AutoWarp] Autofarm activado. Desactivando KillAura y esperando warp..."));

                // Desactivar KillAura durante la navegación, recordando su estado
                killauraEstabaActivo = KillAuraCompat.isEnabled();
                KillAuraCompat.setEnabled(false);

                this.esperandoWarpAutofarm = 0;
                this.destinoX = cfg.autofarmX;
                this.destinoY = cfg.autofarmY;
                this.destinoZ = cfg.autofarmZ;
            }
        }
    }

    private void intentarNavegacion(LocalPlayer player) {
        AutoWarpConfig cfg = AutoWarpConfig.get();

        if (irAAutofarmTrasEspera) {
            irAAutofarmTrasEspera = false;

            if (cfg != null && cfg.autofarmActivado && cfg.autofarmCapturado) {
                player.sendSystemMessage(Component.literal(
                        "[AutoWarp] Autofarm activado. Desactivando KillAura y esperando warp..."));

                // Desactivar KillAura durante la navegación
                killauraEstabaActivo = KillAuraCompat.isEnabled();
                KillAuraCompat.setEnabled(false);

                this.esperandoWarpAutofarm = 0;
                this.destinoX = cfg.autofarmX;
                this.destinoY = cfg.autofarmY;
                this.destinoZ = cfg.autofarmZ;
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
                "[AutoWarp] Iniciando navegación hacia el cartel " + elegida + " ("
                + (int) distancia + " bloques)."));

        iniciarNavegacion(elegida.x, elegida.y, elegida.z, false);
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