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
            new double[] {1, 0, 1, 15, 3, 15},     // brick and iron base
            new double[] {2, 3, 4, 10, 10, 12},    // copper still
            new double[] {4, 10, 6, 8, 15, 10},    // hood
            new double[] {10, 3, 9, 15, 9, 14});   // glass receiver

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
