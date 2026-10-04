package com.ejemplo.autowarp.screen;

import com.ejemplo.autowarp.config.AutoWarpConfig;
import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

public class AutoWarpScreen extends Screen {

    private static final int ROWS = 5;
    private static final int ROW_HEIGHT = 22;

    private final List<CoordStorage.Coordenada> coordsMostradas = new ArrayList<>();
    private int scrollOffset = 0;

    private EditBox comandoBox;
    private EditBox comandoPostBox;

    private int labelComandoY = 0;
    private int labelComandoPostY = 0;

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

        int leftX = centerX - buttonWidth - 10;
        int y = 40;

        // Mod on/off
        this.addRenderableWidget(Button.builder(
                Component.literal("Mod: " + (cfg.modActivado ? "ACTIVADO" : "DESACTIVADO")),
                btn -> {
                    cfg.modActivado = !cfg.modActivado;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Mod: " + (cfg.modActivado ? "ACTIVADO" : "DESACTIVADO")));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Chequeo lleno: " + (cfg.checkeoActivo ? "ACTIVADO" : "DESACTIVADO")),
                btn -> {
                    cfg.checkeoActivo = !cfg.checkeoActivo;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Chequeo lleno: " + (cfg.checkeoActivo ? "ACTIVADO" : "DESACTIVADO")));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Segundos lleno: " + cfg.segundosInventarioLleno + "s"),
                btn -> {
                    cfg.segundosInventarioLleno = (cfg.segundosInventarioLleno % 10) + 1;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Segundos lleno: " + cfg.segundosInventarioLleno + "s"));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Cooldown: " + cfg.segundosCooldown + "s"),
                btn -> {
                    int[] valores = {30, 60, 90, 120, 180, 300, 600};
                    int siguiente = valores[0];
                    for (int i = 0; i < valores.length; i++) {
                        if (cfg.segundosCooldown == valores[i]) {
                            siguiente = valores[(i + 1) % valores.length];
                            break;
                        }
                    }
                    cfg.segundosCooldown = siguiente;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Cooldown: " + cfg.segundosCooldown + "s"));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Mensajes T1/T2/T3: " + (cfg.mostrarMensajesOverlay ? "SÍ" : "NO")),
                btn -> {
                    cfg.mostrarMensajesOverlay = !cfg.mostrarMensajesOverlay;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Mensajes T1/T2/T3: " + (cfg.mostrarMensajesOverlay ? "SÍ" : "NO")));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        // === NUEVO: Zona segura ===
        y += 25;
        this.addRenderableWidget(Button.builder(
                Component.literal("Zona segura: " + (cfg.zonaSegura ? "SÍ" : "NO")),
                btn -> {
                    cfg.zonaSegura = !cfg.zonaSegura;
                    AutoWarpConfig.save();
                    btn.setMessage(Component.literal("Zona segura: " + (cfg.zonaSegura ? "SÍ" : "NO")));
                }
        ).bounds(leftX, y, buttonWidth, buttonHeight).build());

        // Comando principal
        y += 30;
        labelComandoY = y;

        this.comandoBox = new EditBox(
                this.font,
                leftX, labelComandoY + 12,
                buttonWidth, buttonHeight,
                Component.literal("Comando")
        );
        this.comandoBox.setValue(cfg.comandoWarp);
        this.comandoBox.setMaxLength(128);
        this.comandoBox.setResponder(val -> {
            cfg.comandoWarp = val;
            AutoWarpConfig.save();
        });
        this.addRenderableWidget(this.comandoBox);

        // Comando post-carteles
        y += 40;
        labelComandoPostY = y;

        this.comandoPostBox = new EditBox(
                this.font,
                leftX, labelComandoPostY + 12,
                buttonWidth, buttonHeight,
                Component.literal("Comando post-carteles")
        );
        this.comandoPostBox.setValue(cfg.comandoPostCarteles);
        this.comandoPostBox.setMaxLength(128);
        this.comandoPostBox.setResponder(val -> {
            cfg.comandoPostCarteles = val;
            AutoWarpConfig.save();
        });
        this.addRenderableWidget(this.comandoPostBox);

        // Columna derecha
        int rightX = centerX + 10;

        this.addRenderableWidget(Button.builder(
                Component.literal("Añadir cartel que estoy mirando"),
                btn -> {
                    if (CoordStorage.prepararCapturaCartel()) {
                        Minecraft.getInstance().setScreen(new ItemSearchScreen());
                    }
                }
        ).bounds(rightX, 40, buttonWidth, buttonHeight).build());

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

        int leftX = this.width / 2 - 240 - 10;

        graphics.text(this.font,
                "Comando principal:",
                leftX, labelComandoY,
                0xFFAAAAAA, true);

        graphics.text(this.font,
                "Comando post-carteles:",
                leftX, labelComandoPostY,
                0xFFAAAAAA, true);

        String contexto = CoordStorage.getNombreContextoActual();
        graphics.text(this.font,
                "Carteles en: " + contexto,
                this.width / 2 + 10, 70,
                0xFFAAAAAA, true);

        int startX = this.width / 2 + 10;
        int startY = 85;
        int end = Math.min(scrollOffset + ROWS, coordsMostradas.size());

        for (int i = scrollOffset; i < end; i++) {
            CoordStorage.Coordenada c = coordsMostradas.get(i);
            int rowY = startY + (i - scrollOffset) * ROW_HEIGHT;

            graphics.fill(startX - 2, rowY - 2, startX + 242, rowY + 18, 0x40000000);
            graphics.text(this.font, c.toString(), startX + 5, rowY + 5, 0xFFFFFFFF, true);
            graphics.text(this.font, "[X]", startX + 220, rowY + 5, 0xFFFF5555, true);
        }

        if (coordsMostradas.isEmpty()) {
            graphics.text(this.font, "(ninguno)", startX + 5, startY + 5, 0xFF888888, true);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();

        int startX = this.width / 2 + 10;
        int startY = 85;

        if (mouseX >= startX + 215 && mouseX <= startX + 245) {
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