package com.ejemplo.autowarp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class CoordStorage {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CARPETA =
            FabricLoader.getInstance().getConfigDir().resolve("autowarp");
    private static final Path ARCHIVO = CARPETA.resolve("coordenadas.json");

    /** clave (mundo/servidor) -> lista de coordenadas */
    private static Map<String, List<Coordenada>> datos = new HashMap<>();

    public static class Coordenada {
        public int x, y, z;

        public Coordenada() {}

        public Coordenada(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override
        public String toString() {
            return "(" + x + ", " + y + ", " + z + ")";
        }
    }

    // =====================================================
    // CARGA / GUARDADO
    // =====================================================

    public static void cargar() {
        if (!Files.exists(ARCHIVO)) {
            datos = new HashMap<>();
            return;
        }
        try (Reader reader = Files.newBufferedReader(ARCHIVO)) {
            Type type = new TypeToken<Map<String, List<Coordenada>>>() {}.getType();
            Map<String, List<Coordenada>> leido = GSON.fromJson(reader, type);
            datos = leido != null ? leido : new HashMap<>();
        } catch (IOException e) {
            System.err.println("[AutoWarp] No se pudo cargar coords: " + e.getMessage());
            datos = new HashMap<>();
        }
    }

    public static void guardar() {
        try {
            Files.createDirectories(CARPETA);
            try (Writer writer = Files.newBufferedWriter(ARCHIVO)) {
                GSON.toJson(datos, writer);
            }
        } catch (IOException e) {
            System.err.println("[AutoWarp] No se pudo guardar coords: " + e.getMessage());
        }
    }

    // =====================================================
    // CLAVE DE CONTEXTO
    // =====================================================

    public static String getClaveContextoActual() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return "desconocido";

        ServerData server = client.getCurrentServer();
        if (server != null) {
            return "multiplayer:" + server.ip;
        }

        if (client.getSingleplayerServer() != null) {
            String nombre = client.getSingleplayerServer().getWorldData().getLevelName();
            return "singleplayer:" + nombre;
        }

        if (client.level != null) {
            // ✅ CORREGIDO: identifier() en lugar de location() en 26.1
            return "world:" + client.level.dimension().identifier();
        }

        return "desconocido";
    }

    // =====================================================
    // CAPTURA DE CARTEL
    // =====================================================

    public static boolean intentarCapturarCartel() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) {
            return false;
        }

        HitResult hit = client.hitResult;
        if (hit == null || hit.getType() != HitResult.Type.BLOCK) {
            client.player.sendSystemMessage(
                    Component.literal("[AutoWarp] No estás mirando ningún bloque."));
            return false;
        }

        BlockHitResult blockHit = (BlockHitResult) hit;
        BlockPos pos = blockHit.getBlockPos();
        BlockState estado = client.level.getBlockState(pos);

        if (!(estado.getBlock() instanceof SignBlock)) {
            client.player.sendSystemMessage(
                    Component.literal("[AutoWarp] El bloque que miras no es un cartel."));
            return false;
        }

        String clave = getClaveContextoActual();
        Coordenada coord = new Coordenada(pos.getX(), pos.getY(), pos.getZ());

        datos.computeIfAbsent(clave, k -> new ArrayList<>());

        boolean existe = datos.get(clave).stream()
                .anyMatch(c -> c.x == coord.x && c.y == coord.y && c.z == coord.z);

        if (existe) {
            client.player.sendSystemMessage(
                    Component.literal("[AutoWarp] Ese cartel ya está registrado."));
            return false;
        }

        datos.get(clave).add(coord);
        guardar();
        client.player.sendSystemMessage(
                Component.literal("[AutoWarp] Cartel añadido: " + coord));
        return true;
    }

    // =====================================================
    // ELIMINAR COORDENADA
    // =====================================================

    public static void eliminarCoordenada(Coordenada coord) {
        String clave = getClaveContextoActual();
        List<Coordenada> lista = datos.get(clave);
        if (lista != null) {
            lista.remove(coord);
            guardar();
        }
    }

    // =====================================================
    // CONSULTAS
    // =====================================================

    public static List<Coordenada> getCoordenadasActuales() {
        return datos.getOrDefault(getClaveContextoActual(), Collections.emptyList());
    }

    public static String getNombreContextoActual() {
        return getClaveContextoActual();
    }
}