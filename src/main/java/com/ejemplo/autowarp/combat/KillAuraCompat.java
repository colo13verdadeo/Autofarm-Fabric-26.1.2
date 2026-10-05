package com.ejemplo.autowarp.compat;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Clase "puerta" para la integración con KillAura.
 * NO importa KillAuraAPI directamente, así que es segura de cargar
 * aunque KillAura no esté instalado.
 */
public final class KillAuraCompat {

    private KillAuraCompat() {}

    /** Comprueba si KillAura está cargado. */
    public static boolean isAvailable() {
        return FabricLoader.getInstance().isModLoaded("killaura");
    }

    /** Activa o desactiva KillAura. */
    public static void setEnabled(boolean enabled) {
        if (!isAvailable()) return;
        KillAuraCompatImpl.setEnabled(enabled);
    }

    /** Devuelve si KillAura está activado. */
    public static boolean isEnabled() {
        if (!isAvailable()) return false;
        return KillAuraCompatImpl.isEnabled();
    }

    /** Activa o desactiva la rotación de cámara al objetivo. */
    public static void setLookAtTarget(boolean lookAt) {
        if (!isAvailable()) return;
        KillAuraCompatImpl.setLookAtTarget(lookAt);
    }
}