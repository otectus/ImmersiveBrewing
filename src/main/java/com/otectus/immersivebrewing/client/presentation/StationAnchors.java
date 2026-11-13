package com.otectus.immersivebrewing.client.presentation;

import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Authoritative model-space anchors for every dynamic station adornment.  Renderers rotate their pose into north
 * space; particles and sounds use {@link #world} so a north-facing coordinate is never accidentally emitted east.
 */
public final class StationAnchors {
    public record Bounds(float minX, float minZ, float maxX, float maxZ, float minY, float maxY) {
        public float clampX(float x) { return Math.max(minX, Math.min(maxX, x)); }
        public float clampZ(float z) { return Math.max(minZ, Math.min(maxZ, z)); }
    }

    /** Kettle bowl after allowing a visible rim; values are block units converted from the authored model. */
    public static final Bounds KETTLE_BOWL = new Bounds(3.15F / 16F, 3.15F / 16F, 12.85F / 16F, 10.85F / 16F, 6.2F / 16F, 12.6F / 16F);
    public static final Vec3 KETTLE_RIM = new Vec3(.5, 13.5 / 16D, 7 / 16D);
    public static final Vec3 KETTLE_VENT = new Vec3(.5, 4 / 16D, 1.05 / 16D);
    public static final Vec3 KETTLE_PADDLE = new Vec3(.5, 14.1 / 16D, 7 / 16D);
    public static final Vec3 KETTLE_ENTRY = new Vec3(.5, 14.2 / 16D, 7 / 16D);
    public static final Bounds ALEMBIC_RECEIVER_FILL = new Bounds(.66F, .58F, .90F, .84F, .20F, .52F);
    public static final Vec3 ALEMBIC_VAPOR = new Vec3(5 / 16D, 13 / 16D, 7 / 16D);
    public static final Vec3 ALEMBIC_COIL_INLET = new Vec3(9 / 16D, 13 / 16D, 8 / 16D);
    public static final Vec3 ALEMBIC_DIVERTER = new Vec3(.70, .68, .56);
    public static final Vec3 ALEMBIC_OUTPUT = new Vec3(.69, .12, .36);
    public static final Vec3 ALEMBIC_CONDENSER = new Vec3(11.5 / 16D, 10 / 16D, 8 / 16D);
    public static final Vec3 ALEMBIC_RECEIVER = new Vec3(12.5 / 16D, 7 / 16D, 11.5 / 16D);
    public static final Vec3 ALEMBIC_WASTE = new Vec3(9.5 / 16D, 4 / 16D, 13.5 / 16D);
    public static final Vec3 MORTAR_CENTER = new Vec3(.5, 5.1 / 16D, .5);

    public static Vec3 world(net.minecraft.core.BlockPos pos, Direction facing, Vec3 local) {
        double x = local.x - .5, z = local.z - .5;
        double rx, rz;
        switch (facing) {
            case SOUTH -> { rx = -x; rz = -z; }
            case EAST -> { rx = -z; rz = x; }
            case WEST -> { rx = z; rz = -x; }
            default -> { rx = x; rz = z; }
        }
        return new Vec3(pos.getX() + .5 + rx, pos.getY() + local.y, pos.getZ() + .5 + rz);
    }

    private StationAnchors() {}
}
