package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.data.Json;
import com.otectus.immersivebrewing.recipe.adapter.ForgeRegistryAdapter;
import com.otectus.immersivebrewing.registry.ModRecipeSerializers;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code type: immersive_brewing:infusion} (spec 16.2): a declared sequence of authoritative transformations. The
 * expected output validates the delegated result; it never manufactures it. Steps are checked against the adapter when
 * the plan index is built, and the actual output stack is always the adapter's.
 *
 * <p>Not a crafting recipe: {@link #matches} is always false and the recipe book never shows it.
 */
public final class InfusionRecipe implements Recipe<Container> {
    public static final int SCHEMA = 1;
    public static final int MAX_STEPS = 8;
    private static final Set<String> KNOWN = Set.of("type", "schema_version", "adapter", "input", "steps", "expected_output", "batch",
            "brew_profile", "conditions", "forge:conditions", "group");

    private final ResourceLocation id;
    private final ResourceLocation adapter;
    private final PotionSpec input;
    private final List<PlanStep> steps;
    private final PotionSpec expectedOutput;
    private final int maxServings;
    private final int servingsPerReagent;
    @Nullable
    private final ResourceLocation brewProfile;
    private final List<String> warnings;

    public InfusionRecipe(ResourceLocation id, ResourceLocation adapter, PotionSpec input, List<PlanStep> steps, PotionSpec expectedOutput,
                          int maxServings, int servingsPerReagent, @Nullable ResourceLocation brewProfile, List<String> warnings) {
        this.id = id;
        this.adapter = adapter;
        this.input = input;
        this.steps = List.copyOf(steps);
        this.expectedOutput = expectedOutput;
        this.maxServings = maxServings;
        this.servingsPerReagent = servingsPerReagent;
        this.brewProfile = brewProfile;
        this.warnings = List.copyOf(warnings);
    }

    public ResourceLocation adapter() {
        return adapter;
    }

    public PotionSpec input() {
        return input;
    }

    public List<PlanStep> steps() {
        return steps;
    }

    public PotionSpec expectedOutput() {
        return expectedOutput;
    }

    public int maxServings() {
        return maxServings;
    }

    public int servingsPerReagent() {
        return servingsPerReagent;
    }

    @Nullable
    public ResourceLocation brewProfile() {
        return brewProfile;
    }

    /** Parse warnings (unknown fields), reported with the plan index. */
    public List<String> warnings() {
        return warnings;
    }

    /** Container conversions apply to any potion of the input item. */
    public boolean generic() {
        return input.match() == PotionSpec.Match.ITEM;
    }

    @Override
    public boolean matches(Container container, Level level) {
        return false;
    }

    @Override
    public ItemStack assemble(Container container, RegistryAccess access) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canCraftInDimensions(int width, int height) {
        return false;
    }

    @Override
    public ItemStack getResultItem(RegistryAccess access) {
        return expectedOutput.stack(input.potion());
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
        return ModRecipeSerializers.INFUSION.get();
    }

    @Override
    public RecipeType<?> getType() {
        return ModRecipeTypes.INFUSION.get();
    }

    public static final class Serializer implements RecipeSerializer<InfusionRecipe> {
        @Override
        public InfusionRecipe fromJson(ResourceLocation id, JsonObject json) {
            List<String> warnings = new ArrayList<>();
            int schema = Json.integer(json, "schema_version", SCHEMA, 1, 1000);
            if (schema > SCHEMA) throw new JsonParseException("schema_version: " + schema + " is newer than this version understands (" + SCHEMA + ")");
            ResourceLocation adapter = json.has("adapter") ? Json.id(json, "adapter") : ForgeRegistryAdapter.ID;
            PotionSpec input = PotionSpec.parse(Json.object(json, "input"), "input", false);
            JsonArray array = Json.array(json, "steps");
            if (array.isEmpty()) throw new JsonParseException("steps: at least one step is required");
            if (array.size() > MAX_STEPS) throw new JsonParseException("steps: at most " + MAX_STEPS + " steps");
            List<PlanStep> steps = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) steps.add(PlanStep.parse(Json.object(array.get(i), "steps[" + i + "]"), i, warnings));
            PotionSpec output = PotionSpec.parse(Json.object(json, "expected_output"), "expected_output", true);
            if (output.samePotion() && !input.match().equals(PotionSpec.Match.ITEM) && input.potion() == null) {
                throw new JsonParseException("expected_output.potion: 'same' needs an input");
            }
            JsonObject batch = Json.optObject(json, "batch");
            int maxServings = Json.integer(batch, "max_servings", 6, 1, 6);
            int perReagent = Json.integer(batch, "servings_per_reagent", 3, 1, 6);
            ResourceLocation profile = json.has("brew_profile") ? Json.id(json, "brew_profile") : null;
            for (String f : Json.unknown(json, KNOWN)) warnings.add(f + ": unknown field ignored");
            return new InfusionRecipe(id, adapter, input, steps, output, maxServings, perReagent, profile, warnings);
        }

        @Override
        public InfusionRecipe fromNetwork(ResourceLocation id, FriendlyByteBuf buf) {
            ResourceLocation adapter = buf.readResourceLocation();
            PotionSpec input = PotionSpec.read(buf);
            int n = Math.min(buf.readVarInt(), MAX_STEPS);
            List<PlanStep> steps = new ArrayList<>(n);
            for (int i = 0; i < n; i++) steps.add(PlanStep.read(buf));
            PotionSpec output = PotionSpec.read(buf);
            int maxServings = buf.readVarInt();
            int perReagent = buf.readVarInt();
            ResourceLocation profile = buf.readBoolean() ? buf.readResourceLocation() : null;
            return new InfusionRecipe(id, adapter, input, steps, output, maxServings, perReagent, profile, List.of());
        }

        @Override
        public void toNetwork(FriendlyByteBuf buf, InfusionRecipe recipe) {
            buf.writeResourceLocation(recipe.adapter);
            recipe.input.write(buf);
            buf.writeVarInt(recipe.steps.size());
            for (PlanStep s : recipe.steps) s.write(buf);
            recipe.expectedOutput.write(buf);
            buf.writeVarInt(recipe.maxServings);
            buf.writeVarInt(recipe.servingsPerReagent);
            buf.writeBoolean(recipe.brewProfile != null);
            if (recipe.brewProfile != null) buf.writeResourceLocation(recipe.brewProfile);
        }
    }

    @Override
    public String toString() {
        return "InfusionRecipe[" + id + "]";
    }

    static ResourceLocation defaultProfile() {
        return ImmersiveBrewing.id("generic");
    }
}
