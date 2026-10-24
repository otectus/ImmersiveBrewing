package com.otectus.immersivebrewing.recipe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.otectus.immersivebrewing.data.Json;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * A potion container identity in a plan: item, optional potion id, and how strictly a real stack must match
 * (spec 16.2 {@code "match"}). An output spec may say {@code "potion": "same"} to keep the input's potion (container
 * conversions).
 */
public record PotionSpec(Item item, @Nullable Potion potion, Match match, boolean samePotion) {

    public enum Match {
        /** Item, potion and every other tag must equal the canonical form; only the cosmetic {@code display} compound may differ. */
        CANONICAL,
        /** Item and potion id only; foreign tags are allowed and travel to the adapter. */
        POTION,
        /** Item only, any potion (container conversions). */
        ITEM;

        static Match parse(String value) {
            for (Match m : values()) if (m.name().toLowerCase(Locale.ROOT).equals(value)) return m;
            throw new JsonParseException("match: '" + value + "' is not one of canonical, potion, item");
        }
    }

    public static PotionSpec parse(JsonObject json, String field, boolean output) {
        ResourceLocation itemId = Json.id(json, "item");
        Item item = ForgeRegistries.ITEMS.getValue(itemId);
        if (item == null || item == Items.AIR) throw new JsonParseException(field + ".item: unknown item " + itemId);
        Potion potion = null;
        boolean same = false;
        if (json.has("potion")) {
            String value = Json.string(json, "potion");
            if (output && value.equals("same")) {
                same = true;
            } else {
                ResourceLocation potionId = Json.id(value, field + ".potion");
                if (!ForgeRegistries.POTIONS.containsKey(potionId)) throw new JsonParseException(field + ".potion: unknown potion " + potionId);
                potion = ForgeRegistries.POTIONS.getValue(potionId);
            }
        }
        Match match = Match.parse(Json.optString(json, "match", potion != null ? "canonical" : "item"));
        if (match != Match.ITEM && potion == null && !same) throw new JsonParseException(field + ": match '" + match.name().toLowerCase(Locale.ROOT) + "' needs a potion");
        return new PotionSpec(item, potion, match, same);
    }

    /** The canonical example stack for this spec (for an output with "same", the given input's potion). */
    public ItemStack stack(@Nullable Potion input) {
        ItemStack stack = new ItemStack(item);
        Potion p = samePotion ? input : potion;
        if (p != null) PotionUtils.setPotion(stack, p);
        return stack;
    }

    public boolean matches(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != item) return false;
        if (match == Match.ITEM) return true;
        if (PotionUtils.getPotion(stack) != potion) return false;
        if (match == Match.POTION) return true;
        CompoundTag tag = PotionContentsService.canonicalTag(stack);
        if (tag != null && tag.contains("display")) {
            tag = tag.copy();
            tag.remove("display");
        }
        CompoundTag expected = stack(null).getTag();
        return Objects.equals(tag == null || tag.isEmpty() ? null : tag, expected);
    }

    /** Whether an actual output satisfies this expected-output spec, given the step's input. */
    public boolean accepts(ItemStack output, ItemStack input) {
        if (output.isEmpty() || output.getItem() != item) return false;
        Potion expected = samePotion ? PotionUtils.getPotion(input) : potion;
        return expected == null || PotionUtils.getPotion(output) == expected;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeId(BuiltInRegistries.ITEM, item);
        buf.writeBoolean(potion != null);
        if (potion != null) buf.writeResourceLocation(Objects.requireNonNull(ForgeRegistries.POTIONS.getKey(potion)));
        buf.writeEnum(match);
        buf.writeBoolean(samePotion);
    }

    public static PotionSpec read(FriendlyByteBuf buf) {
        Item item = buf.readById(BuiltInRegistries.ITEM);
        Potion potion = buf.readBoolean() ? ForgeRegistries.POTIONS.getValue(buf.readResourceLocation()) : null;
        return new PotionSpec(item == null ? Items.AIR : item, potion, buf.readEnum(Match.class), buf.readBoolean());
    }
}
