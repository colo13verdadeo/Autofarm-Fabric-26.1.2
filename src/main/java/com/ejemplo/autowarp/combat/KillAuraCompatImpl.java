package com.ejemplo.autowarp.compat;

import com.ejemplo.killaura.api.KillAuraAPI;

/**
 * Implementación real de la integración con KillAura.
 * Solo se carga si KillAura está presente en el classpath.
 */
final class KillAuraCompatImpl {

    private KillAuraCompatImpl() {}

    static void setEnabled(boolean enabled) {
        KillAuraAPI.setEnabled(enabled);
    }

    static boolean isEnabled() {
        return KillAuraAPI.isEnabled();
    }

    static void setLookAtTarget(boolean lookAt) {
        KillAuraAPI.setLookAtTarget(lookAt);
    }
}