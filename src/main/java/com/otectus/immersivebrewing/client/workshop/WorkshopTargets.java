package com.otectus.immersivebrewing.client.workshop;

import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.item.BatchCarrierItem;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.registry.ModTags;
import com.otectus.immersivebrewing.workshop.KettleProcess;
import com.otectus.immersivebrewing.workshop.WorkshopAction;
import com.otectus.immersivebrewing.workshop.WorkshopKind;
import com.otectus.immersivebrewing.workshop.WorkshopPart;
import com.otectus.immersivebrewing.workshop.WorkshopParts;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The interaction language of the working view (handoff 4, 5): for every part in every state, whether it is present,
 * whether it can be used now, its short label and what activating it does. Read-only over the synced snapshot and
 * public inventory; it never decides anything the server will not check again.
 */
public final class WorkshopTargets {
    /** What activating a target does on this client before (or instead of) sending an intent. */
    public enum Local { SEND, TAKE_TOOL, PUT_DOWN, CARRY, COMMIT_CARRY, CONTROL, TOGGLE, NONE }

    /**
     * What a part does now. The primary action is a click (or Activate); the batch action a shift-click (or the batch key,
     * then Activate) and always names its quantity; the secondary action a right-click with empty hands (or Put down with
     * nothing held), such as taking a staged bottle back. {@code setup} commits also accept the recipe page shown at
     * {@code revision}.
     */
    public record Target(WorkshopPart part, boolean available, Component label, Local local, @Nullable WorkshopAction action, int argument,
                         @Nullable WorkshopAction batchAction, int batchArgument, @Nullable Component batchLabel,
                         @Nullable WorkshopAction secondaryAction, @Nullable Component secondaryLabel, boolean setup, long revision) {
        public Target(WorkshopPart part, boolean available, Component label, Local local, @Nullable WorkshopAction action, int argument,
                      @Nullable WorkshopAction batchAction, int batchArgument, @Nullable Component batchLabel) {
            this(part, available, label, local, action, argument, batchAction, batchArgument, batchLabel, null, null, false,
                    com.otectus.immersivebrewing.interaction.WorkshopIntent.NO_REVISION);
        }

        static Target info(WorkshopPart part, Component label) {
            return new Target(part, false, label, Local.NONE, null, 0, null, 0, null);
        }

        static Target send(WorkshopPart part, Component label, WorkshopAction action, int argument) {
            return new Target(part, true, label, Local.SEND, action, argument, null, 0, null);
        }

        Target withBatch(WorkshopAction action, int argument, Component label) {
            return new Target(part, available, this.label, local, this.action, this.argument, action, argument, label, secondaryAction,
                    secondaryLabel, setup, revision);
        }

        Target withSecondary(WorkshopAction action, Component label) {
            return new Target(part, available, this.label, local, this.action, argument, batchAction, batchArgument, batchLabel, action, label,
                    setup, revision);
        }

        /** The commit also accepts the recipe page the player read (revision {@code rev}). */
        Target accepting(long rev) {
            return new Target(part, available, label, local, action, argument, batchAction, batchArgument, batchLabel, secondaryAction,
                    secondaryLabel, true, rev);
        }
    }

    /** The synced station state as the client sees it. */
    public record View(BlockEntity be, WorkshopKind kind, CompoundTag snapshot) {
        @Nullable
        public JobState state() {
            return snapshot.contains("state", Tag.TAG_BYTE) ? JobState.values()[Math.max(0, Math.min(JobState.values().length - 1, snapshot.getByte("state")))] : null;
        }

        @Nullable
        public Operation operation() {
            return snapshot.contains("op", Tag.TAG_BYTE) ? Operation.values()[Math.max(0, Math.min(Operation.values().length - 1, snapshot.getByte("op")))] : null;
        }

        public boolean physical() {
            return snapshot.getBoolean("physical");
        }

        public boolean legacy() {
            return snapshot.getBoolean("legacy");
        }

        /** A client-only practice drawn over this station (nothing in it is real). */
        public boolean practice() {
            return snapshot.getBoolean("practice");
        }

        public boolean running() {
            return physical() && state() == JobState.RUNNING;
        }

        public boolean sealed() {
            return physical() && state() == JobState.SEALED;
        }

        public UUID job() {
            return snapshot.hasUUID("job") ? snapshot.getUUID("job") : com.otectus.immersivebrewing.interaction.WorkshopIntent.ANY_JOB;
        }

        public CompoundTag kettle() {
            return snapshot.getCompound("kettle");
        }

        public CompoundTag finishing() {
            return snapshot.getCompound("finishing");
        }

        public CompoundTag mortar() {
            return snapshot.getCompound("mortar");
        }

        public CompoundTag alembic() {
            return snapshot.getCompound("alembic");
        }

        /** The kettle's recipe page (idle stations only). */
        public CompoundTag setup() {
            return snapshot.getCompound("setup");
        }

        /** Whether the recipe page offers a batch a tipped bottle starts now. */
        public boolean setupReady() {
            CompoundTag s = setup();
            int status = s.getByte("status");
            return s.getBoolean("ready") && (status == com.otectus.immersivebrewing.blockentity.KettleSetupService.Status.READY.ordinal()
                    || status == com.otectus.immersivebrewing.blockentity.KettleSetupService.Status.CHOOSE.ordinal());
        }

