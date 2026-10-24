package com.otectus.immersivebrewing.block;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * Collision, selection and interaction shapes built from the same boxes as the block models (spec 4), authored facing
 * north in pixel units and rotated for the other horizontal facings.
 */
public final class StationShape {
    private final Map<Direction, VoxelShape> shapes = new EnumMap<>(Direction.class);

    /** Each box is {minX, minY, minZ, maxX, maxY, maxZ} in pixels, for a block facing north. */
    public StationShape(double[]... boxes) {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (double[] b : boxes) shape = Shapes.joinUnoptimized(shape, rotate(b, facing), BooleanOp.OR);
            shapes.put(facing, shape.optimize());
        }
    }

    public VoxelShape get(Direction facing) {
        return shapes.getOrDefault(facing, shapes.get(Direction.NORTH));
    }

    private static VoxelShape rotate(double[] b, Direction facing) {
        double x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
        return switch (facing) {
            case SOUTH -> Block.box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            case WEST -> Block.box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            case EAST -> Block.box(16 - z2, y1, x1, 16 - z1, y2, x2);
            default -> Block.box(x1, y1, z1, x2, y2, z2);
        };
    }
}
