package com.otectus.immersivebrewing.client.compat.jei;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JEI (spec 18): Preparation, Brewing, Distillation and Reclamation categories built from the synced recipes and
 * definitions, information pages for the stations and items, and the Kettle transfer handler. Every infusion recipe is
 * validated on the client exactly as the server does; recipes the brewing system can not perform are not listed, so JEI
 * only shows what the Kettle will actually brew. JEI rebuilds this after every reload.
 */
@JeiPlugin
public class ImmersiveBrewingJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = ImmersiveBrewing.id("jei_plugin");
    private final Map<ResourceLocation, PlanIndex.Status> statuses = new HashMap<>();
    private mezz.jei.api.runtime.IJeiRuntime runtime;
    private List<InfusionRecipe> visible=List.of();

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(ModItems.PREPARED_REAGENT_KIT.get(),
                (stack, context) -> KitData.read(stack).map(k -> String.valueOf(ForgeRegistries.ITEMS.getKey(k.source().getItem()))).orElse(""));
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var helper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(new PreparationCategory(helper), new BrewingCategory(helper, statuses), new DistillationCategory(helper),
                new ReclamationCategory(helper));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        RecipeManager recipes = level.getRecipeManager();
        BrewingDefinitions defs = BrewingDefinitions.client();
        statuses.clear();
        List<InfusionRecipe> shown = new ArrayList<>();
        for (InfusionRecipe recipe : recipes.getAllRecipesFor(ModRecipeTypes.INFUSION.get())) {
            PlanIndex.Entry entry = com.otectus.immersivebrewing.client.ServerCatalog.entry(recipe.getId());
            if (entry == null || entry.status() == PlanIndex.Status.UNSUPPORTED || entry.status() == PlanIndex.Status.EXCLUDED) continue;
            statuses.put(recipe.getId(), entry.status());
            shown.add(recipe);
        }
        shown.sort(java.util.Comparator.comparing(r -> r.getId().toString()));
        registration.addRecipes(BrewingCategory.TYPE, shown);
        visible=List.copyOf(shown);
        List<PreparationCategory.Entry> preparation = new ArrayList<>();
        for (ReagentProfile profile : defs.reagentProfiles()) {
            if (profile.preparation() == null) continue;
            for (ResourceLocation id : profile.selector().ids()) {
                Item item = ForgeRegistries.ITEMS.getValue(id);
                if (item != null && !defs.exclusions().excludesItem(item)) preparation.add(new PreparationCategory.Entry(new ItemStack(item), profile));
            }
        }
        registration.addRecipes(PreparationCategory.TYPE, preparation);
        registration.addRecipes(DistillationCategory.TYPE, recipes.getAllRecipesFor(ModRecipeTypes.DISTILLATION.get()).stream()
                .filter(r -> !defs.exclusions().excludesRecipe(r.getId())).toList());
        registration.addRecipes(ReclamationCategory.TYPE, recipes.getAllRecipesFor(ModRecipeTypes.RECLAMATION.get()).stream()
                .filter(r -> !defs.exclusions().excludesRecipe(r.getId())).toList());
        info(registration, ModItems.BREWERS_KETTLE.get(), "kettle");
        info(registration, ModItems.APOTHECARY_MORTAR.get(), "mortar");
        info(registration, ModItems.ALEMBIC.get(), "alembic");
        info(registration, ModItems.BATCH_CARRIER.get(), "carrier");
        info(registration, ModItems.BREWERS_NOTEBOOK.get(), "notebook");
        info(registration, ModItems.RECOVERY_BATCH.get(), "recovery");
        info(registration, ModItems.WOODEN_BREWING_PADDLE.get(), "paddle");
        info(registration, ModItems.IRON_BREWING_PADDLE.get(), "paddle");
    }

    private static void info(IRecipeRegistration registration, Item item, String key) {
        registration.addIngredientInfo(new ItemStack(item), VanillaTypes.ITEM_STACK, Component.translatable("jei.immersive_brewing.info." + key));
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModItems.APOTHECARY_MORTAR.get()), PreparationCategory.TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModItems.BREWERS_KETTLE.get()), BrewingCategory.TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModItems.ALEMBIC.get()), DistillationCategory.TYPE, ReclamationCategory.TYPE);
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addRecipeTransferHandler(new KettleBatchTransfer(registration.getTransferHelper()),BrewingCategory.TYPE);
    }

    @Override
    public void onRuntimeAvailable(mezz.jei.api.runtime.IJeiRuntime runtime) {
        this.runtime=runtime;
        com.otectus.immersivebrewing.client.ServerCatalog.changed=this::refresh;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(this::recipesUpdated);
        refresh();
        java.util.Map<String, Long> counts = new java.util.LinkedHashMap<>();
        for (mezz.jei.api.recipe.RecipeType<?> type : List.of(BrewingCategory.TYPE, PreparationCategory.TYPE, DistillationCategory.TYPE, ReclamationCategory.TYPE)) {
            counts.put(type.getUid().getPath(), runtime.getRecipeManager().createRecipeLookup(type).get().count());
        }
        JeiStatus.record(counts);
    }

    private void recipesUpdated(net.minecraftforge.client.event.RecipesUpdatedEvent event) { refresh(); }

    private void refresh() {
        var level=Minecraft.getInstance().level;
        if(runtime==null||level==null)return;
        var manager=runtime.getRecipeManager();
        manager.hideRecipes(BrewingCategory.TYPE,visible);
        List<InfusionRecipe> next=new ArrayList<>(); statuses.clear();
        for(var recipe:level.getRecipeManager().getAllRecipesFor(ModRecipeTypes.INFUSION.get())) {
            var entry=com.otectus.immersivebrewing.client.ServerCatalog.entry(recipe.getId());
            if(entry==null||entry.status()==PlanIndex.Status.UNSUPPORTED||entry.status()==PlanIndex.Status.EXCLUDED)continue;
            statuses.put(recipe.getId(),entry.status()); next.add(recipe);
            if(!visible.contains(recipe)) manager.addRecipes(BrewingCategory.TYPE,List.of(recipe));
        }
        manager.unhideRecipes(BrewingCategory.TYPE,next); visible=List.copyOf(next);
        java.util.Map<String,Long> counts=new java.util.LinkedHashMap<>(JeiStatus.counts());
        counts.put("brewing",(long)visible.size()); JeiStatus.record(counts);
    }
}
