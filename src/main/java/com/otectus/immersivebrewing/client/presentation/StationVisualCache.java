package com.otectus.immersivebrewing.client.presentation;

import com.otectus.immersivebrewing.blockentity.StationVisual;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Sparse observer samples and an integrated tool pose. No shared BER instance owns a station's angle. */
public final class StationVisualCache {
    private record Key(String dimension, long position) {}
    private static final Map<Key, Entry> ENTRIES = new HashMap<>();
    private static final class Entry {
        UUID incarnation, job;
        StationVisual previous, current;
        long revision;
        double sampleAt, interval = 10, lastFrame, angle = 20, fromVelocity, toVelocity;
        Entry(StationVisual visual, double now) {
            incarnation = visual.incarnation(); job = visual.jobId(); revision = visual.revision();
            previous = current = visual; sampleAt = lastFrame = now;
            fromVelocity = toVelocity = visual.active() ? visual.circulation() * 9 : 0;
        }
        double velocity(double time) {
            if (time - sampleAt > interval * 2) return 0;
            double t = Math.max(0, Math.min(1, (time - sampleAt) / interval));
            return fromVelocity + (toVelocity - fromVelocity) * t;
        }
        double integral(double time) {
            double t = Math.max(0, Math.min(interval * 2, time - sampleAt));
            double ramp = Math.min(t, interval);
            return fromVelocity * ramp + (toVelocity - fromVelocity) * ramp * ramp / (2 * interval)
                    + toVelocity * Math.max(0, t - interval);
        }
        void advance(double now, double motion) {
            if (now > lastFrame && current.active() && motion > 0)
                angle = wrap(angle + (integral(now) - integral(lastFrame)) * motion);
            lastFrame = Math.max(lastFrame, now);
        }
        void accept(StationVisual visual, double now, double motion) {
            if (visual.revision() <= revision) return;
            advance(now, motion);
            double velocity = velocity(now);
            interval = Math.max(5, Math.min(10, now - sampleAt));
            previous = current; current = visual; revision = visual.revision(); sampleAt = lastFrame = now;
            fromVelocity = visual.active() ? velocity : 0;
            toVelocity = visual.active() ? visual.circulation() * 9 : 0;
        }
    }
    private static Entry entry(String dimension, long position, double now, StationVisual visual, double motion) {
        Key key = new Key(dimension, position);
        Entry entry = ENTRIES.get(key);
        if (entry == null || !entry.incarnation.equals(visual.incarnation()) || !entry.job.equals(visual.jobId())) {
            entry = new Entry(visual, now); ENTRIES.put(key, entry);
        } else entry.accept(visual, now, motion);
        return entry;
    }
    public static float paddleAngle(String dimension, long position, long tick, float partial, StationVisual visual, double motion) {
        double now = tick + partial;
        Entry e = entry(dimension, position, now, visual, motion);
        e.advance(now, motion);
        return (float) e.angle;
    }
    public static StationVisual sample(String dimension, long position, long tick, float partial, StationVisual visual, double motion) {
        double now = tick + partial;
        Entry e = entry(dimension, position, now, visual, motion);
        var a = e.previous; var b = e.current;
        float t = b.active() && motion > 0 ? (float) Math.max(0, Math.min(1, (now - e.sampleAt) / e.interval)) : 1;
        var m = b.mortar(); var am = a.mortar();
        var k = b.kettle(); var ak = a.kettle();
        var d = b.alembic(); var ad = a.alembic();
        return new StationVisual(b.phase(), b.signature(), mix(a.heat(), b.heat(), t), mix(a.circulation(), b.circulation(), t),
                mix(a.trouble(), b.trouble(), t), mix(a.progress(), b.progress(), t), b.active(), b.accent(), b.particle(),
                new StationVisual.Mortar(m.mode(), mix(am.processed(), m.processed(), t), mix(am.pressure(), m.pressure(), t),
                        mix(am.clump(), m.clump(), t), m.texture(), mix(am.x(), m.x(), t), mix(am.z(), m.z(), t), m.pressing(), m.workRate(), m.foldCounter()),
                new StationVisual.Kettle(k.draft(), mix(ak.burner(), k.burner(), t), k.additionIndex(), k.additionOutcome(),
                        color(ak.currentColor(), k.currentColor(), t), k.nextColor(), mix(ak.phaseProgress(), k.phaseProgress(), t)),
                new StationVisual.Alembic(d.heatSetting(), d.coolantSetting(), mix(ad.collectedFraction(), d.collectedFraction(), t),
                        d.collecting(), d.condensedRate()), b.context());
    }
    private static float mix(float a, float b, float t) { return a + (b - a) * t; }
    private static int color(int a, int b, float t) {
        return Math.round(mix(a >> 16 & 255, b >> 16 & 255, t)) << 16
                | Math.round(mix(a >> 8 & 255, b >> 8 & 255, t)) << 8 | Math.round(mix(a & 255, b & 255, t));
    }
    private static double wrap(double degrees) { return (degrees % 360 + 360) % 360; }
    public static int size() { return ENTRIES.size(); }
    public static void clear() { ENTRIES.clear(); }
    public static void remove(String dimension, long position) { ENTRIES.remove(new Key(dimension, position)); }
    public static void removeDimension(String dimension) { ENTRIES.keySet().removeIf(key -> key.dimension().equals(dimension)); }
    private StationVisualCache() {}
}
