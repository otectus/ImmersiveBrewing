package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ImmersiveBrewing.MOD_ID);

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<ApothecaryMortarBlockEntity>> APOTHECARY_MORTAR = BLOCK_ENTITIES.register("apothecary_mortar",
            () -> BlockEntityType.Builder.of(ApothecaryMortarBlockEntity::new, ModBlocks.APOTHECARY_MORTAR.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<ApothecaryRackBlockEntity>> APOTHECARY_RACK = BLOCK_ENTITIES.register("apothecary_rack",
            () -> BlockEntityType.Builder.of(ApothecaryRackBlockEntity::new, ModBlocks.APOTHECARY_RACK.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<BrewersKettleBlockEntity>> BREWERS_KETTLE = BLOCK_ENTITIES.register("brewers_kettle",
            () -> BlockEntityType.Builder.of(BrewersKettleBlockEntity::new, ModBlocks.BREWERS_KETTLE.get()).build(null));

    @SuppressWarnings("DataFlowIssue")
    public static final RegistryObject<BlockEntityType<AlembicBlockEntity>> ALEMBIC = BLOCK_ENTITIES.register("alembic",
            () -> BlockEntityType.Builder.of(AlembicBlockEntity::new, ModBlocks.ALEMBIC.get()).build(null));

    private ModBlockEntities() {}
}
