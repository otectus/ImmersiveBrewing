package com.otectus.immersivebrewing.smoketest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure frame-time accumulator used by the dense fixture; timestamps are supplied by the render callback. */
final class FrameSampler {
    private final List<Long> nanos = new ArrayList<>();
    private long start;
    synchronized void begin(long now) { start = now; }
    synchronized void clear() { nanos.clear(); start = 0; }
    synchronized void end(long now) { if (start != 0 && now > start) nanos.add(now - start); start = 0; }
    synchronized int samples() { return nanos.size(); }
    synchronized double percentile(double p) {
        if (nanos.isEmpty()) return Double.NaN;
        List<Long> copy = new ArrayList<>(nanos); Collections.sort(copy);
        return copy.get(Math.min(copy.size()-1, (int)Math.ceil(p * copy.size())-1)) / 1_000_000D;
    }
    double medianMs() { return percentile(.5); }
    double p95Ms() { return percentile(.95); }
    double p99Ms() { return percentile(.99); }
}
