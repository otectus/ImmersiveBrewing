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

    /** The kettle's open interior (1.2.0 close-up model): the liquid's extent and height range, in block units. */
    public static final Bounds KETTLE_BOWL = new Bounds(3F / 16F, 4F / 16F, 11F / 16F, 14F / 16F, 5.2F / 16F, 9.4F / 16F);
    public static final Vec3 KETTLE_RIM = new Vec3(7 / 16D, 10.2 / 16D, 9 / 16D);
    public static final Vec3 KETTLE_VENT = new Vec3(7.5 / 16D, 4.2 / 16D, 2 / 16D);
    public static final Vec3 KETTLE_PADDLE = new Vec3(7 / 16D, 9.2 / 16D, 9 / 16D);
    public static final Vec3 KETTLE_ENTRY = new Vec3(7 / 16D, 9.4 / 16D, 8 / 16D);
    /** The alembic's receiver flask interior, block units (close-up model: the flask stands front left). */
    public static final Bounds ALEMBIC_RECEIVER_FILL = new Bounds(12F / 16F, 3.6F / 16F, 14.8F / 16F, 7F / 16F, 3.2F / 16F, 5.6F / 16F);
    public static final Vec3 ALEMBIC_VAPOR = new Vec3(6.6 / 16D, 14 / 16D, 8.4 / 16D);
    public static final Vec3 ALEMBIC_COIL_INLET = new Vec3(11.2 / 16D, 12.2 / 16D, 10.6 / 16D);
    public static final Vec3 ALEMBIC_DIVERTER = new Vec3(12.2 / 16D, 6.6 / 16D, 8.0 / 16D);
    public static final Vec3 ALEMBIC_OUTPUT = new Vec3(13.3 / 16D, 6.8 / 16D, 5.2 / 16D);
    public static final Vec3 ALEMBIC_CONDENSER = new Vec3(12.2 / 16D, 9.6 / 16D, 12.2 / 16D);
    public static final Vec3 ALEMBIC_RECEIVER = new Vec3(13.4 / 16D, 5.8 / 16D, 5.2 / 16D);
    public static final Vec3 ALEMBIC_WASTE = new Vec3(9.8 / 16D, 5.2 / 16D, 7.7 / 16D);
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
