package com.otectus.immersivebrewing.workshop;

import net.minecraft.world.phys.Vec3;

import java.util.stream.IntStream;

/**
 * The authored compositions of the working view (handoff 5.2), in a north-facing station's model pixels: the stable
 * working shot every normal action is done from, and an optional closer inspect shot of the material. The parts listed
 * are the ones that must stay readable and clickable; the camera is fitted to them, never to the block's centre.
 * Presentation profiles may adjust the angles within bounds; a resource pack can never move a part.
 */
public final class WorkshopShots {
    private static final int[] KETTLE_PARTS = IntStream.concat(IntStream.of(WorkshopParts.K_VESSEL, WorkshopParts.K_PADDLE, WorkshopParts.K_BURNER,
            WorkshopParts.K_FUEL, WorkshopParts.K_SPOUT, WorkshopParts.K_BOOK, WorkshopParts.K_BYPRODUCTS),
            IntStream.concat(IntStream.range(WorkshopParts.K_RACK, WorkshopParts.K_RACK + 6),
                    IntStream.range(WorkshopParts.K_CUP, WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS))).toArray();
    private static final int[] MORTAR_PARTS = IntStream.concat(IntStream.of(WorkshopParts.M_BOWL, WorkshopParts.M_PESTLE, WorkshopParts.M_SCRAPER,
            WorkshopParts.M_CUP), IntStream.range(WorkshopParts.M_SHELF, WorkshopParts.M_SHELF + 4)).toArray();
    private static final int[] ALEMBIC_PARTS = {WorkshopParts.A_CHARGE, WorkshopParts.A_CLAMP, WorkshopParts.A_BURNER, WorkshopParts.A_FUEL,
            WorkshopParts.A_COOLANT, WorkshopParts.A_VALVE, WorkshopParts.A_DIVERTER, WorkshopParts.A_RECEIVER, WorkshopParts.A_WASTE,
            WorkshopParts.A_MODE, WorkshopParts.A_INPUT};
    private static final int[] RACK_PARTS = IntStream.rangeClosed(WorkshopParts.R_POSITION, WorkshopParts.R_CRADLE).toArray();

    /** The working shot: everything the station's normal work needs, from the player's side. */
    public static CameraFit.Shot working(WorkshopKind kind) {
        return switch (kind) {
            case KETTLE -> new CameraFit.Shot(kind, new Vec3(8, 7, 6.5), 60, 0, 44, KETTLE_PARTS, 0.05);
            case MORTAR -> new CameraFit.Shot(kind, new Vec3(8, 6, 7), 63, 0, 42, MORTAR_PARTS, 0.06);
            case ALEMBIC -> new CameraFit.Shot(kind, new Vec3(8, 7.5, 7.5), 50, 0, 46, ALEMBIC_PARTS, 0.05);
            case RACK -> new CameraFit.Shot(kind, new Vec3(8, 8, 4.5), 16, 0, 44, RACK_PARTS, 0.06);
        };
    }

    /** The inspect shot: a closer look at the material (the liquid, the bowl, the condensate and receiver). */
    public static CameraFit.Shot inspect(WorkshopKind kind) {
        return switch (kind) {
            case KETTLE -> new CameraFit.Shot(kind, new Vec3(7, 7.5, 9), 68, 0, 40, new int[] {WorkshopParts.K_VESSEL}, 0.02);
            case MORTAR -> new CameraFit.Shot(kind, new Vec3(8, 5.5, 8), 74, 0, 38, new int[] {WorkshopParts.M_BOWL}, 0.03);
            case ALEMBIC -> new CameraFit.Shot(kind, new Vec3(12, 6, 6.5), 42, 0, 40,
                    new int[] {WorkshopParts.A_DIVERTER, WorkshopParts.A_RECEIVER, WorkshopParts.A_WASTE, WorkshopParts.A_VALVE}, 0.05);
            case RACK -> new CameraFit.Shot(kind, new Vec3(8, 8, 4.5), 14, 0, 38, RACK_PARTS, 0.02);
        };
    }

    private WorkshopShots() {}
}
