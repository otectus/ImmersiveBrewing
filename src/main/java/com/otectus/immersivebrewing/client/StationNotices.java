package com.otectus.immersivebrewing.client;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.config.ClientConfig;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Station messages that stay readable (family audit: never action-bar only). Up to three recent notices sit above the
 * hotbar, newest lowest, each for the configured time; a repeated message refreshes instead of stacking, and failures
 * are marked. Every notice is also narrated. With {@code stationNotices} off, messages go to the action bar.
 */
@Mod.EventBusSubscriber(modid = ImmersiveBrewing.MOD_ID, value = Dist.CLIENT)
public final class StationNotices {
    private static final int MAX_NOTICES = 3;
    private static final int MAX_LINES = 2;
    private static final int FADE_MS = 600;

    private record Notice(Component text, boolean failure, long shownMs) {}

    private static final Deque<Notice> NOTICES = new ArrayDeque<>();
    /** Bounded accessible history survives visual expiry for the current connection. */
    private static final Deque<Component> HISTORY = new ArrayDeque<>();

    public static void show(Component text, boolean failure) {
        Minecraft mc = Minecraft.getInstance();
        HISTORY.addLast(text);
        while (HISTORY.size() > 20) HISTORY.removeFirst();
        if (!ClientConfig.get(ClientConfig.STATION_NOTICES)) {
            mc.gui.setOverlayMessage(text, false);
        } else {
            NOTICES.removeIf(n -> n.text().getString().equals(text.getString()));
            NOTICES.addLast(new Notice(text, failure, Util.getMillis()));
            while (NOTICES.size() > MAX_NOTICES) NOTICES.removeFirst();
        }
        mc.getNarrator().sayNow(text);
    }

    public static void clear() {
        NOTICES.clear();
        HISTORY.clear();
    }

    public static int count() {
        return NOTICES.size();
    }

    /** Full untruncated messages for related screens/narration; newest last. */
    public static List<Component> history() {
        List<Component> out = new ArrayList<>();
        for (Component notice : HISTORY) out.add(notice);
        return List.copyOf(out);
    }

    /** Keyboard path used by station screens: the narrator receives full text, never the two-line overlay truncation. */
    public static void narrateHistory() {
        Minecraft mc = Minecraft.getInstance();
        List<Component> all = history();
        if (all.isEmpty()) {
            mc.getNarrator().sayNow(Component.translatable("notice.immersive_brewing.history_empty"));
            return;
        }
        Component line = Component.empty();
        for (Component message : all) line = line.getString().isEmpty() ? message.copy() : line.copy().append(" ").append(message);
        mc.getNarrator().sayNow(line);
    }

    static void register(RegisterGuiOverlaysEvent event) {
        event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "station_notices", StationNotices::render);
    }

    public static boolean visible() {
        long now=Util.getMillis(),life=ClientConfig.get(ClientConfig.NOTICE_SECONDS)*1000L;
        return NOTICES.stream().anyMatch(notice->now-notice.shownMs()<life);
    }

    private static void render(ForgeGui gui, GuiGraphics g, float partialTick, int width, int height) {
        if (NOTICES.isEmpty()) return;
        long now = Util.getMillis();
        long life = ClientConfig.get(ClientConfig.NOTICE_SECONDS) * 1000L;
        NOTICES.removeIf(n -> now - n.shownMs() > life);
        Font font = Minecraft.getInstance().font;
        boolean working=Minecraft.getInstance().screen instanceof com.otectus.immersivebrewing.client.workshop.WorkshopScreen;
        int y=height-(working?62:72);
        List<Notice> list = new ArrayList<>(NOTICES);
        for (int i = list.size() - 1; i >= 0; i--) {
            Notice n = list.get(i);
            long age = now - n.shownMs();
            float alpha = Mth.clamp((life - age) / (float) FADE_MS, 0, 1);
            if (alpha <= 0.05F) continue;
            List<FormattedCharSequence> lines = font.split(n.failure() ? Component.literal("⚠ ").append(n.text()) : n.text(), Math.min(320, width - 40));
            int shown = Math.min(MAX_LINES, lines.size());
            for (int l = shown - 1; l >= 0; l--) {
                int w = font.width(lines.get(l));
                int x = (width - w) / 2;
                int a = (int) (alpha * 255) << 24;
                g.fill(x - 3, y - 2, x + w + 3, y + 9, (int) (alpha * 160) << 24);
                g.drawString(font, lines.get(l), x, y, a | (n.failure() ? 0xFF8080 : 0xFFFFFF), false);
                y -= 11;
            }
            y -= 3;
            if(working) break;
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
    }

    private StationNotices() {}
}
