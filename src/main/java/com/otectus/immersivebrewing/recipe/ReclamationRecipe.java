package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.data.Json;
import com.otectus.immersivebrewing.registry.ModRecipeSerializers;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;

import java.util.Set;

/**
 * {@code type: immersive_brewing:reclamation} (spec 9.2, 16.1): safe salvage. {@code group_size} accepted potions become a
 * fixed, low-value result plus their containers after a passive {@code duration_ticks}; no reagent is ever returned.
 */
public final class ReclamationRecipe implements Recipe<Container> {
    private static final Set<String> KNOWN = Set.of("type", "schema_version", "input", "group_size", "result", "container",
            "fuel_charges", "duration_ticks", "conditions", "forge:conditions", "group");

    private final ResourceLocation id;
    private final PotionMatcher input;
    private final int groupSize;
    private final ItemStack result;
    private final Item container;
    private final int fuelCharges;
    private final int durationTicks;

    public ReclamationRecipe(ResourceLocation id, PotionMatcher input, int groupSize, ItemStack result, Item container, int fuelCharges, int durationTicks) {
        this.id = id;
        this.input = input;
        this.groupSize = groupSize;
        this.result = result;
        this.container = container;
        this.fuelCharges = fuelCharges;
        this.durationTicks = durationTicks;
    }

    public PotionMatcher input() {
        return input;
    }

    public int groupSize() {
        return groupSize;
    }

    public ItemStack result() {
        return result.copy();
    }

    public Item container() {
        return container;
    }

    public int fuelCharges() {
        return fuelCharges;
    }

    public int durationTicks() {
        return durationTicks;
    }

    @Override
    public boolean matches(Container c, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container c, RegistryAccess access) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int w, int h) {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return result.copy();
    }

    @Override
    public boolean isSpecial() {
        return true;
    }

    @Override
    public ResourceLocation getId() {
        return id;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return ModRecipeSerializers.RECLAMATION.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.RECLAMATION.get();
    }

    public static final class Serializer implements RecipeSerializer<ReclamationRecipe> {
        @Override
        public ReclamationRecipe fromJson(ResourceLocation id, JsonObject json) {
            int schema = Json.integer(json, "schema_version", 1, 1, 1000);
            if (schema > 1) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (1)");
            PotionMatcher input = PotionMatcher.parse(Json.object(json, "input"), "input");
            int group = Json.integer(json, "group_size", 3, 1, 6);
            JsonObject resultJson = Json.object(json, "result");
            Item resultItem = DistillationRecipe.item(resultJson, "result");
            int count = Json.integer(resultJson, "count", 1, 1, 4);
            Item container = DistillationRecipe.item(Json.object(json, "container"), "container");
            int fuel = Json.integer(json, "fuel_charges", 1, 0, 20);
            int duration = Json.integer(json, "duration_ticks", 200, 20, 1200);
            for (String f : Json.unknown(json, KNOWN)) com.otectus.immersivebrewing.ImmersiveBrewing.LOGGER.warn("Immersive Brewing: recipe {}: {}: unknown field ignored", id, f);
            return new ReclamationRecipe(id, input, group, new ItemStack(resultItem, count), container, fuel, duration);
        }

        @Override
        public ReclamationRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            return new ReclamationRecipe(id, PotionMatcher.read(buf), buf.readVarInt(), buf.readItem(), buf.readById(BuiltInRegistries.ITEM),
                    buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, ReclamationRecipe r) {
            r.input.write(buf);
            buf.writeVarInt(r.groupSize);
            buf.writeItem(r.result);
            buf.writeId(BuiltInRegistries.ITEM, r.container);
            buf.writeVarInt(r.fuelCharges);
            buf.writeVarInt(r.durationTicks);
        }
    }
}
