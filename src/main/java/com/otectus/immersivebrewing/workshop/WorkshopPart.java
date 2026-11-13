package com.otectus.immersivebrewing.workshop;

/**
 * One interactive part of a station: a stable id (it travels in packets), a role, and a logical interaction volume in
 * block-model pixels for a station facing north (front on the -Z side, 16 = one block). The volume is server-defined:
 * the client draws its outline from the same table, and a resource pack can restyle the model but never move the
 * usable target. Thin handles get deliberately larger volumes than their models.
 */
public record WorkshopPart(int id, String name, Role role, float x0, float y0, float z0, float x1, float y1, float z1) {
    public enum Role { VESSEL, TOOL, CONTROL, SLOT, PORTION, DOSE, SPOUT, LID, BOOK, CRADLE }

    public WorkshopPart {
        if (x1 < x0 || y1 < y0 || z1 < z0) throw new IllegalArgumentException("inverted part volume " + name);
    }

    public float cx() {
        return (x0 + x1) / 2F;
    }

    public float cy() {
        return (y0 + y1) / 2F;
    }

    public float cz() {
        return (z0 + z1) / 2F;
    }

    /** The short label's translation key. */
    public String key() {
        return "part.immersive_brewing." + name;
    }
}
