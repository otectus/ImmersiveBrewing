package com.otectus.immersivebrewing.integration;

import net.minecraftforge.fml.ModList;

import java.util.List;

/**
 * The third-party potion mods Immersive Brewing ships data for (spec 18, "broader mod support"): plans for their
 * registered brewing recipes, reagent profiles, effect policies and Alembic recipes under {@code compat/<modid>/}, each
 * file loaded only with its mod ({@code forge:mod_loaded}). No class of theirs is referenced, so nothing here can load
 * without them; their brewing reaches the Kettle through Forge's brewing registry like any other recipe.
 */
public final class PotionMods {
    /** Apothecary, Potions, Potions And More Potions, PotionsMaster, RPG Potions. */
    public static final List<String> IDS = List.of("apothecary", "morepotions", "potionsmaster", "rpg_potions");

    public static boolean loaded(String id) {
        return ModList.get().isLoaded(id);
    }

    /** The id path prefix of a mod's data, for recipes and definitions alike. */
    public static String prefix(String id) {
        return "compat/" + id + "/";
    }

    private PotionMods() {}
}
