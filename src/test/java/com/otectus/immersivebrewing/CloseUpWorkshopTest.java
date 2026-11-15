package com.otectus.immersivebrewing;

import com.otectus.immersivebrewing.interaction.StationOffsets;
import com.otectus.immersivebrewing.workshop.CameraFit;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.ReactionFamily;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopGeometry;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import com.otectus.immersivebrewing.workshop.WorkshopShots;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Domain tests for the close-up workshop (1.2.0): the camera's framing math, the part table as the single source of
 * picking (every facing round-trips, nothing overlaps by accident), the paddle's posture and the versioned kettle rules,
 * and the neighbour-station offset. No client classes: the framing is the same math the client rig uses.
 */
class CloseUpWorkshopTest {
    private static final BlockPos AT = new BlockPos(10, 64, -7);
    private static final double[] ASPECTS = {4.0 / 3.0, 16.0 / 9.0, 21.0 / 9.0, 1.0};

    // ---- framing ----

    @Test
    void everyFramedPartIsInThePictureAtEveryFacingAndAspect() {
        for (WorkshopKind kind : WorkshopKind.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (double aspect : ASPECTS) {
                    CameraFit.Shot shot = WorkshopShots.working(kind);
                    CameraFit.Pose pose = CameraFit.solve(shot, AT, facing, aspect, 0.75, 2.4, 62);
                    for (Vec3 p : CameraFit.bounds(shot, AT, facing, kind)) {
                        assertTrue(CameraFit.inFrame(pose, p, aspect), kind + " " + facing + " " + aspect + ": " + p + " is off screen");
                    }
                    assertTrue(pose.distance() >= 0.75 - 1e-9 && pose.distance() <= 2.4 + 1e-9, kind + " distance " + pose.distance());
                    assertEquals(0, CameraFit.wrap(pose.yaw() - CameraFit.stationYaw(facing) - shot.yawOffset()), 1e-4, "keyed to the facing, never to north");
                }
            }
        }
    }

    @Test
    void workingPartsClearTheHudAtLargeGuiScales() {
        for (int guiHeight : new int[] {240, 360, 480, 960}) {
            double reserve = Math.max(0.11, 80.0 / guiHeight);
            for (WorkshopKind kind : WorkshopKind.values()) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    for (double aspect : ASPECTS) {
                        CameraFit.Shot shot = WorkshopShots.working(kind);
                        CameraFit.Pose pose = CameraFit.solve(shot, AT, facing, aspect, 0.75, 2.4, 62, reserve);
                        CameraFit.Basis basis = CameraFit.basis(pose.yaw(), pose.pitch());
                        for (Vec3 point : CameraFit.bounds(shot, AT, facing, kind)) {
                            Vec3 delta = point.subtract(pose.position());
                            double screenY = 0.5 - delta.dot(basis.up())
                                    / (2 * Math.tan(Math.toRadians(pose.fov()) / 2) * delta.dot(basis.forward()));
                            String context = kind + " " + facing + " aspect " + aspect + " GUI height " + guiHeight;
                            assertTrue(CameraFit.inFrame(pose, point, aspect), context + ": cropped part");
                            assertTrue(screenY < 1 - reserve, context + ": part overlaps the HUD");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theCameraWorksFromThePlayersSideAboveTheApparatus() {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            CameraFit.Pose pose = CameraFit.solve(WorkshopShots.working(WorkshopKind.KETTLE), AT, facing, 16.0 / 9.0, 0.75, 2.4, 62);
            Vec3 centre = Vec3.atCenterOf(AT);
            Vec3 front = new Vec3(facing.getStepX(), 0, facing.getStepZ());
            assertTrue(pose.position().subtract(centre).dot(front) > 0.2, facing + ": the camera is not on the station's front side");
            assertTrue(pose.position().y > AT.getY() + 0.9, facing + ": the camera is not above the vessel");
            assertTrue(pose.pitch() >= 50 && pose.pitch() <= 65, "the kettle's working angle is 50-65 degrees down: " + pose.pitch());
        }
    }

    @Test
    void aNarrowWindowWidensTheFieldInsteadOfCroppingOrBackingOff() {
        CameraFit.Shot shot = WorkshopShots.working(WorkshopKind.KETTLE);
        CameraFit.Pose wide = CameraFit.solve(shot, AT, Direction.NORTH, 21.0 / 9.0, 0.75, 1.6, 62);
        CameraFit.Pose narrow = CameraFit.solve(shot, AT, Direction.NORTH, 0.8, 0.75, 1.6, 62);
        assertTrue(narrow.fov() >= wide.fov(), "a narrow window should not get a narrower field");
        for (Vec3 p : CameraFit.bounds(shot, AT, Direction.NORTH, WorkshopKind.KETTLE)) assertTrue(CameraFit.inFrame(narrow, p, 0.8));
    }

    @Test
    void theInspectShotIsCloserThanTheWorkingShot() {
        for (WorkshopKind kind : new WorkshopKind[] {WorkshopKind.KETTLE, WorkshopKind.MORTAR, WorkshopKind.ALEMBIC}) {
            CameraFit.Pose working = CameraFit.solve(WorkshopShots.working(kind), AT, Direction.EAST, 16.0 / 9.0, 0.75, 2.4, 62);
            CameraFit.Pose inspect = CameraFit.solve(WorkshopShots.inspect(kind), AT, Direction.EAST, 16.0 / 9.0, 0.5, 2.4, 62);
            assertTrue(inspect.distance() < working.distance(), kind + ": inspect " + inspect.distance() + " vs working " + working.distance());
        }
    }

    @Test
    void theBasisMatchesMinecraftsLookConvention() {
        CameraFit.Basis south = CameraFit.basis(0, 0);
        assertEquals(1, south.forward().z, 1e-9);
        assertEquals(-1, south.right().x, 1e-9, "looking south, the right hand is west");
        CameraFit.Basis down = CameraFit.basis(90, 60);
        assertTrue(down.forward().y < -0.8 && down.forward().x < 0, "yaw 90 looks west, pitch 60 looks down");
        assertEquals(0, down.forward().dot(down.right()), 1e-9);
        assertEquals(0, down.forward().dot(down.up()), 1e-9);
    }

    // ---- the part table: picking agrees at every facing, and parts do not overlap by accident ----

    @Test
    void everyPartIsPickedThroughItsCentreAtEveryFacing() {
        for (WorkshopKind kind : WorkshopKind.values()) {
            for (Direction facing : Direction.Plane.HORIZONTAL) {
                for (WorkshopPart part : WorkshopParts.of(kind)) {
                    Vec3 centre = WorkshopGeometry.center(AT, facing, part);
                    Vec3 back = WorkshopGeometry.toModel(AT, facing, centre);
                    assertEquals(part.cx(), back.x, 1e-6);
                    assertEquals(part.cy(), back.y, 1e-6);
                    assertEquals(part.cz(), back.z, 1e-6);
                    // A ray from straight above the part, down through its centre, picks exactly it (or a part above it).
                    Vec3 origin = centre.add(0, 3, 0);
                    Optional<WorkshopGeometry.Hit> hit = WorkshopGeometry.pick(List.of(part), AT, facing, origin, new Vec3(0, -1, 0), 8, p -> true);
                    assertTrue(hit.isPresent() && hit.get().part().id() == part.id(), kind + " " + facing + ": " + part.name() + " not picked");
                }
            }
        }
    }

    @Test
    void partsDoNotOverlapExceptAlternativesNeverShownTogether() {
        for (WorkshopKind kind : WorkshopKind.values()) {
            List<WorkshopPart> parts = WorkshopParts.of(kind);
            for (int i = 0; i < parts.size(); i++) {
                for (int j = i + 1; j < parts.size(); j++) {
                    WorkshopPart a = parts.get(i), b = parts.get(j);
                    boolean overlap = a.x0() < b.x1() - 1e-4 && b.x0() < a.x1() - 1e-4 && a.y0() < b.y1() - 1e-4 && b.y0() < a.y1() - 1e-4
                            && a.z0() < b.z1() - 1e-4 && b.z0() < a.z1() - 1e-4;
                    if (!overlap) continue;
                    // The kettle's loading positions and its dose cups share the tray: one or the other is present.
                    boolean trayVsCup = kind == WorkshopKind.KETTLE && (isTray(a) && isCup(b) || isCup(a) && isTray(b));
                    assertTrue(trayVsCup, kind + ": " + a.name() + " " + a.id() + " overlaps " + b.name() + " " + b.id());
                }
            }
        }
    }

    private static boolean isTray(WorkshopPart p) {
        return p.id() >= WorkshopParts.K_TRAY && p.id() < WorkshopParts.K_TRAY + 6;
    }

    private static boolean isCup(WorkshopPart p) {
        return p.id() >= WorkshopParts.K_CUP && p.id() < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS;
    }

    @Test
    void theLiquidStaysBelowTheLoweredNearRim() {
        for (int s = 0; s <= 6; s++) assertTrue(WorkshopParts.kettleLevel(s) <= WorkshopParts.KETTLE_NEAR_RIM - 0.5, s + " servings overflow the rim");
        assertTrue(WorkshopParts.kettleLevel(3) > WorkshopParts.kettleLevel(1));
    }

    // ---- posture ----

    @Test
    void postureOnlyQualifiesCreditedMovement() {
        ToolTrack still = new ToolTrack(WorkshopParts.KETTLE_HX, WorkshopParts.KETTLE_HZ);
        double total = 0;
        for (int i = 0; i < 40; i++) {
            still.sample(0.2, 0.1, true, true, i);
            total += still.take(i);
        }
        assertEquals(0, total, 1e-9, "pressing down without moving earns nothing");
        assertEquals(0, still.depth(), 1e-9);
        ToolTrack sweep = new ToolTrack(WorkshopParts.KETTLE_HX, WorkshopParts.KETTLE_HZ);
        for (int i = 0; i < 60; i++) {
            sweep.sample(Math.cos(i * 0.3) * 0.6, Math.sin(i * 0.3) * 0.6, true, true, i);
            sweep.take(i);
        }
        assertTrue(sweep.depth() > 0.9, "sweeping along the bottom: " + sweep.depth());
        assertTrue(sweep.deep());
    }

    private static KettleProcess.Setup setup(ReactionFamily family) {
        return new KettleProcess.Setup(family, family.idealLow, family.idealHigh, family.idealLow - 0.1, family.idealHigh + 0.1, 1.0, 9, 3,
                new double[] {0.05}, new double[] {family.kick}, "test", 1);
    }

    /** Runs a batch to the end of its reaction with a fixed stir and posture; returns the trouble left at the end. */
    private static double react(KettleProcess p, double depth) {
        p.pour(0);
        p.setBurner((int) Math.round((p.setup().idealLow() + p.setup().idealHigh()) / 2 * KettleProcess.BURNER_STEPS));
        double sum = 0;
        int n = 0;
        for (int t = 0; t < 1200 && p.stage() != KettleProcess.Stage.RESTING; t++) {
            if (p.stage() == KettleProcess.Stage.HEATING && p.temperature() >= p.setup().workLow()) p.dose(0);
            boolean reacting = p.stage() == KettleProcess.Stage.REACTING;
            p.tick(reacting ? 0.7 : 0, 0.55, depth);
            if (reacting) {
                sum += p.mixTrouble();
                n++;
            }
        }
        return n == 0 ? 1 : sum / n;
    }

    @Test
    void sweepingTheBottomClearsSedimentOnlyUnderVersionTwoRules() {
        double surface = react(KettleProcess.begin(setup(ReactionFamily.DENSE_SEDIMENT), new int[] {0}, UUID.randomUUID()), 0);
        double bottom = react(KettleProcess.begin(setup(ReactionFamily.DENSE_SEDIMENT), new int[] {0}, UUID.randomUUID()), 1);
        assertTrue(bottom < surface * 0.85, "sweeping the bottom should lift sediment faster: " + bottom + " vs " + surface);
        double plain = react(KettleProcess.begin(setup(ReactionFamily.GENERIC), new int[] {0}, UUID.randomUUID()), 0);
        double plainDeep = react(KettleProcess.begin(setup(ReactionFamily.GENERIC), new int[] {0}, UUID.randomUUID()), 1);
        assertEquals(plain, plainDeep, 1e-12, "a family whose cue names no posture ignores it");
    }

    @Test
    void aVersionOneBatchKeepsItsVersionAndItsRules() {
        KettleProcess fresh = KettleProcess.begin(setup(ReactionFamily.DENSE_SEDIMENT), new int[] {0}, UUID.randomUUID());
        assertEquals(KettleProcess.VERSION, fresh.rules());
        CompoundTag saved = fresh.save();
        saved.putInt("version", 1);
        KettleProcess old = KettleProcess.load(saved);
        assertEquals(1, old.rules());
        assertEquals(1, old.save().getInt("version"), "a version 1 batch is never rewritten as version 2");
        KettleProcess oldDeep = KettleProcess.load(saved.copy());
        assertEquals(react(old, 0), react(oldDeep, 1), 1e-12, "version 1 ignores the posture");
        // Warm and dose a fresh version 1 batch: the sediment cue it shows asks for broad stirring, not for the bottom.
        KettleProcess cue = KettleProcess.load(saved.copy());
        cue.pour(0);
        cue.setBurner(KettleProcess.BURNER_STEPS / 2 + 1);
        for (int t = 0; t < 2000 && cue.temperature() < cue.setup().workLow(); t++) cue.tick(0, 0, 0);
        cue.dose(0);
        assertEquals("cue.immersive_brewing.trouble.dense_sediment.broad", cue.cueKey(), "version 1 never asks for the bottom to be swept");
        KettleProcess cue2 = KettleProcess.begin(setup(ReactionFamily.DENSE_SEDIMENT), new int[] {0}, UUID.randomUUID());
        cue2.pour(0);
        cue2.setBurner(KettleProcess.BURNER_STEPS / 2 + 1);
        for (int t = 0; t < 2000 && cue2.temperature() < cue2.setup().workLow(); t++) cue2.tick(0, 0, 0);
        cue2.dose(0);
        assertEquals("cue.immersive_brewing.trouble.dense_sediment", cue2.cueKey(), "version 2 asks for the bottom to be swept");
        CompoundTag future = fresh.save();
        future.putInt("version", KettleProcess.VERSION + 1);
        boolean refused = false;
        try {
            KettleProcess.load(future);
        } catch (IllegalArgumentException e) {
            refused = true;
        }
        assertTrue(refused, "a newer process is held, never partly read");
    }

    // ---- neighbour stations ----

    @Test
    void neighbourOffsetsRoundTripAndStayLocal() {
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos to = AT.offset(dx, dy, dz);
                    int packed = StationOffsets.pack(AT, to);
                    assertTrue(packed >= 0 && packed <= 4096);
                    assertEquals(to, StationOffsets.unpack(AT, packed));
                }
            }
        }
        assertEquals(-1, StationOffsets.pack(AT, AT.offset(4, 0, 0)));
        assertNull(StationOffsets.unpack(AT, 343));
        assertNull(StationOffsets.unpack(AT, -1));
        assertNotNull(StationOffsets.unpack(AT, 0));
        assertEquals(AT, StationOffsets.unpack(AT, 171), "the middle of the cube is the station itself");
        assertFalse(StationOffsets.unpack(AT, 172).equals(AT));
    }
}
