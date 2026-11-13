package com.otectus.immersivebrewing.client.journal;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.ClientGuide;
import com.otectus.immersivebrewing.client.ServerCatalog;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Minimal outside-the-Notebook reminder from the server-resolved, generation-bound cost snapshot. */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
public final class PinnedChecklistHud {
    private static final int MAX_LINES = 5;
    private static final int ROW_HEIGHT = 18;

    @SubscribeEvent
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "pinned_checklist", PinnedChecklistHud::render);
    }

    private static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        Minecraft mc = Minecraft.getInstance();
        ClientGuide.Pinned pin = ClientGuide.pinned();
        if (pin == null || mc.level == null || mc.options.hideGui
                || (ClientConfig.get(ClientConfig.PINNED_HUD_HIDE_SCREEN) && mc.screen != null)) return;
        boolean available = ServerCatalog.ready() && pin.generation() == ServerCatalog.generation() && !pin.costs().isEmpty();
        boolean compact = ClientConfig.get(ClientConfig.PINNED_HUD_COMPACT);
        int boxW = compact ? 78 : Math.min(150, Math.max(96, width / 4));
        int lines = available ? Math.min(MAX_LINES, pin.costs().size()) : 0;
        boolean overflow = available && pin.costs().size() > lines;
        int boxH = 20 + lines * ROW_HEIGHT + (overflow || !available ? 11 : 0);
        int x = ClientConfig.get(ClientConfig.PINNED_HUD_ANCHOR) == ClientConfig.HudAnchor.LEFT ? 6 : width - boxW - 6;
        int y = Math.max(6, Math.min(height - boxH - 42,
                height / 2 - boxH / 2 + ClientConfig.get(ClientConfig.PINNED_HUD_Y_OFFSET)));
        g.fill(x, y, x + boxW, y + boxH, 0xB0101010);
        Ui.fit(g, Component.translatable("hud.immersive_brewing.pinned", pin.servings(), pin.output().getHoverName()),
                x + 5, y + 5, boxW - 10, 0xFFFFFFFF);
        int row = y + 17;
        if (!available) {
            Ui.fit(g, Component.translatable("hud.immersive_brewing.pinned_unavailable"), x + 5, row, boxW - 10, 0xFFFFAA66);
        } else {
            for (int i = 0; i < lines; i++) {
                ClientGuide.PinCost cost = pin.costs().get(i);
                ItemStack stack = cost.item();
                g.renderItem(stack, x + 4, row);
                Component text = compact ? Component.literal(cost.count() + "×")
                        : Component.literal(cost.count() + "× ").append(stack.getHoverName());
                Ui.fit(g, text, x + 22, row + 4, boxW - 27, 0xFFE0E0E0);
                row += ROW_HEIGHT;
            }
            if (overflow) Ui.fit(g, Component.translatable("hud.immersive_brewing.pinned_more", pin.costs().size() - lines),
                    x + 5, row, boxW - 10, 0xFFFFD27F);
        }
    }

    private PinnedChecklistHud() {}
}
