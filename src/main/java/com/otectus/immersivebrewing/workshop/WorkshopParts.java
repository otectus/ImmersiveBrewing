package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.workshop.WorkshopPart.Role;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The interaction volumes of every station, in model pixels for a north-facing block (handoff 4, 5, 15). This table is
 * the single source for client picking, outlines, labels, the close-up camera's framing bounds and the server's reach
 * and obstruction checks; the block models and renderers are composed around the same numbers. Volumes stay within the
 * block's columns except toward the player (the kettle's bottle rail, the dose cups' ledges) and a paddle handle that
 * rises above it; they never change the station's single-block footprint. A resource pack can restyle the apparatus but
 * never move a usable target.
 */
public final class WorkshopParts {
    // ---- Brewer's Kettle ----
    public static final int K_VESSEL = 0, K_PADDLE = 1, K_BURNER = 2, K_FUEL = 3, K_SPOUT = 4, K_LID = 5, K_BOOK = 6, K_BYPRODUCTS = 7;
    /** Rack bottle positions 0..5 (one per serving). */
    public static final int K_RACK = 10;
    /** Dose cups, one per recipe step 0..7, on the front tray while a batch owns them. */
    public static final int K_CUP = 20;
    public static final int MAX_CUPS = 8;
    /** Tray loading positions 0..5 while no batch owns the tray. */
    public static final int K_TRAY = 30;

    // ---- Apothecary's Mortar ----
    public static final int M_BOWL = 0, M_PESTLE = 1, M_SCRAPER = 2, M_CUP = 3;
    /** Kit shelf positions 0..3. */
    public static final int M_SHELF = 10;

    // ---- Alembic ----
    public static final int A_CHARGE = 0, A_CLAMP = 1, A_BURNER = 2, A_FUEL = 3, A_COOLANT = 4, A_VALVE = 5, A_DIVERTER = 6,
            A_RECEIVER = 7, A_WASTE = 8, A_INPUT = 10;

    // ---- Apothecary Rack ----
    /** Rack positions 0..5. */
    public static final int R_POSITION = 0;
    public static final int R_CRADLE = 6;

    /** Alembic: the purification / reclamation fitting on the charge funnel (handoff 9.2). */
    public static final int A_MODE = 9;

    /**
     * The kettle's open vessel (handoff 4, close-up composition): the interior the paddle works over, in pixels. The near
     * wall is lowered to {@link #KETTLE_NEAR_RIM} so the near side of the liquid stays visible from the working view.
     */
    public static final float KETTLE_CX = 7F, KETTLE_CZ = 9F, KETTLE_HX = 4F, KETTLE_HZ = 5F, KETTLE_LIQUID_MIN = 5.2F, KETTLE_LIQUID_MAX = 9.4F;
    /** Height of the interior floor and of the lowered near rim. */
    public static final float KETTLE_FLOOR = 5F, KETTLE_NEAR_RIM = 10F;
    /** Liquid height added by each charged serving. */
    public static final float KETTLE_PER_SERVING = 0.7F;
    /** The foreground bottle rail: six positions along x, standing at {@link #RAIL_TOP}, centred at {@link #RAIL_Z}. */
    public static final float RAIL_X0 = 2.0F, RAIL_STEP = 2.4F, RAIL_Z = -1.1F, RAIL_TOP = 3.6F;
    /** Where the dose tray's (and the dose cups') first row begins, behind the spout pipe's sightline. */
    public static final float TRAY_Z = 4.3F;
    /** The spout carriage's outlet height over the rail (it slides along x to the bottle being filled). */
    public static final float SPOUT_OUTLET_Y = 9.2F;
    /** The mortar bowl floor, in pixels. */
    public static final float MORTAR_CX = 8F, MORTAR_CZ = 8F, MORTAR_R = 4F, MORTAR_FLOOR = 5.2F;

    /** The x centre of a rail position. */
    public static float railX(int slot) {
        return RAIL_X0 + slot * RAIL_STEP;
    }

    /** The liquid height (pixels) for this many charged servings; never above the near rim. */
    public static float kettleLevel(double servings) {
        return (float) Math.min(KETTLE_LIQUID_MAX, KETTLE_LIQUID_MIN + Math.max(0, servings) * KETTLE_PER_SERVING);
    }

