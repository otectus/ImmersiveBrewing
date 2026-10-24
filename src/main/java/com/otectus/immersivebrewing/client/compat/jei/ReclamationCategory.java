package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.recipe.ReclamationRecipe;
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

/** Reclamation at the Alembic: a group of unwanted potions, the low-value result and every container back (spec 9.2). */
final class ReclamationCategory implements IRecipeCategory<ReclamationRecipe> {
    static final RecipeType<ReclamationRecipe> TYPE = RecipeType.create(ImmersiveBrewing.MOD_ID, "reclamation", ReclamationRecipe.class);

    private final IDrawable icon;

    ReclamationCategory(IGuiHelper helper) {
        this.icon = helper.createDrawableItemStack(new ItemStack(ModItems.SPENT_ALCHEMICAL_SALT.get()));
    }

    @Override
    public RecipeType<ReclamationRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.immersive_brewing.reclamation");
    }

    @Override
    public int getWidth() {
        return 160;
    }

    @Override
    public int getHeight() {
        return 34 + Ui.lines(Component.translatable("jei.immersive_brewing.reclamation.rule", 99999, 99999), getWidth() - 4) * 10;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, ReclamationRecipe recipe, IFocusGroup focuses) {
        builder.addSlot(RecipeIngredientRole.INPUT, 4, 6).addItemStacks(DistillationCategory.examples(recipe.input(), recipe.groupSize()))
                .setStandardSlotBackground();
        builder.addSlot(RecipeIngredientRole.OUTPUT, 80, 6).addItemStack(recipe.result()).setOutputSlotBackground();
        builder.addSlot(RecipeIngredientRole.OUTPUT, 110, 6).addItemStack(new ItemStack(recipe.container(), recipe.groupSize())).setStandardSlotBackground();
        builder.addSlot(RecipeIngredientRole.CATALYST, 138, 6).addItemStack(new ItemStack(ModItems.ALEMBIC.get()));
    }

    @Override
    public void draw(ReclamationRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        JeiDraw.arrow(g, 40, 9);
        Ui.wrapped(g, Component.translatable("jei.immersive_brewing.reclamation.rule", recipe.durationTicks() / 20, recipe.fuelCharges()), 2, 30, getWidth() - 4, 0xFF404040);
    }
}
