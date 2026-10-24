package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.client.screen.Ui;
import net.minecraft.client.gui.GuiGraphics;

/** Small drawables shared by the categories, in the mod's vanilla style. */
final class JeiDraw {
    static void arrow(GuiGraphics g, int x, int y) {
        g.fill(x, y + 4, x + 14, y + 6, 0xFF8B8B8B);
        for (int i = 0; i < 5; i++) g.fill(x + 14 + i, y + i, x + 15 + i, y + 10 - i, 0xFF8B8B8B);
    }

    static void slot(GuiGraphics g, int x, int y) {
        Ui.slot(g, x - 1, y - 1);
    }

    private JeiDraw() {}
}
