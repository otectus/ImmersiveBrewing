package com.otectus.immersivebrewing.minigame;

import java.util.Arrays;

/**
 * Positional, versioned encoding of simulation parameters and state as a {@code long[]}, so the pure simulations stay
 * free of Minecraft types. The station ledger stores the array as NBT and packets send it as a long array; both ends
 * read it back field by field in the same order. Doubles are stored as their exact bits, so a sealed state resumes
 * bit-identically.
 */
public final class SimData {
    /** Upper bound on an encoded array, checked on every decode (spec 21.3: bounded profile and state sizes). */
    public static final int MAX_LENGTH = 4096;

    public static final class Writer {
        private long[] data = new long[32];
        private int size;

        public Writer putLong(long value) {
            if (size == data.length) data = Arrays.copyOf(data, size * 2);
            data[size++] = value;
            return this;
        }

        public Writer putInt(int value) {
            return putLong(value);
        }

        public Writer putBool(boolean value) {
            return putLong(value ? 1 : 0);
        }

        public Writer putDouble(double value) {
            return putLong(Double.doubleToRawLongBits(value));
        }

        public long[] toArray() {
            if (size > MAX_LENGTH) throw new IllegalStateException("simulation data too large: " + size);
            return Arrays.copyOf(data, size);
        }
    }

    public static final class Reader {
        private final long[] data;
        private int index;

        public Reader(long[] data) {
            if (data == null || data.length > MAX_LENGTH) throw new IllegalArgumentException("simulation data missing or too large");
            this.data = data;
        }

        public long getLong() {
            if (index >= data.length) throw new IllegalArgumentException("simulation data truncated at field " + index);
            return data[index++];
        }

        public int getInt() {
            long v = getLong();
            if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw new IllegalArgumentException("field " + (index - 1) + " out of int range");
            return (int) v;
        }

        /** An int that must lie in {@code [min, max]}. */
        public int getInt(int min, int max) {
            int v = getInt();
            if (v < min || v > max) throw new IllegalArgumentException("field " + (index - 1) + " = " + v + " outside " + min + ".." + max);
            return v;
        }

        public boolean getBool() {
            return getLong() != 0;
        }

        /** A finite double; NaN and infinities are rejected so corrupt data can not poison a simulation. */
        public double getDouble() {
            double v = Double.longBitsToDouble(getLong());
            if (!Double.isFinite(v)) throw new IllegalArgumentException("field " + (index - 1) + " is not finite");
            return v;
        }

        public double getDouble(double min, double max) {
            double v = getDouble();
            if (v < min || v > max) throw new IllegalArgumentException("field " + (index - 1) + " = " + v + " outside " + min + ".." + max);
            return v;
        }

        public boolean exhausted() {
            return index >= data.length;
        }
    }

    private SimData() {}
}
