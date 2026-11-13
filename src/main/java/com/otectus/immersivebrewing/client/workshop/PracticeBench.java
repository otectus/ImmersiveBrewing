package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.client.StationNotices;
import com.otectus.immersivebrewing.potion.Grade;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.registry.ModSounds;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.workshop.AlembicProcess;
import com.otectus.immersivebrewing.workshop.Effort;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.MortarProcess;
import com.otectus.immersivebrewing.workshop.ToolTrack;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client-only practice (handoff 10): the same workshop processes, tool tracking and part actions as a real batch, with a
 * setup the server resolved, run locally over an idle station's apparatus. Intents the working view would send are
 * applied here instead, and the station's view is replaced by the bench's snapshot for this player only. Nothing is
 * ever sent: practice makes no item, quality metadata, XP or advancement, and other players see nothing.
 */
public final class PracticeBench {
    private static final UUID PRACTICE_JOB = new UUID(0x7072616374696365L, 1);
    @Nullable
    private static SessionKind pendingKind;
    @Nullable
    private static CompoundTag pending;
    @Nullable
    private static PracticeBench active;

    private final BlockPos pos;
    private final WorkshopKind kind;
    private final CompoundTag practice;
    @Nullable
    private KettleProcess kettle;
    @Nullable
    private MortarProcess mortar;
    @Nullable
    private AlembicProcess alembic;
    private final ToolTrack track;
    private int tool = -1;
    private Effort effort = Effort.NORMAL;
    private int pourQueue;
    private int fillQueue;
    private int cooldown;
    private long ticks;
    private int control = -1;
    private long revision;
    private final UUID self;

    private PracticeBench(BlockPos pos, WorkshopKind kind, CompoundTag practice, UUID self) {
        this.pos = pos.immutable();
        this.kind = kind;
        this.practice = practice;
        this.self = self;
        this.track = kind == WorkshopKind.KETTLE ? new ToolTrack(WorkshopParts.KETTLE_HX, WorkshopParts.KETTLE_HZ)
                : new ToolTrack(WorkshopParts.MORTAR_R, WorkshopParts.MORTAR_R);
    }

    // ---- lifecycle ----

    /** A practice setup arrived: start now if the working view is open at an idle station of that kind, else wait for one. */
    public static void request(SessionKind kind, CompoundTag practice) {
        pendingKind = kind;
        pending = practice;
        if (!tryStart()) {
            StationNotices.show(Component.translatable("practice.immersive_brewing.workshop.waiting",
                    Component.translatable("practice.immersive_brewing.workshop.station." + kind.name().toLowerCase(java.util.Locale.ROOT))), false);
        }
    }

    /** Called when a working view opens: a waiting practice starts at a matching idle station. */
    static boolean tryStart() {
        var mc = Minecraft.getInstance();
        BlockPos at = ClientWorkshop.focus();
        WorkshopKind k = ClientWorkshop.kind();
        if (pending == null || pendingKind == null || at == null || k == null || mc.level == null || mc.player == null) return false;
        if (k != kindOf(pendingKind)) return false;
        if (!(mc.level.getBlockEntity(at) instanceof StationBlockEntity station) || station.workshopView().contains("state")) {
            StationNotices.show(Component.translatable("practice.immersive_brewing.workshop.busy"), true);
            return false;
        }
        PracticeBench bench = new PracticeBench(at, k, pending, mc.player.getUUID());
        if (!bench.build(station)) return false;
        ClientWorkshop.resetHand();
        active = bench;
        pending = null;
        pendingKind = null;
        StationNotices.show(Component.translatable("practice.immersive_brewing.workshop.started"), false);
        return true;
    }

    static WorkshopKind kindOf(SessionKind kind) {
        return switch (kind) {
            case BREWING -> WorkshopKind.KETTLE;
            case PREPARATION -> WorkshopKind.MORTAR;
            case DISTILLATION -> WorkshopKind.ALEMBIC;
        };
    }

    public static boolean active(BlockPos pos) {
        return active != null && active.pos.equals(pos);
    }

    public static boolean any() {
        return active != null;
    }

    /** Ends the practice: nothing is kept. */
    public static void stop(@Nullable Component reason) {
        if (active == null) return;
        active = null;
        ClientWorkshop.resetHand();
        if (reason != null) StationNotices.show(reason, false);
    }

