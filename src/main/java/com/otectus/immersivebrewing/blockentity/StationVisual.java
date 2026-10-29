package com.otectus.immersivebrewing.blockentity;

import net.minecraft.nbt.CompoundTag;

/**
 * What another player sees of a running job without opening the station (spec 4: world effects follow validated
 * state). Sent at most twice a second; renderers interpolate. Values are 0..1 except circulation (-1..1).
 *
 * @param phase -1 when idle, else the kettle phase ordinal (or 0 for other stations)
 */
public record StationVisual(int phase, int signature, float heat, float circulation, float trouble, float progress, boolean active,
                            int accent,String particle) {
    public StationVisual(int phase,int signature,float heat,float circulation,float trouble,float progress,boolean active) {
        this(phase,signature,heat,circulation,trouble,progress,active,0xFFFFFF,"steam");
    }
    public static final StationVisual IDLE = new StationVisual(-1, 0, 0, 0, 0, 0, false);

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putByte("phase", (byte) phase);
        tag.putByte("sig", (byte) signature);
        tag.putFloat("heat", heat);
        tag.putFloat("circ", circulation);
        tag.putFloat("trouble", trouble);
        tag.putFloat("progress", progress);
        tag.putBoolean("active", active);
        tag.putInt("accent",accent);
        tag.putString("particle",particle);
        return tag;
    }

    public static StationVisual load(CompoundTag tag) {
        return new StationVisual(tag.getByte("phase"), tag.getByte("sig"), clamp(tag.getFloat("heat"), 0, 1),
                clamp(tag.getFloat("circ"), -1, 1), clamp(tag.getFloat("trouble"), 0, 1), clamp(tag.getFloat("progress"), 0, 1),
                tag.getBoolean("active"),tag.contains("accent")?tag.getInt("accent"):0xFFFFFF,
                tag.contains("particle")?tag.getString("particle"):"steam");
    }

    private static float clamp(float v, float min, float max) {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
    }
}
