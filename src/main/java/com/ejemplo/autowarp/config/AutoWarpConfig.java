package com.ejemplo.autowarp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class AutoWarpConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CARPETA =
            FabricLoader.getInstance().getConfigDir().resolve("autowarp");
    private static final Path CONFIG_PATH = CARPETA.resolve("autowarp.json");

    public boolean modActivado = true;
    public boolean checkeoActivo = true;
    public String comandoWarp = "warp shop";
    public int segundosInventarioLleno = 3;
    public int segundosCooldown = 120;
    public boolean mostrarMensajesOverlay = true;

    public String comandoPostCarteles = "visit kfcblock";

    /**
     * Si está activado, el AutoWalker aplica protección contra precipicios
     * y obstáculos no escalables. Si está desactivado, navega directo
     * rodeando obstáculos pero puede caer por precipicios.
     */
    public boolean zonaSegura = true;

    private static AutoWarpConfig INSTANCE = new AutoWarpConfig();

    private AutoWarpConfig() {}

    public static AutoWarpConfig get() {
        return INSTANCE;
    }

    public static void load() {
        if (!Files.exists(CONFIG_PATH)) {
            INSTANCE = new AutoWarpConfig();
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            AutoWarpConfig leido = GSON.fromJson(reader, AutoWarpConfig.class);
            INSTANCE = leido != null ? leido : new AutoWarpConfig();
        } catch (IOException e) {
            System.err.println("[AutoWarp] No se pudo cargar config: " + e.getMessage());
            INSTANCE = new AutoWarpConfig();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CARPETA);
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(INSTANCE, writer);
            }
        } catch (IOException e) {
            System.err.println("[AutoWarp] No se pudo guardar config: " + e.getMessage());
        }
    }
}