package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.client.screen.Ui;
import net.minecraft.client.gui.GuiGraphics;

/** Small drawables shared by the categories, in the mod's vanilla style. */
final class JeiDraw {
    static void arrow(GuiGraphics g, int x, int y) {
        Ui.arrow(g,x,y,Ui.palette().muted());
    }

    static void slot(GuiGraphics g, int x, int y) {
        Ui.slot(g, x - 1, y - 1);
    }

    private JeiDraw() {}
}
