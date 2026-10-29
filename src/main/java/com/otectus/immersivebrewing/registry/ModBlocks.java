package com.otectus.immersivebrewing.registry;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.block.AlembicBlock;
import com.otectus.immersivebrewing.block.ApothecaryMortarBlock;
import com.otectus.immersivebrewing.block.ApothecaryRackBlock;
import com.otectus.immersivebrewing.block.BrewersKettleBlock;
import com.otectus.immersivebrewing.block.StationBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModBlocks {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ImmersiveBrewing.MOD_ID);

    public static final RegistryObject<ApothecaryMortarBlock> APOTHECARY_MORTAR = BLOCKS.register("apothecary_mortar",
            () -> new ApothecaryMortarBlock(BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(2.0F, 6.0F)
                    .sound(SoundType.STONE).requiresCorrectToolForDrops().noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<ApothecaryRackBlock> APOTHECARY_RACK = BLOCKS.register("apothecary_rack",
            () -> new ApothecaryRackBlock(BlockBehaviour.Properties.of().mapColor(MapColor.WOOD).strength(2.0F, 3.0F)
                    .sound(SoundType.WOOD).noOcclusion().pushReaction(PushReaction.BLOCK)));

    public static final RegistryObject<BrewersKettleBlock> BREWERS_KETTLE = BLOCKS.register("brewers_kettle",
            () -> new BrewersKettleBlock(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3.5F, 6.0F)
                    .sound(SoundType.METAL).requiresCorrectToolForDrops().noOcclusion().pushReaction(PushReaction.BLOCK)
                    .lightLevel(state -> state.getValue(StationBlock.LIT) ? 9 : 0)));

    public static final RegistryObject<AlembicBlock> ALEMBIC = BLOCKS.register("alembic",
            () -> new AlembicBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_ORANGE).strength(3.0F, 6.0F)
                    .sound(SoundType.COPPER).requiresCorrectToolForDrops().noOcclusion().pushReaction(PushReaction.BLOCK)
                    .lightLevel(state -> state.getValue(StationBlock.LIT) ? 7 : 0)));

    private ModBlocks() {}
}
