package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.registry.ModItems;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * Preparation at the Mortar: one profiled reagent becomes one kit covering a serving group (up to four kits per
 * session, four times the reagents). The kit's extraction is what the player earns; nothing else changes.
 */
final class PreparationCategory implements IRecipeCategory<PreparationCategory.Entry> {
    static final RecipeType<Entry> TYPE = RecipeType.create(ImmersiveBrewing.MOD_ID, "preparation", Entry.class);

    record Entry(ItemStack reagent, ReagentProfile profile) {}

    private final IDrawable icon;

    PreparationCategory(IGuiHelper helper) {
        this.icon = helper.createDrawableItemStack(new ItemStack(ModItems.APOTHECARY_MORTAR.get()));
    }

    @Override
    public RecipeType<Entry> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.immersive_brewing.preparation");
    }

    @Override
    public int getWidth() {
        return 150;
    }

    @Override
    public int getHeight() {
        int mode = java.util.Arrays.stream(com.otectus.immersivebrewing.minigame.preparation.PreparationMode.values())
                .mapToInt(m -> Ui.lines(Component.translatable("mode.immersive_brewing." + m.serialized()), getWidth() - 4)).max().orElse(1);
        return 34 + mode * 10 + Ui.lines(Component.translatable("jei.immersive_brewing.preparation.covers"), getWidth() - 4) * 10;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, Entry entry, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 10, 6).addItemStack(entry.reagent()).setStandardSlotBackground();
        builder.addSlot(RecipeIngredientRole.OUTPUT, 70, 6)
                .addItemStack(new KitData(entry.reagent(), 1, entry.profile().id(), 50).toStack(1)).setOutputSlotBackground();
        builder.addSlot(RecipeIngredientRole.CATALYST, 120, 6).addItemStack(new ItemStack(ModItems.APOTHECARY_MORTAR.get()));
    }

    @Override
    public void draw(Entry entry, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        JeiDraw.arrow(g, 36, 9);
        var prep = entry.profile().preparation();
        int y = 30 + Ui.wrapped(g, Component.translatable("mode.immersive_brewing." + prep.mode().serialized()), 2, 28, getWidth() - 4, 0xFF404040);
        Ui.wrapped(g, Component.translatable("jei.immersive_brewing.preparation.covers"), 2, y, getWidth() - 4, 0xFF555555);
    }
}
