package com.ejemplo.autowarp.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
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

    private static Map<String, List<Coordenada>> datos = new HashMap<>();
    private static Coordenada capturaPendiente = null;

    public static class Coordenada {
        public int x, y, z;
        public String itemId;

        public Coordenada() {}

        public Coordenada(int x, int y, int z) {
            this.x = x; this.y = y; this.z = z;
        }

        @Override
        public String toString() {
            String base = "(" + x + ", " + y + ", " + z + ")";
            if (itemId != null && !itemId.isEmpty()) {
                base += " → " + itemId;
            }
            return base;
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
            return "world:" + client.level.dimension().identifier();
        }

        return "desconocido";
    }

    // =====================================================
    // CAPTURA DE CARTEL
    // =====================================================

    public static boolean prepararCapturaCartel() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.level == null) return false;

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

        capturaPendiente = new Coordenada(pos.getX(), pos.getY(), pos.getZ());
        return true;
    }

    public static void confirmarCapturaConItem(Item item) {
        if (capturaPendiente == null) return;

        if (item != null) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                capturaPendiente.itemId = id.toString();
            }
        }

        String clave = getClaveContextoActual();
        datos.computeIfAbsent(clave, k -> new ArrayList<>());

        boolean existe = datos.get(clave).stream()
                .anyMatch(c -> c.x == capturaPendiente.x
                        && c.y == capturaPendiente.y
                        && c.z == capturaPendiente.z);

        Minecraft client = Minecraft.getInstance();
        if (existe) {
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[AutoWarp] Ese cartel ya está registrado."));
            }
        } else {
            datos.get(clave).add(capturaPendiente);
            guardar();
            if (client.player != null) {
                client.player.sendSystemMessage(
                        Component.literal("[AutoWarp] Cartel añadido: " + capturaPendiente));
            }
        }

        capturaPendiente = null;
    }

    public static void cancelarCaptura() {
        capturaPendiente = null;
    }

    public static boolean hayCapturaPendiente() {
        return capturaPendiente != null;
    }

    // =====================================================
    // ELIMINAR / CONSULTAR
    // =====================================================

    public static void eliminarCoordenada(Coordenada coord) {
        String clave = getClaveContextoActual();
        List<Coordenada> lista = datos.get(clave);
        if (lista != null) {
            lista.remove(coord);
            guardar();
        }
    }

    public static List<Coordenada> getCoordenadasActuales() {
        return datos.getOrDefault(getClaveContextoActual(), Collections.emptyList());
    }

    public static String getNombreContextoActual() {
        return getClaveContextoActual();
    }

    /**
     * Busca la coordenada registrada más cercana al jugador actual.
     */
    public static Coordenada getCartelMasCercano() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return null;

        List<Coordenada> lista = getCoordenadasActuales();
        if (lista.isEmpty()) return null;

        double px = client.player.getX();
        double pz = client.player.getZ();

        Coordenada mejor = null;
        double mejorDist = Double.MAX_VALUE;

        for (Coordenada c : lista) {
            double dx = c.x - px;
            double dz = c.z - pz;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < mejorDist) {
                mejorDist = dist;
                mejor = c;
            }
        }
        return mejor;
    }
}