package com.ejemplo.autowarp.screen;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class AutoWarpScreen extends Screen {

    private static final int ROWS = 6;
    private static final int ROW_HEIGHT = 22;

    private final List<CoordStorage.Coordenada> coordsMostradas = new ArrayList<>();
    private int scrollOffset = 0;

    public AutoWarpScreen() {
        super(Component.literal("Auto-Warp - Configuración"));
    }

    @Override
    protected void init() {
        super.init();

        AutoWarpConfig cfg = AutoWarpConfig.get();
        int centerX = this.width / 2;
        int buttonWidth = 240;
        int buttonHeight = 20;
        int y = 40;

        // Mod on/off
        this.addRenderableWidget(Button.builder(
                Component.literal("Mod: " + (cfg.modActivado ? "ACTIVADO" : "DESACTIVADO")),
                btn -> {
                    cfg.modActivado = !cfg.modActivado;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Mod: " + (cfg.modActivado ? "ACTIVADO" : "DESACTIVADO")));
                }
        ).bounds(centerX - buttonWidth / 2, y, buttonWidth, buttonHeight).build());

        // Segundos inventario lleno
        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Segundos lleno: " + cfg.segundosInventarioLleno + "s"),
                btn -> {
                    cfg.segundosInventarioLleno = (cfg.segundosInventarioLleno % 10) + 1;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Segundos lleno: " + cfg.segundosInventarioLleno + "s"));
                }
        ).bounds(centerX - buttonWidth / 2, y, buttonWidth, buttonHeight).build());

        // Minutos de delay
        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Delay tras comando: " + cfg.minutosDelayTrasComando + " min"),
                btn -> {
                    cfg.minutosDelayTrasComando = (cfg.minutosDelayTrasComando % 30) + 1;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Delay tras comando: " + cfg.minutosDelayTrasComando + " min"));
                }
        ).bounds(centerX - buttonWidth / 2, y, buttonWidth, buttonHeight).build());

        // Mensajes overlay
        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Mensajes T1/T2/T3: " + (cfg.mostrarMensajesOverlay ? "SÍ" : "NO")),
                btn -> {
                    cfg.mostrarMensajesOverlay = !cfg.mostrarMensajesOverlay;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Mensajes T1/T2/T3: " + (cfg.mostrarMensajesOverlay ? "SÍ" : "NO")));
                }
        ).bounds(centerX - buttonWidth / 2, y, buttonWidth, buttonHeight).build());

        // Añadir cartel
        y += 35;
        this.addRenderableWidget(Button.builder(
                Component.literal("Añadir cartel que estoy mirando"),
                btn -> {
                    CoordStorage.intentarCapturarCartel();
                    refrescarLista();
                }
        ).bounds(centerX - buttonWidth / 2, y, buttonWidth, buttonHeight).build());

        // Volver
        this.addRenderableWidget(Button.builder(
                Component.literal("Volver"),
                btn -> Minecraft.getInstance().setScreen(null)
        ).bounds(centerX - 100, this.height - 30, 200, 20).build());

        refrescarLista();
    }

    private void refrescarLista() {
        coordsMostradas.clear();
        coordsMostradas.addAll(CoordStorage.getCoordenadasActuales());
        scrollOffset = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, this.title, this.width / 2, 15, 0xFFFFFFFF);

        String contexto = CoordStorage.getNombreContextoActual();
        graphics.text(this.font,
                "Carteles en: " + contexto,
                this.width / 2 - 100, this.height - 140,
                0xFFAAAAAA, true);

        int startX = this.width / 2 - 100;
        int startY = this.height - 120;
        int end = Math.min(scrollOffset + ROWS, coordsMostradas.size());

        for (int i = scrollOffset; i < end; i++) {
            CoordStorage.Coordenada c = coordsMostradas.get(i);
            int rowY = startY + (i - scrollOffset) * ROW_HEIGHT;

            graphics.fill(startX - 2, rowY - 2, startX + 202, rowY + 18, 0x40000000);
            graphics.text(this.font, c.toString(), startX + 5, rowY + 5, 0xFFFFFFFF, true);
            graphics.text(this.font, "[X]", startX + 175, rowY + 5, 0xFFFF5555, true);
        }

        if (coordsMostradas.isEmpty()) {
            graphics.text(this.font, "(ninguno)", startX + 5, startY + 5, 0xFF888888, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();

        int startX = this.width / 2 - 100;
        int startY = this.height - 120;

        if (mouseX >= startX + 170 && mouseX <= startX + 205) {
            for (int i = scrollOffset; i < Math.min(scrollOffset + ROWS, coordsMostradas.size()); i++) {
                int rowY = startY + (i - scrollOffset) * ROW_HEIGHT;
                if (mouseY >= rowY - 2 && mouseY <= rowY + 18) {
                    CoordStorage.eliminarCoordenada(coordsMostradas.get(i));
                    refrescarLista();
                    return true;
                }
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (vertical != 0) {
            scrollOffset -= (int) vertical;
            int max = Math.max(0, coordsMostradas.size() - ROWS);
            scrollOffset = Math.max(0, Math.min(scrollOffset, max));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new AutoWarpScreen());
    }
}