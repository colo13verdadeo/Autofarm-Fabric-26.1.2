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

    // ==== Variables configurables ====
    public boolean modActivado = true;
    public int segundosInventarioLleno = 3;
    public int minutosDelayTrasComando = 2;
    public boolean mostrarMensajesOverlay = true;

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