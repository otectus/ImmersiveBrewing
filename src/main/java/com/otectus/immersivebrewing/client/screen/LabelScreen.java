package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.SetLabelC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;

/**
 * Labelling a brewed potion held in the other hand (spec 10): a literal label of at most 48 code points and a note of at
 * most 120. Changing or clearing them never changes the potion. The server validates again and rejects bad text.
 */
public class LabelScreen extends Screen {
    private final ItemStack potion;
    private final InteractionHand hand;
    private EditBox label;
    private EditBox note;
    private int px, py, pw, ph;

    public LabelScreen(ItemStack potion, InteractionHand hand) {
        super(Component.translatable("screen.immersive_brewing.label_title"));
        this.potion = potion;
        this.hand = hand;
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 300);
        ph = 154 + Ui.lines(limits(), pw - 16) * 10;
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        int w = pw - 16;
        int x = px + 8;
        int y = py + 58;
        BrewingMetadata meta = BrewingMetadata.read(potion).orElse(null);
        String oldLabel = label != null ? label.getValue() : meta != null && meta.label() != null ? meta.label() : "";
        String oldNote = note != null ? note.getValue() : meta != null && meta.note() != null ? meta.note() : "";
        label = new EditBox(font, x, y, w, 18, Component.translatable("screen.immersive_brewing.label"));
        label.setMaxLength(BrewingMetadata.MAX_LABEL);
        Ui.hint(label, Component.translatable("screen.immersive_brewing.label_hint"));
        label.setValue(oldLabel);
        addRenderableWidget(label);
        note = new EditBox(font, x, y + 36, w, 18, Component.translatable("screen.immersive_brewing.note"));
        note.setMaxLength(BrewingMetadata.MAX_NOTE);
        Ui.hint(note, Component.translatable("screen.immersive_brewing.note_hint"));
        note.setValue(oldNote);
        addRenderableWidget(note);
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.apply_label"), b -> {
            ModNetwork.sendToServer(new SetLabelC2S(false, BlockPos.ZERO, hand, label.getValue(), note.getValue()));
            onClose();
        }).bounds(x, y + 64, w / 3 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.clear_label"), b -> {
            ModNetwork.sendToServer(new SetLabelC2S(false, BlockPos.ZERO, hand, "", ""));
            onClose();
        }).bounds(x + w / 3 + 1, y + 64, w / 3 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose()).bounds(x + 2 * w / 3 + 2, y + 64, w / 3 - 2, 20).build());
        setInitialFocus(label);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Ui.panel(g, px, py, pw, ph);
        Ui.Palette p = Ui.palette();
        Ui.fit(g, title, px + 8, py + 8, pw - 16, p.text());
        g.renderItem(potion, px + 8, py + 24);
        Ui.fit(g, potion.getHoverName(), px + 28, py + 28, pw - 36, p.text());
        g.drawString(font, Component.translatable("screen.immersive_brewing.label"), px + 8, py + 46, p.text(), false);
        g.drawString(font, Component.translatable("screen.immersive_brewing.note"), px + 8, py + 82, p.text(), false);
        Ui.wrapped(g, limits(), px + 8, py + 150, pw - 16, p.muted());
        super.render(g, mouseX, mouseY, partialTick);
        if (Ui.inside(mouseX, mouseY, px + 8, py + 24, pw - 16, 16)) g.renderTooltip(font, potion, mouseX, mouseY);
    }

    private Component limits() {
        return Component.translatable("screen.immersive_brewing.label_limits", BrewingMetadata.MAX_LABEL, BrewingMetadata.MAX_NOTE);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
