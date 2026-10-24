package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

public final class ModTags {
    /** Tools the Kettle accepts in its paddle slot. */
    public static final TagKey<Item> BREWING_PADDLES = item("brewing_paddles");
    /** Fuel for the Kettle's and Alembic's enclosed burners (blaze powder). */
    public static final TagKey<Item> BURNER_FUEL = item("burner_fuel");
    /** Never stored in a Batch Carrier or a station: inventory-bearing and ledger-bearing items. */
    public static final TagKey<Item> CARRIER_EXCLUDED = item("carrier_excluded");
    /** Extra potion containers a Batch Carrier accepts besides vanilla potion items. */
    public static final TagKey<Item> CARRIER_STORABLE = item("carrier_storable");

    private static TagKey<Item> item(String name) {
        return TagKey.create(Registries.ITEM, ImmersiveBrewing.id(name));
    }

    private ModTags() {}
}
