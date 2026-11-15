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
            new double[] {3, 0, 3, 13, 3, 13},          // wooden base
            new double[] {2.3, 3, 2.3, 13.7, 9, 13.7},  // stone bowl (near wall lowered in the model)
            new double[] {11.6, 0, 0, 16, 0.8, 2},      // dose cup ledge
            new double[] {0, 0, 11.2, 5.4, 0.8, 16});   // kit shelf

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
