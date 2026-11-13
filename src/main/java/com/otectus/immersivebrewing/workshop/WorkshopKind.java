package com.otectus.immersivebrewing.workshop;

/** The four hands-on stations. The ordinal travels in packets; append new kinds only. */
public enum WorkshopKind {
    KETTLE, MORTAR, ALEMBIC, RACK;

    public static WorkshopKind byId(int id) {
        WorkshopKind[] all = values();
        return id >= 0 && id < all.length ? all[id] : null;
    }
}
