package com.otectus.immersivebrewing.block;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Iron vessel with a copper rim over an enclosed blaze burner, draft vent at the front and a bottle rack behind (spec 4). */
public class BrewersKettleBlock extends StationBlock {
    private static final StationShape SHAPE = new StationShape(
            new double[] {1, 0, 1, 15, 5, 15},     // burner housing
            new double[] {2, 5, 2, 14, 13, 14},    // vessel
            new double[] {1, 13, 1, 15, 14, 15},   // copper rim
            new double[] {0, 0, 13, 16, 9, 16});   // bottle rack at the back

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
