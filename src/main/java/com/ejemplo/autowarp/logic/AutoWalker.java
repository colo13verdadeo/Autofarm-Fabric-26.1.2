package com.ejemplo.autowarp.logic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Navegación simple: camina en línea recta hacia las coordenadas objetivo,
 * ajustando la rotación de la cámara. Sin pathfinding real (no evita obstáculos).
 */
public class AutoWalker {

    private static final double DISTANCIA_LLEGADA = 2.0; // bloques
    private static final double VELOCIDAD_ROTACION = 15.0; // grados por tick

    private boolean activo = false;
    private int targetX, targetY, targetZ;
    private int timeoutTicks = 0;
    private static final int TIMEOUT_MAX = 20 * 120; // 2 minutos máximo caminando

    public void iniciar(int x, int y, int z) {
        this.activo = true;
        this.targetX = x;
        this.targetY = y;
        this.targetZ = z;
        this.timeoutTicks = 0;
    }

    public void detener() {
        this.activo = false;
    }

    public boolean estaActivo() {
        return activo;
    }

    public void tick(Minecraft client) {
        if (!activo) return;

        LocalPlayer player = client.player;
        if (player == null || client.level == null) {
            detener();
            return;
        }

        // Si el jugador pulsa una tecla de movimiento manualmente, cancelar
        if (client.options.keyUp.isDown()
                || client.options.keyDown.isDown()
                || client.options.keyLeft.isDown()
                || client.options.keyRight.isDown()) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Movimiento manual detectado. Cancelando navegación."));
            detener();
            return;
        }

        // Timeout de seguridad
        timeoutTicks++;
        if (timeoutTicks > TIMEOUT_MAX) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Tiempo de navegación agotado."));
            detener();
            return;
        }

        double dx = targetX + 0.5 - player.getX();
        double dz = targetZ + 0.5 - player.getZ();
        double distanciaHorizontal = Math.sqrt(dx * dx + dz * dz);

        // ¿Llegamos?
        if (distanciaHorizontal <= DISTANCIA_LLEGADA) {
            player.sendSystemMessage(Component.literal("[AutoWarp] Destino alcanzado."));
            detener();
            return;
        }

        // Calcular ángulo objetivo (yaw)
        float yawObjetivo = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float yawActual = player.getYRot();

        // Interpolar rotación suavemente
        float diferencia = normalizarAngulo(yawObjetivo - yawActual);
        float paso = (float) Math.max(-VELOCIDAD_ROTACION, Math.min(VELOCIDAD_ROTACION, diferencia));
        player.setYRot(yawActual + paso);

        // Avanzar: simular que el jugador mantiene la tecla W
        player.input.forwardImpulse = 1.0f;

        // Correr si el hambre lo permite
        player.setSprinting(true);
    }

    private float normalizarAngulo(float angulo) {
        while (angulo > 180) angulo -= 360;
        while (angulo < -180) angulo += 360;
        return angulo;
    }
}