        /** The live holder's name of a part, or null. */
        @Nullable
        public String holder(int part) {
            ListTag held = snapshot.getList("held", Tag.TAG_COMPOUND);
            for (int i = 0; i < held.size(); i++) if (held.getCompound(i).getByte("part") == part) return held.getCompound(i).getString("name");
            return null;
        }

        @Nullable
        public UUID holderId(int part) {
            ListTag held = snapshot.getList("held", Tag.TAG_COMPOUND);
            for (int i = 0; i < held.size(); i++) {
                CompoundTag h = held.getCompound(i);
                if (h.getByte("part") == part && h.hasUUID("id")) return h.getUUID("id");
            }
            return null;
        }

        @Nullable
        public CompoundTag pose(int part) {
            ListTag held = snapshot.getList("held", Tag.TAG_COMPOUND);
            for (int i = 0; i < held.size(); i++) if (held.getCompound(i).getByte("part") == part) return held.getCompound(i);
            return null;
        }
    }

    public static View view(BlockEntity be, WorkshopKind kind) {
        CompoundTag snapshot = PracticeBench.snapshotAt(be.getBlockPos());
        if (snapshot == null) snapshot = be instanceof StationBlockEntity station ? station.workshopView() : new CompoundTag();
        return new View(be, kind, snapshot);
    }

    private static Component t(String key, Object... args) {
        return Component.translatable("target.immersive_brewing." + key, args);
    }

    private static ItemStack held() {
        var player = Minecraft.getInstance().player;
        return player == null ? ItemStack.EMPTY : player.getMainHandItem();
    }

    private static boolean mine(View v, int part) {
        var player = Minecraft.getInstance().player;
        UUID id = v.holderId(part);
        return player != null && id != null && id.equals(player.getUUID());
    }

    /** Parts that exist in the current state, in a stable keyboard order. */
    public static List<WorkshopPart> present(View v) {
        List<WorkshopPart> out = new ArrayList<>();
        for (WorkshopPart part : WorkshopParts.of(v.kind())) if (present(v, part)) out.add(part);
        return out;
    }

