package com.otectus.immersivebrewing.block;

import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Copper still and hood, cooling coil and a glass receiver with visible condensate (spec 4). */
public class AlembicBlock extends StationBlock {
    private static final StationShape SHAPE = new StationShape(
            new double[] {1, 0, 1, 15, 3, 15},             // brick base
            new double[] {1.6, 3, 5, 8, 9.4, 11.6},        // boiler
            new double[] {2.6, 9.4, 6, 7, 12.4, 10.6},     // hood
            new double[] {6.2, 11.8, 8, 11.6, 14.4, 8.8},  // vapour neck
            new double[] {10.2, 3, 9.4, 14.2, 13.8, 15.2}, // condenser jacket on its stand (back)
            new double[] {11.8, 3, 3.4, 15, 6.6, 7.2},     // receiver flask (front left)
            new double[] {8.6, 3, 6.2, 11, 5.2, 9.2},      // waste crock (front centre)
            new double[] {0.6, 3, 0.6, 7, 3.4, 4.6});      // charge stand

    public AlembicBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected StationShape shape() {
        return SHAPE;
    }

    @Override
    protected BlockEntityType<?> blockEntityType() {
        return ModBlockEntities.ALEMBIC.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new AlembicBlockEntity(pos, state);
    }
}
