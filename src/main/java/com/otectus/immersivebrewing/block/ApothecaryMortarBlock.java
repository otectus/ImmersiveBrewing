package com.otectus.immersivebrewing.block;

import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Stone bowl on a small wooden base; the pestle lives in it (spec 4). */
public class ApothecaryMortarBlock extends StationBlock {
    private static final StationShape SHAPE = new StationShape(
            new double[] {3, 0, 3, 13, 3, 13},     // wooden base
            new double[] {2, 3, 2, 14, 9, 14},     // stone bowl
            new double[] {9, 9, 5, 11, 15, 7});    // pestle handle

    public ApothecaryMortarBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected StationShape shape() {
        return SHAPE;
    }

    @Override
    protected BlockEntityType<?> blockEntityType() {
        return ModBlockEntities.APOTHECARY_MORTAR.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ApothecaryMortarBlockEntity(pos, state);
    }
}
