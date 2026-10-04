package com.ejemplo.autowarp.screen;

import com.ejemplo.autowarp.config.CoordStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pantalla de búsqueda de ítems para asignar a un cartel.
 * Reutiliza el patrón de initInterfaz (KillAura).
 */
public class ItemSearchScreen extends Screen {

    private static final int ROWS = 10;
    private static final int ROW_HEIGHT = 22;

    private final List<Item> filteredItems = new ArrayList<>();
    private int scrollOffset = 0;
    private EditBox searchBox;

    public ItemSearchScreen() {
        super(Component.literal("Seleccionar ítem para el cartel"));
    }

    @Override
    protected void init() {
        super.init();

        this.searchBox = new EditBox(
                this.font,
                this.width / 2 - 100, 20,
                200, 20,
                Component.literal("Buscar ítem...")
        );
        this.searchBox.setResponder(this::refreshFilter);
        this.addRenderableWidget(this.searchBox);

        // Botón: confirmar sin ítem
        this.addRenderableWidget(Button.builder(
                Component.literal("Guardar sin ítem"),
                btn -> {
                    CoordStorage.confirmarCapturaConItem(null);
                    Minecraft.getInstance().setScreen(new AutoWarpScreen());
                }
        ).bounds(this.width / 2 - 155, this.height - 30, 150, 20).build());

        // Botón: cancelar
        this.addRenderableWidget(Button.builder(
                Component.literal("Cancelar"),
                btn -> {
                    CoordStorage.cancelarCaptura();
                    Minecraft.getInstance().setScreen(new AutoWarpScreen());
                }
        ).bounds(this.width / 2 + 5, this.height - 30, 150, 20).build());

        refreshFilter("");
    }

    private void refreshFilter(String query) {
        filteredItems.clear();
        String q = query.toLowerCase(Locale.ROOT);

        for (Item item : BuiltInRegistries.ITEM) {
            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) continue;
            if (q.isEmpty() || id.toString().toLowerCase(Locale.ROOT).contains(q)) {
                filteredItems.add(item);
            }
        }
        scrollOffset = 0;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        super.extractRenderState(graphics, mouseX, mouseY, delta);

        graphics.centeredText(this.font, this.title, this.width / 2, 6, 0xFFFFFFFF);

        int startX = this.width / 2 - 100;
        int startY = 50;

        int end = Math.min(scrollOffset + ROWS, filteredItems.size());
        for (int i = scrollOffset; i < end; i++) {
            Item item = filteredItems.get(i);
            int rowY = startY + (i - scrollOffset) * ROW_HEIGHT;

            graphics.fill(startX - 2, rowY - 2, startX + 202, rowY + 18, 0x40000000);

            Identifier id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                graphics.blit(
                        RenderPipelines.GUI_TEXTURED,
                        id.withPrefix("textures/item/").withSuffix(".png"),
                        startX, rowY,
                        0, 0,
                        16, 16,
                        16, 16
                );
            }

            graphics.text(this.font,
                    id != null ? id.toString() : "?",
                    startX + 20, rowY + 5,
                    0xFFFFFFFF, true);

            graphics.text(this.font, "[Elegir]", startX + 155, rowY + 5, 0xFF55FF55, true);
        }

        if (filteredItems.size() > ROWS) {
            int barHeight = Math.max(10, (ROWS * 100) / filteredItems.size());
            int barY = startY + (scrollOffset * 100) / filteredItems.size();
            graphics.fill(this.width / 2 + 110, startY, this.width / 2 + 114, startY + 200, 0x40FFFFFF);
            graphics.fill(this.width / 2 + 110, barY, this.width / 2 + 114, barY + barHeight, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();

        int startX = this.width / 2 - 100;
        int startY = 50;

        if (mouseX >= startX - 2 && mouseX <= startX + 202) {
            for (int i = scrollOffset; i < Math.min(scrollOffset + ROWS, filteredItems.size()); i++) {
                int rowY = startY + (i - scrollOffset) * ROW_HEIGHT;
                if (mouseY >= rowY - 2 && mouseY <= rowY + 18) {
                    CoordStorage.confirmarCapturaConItem(filteredItems.get(i));
                    Minecraft.getInstance().setScreen(new AutoWarpScreen());
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
            int max = Math.max(0, filteredItems.size() - ROWS);
            scrollOffset = Math.max(0, Math.min(scrollOffset, max));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}