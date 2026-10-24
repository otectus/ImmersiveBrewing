package com.otectus.immersivebrewing.minigame.distillation;

/**
 * One step of Alembic input: heat valve and coolant valve positions (0..4 each) and the receiver routing
 * (true collects into the receiver, false sends condensate to the waste channel).
 */
public record DistillationInput(int heat, int coolant, boolean collect) {
    public static final int LEVELS = 5;

    public DistillationInput {
        heat = Math.max(0, Math.min(LEVELS - 1, heat));
        coolant = Math.max(0, Math.min(LEVELS - 1, coolant));
    }

    public int pack() {
        return heat | coolant << 3 | (collect ? 1 << 6 : 0);
    }

    public static DistillationInput unpack(int packed) {
        return new DistillationInput(packed & 0x7, packed >> 3 & 0x7, (packed & 1 << 6) != 0);
    }
}
