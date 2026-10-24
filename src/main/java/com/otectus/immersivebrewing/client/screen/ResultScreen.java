package com.otectus.immersivebrewing.client.screen;

import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.client.ClientSession;
import com.otectus.immersivebrewing.client.sound.BrewingSounds;
import com.otectus.immersivebrewing.network.ModNetwork;
import com.otectus.immersivebrewing.network.packet.SetLabelC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.Grade;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

/**
 * The result explanation (spec 10, 11.4): the actual score contributors and one concrete improvement tip, never "bad
 * luck". A finished kettle batch can be labelled here. Sessions that ended without a result explain why and whether the
 * batch is sealed and resumable.
 */
public class ResultScreen extends Screen {
    private final ClientSession session;
    @Nullable
    private final CompoundTag result;
    private EditBox label;
    private EditBox note;
    private int px, py, pw, ph;
    private final Ui.ScrollArea content = new Ui.ScrollArea();

    public ResultScreen(ClientSession session) {
        super(Component.translatable("screen.immersive_brewing.result"));
        this.session = session;
        this.result = session.result();
        if (result != null && !session.practice) BrewingSounds.complete();
        BrewingSounds.stop();
    }

    @Override
    protected void init() {
        pw = Math.min(width - 16, 320);
        ph = Math.min(height - 16, 228);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        boolean labelable = result != null && !session.practice && "brewing".equals(result.getString("kind"));
        content.bounds(px + 8, py + 8, pw - 16, ph - (labelable ? 70 : 48));
        if (labelable) {
            String oldLabel = label == null ? "" : label.getValue();
            String oldNote = note == null ? "" : note.getValue();
            label = new EditBox(font, px + 8, py + ph - 52, pw / 2 - 12, 16, Component.translatable("screen.immersive_brewing.label"));
            label.setMaxLength(BrewingMetadata.MAX_LABEL);
            label.setValue(oldLabel);
            Ui.hint(label, Component.translatable("screen.immersive_brewing.label_hint"));
            addRenderableWidget(label);
            note = new EditBox(font, px + pw / 2, py + ph - 52, pw / 2 - 8, 16, Component.translatable("screen.immersive_brewing.note"));
            note.setMaxLength(BrewingMetadata.MAX_NOTE);
            note.setValue(oldNote);
            Ui.hint(note, Component.translatable("screen.immersive_brewing.note_hint"));
            addRenderableWidget(note);
            addRenderableWidget(Button.builder(Component.translatable("screen.immersive_brewing.apply_label"), b -> applyLabel())
                    .bounds(px + 8, py + ph - 30, pw / 2 - 12, 20).build());
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(labelable ? px + pw / 2 : px + pw / 2 - 50, py + ph - 30, labelable ? pw / 2 - 8 : 100, 20).build());
    }

    private void applyLabel() {
        ModNetwork.sendToServer(new SetLabelC2S(true, session.pos, InteractionHand.MAIN_HAND, label.getValue(), note.getValue()));
    }

    @Override
    public void onClose() {
        ClientSession.clear();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        Ui.panel(g, px, py, pw, ph);
        content.begin(g);
        int bottom = drawResult(g);
        content.end(g, bottom);
        super.render(g, mouseX, mouseY, partialTick);
    }

    private int drawResult(GuiGraphics g) {
        Ui.Palette p = Ui.palette();
        int y = content.top();
        if (result == null) {
            y += Ui.wrapped(g, Component.translatable(session.endSealed() ? "screen.immersive_brewing.sealed_title" : "screen.immersive_brewing.ended_title"),
                    px + 8, y, content.width(), p.text()) + 6;
            String reason = session.endReason() == null ? "session.immersive_brewing.end.closed" : session.endReason();
            y += Ui.wrapped(g, Component.translatable(reason), px + 8, y, content.width(), p.text());
            y += 6 + Ui.wrapped(g, Component.translatable(session.endSealed() ? "screen.immersive_brewing.sealed_body" : "screen.immersive_brewing.released_body"),
                    px + 8, y + 6, content.width(), p.muted());
            return y;
        }
        String kind = result.getString("kind");
        ItemStack output = LedgerCodec.loadStack(result.getCompound("output"));
        if (session.practice) {
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.practice_result"), px + 8, y, content.width(), p.warn()) + 2;
        }
        if (!output.isEmpty()) {
            g.renderItem(output, px + 8, y);
            y += Math.max(20, Ui.wrapped(g, Component.translatable("screen.immersive_brewing.result.made", result.getInt("count"), output.getHoverName()),
                    px + 28, y + 4, content.width() - 20, p.text()) + 6);
        }
        switch (kind) {
            case "brewing" -> y = brewing(g, y);
            case "preparation" -> y = preparation(g, y);
            case "distillation" -> y = distillation(g, y);
            default -> {
            }
        }
        Component tip = tipComponent();
        if (tip != null) {
            g.drawString(font, Component.translatable("screen.immersive_brewing.result.tip"), px + 8, y + 4, p.accent(), false);
            y += 15 + Ui.wrapped(g, tip, px + 8, y + 15, content.width(), p.text());
        }
        return y + 4;
    }

