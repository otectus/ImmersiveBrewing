package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.BrewersNotebookItem;
import com.otectus.immersivebrewing.item.BrewingPaddleItem;
import com.otectus.immersivebrewing.item.PreparedReagentKitItem;
import com.otectus.immersivebrewing.item.RecoveryBatchItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModItems {
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<BlockItem> APOTHECARY_MORTAR = ITEMS.register("apothecary_mortar",
            () -> new BlockItem(ModBlocks.APOTHECARY_MORTAR.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> APOTHECARY_RACK = ITEMS.register("apothecary_rack",
            () -> new BlockItem(ModBlocks.APOTHECARY_RACK.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> BREWERS_KETTLE = ITEMS.register("brewers_kettle",
            () -> new BlockItem(ModBlocks.BREWERS_KETTLE.get(), new Item.Properties()));
    public static final RegistryObject<BlockItem> ALEMBIC = ITEMS.register("alembic",
            () -> new BlockItem(ModBlocks.ALEMBIC.get(), new Item.Properties()));

    /** Wooden paddle: 64 brews. Paddles never add quality; the iron one only lasts longer. */
    public static final RegistryObject<BrewingPaddleItem> WOODEN_BREWING_PADDLE = ITEMS.register("wooden_brewing_paddle",
            () -> new BrewingPaddleItem(new Item.Properties().durability(64)));
    public static final RegistryObject<BrewingPaddleItem> IRON_BREWING_PADDLE = ITEMS.register("iron_brewing_paddle",
            () -> new BrewingPaddleItem(new Item.Properties().durability(256)));

    public static final RegistryObject<PreparedReagentKitItem> PREPARED_REAGENT_KIT = ITEMS.register("prepared_reagent_kit",
            () -> new PreparedReagentKitItem(new Item.Properties().stacksTo(16)));
    public static final RegistryObject<RecoveryBatchItem> RECOVERY_BATCH = ITEMS.register("recovery_batch",
            () -> new RecoveryBatchItem(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON).fireResistant()));
    public static final RegistryObject<BatchCarrierItem> BATCH_CARRIER = ITEMS.register("batch_carrier",
            () -> new BatchCarrierItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BrewersNotebookItem> BREWERS_NOTEBOOK = ITEMS.register("brewers_notebook",
            () -> new BrewersNotebookItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<Item> SPENT_ALCHEMICAL_SALT = ITEMS.register("spent_alchemical_salt",
            () -> new Item(new Item.Properties()));

    private ModItems() {}
}