    public static void clear() {
        active = null;
        pending = null;
        pendingKind = null;
    }

    /** The practice snapshot drawn instead of the station's own state, for this player only. */
    @Nullable
    public static CompoundTag snapshotAt(BlockPos pos) {
        return active(pos) ? active.snapshot() : null;
    }

    private boolean build(StationBlockEntity station) {
        try {
            CompoundTag setup = practice.getCompound("setup");
            switch (kind) {
                case KETTLE -> {
                    List<Integer> free = new ArrayList<>();
                    for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
                        if (station.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i).isEmpty()) free.add(i);
                    }
                    int servings = Math.min(practice.getInt("servings"), free.size());
                    if (servings < 1) {
                        StationNotices.show(Component.translatable("practice.immersive_brewing.workshop.rack_full"), true);
                        return false;
                    }
                    int[] slots = new int[servings];
                    for (int i = 0; i < servings; i++) slots[i] = free.get(i);
                    kettle = KettleProcess.begin(KettleProcess.Setup.load(setup), slots, self);
                }
                case MORTAR -> mortar = MortarProcess.begin(MortarProcess.Setup.load(setup), self);
                case ALEMBIC -> {
                    alembic = AlembicProcess.begin(AlembicProcess.Setup.load(setup), self);
                    alembic.setClamped(false);
                }
                case RACK -> {
                    return false;
                }
            }
            return true;
        } catch (RuntimeException e) {
            StationNotices.show(Component.translatable("notice.immersive_brewing.practice_unavailable"), true);
            return false;
        }
    }

    // ---- actions (the same rules the stations apply) ----

    /** Applies an intent locally. Returns true when it was a practice action (it must then not be sent). */
    static boolean act(int part, WorkshopAction action, int argument, double x, double z, boolean contact, Effort chosen, boolean batch) {
        if (active == null || !active.pos.equals(ClientWorkshop.focus())) return false;
        if (action == WorkshopAction.LEAVE) {
            stop(null);
            return false; // the server still hears that the view closed
        }
        if (action == WorkshopAction.DRAWER || action == WorkshopAction.BOOK) {
            StationNotices.show(Component.translatable("practice.immersive_brewing.workshop.no_real"), true);
            return true;
        }
        active.effort = chosen;
        Component refusal = active.apply(part, action, argument, x, z, contact, batch);
        if (refusal != null) StationNotices.show(refusal, true);
        active.revision++;
        return true;
    }

    @Nullable
    private Component apply(int part, WorkshopAction action, int argument, double x, double z, boolean contact, boolean batch) {
        if (action == WorkshopAction.SEAL || action == WorkshopAction.CANCEL) {
            stop(Component.translatable("practice.immersive_brewing.workshop.ended"));
            return null;
        }
        switch (kind) {
            case KETTLE -> {
                return kettleAct(part, action, argument, x, z, contact, batch);
            }
            case MORTAR -> {
                return mortarAct(part, action, x, z, contact);
            }
            case ALEMBIC -> {
                return alembicAct(part, action, argument);
            }
            default -> {
                return null;
            }
        }
    }

    @Nullable
    private Component kettleAct(int part, WorkshopAction action, int argument, double x, double z, boolean contact, boolean batch) {
        KettleProcess p = kettle;
        switch (action) {
            case POUR -> {
                if (batch || argument < 0) {
                    pourQueue = p.count(KettleProcess.Portion.STAGED);
                    return null;
                }
                int index = part >= WorkshopParts.K_RACK && part < WorkshopParts.K_RACK + 6 ? p.portionAtSlot(part - WorkshopParts.K_RACK) : argument;
                if (index >= 0 && p.pour(index)) sound(ModSounds.KETTLE_POUR.get());
                return null;
            }
            case DOSE -> {
                int step = part >= WorkshopParts.K_CUP && part < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS ? part - WorkshopParts.K_CUP : argument;
                String problem = p.doseProblem(step);
                if (problem != null) return Component.translatable(problem);
                p.dose(step);
                sound(ModSounds.INGREDIENT_ADD.get());
                return null;
            }
            case SET_CONTROL -> {
                if (part == WorkshopParts.K_BURNER && p.setBurner(argument)) sound(ModSounds.VALVE_TURN.get());
                return null;
            }
            case GRAB -> {
                if (part == WorkshopParts.K_PADDLE) {
                    tool = part;
                    track.reset();
                }
                return null;
            }
            case RELEASE -> {
                if (part == tool) {
                    tool = -1;
                    track.reset();
                }
                return null;
            }
            case SAMPLE -> {
                if (part == tool) track.sample(x, z, contact, ticks);
                return null;
            }
            case FILL -> {
                if (!p.finalized()) return Component.translatable("workshop.immersive_brewing.kettle.not_ready");
                // As at a real kettle: the batch action fills every ready bottle in turn, the spout alone the next one.
                fillQueue = batch ? p.count(KettleProcess.Portion.READY) : 1;
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    @Nullable
    private Component mortarAct(int part, WorkshopAction action, double x, double z, boolean contact) {
        MortarProcess m = mortar;
        switch (action) {
            case GRAB -> {
                if (part == WorkshopParts.M_PESTLE || part == WorkshopParts.M_SCRAPER) {
                    tool = part;
                    track.reset();
                }
                return null;
            }
            case RELEASE -> {
                if (part == tool) {
                    tool = -1;
                    track.reset();
                }
                return null;
            }
            case SAMPLE -> {
                if (part == tool) track.sample(x, z, contact, ticks);
                return null;
            }
            case PRESS -> {
                if (tool != WorkshopParts.M_PESTLE) return Component.translatable("workshop.immersive_brewing.mortar.take_pestle");
                if ((m.setup().operations() & MortarProcess.OP_PRESS) == 0) return Component.translatable("workshop.immersive_brewing.mortar.no_press");
                if (m.press(Math.max(-1, Math.min(1, x)), Math.max(-1, Math.min(1, z)), effort.value, ticks)) sound(ModSounds.MORTAR_CRUSH.get());
                return null;
            }
            case FINISH -> {
                MortarProcess.Evaluation e = m.evaluate();
                if (!e.usable()) return Component.translatable("workshop.immersive_brewing.mortar.not_usable", Component.translatable(e.textureKey()));
                stop(Component.translatable("practice.immersive_brewing.workshop.result.mortar", e.extraction(), Component.translatable(m.tipKey())));
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    @Nullable
    private Component alembicAct(int part, WorkshopAction action, int argument) {
        AlembicProcess a = alembic;
        if (action != WorkshopAction.SET_CONTROL) return null;
        boolean changed = false;
        if (part == WorkshopParts.A_CLAMP) {
            a.setClamped(argument != 0);
            changed = true;
        } else if (part == WorkshopParts.A_BURNER) changed = a.setBurner(argument);
        else if (part == WorkshopParts.A_VALVE) changed = a.setValve(argument);
        else if (part == WorkshopParts.A_DIVERTER) changed = a.setDiverter(argument != 0);
        if (changed) sound(part == WorkshopParts.A_DIVERTER ? ModSounds.RECEIVER_SWITCH.get() : ModSounds.VALVE_TURN.get());
        return null;
    }

    // ---- stepping (one step per client tick, the server's step length) ----

    public static void tick() {
        if (active == null) return;
        var mc = Minecraft.getInstance();
        if (mc.level == null || !active.pos.equals(ClientWorkshop.focus())) {
            stop(null);
            return;
        }
        if (mc.isPaused()) return;
        active.step();
    }

    private void step() {
        ticks++;
        double px = tool >= 0 ? track.take(ticks) : 0;
        switch (kind) {
            case KETTLE -> {
                KettleProcess p = kettle;
                if (cooldown > 0) cooldown--;
                else if (pourQueue > 0) {
                    for (int i = 0; i < p.portions(); i++) if (p.portion(i) == KettleProcess.Portion.STAGED) {
                        p.pour(i);
                        sound(ModSounds.KETTLE_POUR.get());
                        break;
                    }
                    pourQueue--;
                    cooldown = 6;
                } else if (fillQueue > 0 && p.finalized()) {
                    for (int i = 0; i < p.portions(); i++) if (p.portion(i) == KettleProcess.Portion.READY) {
                        p.removePortion(i);
                        sound(ModSounds.BOTTLE_FILL.get());
                        break;
                    }
                    fillQueue--;
                    cooldown = 8;
                    if (p.portions() == 0) {
                        int quality = QualityMath.quality(QualityMath.RAW_EXTRACTION, control);
                        stop(Component.translatable("practice.immersive_brewing.workshop.result.kettle", Component.translatable(Grade.of(quality).key()),
                                quality, control, Component.translatable(p.tipKey())));
                        return;
                    }
                }
                p.tick(tool == WorkshopParts.K_PADDLE ? px : 0, track.broadness());
                if (p.readyToFinalize()) {
                    control = p.finalizeControl();
                    p.markFinalized();
                    sound(ModSounds.BREW_COMPLETE.get());
                }
            }
            case MORTAR -> {
                MortarProcess m = mortar;
                if (tool == WorkshopParts.M_PESTLE && px > 0) m.grind(px, track.u(), track.v(), effort.value);
                if (tool == WorkshopParts.M_SCRAPER && px > 0) m.fold(px);
                m.tick();
            }
            case ALEMBIC -> {
                AlembicProcess a = alembic;
                a.tick();
                if (a.finished()) {
                    int d = a.distillation();
                    stop(Component.translatable("practice.immersive_brewing.workshop.result.alembic", d, QualityMath.purified(50, d, 70),
                            Component.translatable(a.tipKey())));
                }
            }
            default -> { }
        }
        revision++;
    }

    private void sound(SoundEvent sound) {
        var level = Minecraft.getInstance().level;
        if (level != null) level.playLocalSound(pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5, sound, SoundSource.BLOCKS, 0.4F, 1.0F, false);
    }

    // ---- presentation ----

    private CompoundTag snapshot() {
        CompoundTag t = new CompoundTag();
        t.putByte("kind", (byte) kind.ordinal());
        t.putByte("state", (byte) com.otectus.immersivebrewing.batch.JobState.RUNNING.ordinal());
        t.putBoolean("physical", true);
        t.putBoolean("practice", true);
        t.putUUID("job", PRACTICE_JOB);
        ListTag held = new ListTag();
        if (tool >= 0) {
            CompoundTag h = new CompoundTag();
            h.putByte("part", (byte) tool);
            h.putString("name", Component.translatable("practice.immersive_brewing.workshop.you").getString());
            h.putUUID("id", self);
            h.putFloat("x", (float) track.u());
            h.putFloat("z", (float) track.v());
            h.putBoolean("contact", track.contact());
            held.add(h);
        }
        t.put("held", held);
        switch (kind) {
            case KETTLE -> {
                t.putByte("op", (byte) com.otectus.immersivebrewing.batch.Operation.BREW.ordinal());
                t.putBoolean("paddle", true);
                CompoundTag k = new CompoundTag();
                kettle.writeSnapshot(k);
                t.put("kettle", k);
                t.put("doseItems", practice.getList("doseItems", Tag.TAG_STRING).copy());
                t.putIntArray("stageColors", practice.getIntArray("stageColors"));
                ListTag portions = new ListTag();
                ItemStack input = ItemStack.of(practice.getCompound("input"));
                ItemStack output = ItemStack.of(practice.getCompound("output"));
                if (input.isEmpty()) input = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
                for (int i = 0; i < kettle.portions(); i++) {
                    ItemStack shown = kettle.portion(i) == KettleProcess.Portion.READY && !output.isEmpty() ? output : input;
                    CompoundTag item = shown.save(new CompoundTag());
                    portions.add(item);
                }
                t.put("portionItems", portions);
            }
            case MORTAR -> {
                t.putByte("op", (byte) com.otectus.immersivebrewing.batch.Operation.PREPARE.ordinal());
                CompoundTag m = new CompoundTag();
                mortar.writeSnapshot(m);
                t.put("mortar", m);
                t.put("reagent", practice.getCompound("reagent").copy());
                t.putInt("units", 1);
                t.putBoolean("preparable", true);
            }
            case ALEMBIC -> {
                t.putByte("op", (byte) com.otectus.immersivebrewing.batch.Operation.PURIFY.ordinal());
                CompoundTag a = new CompoundTag();
                alembic.writeSnapshot(a);
                t.put("alembic", a);
                t.put("charge", practice.getCompound("input").copy());
                t.putInt("chargeCount", 3);
                t.putInt("coolantMb", 1000);
                t.putInt("coolantCapacity", 1000);
                t.putInt("coolantNeed", 250);
            }
            default -> { }
        }
        return t;
    }
}