    private int scoreLine(GuiGraphics g, int y, String key, int value, boolean grade) {
        Ui.Palette p = Ui.palette();
        Component text = grade
                ? Component.translatable(key, value, Component.literal(String.valueOf(Grade.of(value).symbol())).append(" ").append(Component.translatable(Grade.of(value).key())))
                : Component.translatable(key, value);
        if (grade) {
            int h = Ui.lines(text, content.width() - 8) * 10 + 6;
            Ui.inset(g, px + 8, y, content.width(), h);
            Ui.wrapped(g, text, px + 12, y + 3, content.width() - 8, 0xFF000000 | Grade.of(value).color());
            return y + h + 3;
        }
        return y + Ui.wrapped(g, text, px + 8, y, content.width(), p.text()) + 2;
    }

    private int term(GuiGraphics g, int y, String key, double value) {
        Ui.Palette p = Ui.palette();
        int labelW = content.width() / 2;
        int h = Ui.wrapped(g, Component.translatable(key), px + 8, y, labelW - 6, p.text());
        Ui.progress(g, px + 8 + labelW, y, content.width() - labelW - 34, 8, value,
                value >= 0.85 ? p.good() : value >= 0.6 ? p.warn() : p.bad());
        g.drawString(font, Math.round(value * 100) + "%", px + 8 + content.width() - 30, y, p.muted(), false);
        return y + Math.max(10, h) + 2;
    }

    private int brewing(GuiGraphics g, int y) {
        Ui.Palette p = Ui.palette();
        y = scoreLine(g, y, "screen.immersive_brewing.result.quality", result.getInt("Q"), true);
        y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.result.terms", result.getInt("E"), result.getInt("C")), px + 8, y, content.width(), p.text()) + 2;
        y = term(g, y, "screen.immersive_brewing.result.thermal", result.getDouble("thermal"));
        y = term(g, y, "screen.immersive_brewing.result.circulation", result.getDouble("circulation"));
        y = term(g, y, "screen.immersive_brewing.result.dosing", result.getDouble("dosing"));
        ListTag additions = result.getList("additions", Tag.TAG_COMPOUND);
        Component line = Component.empty();
        for (int i = 0; i < additions.size(); i++) {
            CompoundTag a = additions.getCompound(i);
            ItemStack item = item(a.getString("item"));
            if (i > 0) line = line.copy().append(", ");
            line = line.copy().append(item.getHoverName()).append(": ").append(Component.translatable(a.getString("outcome")));
        }
        if (!additions.isEmpty()) y += Ui.wrapped(g, line, px + 8, y, content.width(), p.muted()) + 1;
        if (result.getBoolean("capped")) {
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.result.capped", result.getInt("candidate")), px + 8, y, content.width(), p.warn());
        }
        return y;
    }

    private int preparation(GuiGraphics g, int y) {
        Ui.Palette p = Ui.palette();
        y = scoreLine(g, y, "screen.immersive_brewing.result.extraction", result.getInt("E"), false);
        y = term(g, y, "screen.immersive_brewing.result.accuracy", result.getDouble("accuracy"));
        y = term(g, y, "screen.immersive_brewing.result.restraint", result.getDouble("restraint"));
        return y + Ui.wrapped(g, Component.translatable("screen.immersive_brewing.result.texture", Component.translatable(result.getString("texture")),
                result.getInt("folds")), px + 8, y, content.width(), p.muted()) + 2;
    }

    private int distillation(GuiGraphics g, int y) {
        Ui.Palette p = Ui.palette();
        y = scoreLine(g, y, "screen.immersive_brewing.result.distillation", result.getInt("D"), false);
        y = term(g, y, "screen.immersive_brewing.result.collection", result.getDouble("collection"));
        y = term(g, y, "screen.immersive_brewing.result.thermal", result.getDouble("thermal"));
        y = term(g, y, "screen.immersive_brewing.result.cooling", result.getDouble("cooling"));
        int qin = result.getInt("Qin");
        int q = result.getInt("Q");
        y += Ui.wrapped(g, Component.translatable(q > qin ? "screen.immersive_brewing.result.purified" : "screen.immersive_brewing.result.not_improved", qin, q),
                px + 8, y, content.width(), q > qin ? p.good() : p.warn()) + 2;
        if (result.contains("sacrificed")) {
            y += Ui.wrapped(g, Component.translatable("screen.immersive_brewing.result.sacrificed", result.getInt("sacrificed")), px + 8, y, content.width(), p.muted()) + 2;
        }
        return y;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return content.wheel(mx, my, delta) || super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        return content.click(mx, my, button) || super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return content.drag(my) || super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        content.release();
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (!(getFocused() instanceof EditBox) && content.key(key)) return true;
        return super.keyPressed(key, scan, modifiers);
    }

    @Nullable
    private Component tipComponent() {
        if (result == null || !result.contains("tip")) return null;
        ListTag args = result.getList("tipArgs", Tag.TAG_STRING);
        Object[] values = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            String a = args.getString(i);
            values[i] = a.startsWith("phase.") ? Component.translatable(a) : item(a).isEmpty() ? Component.literal(a) : item(a).getHoverName();
        }
        return Component.translatable(result.getString("tip"), values);
    }

    private static ItemStack item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null || !ForgeRegistries.ITEMS.containsKey(rl)) return ItemStack.EMPTY;
        return new ItemStack(ForgeRegistries.ITEMS.getValue(rl));
    }
}
