package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.block.ApothecaryRackBlock;
import com.otectus.immersivebrewing.blockentity.*;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.fml.ModList;
import java.util.List;

/** Uses the engine's occluded reach-limited target; never performs a longer through-wall search. */
public final class StationInspection {
    public static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "station_inspection", (gui, graphics, partial, width, height) -> {
            var mc = Minecraft.getInstance();
            if (!ClientConfig.get(ClientConfig.INSPECTION_HINTS) || ModList.get().isLoaded("jade") || mc.screen != null
                    || mc.level == null || mc.player == null || mc.options.hideGui || !(mc.hitResult instanceof BlockHitResult hit)) return;
            var entity = mc.level.getBlockEntity(hit.getBlockPos());
            List<Component> lines;
            if (entity instanceof StationBlockEntity station) lines = StationSummary.lines(station.view());
            else if (entity instanceof ApothecaryRackBlockEntity rack) {
                int slot = ApothecaryRackBlock.targetSlot(rack.getBlockState(), hit.getBlockPos(), hit);
                var stack = rack.inventory().getStackInSlot(slot);
                lines = List.of(Component.translatable("screen.immersive_brewing.carrier.slot", slot + 1, rack.label(slot)),
                        stack.isEmpty() ? Component.translatable(rack.filter(slot).isEmpty() ? "screen.immersive_brewing.carrier.empty" : "state.immersive_brewing.reserved")
                                : Component.translatable("screen.immersive_brewing.result.made", stack.getCount(), stack.getHoverName()));
            } else return;
            int panelWidth = Math.min(256, width - 32), y = 38;
            graphics.fill((width - panelWidth) / 2 - 4, y - 4, (width + panelWidth) / 2 + 4, y + lines.size() * 12, 0xC0101010);
            for (Component line : lines) {
                Ui.fit(graphics, line, (width - panelWidth) / 2, y, panelWidth, 0xFFFFFFFF); y += 12;
            }
        });
    }
    private StationInspection() {}
}
