package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.client.screen.Ui;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanStep;
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
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Brewing in the Kettle (spec 18): the input bottle, every step's reagent and phase, the native result, fuel per three
 * servings, the serving limit and what quality may change. Container conversions show every potion they accept.
 */
final class BrewingCategory implements IRecipeCategory<InfusionRecipe> {
    static final RecipeType<InfusionRecipe> TYPE = RecipeType.create(ImmersiveBrewing.MOD_ID, "brewing", InfusionRecipe.class);
    private static final int WIDTH = 176;

    private final IDrawable icon;
    private final Map<net.minecraft.resources.ResourceLocation, PlanIndex.Status> statuses;

    BrewingCategory(IGuiHelper helper, Map<net.minecraft.resources.ResourceLocation, PlanIndex.Status> statuses) {
        this.icon = helper.createDrawableItemStack(new ItemStack(ModItems.BREWERS_KETTLE.get()));
        this.statuses = statuses;
    }

    @Override
    public RecipeType<InfusionRecipe> getRecipeType() {
        return TYPE;
    }

    @Override
    public Component getTitle() {
        return Component.translatable("jei.immersive_brewing.brewing");
    }

    @Override
    public int getWidth() {
        return WIDTH;
    }

    @Override
    public int getHeight() {
        return 74 + Ui.lines(Component.translatable("jei.immersive_brewing.brewing.fuel", InfusionRecipe.MAX_STEPS, 6), WIDTH - 4) * 10
                + Math.max(Ui.lines(Component.translatable("jei.immersive_brewing.brewing.quality"), WIDTH - 4),
                Ui.lines(Component.translatable("jei.immersive_brewing.brewing.recorded"), WIDTH - 4)) * 10;
    }

    @Override
    public IDrawable getIcon() {
        return icon;
    }

    static List<ItemStack> inputs(InfusionRecipe recipe) {
        if (!recipe.generic()) return List.of(recipe.input().stack(null));
        List<ItemStack> out = new ArrayList<>();
        for (Potion potion : ForgeRegistries.POTIONS) {
            if (potion.getEffects().isEmpty()) continue;
            out.add(PotionUtils.setPotion(new ItemStack(recipe.input().item()), potion));
        }
        return out;
    }

    @Override
    public void setRecipe(IRecipeLayoutBuilder builder, InfusionRecipe recipe, IFocusGroup focuses) {
        List<ItemStack> inputs = inputs(recipe);
        var in = builder.addSlot(RecipeIngredientRole.INPUT, 2, 29).addItemStacks(inputs).setStandardSlotBackground();
        for (int i = 0; i < recipe.steps().size(); i++) {
            PlanStep step = recipe.steps().get(i);
            builder.addSlot(RecipeIngredientRole.INPUT, 28 + i % 4 * 24, 14 + i / 4 * 30 + (recipe.steps().size() <= 4 ? 15 : 0))
                    .addIngredients(step.reagent()).setStandardSlotBackground()
                    .addTooltipCallback((slot, tooltip) -> tooltip.add(Component.translatable("phase.immersive_brewing." + step.phase().serialized())));
        }
        List<ItemStack> outputs = new ArrayList<>();
        for (ItemStack i : inputs) outputs.add(recipe.expectedOutput().stack(PotionUtils.getPotion(i)));
        var out = builder.addSlot(RecipeIngredientRole.OUTPUT, WIDTH - 22, 29).addItemStacks(outputs).setOutputSlotBackground();
        if (recipe.generic()) builder.createFocusLink(in, out);
    }

    @Override
    public void draw(InfusionRecipe recipe, IRecipeSlotsView view, GuiGraphics g, double mouseX, double mouseY) {
        var font = Minecraft.getInstance().font;
        for (int i = 0; i < recipe.steps().size(); i++) {
            Component phase = Component.translatable("phase.immersive_brewing." + recipe.steps().get(i).phase().serialized());
            g.drawString(font, font.plainSubstrByWidth(phase.getString(), 22), 28 + i % 4 * 24,
                    4 + i / 4 * 30 + (recipe.steps().size() <= 4 ? 15 : 0), 0xFF555555, false);
        }
        JeiDraw.arrow(g, Math.min(130, 36 + Math.min(4, recipe.steps().size()) * 24), 32);
        int y = 68 + Ui.wrapped(g, Component.translatable("jei.immersive_brewing.brewing.fuel", recipe.steps().size(), recipe.maxServings()),
                2, 66, WIDTH - 4, 0xFF404040);
        PlanIndex.Status status = statuses.getOrDefault(recipe.getId(), PlanIndex.Status.SUPPORTED);
        Component quality = Component.translatable(status == PlanIndex.Status.METADATA_ONLY ? "jei.immersive_brewing.brewing.recorded"
                : "jei.immersive_brewing.brewing.quality");
        Ui.wrapped(g, quality, 2, y, WIDTH - 4, 0xFF404040);
    }
}
