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
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Set;

/**
 * {@code type: immersive_brewing:distillation} (spec 9.1, 16.1): an explicitly supported purification policy. A group of
 * {@code group_size} identical, unrefined potions becomes {@code yield} of the same potion with improved, capped
 * craftsmanship; the sacrificed potions return {@code container}. The server's own cap narrows {@code quality_cap}.
 */
public final class DistillationRecipe implements Recipe<Container> {
    private static final Set<String> KNOWN = Set.of("type", "schema_version", "input", "group_size", "yield", "fuel_charges",
            "quality_cap", "container", "conditions", "forge:conditions", "group");

    private final ResourceLocation id;
    private final PotionMatcher input;
    private final int groupSize;
    private final int yield;
    private final int fuelCharges;
    private final int qualityCap;
    private final Item container;

    public DistillationRecipe(ResourceLocation id, PotionMatcher input, int groupSize, int yield, int fuelCharges, int qualityCap, Item container) {
        this.id = id;
        this.input = input;
        this.groupSize = groupSize;
        this.yield = yield;
        this.fuelCharges = fuelCharges;
        this.qualityCap = qualityCap;
        this.container = container;
    }

    public PotionMatcher input() {
        return input;
    }

    public int groupSize() {
        return groupSize;
    }

    public int yield() {
        return yield;
    }

    public int fuelCharges() {
        return fuelCharges;
    }

    public int qualityCap() {
        return qualityCap;
    }

    public Item container() {
        return container;
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
        return ItemStack.EMPTY;
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
        return ModRecipeSerializers.DISTILLATION.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.DISTILLATION.get();
    }

    static Item item(JsonObject json, String field) {
        ResourceLocation id = Json.id(json, "item");
        Item item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null || item == Items.AIR) throw new JsonParseException(field + ".item: unknown item " + id);
        return item;
    }

    public static final class Serializer implements RecipeSerializer<DistillationRecipe> {
        @Override
        public DistillationRecipe fromJson(ResourceLocation id, JsonObject json) {
            int schema = Json.integer(json, "schema_version", 1, 1, 1000);
            if (schema > 1) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (1)");
            PotionMatcher input = PotionMatcher.parse(Json.object(json, "input"), "input");
            int group = Json.integer(json, "group_size", 3, 2, 6);
            int yield = Json.integer(json, "yield", 2, 1, 6);
            if (yield >= group) throw new JsonParseException("yield: must be smaller than group_size (purification costs quantity)");
            int fuel = Json.integer(json, "fuel_charges", 1, 1, 20);
            int cap = Json.integer(json, "quality_cap", 70, 35, 89);
            Item container = item(Json.object(json, "container"), "container");
            for (String f : Json.unknown(json, KNOWN)) com.otectus.immersivebrewing.ImmersiveBrewing.LOGGER.warn("Immersive Brewing: recipe {}: {}: unknown field ignored", id, f);
            return new DistillationRecipe(id, input, group, yield, fuel, cap, container);
        }

        @Override
        public DistillationRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            return new DistillationRecipe(id, PotionMatcher.read(buf), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readById(BuiltInRegistries.ITEM));
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, DistillationRecipe r) {
            r.input.write(buf);
            buf.writeVarInt(r.groupSize);
            buf.writeVarInt(r.yield);
            buf.writeVarInt(r.fuelCharges);
            buf.writeVarInt(r.qualityCap);
            buf.writeId(BuiltInRegistries.ITEM, r.container);
        }
    }
}
