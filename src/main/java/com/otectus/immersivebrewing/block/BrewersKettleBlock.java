package com.otectus.immersivebrewing.block;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * An open copper pot with a lowered near rim over an iron firebox (1.2.0 close-up composition): the bottle rail in front
 * (outside the collision shape, like a shelf ledge), the spout carriage's pipe along the near side, the dose tray and
 * book stand on the left and the by-product basket on the right.
 */
public class BrewersKettleBlock extends StationBlock {
    private static final StationShape SHAPE = new StationShape(
            new double[] {0.6, 0, 0.8, 15.4, 4, 15.4},    // firebox
            new double[] {2, 4, 3, 12, 12.5, 15},         // vessel
            new double[] {0.6, 4, 0.8, 15.4, 9.9, 1.6},   // spout pipe and posts
            new double[] {12.2, 4, 4.1, 15.8, 9, 15.9},   // dose tray and book stand
            new double[] {0.2, 4, 11.4, 2.2, 6.6, 15.4}); // by-product basket

    public BrewersKettleBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected StationShape shape() {
        return SHAPE;
    }

    @Override
    protected BlockEntityType<?> blockEntityType() {
        return ModBlockEntities.BREWERS_KETTLE.get();
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BrewersKettleBlockEntity(pos, state);
    }
}