    /**
     * The same parts with the ones the work needs now first (handoff 13: relevant targets first, a predictable order
     * after them): the vessel while something is carried or a tool is held, the bottles while a batch is being set out
     * or charged, the next dose cup, the spout when the brew is ready.
     */
    public static List<WorkshopPart> ordered(View v, ClientWorkshop.Hand hand) {
        List<WorkshopPart> all = present(v);
        List<Integer> first = new ArrayList<>();
        if (v.kind() == WorkshopKind.KETTLE) {
            CompoundTag k = v.kettle();
            if (hand.carried() >= 0 || hand.tool() >= 0) first.add(WorkshopParts.K_VESSEL);
            else if (v.state() == null && v.setupReady()) {
                first.add(WorkshopParts.K_BOOK);
                for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) first.add(WorkshopParts.K_RACK + i);
            } else if (k.contains("stage")) {
                KettleProcess.Stage stage = KettleProcess.Stage.values()[Math.max(0, Math.min(5, k.getByte("stage")))];
                switch (stage) {
                    case CHARGING -> {
                        byte[] slots = k.getByteArray("slots"), portions = k.getByteArray("portions");
                        for (int i = 0; i < Math.min(slots.length, portions.length); i++) {
                            if (portions[i] == KettleProcess.Portion.STAGED.ordinal()) first.add(WorkshopParts.K_RACK + slots[i]);
                        }
                    }
                    case HEATING, WAITING_DOSE -> {
                        first.add(WorkshopParts.K_BURNER);
                        first.add(WorkshopParts.K_CUP + k.getByte("doses"));
                    }
                    case REACTING -> {
                        first.add(WorkshopParts.K_PADDLE);
                        first.add(WorkshopParts.K_BURNER);
                    }
                    case RESTING -> {
                        first.add(WorkshopParts.K_BURNER);
                        first.add(WorkshopParts.K_PADDLE);
                    }
                    case READY -> first.add(WorkshopParts.K_SPOUT);
                }
            }
        } else if (v.kind() == WorkshopKind.MORTAR) {
            if (hand.tool() >= 0) first.add(WorkshopParts.M_BOWL);
            else if (v.mortar().getBoolean("usable")) first.add(WorkshopParts.M_CUP);
            first.add(WorkshopParts.M_PESTLE);
        } else if (v.kind() == WorkshopKind.ALEMBIC) {
            if (v.state() == null) {
                first.add(WorkshopParts.A_MODE);
                first.add(WorkshopParts.A_CHARGE);
            } else {
                first.add(WorkshopParts.A_CLAMP);
                first.add(WorkshopParts.A_BURNER);
                first.add(WorkshopParts.A_VALVE);
                first.add(WorkshopParts.A_DIVERTER);
            }
        }
        List<WorkshopPart> out = new ArrayList<>();
        for (int id : first) for (WorkshopPart p : all) if (p.id() == id && !out.contains(p)) out.add(p);
        for (WorkshopPart p : all) if (!out.contains(p)) out.add(p);
        return out;
    }

    private static boolean present(View v, WorkshopPart part) {
        if (v.kind() == WorkshopKind.KETTLE) {
            int id = part.id();
            boolean job = v.state() != null;
            if (id >= WorkshopParts.K_CUP && id < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS) {
                int steps = v.kettle().contains("steps") ? v.kettle().getByte("steps") : v.finishing().getByte("steps");
                return job && v.physical() && id - WorkshopParts.K_CUP < steps;
            }
            if (id >= WorkshopParts.K_TRAY && id < WorkshopParts.K_TRAY + BrewersKettleBlockEntity.TRAY_SIZE) return !job;
        }
        return true;
    }

    public static Target describe(View v, WorkshopPart part, ClientWorkshop.Hand hand) {
        return switch (v.kind()) {
            case KETTLE -> kettle(v, part, hand);
            case MORTAR -> mortar(v, part, hand);
            case ALEMBIC -> alembic(v, part, hand);
            case RACK -> rack(v, part);
        };
    }

    // ---- kettle ----

    private static Target kettle(View v, WorkshopPart part, ClientWorkshop.Hand hand) {
        BrewersKettleBlockEntity kettle = (BrewersKettleBlockEntity) v.be();
        CompoundTag k = v.kettle();
        boolean brewing = v.running() && v.operation() == Operation.BREW;
        boolean finishing = v.physical() && v.operation() == Operation.FINISH;
        KettleProcess.Stage stage = k.contains("stage") ? KettleProcess.Stage.values()[Math.max(0, Math.min(5, k.getByte("stage")))] : null;
        int id = part.id();
        ItemStack held = held();
        if (id == WorkshopParts.K_VESSEL) {
            if (hand.carried() >= 0) return commitCarry(v, part, hand);
            if (v.sealed()) return Target.send(part, t("kettle.unseal"), WorkshopAction.UNSEAL, 0);
            if (hand.tool() == WorkshopParts.K_PADDLE) return Target.info(part, t("kettle.stirring"));
            if (k.contains("cue")) return Target.info(part, Component.translatable(k.getString("cue")));
            if (v.finishing().contains("cue")) return Target.info(part, Component.translatable(v.finishing().getString("cue")));
            return Target.info(part, kettle.view().status());
        }
        if (id == WorkshopParts.K_PADDLE) {
            if (mine(v, id) || hand.tool() == id) return new Target(part, true, t("tool.put_down"), Local.PUT_DOWN, WorkshopAction.RELEASE, 0, null, 0, null);
            String other = v.holder(id);
            if (other != null) return Target.info(part, t("tool.held_by", other));
            if (held.is(ModTags.BREWING_PADDLES) && kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE).isEmpty()) {
                return Target.send(part, t("kettle.paddle.place"), WorkshopAction.INSERT, 0);
            }
            if (kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.PADDLE).isEmpty() && !v.practice()) return Target.info(part, t("kettle.paddle.missing"));
            if (brewing && stage != KettleProcess.Stage.READY) return new Target(part, true, t("kettle.paddle.take"), Local.TAKE_TOOL, WorkshopAction.GRAB, 0, null, 0, null);
            if (v.state() == null) return Target.send(part, t("kettle.paddle.remove"), WorkshopAction.TAKE, 0);
            return Target.info(part, t("kettle.paddle.resting"));
        }
        if (id == WorkshopParts.K_BURNER) {
            if (!brewing) return Target.info(part, t("kettle.burner.off"));
            int burner = k.getByte("burner");
            return new Target(part, true, t("kettle.burner", burner, KettleProcess.BURNER_STEPS,
                    Component.translatable("heat.immersive_brewing.band." + KettleProcess.heatBand(k.getByte("temp") / 100.0))),
                    Local.CONTROL, WorkshopAction.SET_CONTROL, burner, null, 0, null);
        }
        if (id == WorkshopParts.K_FUEL) {
            if (held.is(ModTags.BURNER_FUEL)) return Target.send(part, t("kettle.fuel.add_one", held.getHoverName()), WorkshopAction.INSERT, 0)
                    .withBatch(WorkshopAction.INSERT, 0, t("kettle.fuel.add_all", held.getCount(), held.getHoverName()));
            ItemStack fuel = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.FUEL);
            Target info = Target.info(part, t("kettle.fuel", v.snapshot().getInt("fuel")));
            return fuel.isEmpty() ? info : Target.send(part, t("kettle.fuel.take", v.snapshot().getInt("fuel")), WorkshopAction.TAKE, 0);
        }
        if (id == WorkshopParts.K_SPOUT) {
            if (brewing && stage == KettleProcess.Stage.READY) {
                int ready = count(k, KettleProcess.Portion.READY);
                return Target.send(part, t("kettle.spout.fill"), WorkshopAction.FILL, -1)
                        .withBatch(WorkshopAction.FILL, -1, t("kettle.spout.fill_all", ready));
            }
            return Target.info(part, t("kettle.spout.closed"));
        }
        if (id == WorkshopParts.K_LID) {
            if (finishing && v.running()) {
                boolean closed = v.finishing().getBoolean("closed");
                return new Target(part, true, t(closed ? "kettle.fitting.open" : "kettle.fitting.close"), Local.TOGGLE, WorkshopAction.SET_CONTROL,
                        closed ? 0 : 1, null, 0, null);
            }
            if (v.sealed()) return Target.send(part, t("kettle.unseal"), WorkshopAction.UNSEAL, 0);
            if (brewing) return Target.send(part, t("kettle.seal"), WorkshopAction.SEAL, 0)
                    .withBatch(WorkshopAction.CANCEL, 0, t("kettle.withdraw"));
            return Target.info(part, t("kettle.lid"));
        }
        if (id == WorkshopParts.K_BOOK) {
            if (v.legacy() && v.state() == JobState.SEALED) return Target.send(part, t("legacy.finish"), WorkshopAction.LEGACY_RESUME, 0);
            if (v.state() != null) return Target.info(part, t("kettle.book.busy", jobOutput(v).getHoverName()));
            // The recipe page (handoff 6.2): what a tipped bottle starts. Several equal routes turn as pages; the full,
            // searchable planner stays one deliberate step away and is never needed for an ordinary batch.
            CompoundTag page = v.setup();
            int pages = page.getByte("pages"), at = page.getByte("page");
            Component summary = pageSummary(v);
            if (pages > 1) {
                return new Target(part, true, t("kettle.book.turn", at + 1, pages, summary), Local.SEND, WorkshopAction.SETUP_PAGE, (at + 1) % pages,
                        null, 0, null).withSecondary(WorkshopAction.BOOK, t("kettle.book.search"));
            }
            return new Target(part, false, summary, Local.NONE, null, 0, null, 0, null).withSecondary(WorkshopAction.BOOK, t("kettle.book.search"));
        }
        if (id == WorkshopParts.K_BYPRODUCTS) {
            boolean any = false;
            for (int i = 0; i < BrewersKettleBlockEntity.BYPRODUCT_SIZE; i++) any |= !kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i).isEmpty();
            return any ? Target.send(part, t("kettle.byproducts.take"), WorkshopAction.TAKE, 0) : Target.info(part, t("kettle.byproducts"));
        }
        if (id >= WorkshopParts.K_RACK && id < WorkshopParts.K_RACK + BrewersKettleBlockEntity.RACK_SIZE) {
            int slot = id - WorkshopParts.K_RACK;
            int portion = portionAt(k, slot);
            if (portion < 0 && finishing) {
                byte[] slots = v.finishing().getByteArray("slots");
                for (byte s : slots) if (s == slot) return Target.info(part, t("kettle.bottle.fitting"));
            }
            if (portion >= 0 && brewing) {
                KettleProcess.Portion state = KettleProcess.Portion.values()[k.getByteArray("portions")[portion]];
                return switch (state) {
                    case STAGED -> new Target(part, true, t("kettle.bottle.lift"), Local.CARRY, WorkshopAction.POUR, portion,
                            WorkshopAction.POUR, -1, t("kettle.bottle.pour_all", count(k, KettleProcess.Portion.STAGED)));
                    case CHARGED -> Target.info(part, t("kettle.bottle.charged"));
                    case READY -> Target.send(part, t("kettle.bottle.fill"), WorkshopAction.FILL, portion)
                            .withBatch(WorkshopAction.FILL, -1, t("kettle.spout.fill_all", count(k, KettleProcess.Portion.READY)));
                };
            }
            if (portion >= 0) return Target.info(part, t("kettle.bottle.reserved"));
            ItemStack stack = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + slot);
            if (!stack.isEmpty() && v.state() == null && v.setupReady() && !kettle.finishedAt(BrewersKettleBlockEntity.RACK + slot)) {
                // Lifting a staged bottle and tipping it in starts exactly the batch the page shows (handoff 6.2 step 6).
                ItemStack output = pageOutput(v);
                int servings = v.setup().getByte("servings");
                return new Target(part, true, t("kettle.bottle.lift_begin", output.getHoverName(), servings), Local.CARRY, WorkshopAction.POUR, 0,
                        WorkshopAction.POUR, assistArgument(), t("kettle.bottle.pour_all_begin", servings, output.getHoverName()))
                        .accepting(v.setup().getLong("rev")).withSecondary(WorkshopAction.TAKE, t("take", stack.getHoverName()));
            }
            if (!stack.isEmpty()) return Target.send(part, t("take", stack.getHoverName()), WorkshopAction.TAKE, 0)
                    .withBatch(WorkshopAction.TAKE, 0, t("take_matching", stack.getHoverName()));
            if (v.state() != null) return Target.info(part, t("kettle.bottle.reserved"));
            if (BrewersKettleBlockEntity.isBottle(held)) return Target.send(part, t("kettle.bottle.place"), WorkshopAction.INSERT, 0);
            return Target.info(part, t("kettle.bottle.empty"));
        }
        if (id >= WorkshopParts.K_CUP && id < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS) {
            int step = id - WorkshopParts.K_CUP;
            ItemStack dose = doseItem(v, step);
            CompoundTag process = finishing ? v.finishing() : k;
            int doses = process.getByte("doses");
            if (step < doses) return Target.info(part, t("kettle.cup.added", dose.getHoverName()));
            if (step > doses) return Target.info(part, t("kettle.cup.later", dose.getHoverName()));
            if (v.sealed()) return Target.info(part, t("kettle.sealed"));
            if (finishing) {
                if (process.getBoolean("closed")) return Target.info(part, t("kettle.fitting.closed"));
                return new Target(part, true, t("kettle.cup.lift", dose.getHoverName()), Local.CARRY, WorkshopAction.DOSE, step, null, 0, null);
            }
            if (!brewing) return Target.info(part, dose.getHoverName());
            if (count(k, KettleProcess.Portion.STAGED) > 0) return Target.info(part, t("kettle.cup.charge_first", dose.getHoverName()));
            if (doses > 0 && k.getByte("progress") < 100) return Target.info(part, t("kettle.cup.wait", dose.getHoverName()));
            return new Target(part, true, t("kettle.cup.lift", dose.getHoverName()), Local.CARRY, WorkshopAction.DOSE, step, null, 0, null);
        }
        if (id >= WorkshopParts.K_TRAY && id < WorkshopParts.K_TRAY + BrewersKettleBlockEntity.TRAY_SIZE) {
            ItemStack stack = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.TRAY + id - WorkshopParts.K_TRAY);
            if (BrewersKettleBlockEntity.isIngredient(held)) {
                Target place = Target.send(part, t("kettle.tray.place_one", held.getHoverName()), WorkshopAction.INSERT, 0)
                        .withBatch(WorkshopAction.INSERT, 0, t("kettle.tray.place_all", held.getCount(), held.getHoverName()));
                return stack.isEmpty() ? place : place.withSecondary(WorkshopAction.TAKE, t("take", stack.getHoverName()));
            }
            if (!stack.isEmpty()) return Target.send(part, t("take", stack.getHoverName()), WorkshopAction.TAKE, 0);
            ItemStack ghost = ghost(v, id);
            if (!ghost.isEmpty()) return Target.info(part, t("kettle.tray.needs", ghost.getCount(), ghost.getHoverName()));
            return Target.info(part, t("kettle.tray"));
        }
        return Target.info(part, Component.translatable(part.key()));
    }

    private static Target commitCarry(View v, WorkshopPart vessel, ClientWorkshop.Hand hand) {
        int carried = hand.carried();
        if (carried >= WorkshopParts.K_CUP && carried < WorkshopParts.K_CUP + WorkshopParts.MAX_CUPS) {
            int step = carried - WorkshopParts.K_CUP;
            return new Target(vessel, true, t("kettle.cup.tip", doseItem(v, step).getHoverName()), Local.COMMIT_CARRY, WorkshopAction.DOSE, step, null, 0, null);
        }
        if (carried >= WorkshopParts.K_RACK && carried < WorkshopParts.K_RACK + BrewersKettleBlockEntity.RACK_SIZE) {
            if (v.state() == null) {
                // Tipping the first bottle sets out the batch the page shows; nothing is consumed until the server agrees.
                return new Target(vessel, true, t("kettle.bottle.tip_begin", pageOutput(v).getHoverName(), v.setup().getByte("servings")),
                        Local.COMMIT_CARRY, WorkshopAction.POUR, assistArgument(), null, 0, null).accepting(v.setup().getLong("rev"));
            }
            return new Target(vessel, true, t("kettle.bottle.pour"), Local.COMMIT_CARRY, WorkshopAction.POUR, 0, null, 0, null);
        }
        return Target.info(vessel, t("kettle.vessel"));
    }

    /** The approved-assistance request a set-out carries (band widening in hundredths above 1, within the server's cap). */
    static int assistArgument() {
        if (!com.otectus.immersivebrewing.config.ClientConfig.get(com.otectus.immersivebrewing.config.ClientConfig.REQUEST_ASSIST)) return 0;
        return (int) Math.round((com.otectus.immersivebrewing.config.ClientConfig.get(com.otectus.immersivebrewing.config.ClientConfig.ASSIST_BAND) - 1) * 100);
    }

    /** The potion the recipe page shows. */
    public static ItemStack pageOutput(View v) {
        CompoundTag page = v.setup();
        return page.contains("output") ? ItemStack.of(page.getCompound("output")) : ItemStack.EMPTY;
    }

    /** The potion the batch in progress makes. */
    public static ItemStack jobOutput(View v) {
        return v.snapshot().contains("jobOutput") ? ItemStack.of(v.snapshot().getCompound("jobOutput")) : ItemStack.EMPTY;
    }

    /** The page in words (also what narration reads): the batch, its steps and costs, or why there is none. */
    public static Component pageSummary(View v) {
        CompoundTag page = v.setup();
        var status = com.otectus.immersivebrewing.blockentity.KettleSetupService.Status.values()[Math.max(0, Math.min(
                com.otectus.immersivebrewing.blockentity.KettleSetupService.Status.values().length - 1, page.getByte("status")))];
        Component problem = page.contains("problem") ? Component.Serializer.fromJson(page.getString("problem")) : null;
        ItemStack output = pageOutput(v);
        if (!output.isEmpty()) {
            net.minecraft.network.chat.MutableComponent steps = Component.empty();
            ListTag ids = page.getList("steps", Tag.TAG_STRING);
            byte[] counts = page.getByteArray("counts");
            for (int i = 0; i < ids.size(); i++) {
                ResourceLocation rid = ResourceLocation.tryParse(ids.getString(i));
                var item = rid == null ? null : ForgeRegistries.ITEMS.getValue(rid);
                if (item == null) continue;
                if (i > 0) steps.append(Component.translatable("target.immersive_brewing.kettle.book.then"));
                steps.append(Component.translatable("target.immersive_brewing.kettle.book.step", i < counts.length ? counts[i] : 1, new ItemStack(item).getHoverName()));
            }
            Component batch = t("kettle.book.page", output.getHoverName(), page.getByte("servings"), steps, page.getShort("fuel"));
            if (status == com.otectus.immersivebrewing.blockentity.KettleSetupService.Status.MISSING && problem != null) {
                return Component.empty().append(batch).append(" — ").append(problem);
            }
            return batch;
        }
        return switch (status) {
            case EMPTY -> t("kettle.book.empty");
            case NEED_BOTTLES -> t("kettle.book.need_bottles");
            case NEED_INGREDIENTS -> t("kettle.book.need_ingredients");
            case BUSY -> t("kettle.book.busy", Component.empty());
            default -> problem != null ? problem : t("kettle.book.unsupported");
        };
    }

    /** What the page says belongs at a part and is missing there (a ghost), or empty. */
    public static ItemStack ghost(View v, int part) {
        ListTag ghosts = v.setup().getList("ghosts", Tag.TAG_COMPOUND);
        for (int i = 0; i < ghosts.size(); i++) {
            CompoundTag g = ghosts.getCompound(i);
            if (g.getByte("part") != part) continue;
            ResourceLocation id = ResourceLocation.tryParse(g.getString("item"));
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item != null) return new ItemStack(item, Math.max(1, g.getByte("count")));
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack doseItem(View v, int step) {
        ListTag items = v.snapshot().getList("doseItems", Tag.TAG_STRING);
        if (step < 0 || step >= items.size()) return ItemStack.EMPTY;
        ResourceLocation id = ResourceLocation.tryParse(items.getString(step));
        var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    public static ItemStack portionItem(View v, int portion) {
        ListTag items = v.snapshot().getList("portionItems", Tag.TAG_COMPOUND);
        return portion < 0 || portion >= items.size() ? ItemStack.EMPTY : ItemStack.of(items.getCompound(portion));
    }

    public static int portionAt(CompoundTag kettle, int slot) {
        byte[] slots = kettle.getByteArray("slots");
        for (int i = 0; i < slots.length; i++) if (slots[i] == slot) return i;
        return -1;
    }

    static int count(CompoundTag kettle, KettleProcess.Portion state) {
        int n = 0;
        for (byte b : kettle.getByteArray("portions")) if (b == state.ordinal()) n++;
        return n;
    }

    // ---- mortar ----

    private static Target mortar(View v, WorkshopPart part, ClientWorkshop.Hand hand) {
        ApothecaryMortarBlockEntity mortar = (ApothecaryMortarBlockEntity) v.be();
        CompoundTag m = v.mortar();
        boolean running = v.running() && v.operation() == Operation.PREPARE;
        int id = part.id();
        ItemStack held = held();
        ItemStack bowl = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT);
        boolean preparable = v.snapshot().getBoolean("preparable");
        if (id == WorkshopParts.M_BOWL) {
            if (hand.tool() == WorkshopParts.M_PESTLE) {
                Component texture = Component.translatable(m.contains("cue") ? m.getString("cue") : "texture.immersive_brewing.workshop.waiting");
                boolean presses = !m.contains("ops") || (m.getByte("ops") & com.otectus.immersivebrewing.workshop.MortarProcess.OP_PRESS) != 0;
                if (!presses) return Target.info(part, t("mortar.sweep", texture));
                return new Target(part, true, t("mortar.press", texture), Local.SEND, WorkshopAction.PRESS, 0, null, 0, null);
            }
            if (hand.tool() == WorkshopParts.M_SCRAPER) return Target.info(part, t("mortar.folding"));
            if (v.sealed()) return Target.send(part, t("mortar.unseal"), WorkshopAction.UNSEAL, 0);
            if (v.state() == null && com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.isIngredient(held)) {
                return Target.send(part, t("mortar.place", held.getHoverName()), WorkshopAction.INSERT, 0)
                        .withBatch(WorkshopAction.INSERT, 0, t("mortar.place_all", held.getHoverName()));
            }
            if (running) return Target.info(part, Component.translatable(m.getString("cue")));
            if (!bowl.isEmpty()) {
                Target info = new Target(part, false, preparable ? t("mortar.loaded", bowl.getCount(), bowl.getHoverName())
                        : t("mortar.unprofiled", bowl.getHoverName()), Local.NONE, null, 0, null, 0, null);
                return info.withBatch(WorkshopAction.TAKE, 0, t("take", bowl.getHoverName()));
            }
            return Target.info(part, t("mortar.empty"));
        }
        if (id == WorkshopParts.M_PESTLE || id == WorkshopParts.M_SCRAPER) {
            if (mine(v, id) || hand.tool() == id) return new Target(part, true, t("tool.put_down"), Local.PUT_DOWN, WorkshopAction.RELEASE, 0, null, 0, null);
            String other = v.holder(id);
            if (other != null) return Target.info(part, t("tool.held_by", other));
            if (v.sealed()) return Target.info(part, t("mortar.sealed"));
            if (id == WorkshopParts.M_SCRAPER && !running) return Target.info(part, t("mortar.scraper.idle"));
            if (id == WorkshopParts.M_PESTLE && !running) {
                if (bowl.isEmpty()) return Target.info(part, t("mortar.pestle.empty"));
                if (!preparable) return Target.info(part, t("mortar.unprofiled", bowl.getHoverName()));
            }
            return new Target(part, true, t(id == WorkshopParts.M_PESTLE ? "mortar.pestle.take" : "mortar.scraper.take"), Local.TAKE_TOOL,
                    WorkshopAction.GRAB, 0, null, 0, null);
        }
        if (id == WorkshopParts.M_CUP) {
            if (!running) return Target.info(part, t("mortar.cup.idle"));
            Component texture = Component.translatable(m.getString("cue"));
            if (!m.getBoolean("usable")) return Target.info(part, t("mortar.cup.not_yet", texture));
            return Target.send(part, t("mortar.cup.finish", m.getByte("units"), texture), WorkshopAction.FINISH, 0);
        }
        if (id >= WorkshopParts.M_SHELF && id < WorkshopParts.M_SHELF + ApothecaryMortarBlockEntity.OUTPUT_SIZE) {
            ItemStack kit = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + id - WorkshopParts.M_SHELF);
            return kit.isEmpty() ? Target.info(part, t("mortar.shelf")) : Target.send(part, t("take", kit.getHoverName()), WorkshopAction.TAKE, 0);
        }
        return Target.info(part, Component.translatable(part.key()));
    }

    // ---- alembic ----

    private static Target alembic(View v, WorkshopPart part, ClientWorkshop.Hand hand) {
        AlembicBlockEntity alembic = (AlembicBlockEntity) v.be();
        CompoundTag a = v.alembic();
        boolean running = v.running() && v.operation() == Operation.PURIFY;
        int id = part.id();
        ItemStack held = held();
        boolean loaded = false;
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) loaded |= !alembic.inventory().getStackInSlot(AlembicBlockEntity.INPUT + i).isEmpty();
        if (id == WorkshopParts.A_INPUT) {
            if (held.getItem() instanceof PotionItem && v.state() == null) return Target.send(part, t("alembic.input.place", held.getHoverName()), WorkshopAction.INSERT, 0);
            if (v.state() != null) return Target.info(part, t("alembic.input.charged", v.snapshot().getInt("chargeCount")));
            if (loaded) return Target.send(part, t("alembic.input.take"), WorkshopAction.TAKE, 0).withBatch(WorkshopAction.TAKE, 0, t("alembic.input.take_all"));
            return Target.info(part, t("alembic.input.empty"));
        }
        if (id == WorkshopParts.A_CHARGE) {
            if (v.sealed()) return Target.send(part, t("alembic.unseal"), WorkshopAction.UNSEAL, 0);
            if (running) return Target.info(part, Component.translatable(a.getString("cue")));
            if (v.state() == null && loaded) {
                // The fitting decides which operation the charge commits; its outcome is shown before anything is used.
                CompoundTag snap = v.snapshot();
                boolean reclaim = snap.getBoolean("reclaimMode");
                Component outcome = reclaim ? reclaimOutcome(snap) : purifyOutcome(snap);
                boolean possible = reclaim ? !snap.contains("reclaimProblem") : !snap.contains("previewProblem");
                if (!possible) return Target.info(part, outcome);
                return Target.send(part, outcome, WorkshopAction.FINISH, 0);
            }
            if (v.state() != null && v.operation() == Operation.RECLAIM) return Target.info(part, t("alembic.reclaiming", v.snapshot().getByte("reclaimProgress")));
            return Target.info(part, v.state() == null ? t("alembic.charge.empty") : alembic.view().status());
        }
        if (id == WorkshopParts.A_MODE) {
            CompoundTag snap = v.snapshot();
            boolean reclaim = snap.getBoolean("reclaimMode");
            if (v.state() != null) return Target.info(part, t(reclaim ? "alembic.mode.reclaim" : "alembic.mode.purify"));
            if (!reclaim && !snap.getBoolean("reclaimAllowed")) return Target.info(part, t("alembic.mode.purify_only"));
            // Turning the fitting shows the other operation's costs and outcome before it can be charged.
            Component other = loaded ? (reclaim ? purifyOutcome(snap) : reclaimOutcome(snap)) : t("alembic.charge.empty");
            return new Target(part, true, t(reclaim ? "alembic.mode.to_purify" : "alembic.mode.to_reclaim", other), Local.TOGGLE,
                    WorkshopAction.SET_CONTROL, reclaim ? 0 : 1, null, 0, null);
        }
        if (id == WorkshopParts.A_CLAMP) {
            if (v.sealed()) return Target.send(part, t("alembic.unseal"), WorkshopAction.UNSEAL, 0);
            if (!running) return Target.info(part, t("alembic.clamp.idle"));
            boolean clamped = a.getBoolean("clamped");
            Target toggle = new Target(part, true, t(clamped ? "alembic.clamp.open" : "alembic.clamp.close"), Local.TOGGLE, WorkshopAction.SET_CONTROL,
                    clamped ? 0 : 1, null, 0, null);
            return toggle.withBatch(WorkshopAction.CANCEL, 0, t("alembic.withdraw"));
        }
        if (id == WorkshopParts.A_BURNER || id == WorkshopParts.A_VALVE) {
            if (!running) return Target.info(part, t(id == WorkshopParts.A_BURNER ? "alembic.burner.off" : "alembic.valve.off"));
            int value = a.getByte(id == WorkshopParts.A_BURNER ? "burner" : "valve");
            String other = v.holder(id);
            if (other != null && !mine(v, id)) return Target.info(part, t("tool.held_by", other));
            return new Target(part, true, t(id == WorkshopParts.A_BURNER ? "alembic.burner" : "alembic.valve", value, 4), Local.CONTROL,
                    WorkshopAction.SET_CONTROL, value, null, 0, null);
        }
        if (id == WorkshopParts.A_DIVERTER) {
            if (!running) return Target.info(part, t("alembic.diverter.idle"));
            boolean receiver = a.getBoolean("receiver");
            return new Target(part, true, t(receiver ? "alembic.diverter.to_waste" : "alembic.diverter.to_receiver",
                    Component.translatable("condensate.immersive_brewing." + condensate(a))), Local.TOGGLE, WorkshopAction.SET_CONTROL, receiver ? 0 : 1, null, 0, null);
        }
        if (id == WorkshopParts.A_FUEL) {
            if (held.is(ModTags.BURNER_FUEL)) return Target.send(part, t("kettle.fuel.add"), WorkshopAction.INSERT, 0);
            return Target.info(part, t("alembic.fuel", alembic.fuelCharges()));
        }
        if (id == WorkshopParts.A_COOLANT) {
            int mb = v.snapshot().getInt("coolantMb"), cap = v.snapshot().getInt("coolantCapacity");
            boolean water = held.is(Items.POTION) && PotionUtils.getPotion(held) == Potions.WATER;
            if (water) return Target.send(part, t("alembic.coolant.pour", mb, cap), WorkshopAction.INSERT, 0);
            return Target.info(part, t("alembic.coolant", mb, cap, v.snapshot().getInt("coolantNeed")));
        }
        if (id == WorkshopParts.A_RECEIVER || id == WorkshopParts.A_WASTE) {
            boolean any = false;
            for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) any |= !alembic.inventory().getStackInSlot(AlembicBlockEntity.OUTPUT + i).isEmpty();
            if (id == WorkshopParts.A_RECEIVER && any) return Target.send(part, t("alembic.receiver.take"), WorkshopAction.TAKE, 0)
                    .withBatch(WorkshopAction.TAKE, 0, t("alembic.receiver.take_all"));
            return Target.info(part, t(id == WorkshopParts.A_RECEIVER ? "alembic.receiver" : "alembic.waste",
                    a.getByte(id == WorkshopParts.A_RECEIVER ? "toReceiver" : "toWaste")));
        }
        return Target.info(part, Component.translatable(part.key()));
    }

    /** What a purification charge would do (or why it can not), from the snapshot's preview. */
    static Component purifyOutcome(CompoundTag snap) {
        if (snap.contains("previewProblem")) {
            Component problem = Component.Serializer.fromJson(snap.getString("previewProblem"));
            return problem == null ? t("alembic.charge.empty") : t("alembic.charge.cannot_purify", problem);
        }
        return t("alembic.charge", snap.getInt("previewIn"), snap.getInt("previewOut"), snap.getInt("previewReturned"), snap.getInt("previewCoolant"));
    }

    /** What a reclamation would do (or why it can not). */
    static Component reclaimOutcome(CompoundTag snap) {
        if (snap.contains("reclaimProblem")) {
            Component problem = Component.Serializer.fromJson(snap.getString("reclaimProblem"));
            return problem == null ? t("alembic.charge.empty") : t("alembic.charge.cannot_reclaim", problem);
        }
        ItemStack result = ItemStack.of(snap.getCompound("reclaimResult"));
        return t("alembic.reclaim", snap.getInt("reclaimUsed"), snap.getInt("reclaimOut"), result.getHoverName(), snap.getInt("reclaimUsed"),
                snap.getInt("reclaimFuel"));
    }

    public static String condensate(CompoundTag a) {
        int c = a.getByte("condensate");
        var all = com.otectus.immersivebrewing.workshop.AlembicProcess.Condensate.values();
        return all[Math.max(0, Math.min(all.length - 1, c))].name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---- rack ----

    private static Target rack(View v, WorkshopPart part) {
        ApothecaryRackBlockEntity rack = (ApothecaryRackBlockEntity) v.be();
        ItemStack held = held();
        if (part.id() == WorkshopParts.R_CRADLE) {
            if (!(held.getItem() instanceof BatchCarrierItem)) return Target.info(part, t("rack.cradle"));
            return Target.send(part, t("rack.pack", CarrierContents.count(held)), WorkshopAction.PACK, 0)
                    .withBatch(WorkshopAction.UNPACK, 0, t("rack.unpack"));
        }
        int slot = part.id() - WorkshopParts.R_POSITION;
        ItemStack stack = rack.inventory().getStackInSlot(slot);
        String label = rack.label(slot);
        if (!stack.isEmpty()) {
            Component name = label == null || label.isEmpty() ? stack.getHoverName() : Component.literal(label);
            var meta = com.otectus.immersivebrewing.potion.BrewingMetadata.read(stack);
            Component take = meta.isPresent()
                    ? t("take_graded", stack.getCount(), name, Component.translatable(com.otectus.immersivebrewing.potion.Grade.of(meta.get().quality()).key()))
                    : t("take", name);
            return Target.send(part, take, WorkshopAction.TAKE, 0).withBatch(WorkshopAction.TAKE_GROUP, 0, t("take_matching", name));
        }
        if (CarrierContents.accepts(held)) return Target.send(part, t("rack.place", held.getHoverName()), WorkshopAction.INSERT, 0);
        ItemStack ghost = rack.filter(slot);
        return Target.info(part, ghost.isEmpty() ? t("rack.empty") : t("rack.reserved_for", ghost.getHoverName()));
    }

    private WorkshopTargets() {}
}
