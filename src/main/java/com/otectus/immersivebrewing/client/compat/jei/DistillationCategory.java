package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.recipe.DistillationRecipe;
import com.otectus.immersivebrewing.recipe.PotionMatcher;
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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;

/** Purification at the Alembic: exact yield, the returned containers, fuel and the quality cap (spec 9.1, 18). */
final class DistillationCategory implements IRecipeCategory<DistillationRecipe> {
    static final RecipeType<DistillationRecipe> TYPE = RecipeType.create(ImmersiveBrewing.MOD_ID, "distillation", DistillationRecipe.class);

    private final IDrawable icon;

    DistillationCategory(IGuiHelper helper) {
        this.icon = helper.createDrawableItemStack(new ItemStack(ModItems.ALEMBIC.get()));
    }

    static List<ItemStack> examples(PotionMatcher matcher, int count) {
        List<ItemStack> out = new ArrayList<>();
        for (Item item : matcher.itemList()) {
            for (Potion potion : ForgeRegistries.POTIONS) {
                ItemStack stack = PotionUtils.setPotion(new ItemStack(item, count), potion);
                if (matcher.matches(stack)) out.add(stack);
            }
        }
        return out;
    }

    @Override
    public RecipeType<DistillationRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.immersive_brewing.distillation");
    }

    @Override
    public int getWidth() {
        return 160;
    }

    @Override
    public int getHeight() {
        return 36 + Ui.lines(Component.translatable("jei.immersive_brewing.distillation.rule", 100, 99999), getWidth() - 4) * 10
                + Ui.lines(Component.translatable("jei.immersive_brewing.distillation.once"), getWidth() - 4) * 10;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, DistillationRecipe recipe, IFocusGroup focuses) {
        List<ItemStack> in = examples(recipe.input(), recipe.groupSize());
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack s : in) out.add(s.copyWithCount(recipe.yield()));
        var inSlot = builder.addSlot(RecipeIngredientRole.INPUT, 4, 6).addItemStacks(in).setStandardSlotBackground();
        var outSlot = builder.addSlot(RecipeIngredientRole.OUTPUT, 80, 6).addItemStacks(out).setOutputSlotBackground();
        builder.addSlot(RecipeIngredientRole.OUTPUT, 110, 6).addItemStack(new ItemStack(recipe.container(), recipe.groupSize() - recipe.yield()))
                .setStandardSlotBackground();
        builder.addSlot(RecipeIngredientRole.CATALYST, 138, 6).addItemStack(new ItemStack(ModItems.ALEMBIC.get()));
        builder.createFocusLink(inSlot, outSlot);
    }

    @Override
    public void draw(DistillationRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        JeiDraw.arrow(g, 40, 9);
        int y = 32 + Ui.wrapped(g, Component.translatable("jei.immersive_brewing.distillation.rule", recipe.qualityCap(), recipe.fuelCharges()),
                2, 30, getWidth() - 4, 0xFF404040);
        Ui.wrapped(g, Component.translatable("jei.immersive_brewing.distillation.once"), 2, y, getWidth() - 4, 0xFF555555);
    }
}