    private static final Map<WorkshopKind, List<WorkshopPart>> PARTS = new EnumMap<>(WorkshopKind.class);

    static {
        // ---- Kettle (north-facing; x 0 is the player's right, z 0 the near side): rail in front, vessel in the middle,
        // dose tray and book stand on the left, paddle rest and by-product basket on the right. ----
        List<WorkshopPart> k = new ArrayList<>();
        k.add(new WorkshopPart(K_VESSEL, "kettle.vessel", Role.VESSEL, 2.7F, 4.5F, 3.5F, 11.5F, 12.5F, 14.5F));
        k.add(new WorkshopPart(K_PADDLE, "kettle.paddle", Role.TOOL, 0F, 9F, 4.5F, 2.6F, 15.5F, 10.5F));
        k.add(new WorkshopPart(K_BURNER, "kettle.burner", Role.CONTROL, 12.2F, 3.8F, 0.4F, 15.8F, 6.4F, 3.2F));
        k.add(new WorkshopPart(K_FUEL, "kettle.fuel", Role.SLOT, 0.4F, 3.8F, 0.4F, 4.6F, 5.8F, 3.0F));
        // The spout is picked by its pipe and carriage only: a larger box would sit, invisible, in front of the fuel hatch
        // and the tray from the close-up camera.
        k.add(new WorkshopPart(K_SPOUT, "kettle.spout", Role.SPOUT, 0.6F, 8.9F, 0.5F, 15.4F, 10.2F, 1.9F));
        k.add(new WorkshopPart(K_LID, "kettle.lid", Role.LID, 2.8F, 9.5F, 14.6F, 8.2F, 15.8F, 16.0F));
        k.add(new WorkshopPart(K_BOOK, "kettle.book", Role.BOOK, 12.2F, 4.4F, 12.1F, 15.8F, 10.4F, 16.0F));
        k.add(new WorkshopPart(K_BYPRODUCTS, "kettle.byproducts", Role.SLOT, 0F, 4F, 11.2F, 2.2F, 8.2F, 15.6F));
        for (int i = 0; i < 6; i++) {
            float c = railX(i);
            k.add(new WorkshopPart(K_RACK + i, "kettle.bottle", Role.PORTION, c - 1.15F, RAIL_TOP, RAIL_Z - 1.3F, c + 1.15F, 8.8F, RAIL_Z + 1.3F));
        }
        for (int i = 0; i < MAX_CUPS; i++) {
            int col = i % 2, row = i / 2;
            float x = 12.35F + col * 1.7F;
            // The first row stands clear of the spout pipe's sightline from the close-up camera.
            float z = TRAY_Z + row * 1.95F;
            k.add(new WorkshopPart(K_CUP + i, "kettle.cup", Role.DOSE, x, 4.4F, z, x + 1.6F, 6.6F, z + 1.9F));
        }
        for (int i = 0; i < 6; i++) {
            int col = i % 2, row = i / 2;
            float x = 12.35F + col * 1.7F;
            float z = TRAY_Z + row * 2.5F;
            k.add(new WorkshopPart(K_TRAY + i, "kettle.tray", Role.SLOT, x, 4.4F, z, x + 1.6F, 6.8F, z + 2.4F));
        }
        PARTS.put(WorkshopKind.KETTLE, Collections.unmodifiableList(k));

        // ---- Mortar: the bowl and its material in the middle, the near wall lowered; pestle rest at the back left,
        // scraper rest at the front right, dose cup on the front ledge, kit shelf at the back right. ----
        List<WorkshopPart> m = new ArrayList<>();
        m.add(new WorkshopPart(M_BOWL, "mortar.bowl", Role.VESSEL, 3.5F, 4.5F, 3.5F, 12.5F, 9.5F, 12.5F));
        m.add(new WorkshopPart(M_PESTLE, "mortar.pestle", Role.TOOL, 11F, 9.6F, 11F, 14.8F, 17.5F, 14.8F));
        m.add(new WorkshopPart(M_SCRAPER, "mortar.scraper", Role.TOOL, 0.4F, 7.6F, 0.6F, 4.4F, 11F, 3.4F));
        m.add(new WorkshopPart(M_CUP, "mortar.cup", Role.DOSE, 12F, 0F, -1.8F, 15.8F, 4F, 1.8F));
        for (int i = 0; i < 4; i++) {
            float x = 0.5F + (i % 2) * 2.6F;
            float z = 11.4F + (i / 2) * 2.2F;
            m.add(new WorkshopPart(M_SHELF + i, "mortar.shelf", Role.SLOT, x - 0.5F, 0, z, x + 2.1F, 3.5F, z + 2.2F));
        }
        PARTS.put(WorkshopKind.MORTAR, Collections.unmodifiableList(m));

        // ---- Alembic: boiler (with a sight window) -> hood and charge funnel -> vapour neck -> the glass condenser jacket
        // at the back -> diverter -> the receiver flask (front left) or the separate waste crock (front centre). Coolant
        // funnel and handwheel on the condenser, burner knob and fuel hatch at the base, the bottles to charge front right. ----
        List<WorkshopPart> a = new ArrayList<>();
        a.add(new WorkshopPart(A_CHARGE, "alembic.charge", Role.VESSEL, 4.4F, 12.2F, 6.6F, 7.4F, 15.4F, 10.2F));
        a.add(new WorkshopPart(A_CLAMP, "alembic.clamp", Role.LID, 1.6F, 9.2F, 6.4F, 3.4F, 12.8F, 10.4F));
        a.add(new WorkshopPart(A_BURNER, "alembic.burner", Role.CONTROL, 7.2F, 3F, 0.6F, 9.8F, 5.2F, 3.4F));
        a.add(new WorkshopPart(A_FUEL, "alembic.fuel", Role.SLOT, 10F, 0.4F, -0.4F, 14F, 3F, 1.2F));
        a.add(new WorkshopPart(A_COOLANT, "alembic.coolant", Role.SLOT, 11.2F, 12F, 10.4F, 14.2F, 14.4F, 13.6F));
        a.add(new WorkshopPart(A_VALVE, "alembic.valve", Role.CONTROL, 14F, 7.2F, 10.6F, 16F, 10.4F, 13.8F));
        a.add(new WorkshopPart(A_DIVERTER, "alembic.diverter", Role.CONTROL, 10.6F, 5.8F, 7.4F, 14F, 8.2F, 9.2F));
        a.add(new WorkshopPart(A_RECEIVER, "alembic.receiver", Role.SLOT, 11.4F, 3F, 3.2F, 15.4F, 7.6F, 7.3F));
        a.add(new WorkshopPart(A_WASTE, "alembic.waste", Role.SLOT, 8.4F, 3F, 6.0F, 11.2F, 5.8F, 9.4F));
        a.add(new WorkshopPart(A_MODE, "alembic.mode", Role.CONTROL, 7.6F, 12.2F, 6.6F, 9.6F, 14.6F, 10.2F));
        a.add(new WorkshopPart(A_INPUT, "alembic.input", Role.PORTION, 0.4F, 3F, 0.4F, 7F, 8.2F, 4.6F));
        PARTS.put(WorkshopKind.ALEMBIC, Collections.unmodifiableList(a));

        List<WorkshopPart> r = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int col = i % 3, row = i / 3;
            float y0 = row == 0 ? 9 : 2;
            float x0 = col * 16F / 3F + 0.3F;
            r.add(new WorkshopPart(R_POSITION + i, "rack.position", Role.SLOT, x0, y0, 1.5F, x0 + 16F / 3F - 0.6F, y0 + 5, 8.5F));
        }
        r.add(new WorkshopPart(R_CRADLE, "rack.cradle", Role.CRADLE, 4, -0.5F, -0.5F, 12, 2.0F, 2));
        PARTS.put(WorkshopKind.RACK, Collections.unmodifiableList(r));
    }

    public static List<WorkshopPart> of(WorkshopKind kind) {
        return PARTS.get(kind);
    }

    /** The part with this id, or null (a forged or stale id names nothing). */
    public static WorkshopPart get(WorkshopKind kind, int id) {
        for (WorkshopPart p : PARTS.get(kind)) if (p.id() == id) return p;
        return null;
    }

    private WorkshopParts() {}
}
