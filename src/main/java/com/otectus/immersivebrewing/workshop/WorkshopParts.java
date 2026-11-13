package com.otectus.immersivebrewing.workshop;

import com.otectus.immersivebrewing.workshop.WorkshopPart.Role;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The interaction volumes of every station, in model pixels for a north-facing block (handoff 4, 5, 15). This table is
 * the single source for client picking, outlines, labels and server reach/obstruction checks. Volumes may extend a
 * few pixels past the block (a tray ledge, a book stand) but never change the station's single-block footprint.
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

    /** The kettle's liquid surface, in pixels: the vessel interior the paddle works over. */
    public static final float KETTLE_CX = 8F, KETTLE_CZ = 7F, KETTLE_HX = 5F, KETTLE_HZ = 4F, KETTLE_LIQUID_MIN = 6F, KETTLE_LIQUID_MAX = 12.6F;
    /** The mortar bowl floor, in pixels. */
    public static final float MORTAR_CX = 8F, MORTAR_CZ = 8F, MORTAR_R = 4F, MORTAR_FLOOR = 5.2F;

    private static final Map<WorkshopKind, List<WorkshopPart>> PARTS = new EnumMap<>(WorkshopKind.class);

    static {
        List<WorkshopPart> k = new ArrayList<>();
        k.add(new WorkshopPart(K_VESSEL, "kettle.vessel", Role.VESSEL, 3, 11, 3, 13, 14.5F, 11));
        k.add(new WorkshopPart(K_PADDLE, "kettle.paddle", Role.TOOL, 11, 13, 1.5F, 14.5F, 21.5F, 5));
        k.add(new WorkshopPart(K_BURNER, "kettle.burner", Role.CONTROL, 10.5F, 0.5F, -1.5F, 14, 4.5F, 1));
        k.add(new WorkshopPart(K_FUEL, "kettle.fuel", Role.SLOT, 4.5F, 0.5F, -1, 8.5F, 4.5F, 1));
        k.add(new WorkshopPart(K_SPOUT, "kettle.spout", Role.SPOUT, 6.5F, 12, 10.5F, 9.5F, 15.5F, 13));
        k.add(new WorkshopPart(K_LID, "kettle.lid", Role.LID, 2, 13.5F, 0, 6.5F, 16.5F, 3));
        k.add(new WorkshopPart(K_BOOK, "kettle.book", Role.BOOK, 15.5F, 3, 3, 19.5F, 9, 9));
        k.add(new WorkshopPart(K_BYPRODUCTS, "kettle.byproducts", Role.SLOT, -3.5F, 0.5F, 4, 0.5F, 5, 10));
        for (int i = 0; i < 6; i++) {
            float c = 1.92F + i * 2.43F;
            k.add(new WorkshopPart(K_RACK + i, "kettle.bottle", Role.PORTION, c - 1.2F, 9, 12.5F, c + 1.2F, 14.5F, 16.5F));
        }
        for (int i = 0; i < MAX_CUPS; i++) {
            int col = i % 4, row = i / 4;
            float x = 2 + col * 3;
            float z = row == 0 ? -1.75F : -4.25F;
            k.add(new WorkshopPart(K_CUP + i, "kettle.cup", Role.DOSE, x, 4.5F, z, x + 3, 7.5F, z + 2.5F));
        }
        for (int i = 0; i < 6; i++) {
            int col = i % 3, row = i / 3;
            float x = 2 + col * 4;
            float z = row == 0 ? -1.75F : -4.25F;
            k.add(new WorkshopPart(K_TRAY + i, "kettle.tray", Role.SLOT, x, 4.5F, z, x + 4, 7.5F, z + 2.5F));
        }
        PARTS.put(WorkshopKind.KETTLE, Collections.unmodifiableList(k));

        List<WorkshopPart> m = new ArrayList<>();
        m.add(new WorkshopPart(M_BOWL, "mortar.bowl", Role.VESSEL, 4, 4.5F, 4, 12, 9.5F, 12));
        m.add(new WorkshopPart(M_PESTLE, "mortar.pestle", Role.TOOL, 11, 8, 11, 14.5F, 17.5F, 14.5F));
        m.add(new WorkshopPart(M_SCRAPER, "mortar.scraper", Role.TOOL, 0.5F, 8, 0.5F, 4.5F, 11.5F, 4.5F));
        m.add(new WorkshopPart(M_CUP, "mortar.cup", Role.DOSE, 12.5F, 0, -2, 16.5F, 4, 2));
        for (int i = 0; i < 4; i++) {
            float x = 0.5F + (i % 2) * 2.6F;
            float z = 11.4F + (i / 2) * 2.2F;
            m.add(new WorkshopPart(M_SHELF + i, "mortar.shelf", Role.SLOT, x - 0.5F, 0, z, x + 2.1F, 3.5F, z + 2.2F));
        }
        PARTS.put(WorkshopKind.MORTAR, Collections.unmodifiableList(m));

        List<WorkshopPart> a = new ArrayList<>();
        a.add(new WorkshopPart(A_CHARGE, "alembic.charge", Role.VESSEL, 4, 14, 6, 8, 17.5F, 10));
        a.add(new WorkshopPart(A_CLAMP, "alembic.clamp", Role.LID, 2, 10, 4.5F, 4.5F, 13.5F, 8.5F));
        a.add(new WorkshopPart(A_BURNER, "alembic.burner", Role.CONTROL, 6, 0.5F, -1, 9.5F, 4, 1.5F));
        a.add(new WorkshopPart(A_FUEL, "alembic.fuel", Role.SLOT, 2.5F, 2.5F, -0.5F, 5.5F, 6.5F, 1.2F));
        a.add(new WorkshopPart(A_COOLANT, "alembic.coolant", Role.SLOT, 12, 12, 5, 15.5F, 15.5F, 9));
        a.add(new WorkshopPart(A_VALVE, "alembic.valve", Role.CONTROL, 12.5F, 8, 3.5F, 15.5F, 11.5F, 6.5F));
        a.add(new WorkshopPart(A_DIVERTER, "alembic.diverter", Role.CONTROL, 9.5F, 9, 8, 12.5F, 12.5F, 11.5F));
        a.add(new WorkshopPart(A_RECEIVER, "alembic.receiver", Role.SLOT, 10.5F, 2.5F, 11.5F, 14.5F, 8.5F, 15.5F));
        a.add(new WorkshopPart(A_WASTE, "alembic.waste", Role.SLOT, 6.5F, 3, 12.5F, 10.5F, 6.5F, 15.5F));
        a.add(new WorkshopPart(A_INPUT, "alembic.input", Role.PORTION, 0.5F, 3, 0, 7.5F, 8, 4.2F));
        PARTS.put(WorkshopKind.ALEMBIC, Collections.unmodifiableList(a));

        List<WorkshopPart> r = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            int col = i % 3, row = i / 3;
            float y0 = row == 0 ? 9 : 2;
            float x0 = col * 16F / 3F + 0.3F;
            r.add(new WorkshopPart(R_POSITION + i, "rack.position", Role.SLOT, x0, y0, 1.5F, x0 + 16F / 3F - 0.6F, y0 + 5, 8.5F));
        }
        r.add(new WorkshopPart(R_CRADLE, "rack.cradle", Role.CRADLE, 4, -0.5F, -0.5F, 12, 2.2F, 2));
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
