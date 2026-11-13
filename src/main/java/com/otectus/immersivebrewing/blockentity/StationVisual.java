package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.batch.Operation;
import io.netty.buffer.Unpooled;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.UUID;

/** Bounded presentation only. No inventory, recipe authority, score or persistent renderer state. */
public record StationVisual(int phase, int signature, float heat, float circulation, float trouble, float progress,
                            boolean active, int accent, String particle, Mortar mortar, Kettle kettle, Alembic alembic,
                            Context context) {
    public static final int SCHEMA = 1;
    public static final UUID NONE = new UUID(0, 0);
    public enum Activity { IDLE, COUNT_IN, WORKING, HELP_PAUSED, RESUME_COUNT_IN, SEALED, RECOVERY, OUTPUT_READY }
    public record Mortar(int mode, float processed, float pressure, float clump, int texture, float x, float z,
                         boolean pressing, float workRate, int foldCounter) {
        public static final Mortar EMPTY = new Mortar(0, 0, 0, 0, 0, 0, 0, false, 0, 0);
    }
    public record Kettle(int draft, float burner, int additionIndex, int additionOutcome, int currentColor,
                         int nextColor, float phaseProgress) {
        public static final Kettle EMPTY = new Kettle(0, 0, 0, 0, 0x385DC6, 0x385DC6, 0);
        public Kettle colors(int current, int next) {
            return new Kettle(draft, burner, additionIndex, additionOutcome, current, next, phaseProgress);
        }
    }
    public record Alembic(int heatSetting, int coolantSetting, float collectedFraction, boolean collecting,
                          float condensedRate) {
        public static final Alembic EMPTY = new Alembic(0, 0, 0, false, 0);
    }
    public record Context(UUID incarnation, UUID jobId, long revision, long sampleTime, int simulationStep,
                          Operation operation, Activity activity, UUID controller, int servings) {
        public static final Context EMPTY = new Context(NONE, NONE, 0, 0, 0, null, Activity.IDLE, NONE, 0);
    }
    public StationVisual(int phase, int signature, float heat, float circulation, float trouble, float progress, boolean active) {
        this(phase, signature, heat, circulation, trouble, progress, active, 0xFFFFFF, "steam");
    }
    public StationVisual(int phase, int signature, float heat, float circulation, float trouble, float progress,
                         boolean active, int accent, String particle) {
        this(phase, signature, heat, circulation, trouble, progress, active, accent, particle,
                Mortar.EMPTY, Kettle.EMPTY, Alembic.EMPTY, Context.EMPTY);
    }
    public static final StationVisual IDLE = new StationVisual(-1, 0, 0, 0, 0, 0, false);
    public UUID incarnation() { return context.incarnation(); }
    public UUID jobId() { return context.jobId(); }
    public long revision() { return context.revision(); }
    public long sampleTime() { return context.sampleTime(); }
    public int simulationStep() { return context.simulationStep(); }
    public Operation operation() { return context.operation(); }
    public Activity activity() { return context.activity(); }
    public UUID controller() { return context.controller(); }
    public int servings() { return context.servings(); }

    public StationVisual details(Mortar m, Kettle k, Alembic a, int step, Operation operation) {
        return new StationVisual(phase, signature, heat, circulation, trouble, progress, active, accent, particle, m, k, a,
                new Context(incarnation(), jobId(), revision(), sampleTime(), step, operation,
                        active ? Activity.WORKING : Activity.IDLE, controller(), servings()));
    }
    public StationVisual withContext(Context c) {
        return new StationVisual(phase, signature, heat, circulation, trouble, progress, c.activity() == Activity.WORKING,
                accent, particle, mortar, kettle, alembic, c);
    }
    public StationVisual withActivity(Activity activity) {
        return withContext(new Context(incarnation(), jobId(), revision(), sampleTime(), simulationStep(), operation(), activity, controller(), servings()));
    }
    public StationVisual appearance(int accent, String particle, int currentColor, int nextColor) {
        return new StationVisual(phase, signature, heat, circulation, trouble, progress, active, accent, particle,
                mortar, kettle.colors(currentColor, nextColor), alembic, context);
    }

    /** Fixed-size operation payload; descriptors travel only in the baseline StationView. */
    public void write(FriendlyByteBuf b) {
        b.writeByte(SCHEMA);
        b.writeUUID(incarnation()); b.writeUUID(jobId()); b.writeVarLong(revision()); b.writeVarLong(sampleTime());
        b.writeVarInt(simulationStep()); b.writeByte(operation() == null ? -1 : operation().ordinal());
        b.writeEnum(activity()); b.writeUUID(controller()); b.writeByte(servings());
        b.writeByte(phase); b.writeByte(signature); unit(b, heat); signed(b, circulation); unit(b, trouble); unit(b, progress);
        b.writeInt(accent); b.writeByte(switch (particle) { case "foam" -> 1; case "bubble" -> 2; case "glint" -> 3; case "mote" -> 4; default -> 0; });
        if (operation() == Operation.PREPARE) {
            b.writeByte(mortar.mode()); unit(b, mortar.processed()); unit(b, mortar.pressure()); unit(b, mortar.clump());
            b.writeByte(mortar.texture()); signed(b, mortar.x()); signed(b, mortar.z()); b.writeBoolean(mortar.pressing());
            b.writeFloat(mortar.workRate()); b.writeVarInt(mortar.foldCounter());
        } else if (operation() == Operation.BREW || operation() == Operation.FINISH) {
            b.writeByte(kettle.draft()); unit(b, kettle.burner()); b.writeVarInt(kettle.additionIndex()); b.writeByte(kettle.additionOutcome());
            b.writeInt(kettle.currentColor()); b.writeInt(kettle.nextColor()); unit(b, kettle.phaseProgress());
        } else if (operation() == Operation.PURIFY) {
            b.writeByte(alembic.heatSetting()); b.writeByte(alembic.coolantSetting()); unit(b, alembic.collectedFraction());
            b.writeBoolean(alembic.collecting()); b.writeFloat(alembic.condensedRate());
        }
    }
    public static StationVisual read(FriendlyByteBuf b) {
        if (b.readUnsignedByte() != SCHEMA) throw new IllegalArgumentException("Unknown station presentation schema");
        UUID incarnation = b.readUUID(), job = b.readUUID(); long revision = b.readVarLong(), sample = b.readVarLong();
        int step = Math.max(0, b.readVarInt()), op = b.readByte();
        Operation operation = op >= 0 && op < Operation.values().length ? Operation.values()[op] : null;
        Activity activity = b.readEnum(Activity.class); UUID controller = b.readUUID(); int servings = Math.min(64, b.readUnsignedByte());
        int phase = b.readByte(), signature = b.readUnsignedByte(); float heat = unit(b), circulation = signed(b), trouble = unit(b), progress = unit(b);
        int accent = b.readInt(); String particle = switch (b.readUnsignedByte()) { case 1 -> "foam"; case 2 -> "bubble"; case 3 -> "glint"; case 4 -> "mote"; default -> "steam"; };
        Mortar m = Mortar.EMPTY; Kettle k = Kettle.EMPTY; Alembic a = Alembic.EMPTY;
        if (operation == Operation.PREPARE) {
            m = new Mortar(Math.min(4, b.readUnsignedByte()), unit(b), unit(b), unit(b), Math.min(4, b.readUnsignedByte()),
                    signed(b), signed(b), b.readBoolean(), clamp(b.readFloat(), 0, 1), Math.max(0, b.readVarInt()));
        } else if (operation == Operation.BREW || operation == Operation.FINISH) {
            k = new Kettle(Math.min(2, b.readUnsignedByte()), unit(b), Math.max(0, b.readVarInt()), b.readUnsignedByte(), b.readInt(), b.readInt(), unit(b));
        } else if (operation == Operation.PURIFY) {
            a = new Alembic(Math.min(4, b.readUnsignedByte()), Math.min(4, b.readUnsignedByte()), unit(b), b.readBoolean(), clamp(b.readFloat(), 0, 1));
        }
        return new StationVisual(phase, signature, heat, circulation, trouble, progress, activity == Activity.WORKING,
                accent, particle, m, k, a, new Context(incarnation, job, Math.max(0, revision), sample, step, operation, activity, controller, servings));
    }
    public CompoundTag save() {
        FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.buffer());
        try {
            write(b); byte[] bytes = new byte[b.readableBytes()]; b.readBytes(bytes);
            CompoundTag tag = new CompoundTag(); tag.putByteArray("snapshot", bytes); return tag;
        } finally { b.release(); }
    }
    public static StationVisual load(CompoundTag tag) {
        byte[] bytes = tag.getByteArray("snapshot");
        if (bytes.length > 0 && bytes.length <= 192) {
            FriendlyByteBuf b = new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            try { return read(b); } catch (RuntimeException ignored) { return IDLE; } finally { b.release(); }
        }
        // Old visual tags are not authoritative saved jobs; the server rebuilds those from the ledger.
        return IDLE;
    }
    private static void unit(FriendlyByteBuf b, float value) { b.writeShort(Math.round(clamp(value, 0, 1) * 65535)); }
    private static float unit(FriendlyByteBuf b) { return b.readUnsignedShort() / 65535F; }
    private static void signed(FriendlyByteBuf b, float value) { b.writeShort(Math.round(clamp(value, -1, 1) * 32767)); }
    private static float signed(FriendlyByteBuf b) { return clamp(b.readShort() / 32767F, -1, 1); }
    private static float clamp(float v, float min, float max) { return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : min; }
}
