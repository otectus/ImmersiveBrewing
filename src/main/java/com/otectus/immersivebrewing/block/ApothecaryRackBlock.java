package com.otectus.immersivebrewing.block;

import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.Nullable;

/** Six-slot tabletop/wall-style potion display with direct target-slot interaction and an accessible inventory screen. */
public class ApothecaryRackBlock extends BaseEntityBlock {
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    private static final StationShape SHAPE = new StationShape(
            new double[] {0, 0, 2, 16, 2, 8}, new double[] {0, 7, 2, 16, 9, 8}, new double[] {0, 14, 2, 16, 16, 8},
            new double[] {0, 0, 7, 1, 16, 9}, new double[] {15, 0, 7, 16, 16, 9});

    public ApothecaryRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING); }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override @SuppressWarnings("deprecation") public BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override @SuppressWarnings("deprecation") public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE.get(state.getValue(FACING));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ApothecaryRackBlockEntity(pos, state); }

    @Override
    @SuppressWarnings("deprecation")
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof ApothecaryRackBlockEntity rack)) return InteractionResult.PASS;
        ItemStack held = player.getItemInHand(hand);
        if (held.getItem() instanceof BatchCarrierItem) {
            if (!level.isClientSide) rack.exchangeWithCarrier(player, held);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        int target = targetSlot(state, pos, hit);
        if (CarrierContents.accepts(held)) {
            if (!level.isClientSide) {
                ItemStack left = rack.inventory().insertItem(target, held, false);
                player.setItemInHand(hand, left);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (held.isEmpty() && player.isShiftKeyDown()) {
            if (!level.isClientSide) {
                ItemStack taken = rack.inventory().extractItem(target, Integer.MAX_VALUE, false);
                if (!taken.isEmpty() && !player.addItem(taken)) Containers.dropItemStack(level, player.getX(), player.getY(), player.getZ(), taken);
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            NetworkHooks.openScreen(sp, rack, buf -> buf.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    private static int targetSlot(BlockState state, BlockPos pos, BlockHitResult hit) {
        double x = hit.getLocation().x - pos.getX();
        double z = hit.getLocation().z - pos.getZ();
        double across = switch (state.getValue(FACING)) {
            case SOUTH -> 1.0 - x;
            case WEST -> z;
            case EAST -> 1.0 - z;
            default -> x;
        };
        int column = Math.max(0, Math.min(2, (int) (across * 3.0)));
        double y = hit.getLocation().y - pos.getY();
        int row = y >= 0.5 ? 0 : 1;
        return row * 3 + column;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof ApothecaryRackBlockEntity rack) rack.dropContentsOnce();
        super.onRemove(state, level, pos, replacement, moved);
    }

    @Override @SuppressWarnings("deprecation") public boolean hasAnalogOutputSignal(BlockState state) { return true; }
    @Override @SuppressWarnings("deprecation") public int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ApothecaryRackBlockEntity rack ? rack.comparatorSignal() : 0;
    }
}
