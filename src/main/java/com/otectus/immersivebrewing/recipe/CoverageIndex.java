package com.otectus.immersivebrewing.recipe;

import com.otectus.immersivebrewing.potion.PotionContentsService;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;

import java.util.Set;

/**
 * The transformations the Kettle covers, for replacement mode (spec 14): (canonical bottle fingerprint, ingredient item)
 * pairs taken only from validated plans with a working Kettle alternative. Modifier and container conversions are
 * included only when {@code replaceModifiers} is on. Rebuilt with the plan index; never consulted in COEXIST mode.
 */
public final class CoverageIndex {
    /** One covered step: a bottle's canonical identity and the ingredient that transforms it. */
    public record Pair(long input, Item ingredient) {}

    private static volatile CoverageIndex server = new CoverageIndex(Set.of(), Set.of());

    private final Set<Pair> covered;
    private final Set<Pair> modifiers;

    public CoverageIndex(Set<Pair> covered, Set<Pair> modifiers) {
        this.covered = Set.copyOf(covered);
        this.modifiers = Set.copyOf(modifiers);
    }

    public static CoverageIndex server() {
        return server;
    }

    static void activate(CoverageIndex index) {
        server = index;
    }

    public int size() {
        return covered.size();
    }

    public boolean covers(ItemStack bottle, Item ingredient, boolean includeModifiers) {
        Pair pair = new Pair(PotionContentsService.fingerprint(bottle), ingredient);
        return covered.contains(pair) || includeModifiers && modifiers.contains(pair);
    }

    /**
     * True when the stand would brew only covered transformations. A cycle with any uncovered valid transformation
     * passes through whole (the conservative exception), as does a stand with nothing to brew.
     */
    public boolean coversStand(ItemStack ingredient, ItemStack... bottles) {
        if (ingredient.isEmpty() || covered.isEmpty() && modifiers.isEmpty()) return false;
        boolean includeModifiers = com.otectus.immersivebrewing.config.ServerConfig.get(com.otectus.immersivebrewing.config.ServerConfig.REPLACE_MODIFIERS);
        boolean any = false;
        for (ItemStack bottle : bottles) {
            if (bottle.isEmpty()) continue;
            if (covers(bottle, ingredient.getItem(), includeModifiers)) {
                any = true;
            } else if (BrewingRecipeRegistry.hasOutput(bottle, ingredient)) {
                return false;
            }
        }
        return any;
    }
}
