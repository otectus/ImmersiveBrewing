package com.otectus.immersivebrewing.blockentity;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.advancement.Milestone;
import com.otectus.immersivebrewing.advancement.ModAdvancements;
import com.otectus.immersivebrewing.api.event.ReagentPreparedEvent;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.menu.MortarMenu;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationScore;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.registry.ModBlockEntities;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModSounds;
import com.otectus.immersivebrewing.registry.ModTags;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PotionItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.MinecraftForge;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * The Apothecary's Mortar (spec 6): one reagent bowl and a four-slot kit shelf. A preparation moves up to four
 * profiled reagents into its ledger; the commit turns exactly those into the same number of kits, all with the earned
 * extraction. Unprofiled reagents are refused: they brew at the raw extraction of 50 without preparation.
 */
public class ApothecaryMortarBlockEntity extends StationBlockEntity {
    public static final int INPUT = 0;
    public static final int OUTPUT = 1;
    public static final int OUTPUT_SIZE = 4;
    public static final int SLOTS = 5;

    public ApothecaryMortarBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.APOTHECARY_MORTAR.get(), pos, state, SLOTS);
    }

    @Override
    public ResourceLocation stationId() {
        return ImmersiveBrewing.id("apothecary_mortar");
    }

    @Override
    protected int slotLimit(int slot) {
        return 64;
    }

    @Override
    protected boolean canPlace(int slot, ItemStack stack) {
        if (slot != INPUT || ledger != null) return false;
        if (stack.is(ModTags.CARRIER_EXCLUDED) || stack.getItem() instanceof PotionItem || stack.is(ModItems.PREPARED_REAGENT_KIT.get())) return false;
        return true;
    }

    @Override
    protected boolean automationInsert(@Nullable Direction side, int slot, ItemStack stack) {
        return side != Direction.DOWN && slot == INPUT && canPlace(slot, stack);
    }

    @Override
    protected boolean automationExtract(@Nullable Direction side, int slot) {
        return (side == null || side == Direction.DOWN) && slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE;
    }

    @Override
    protected boolean isOutputSlot(int slot) {
        return slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE;
    }

    @Override
    protected boolean loaded() {
        return !inventory.getStackInSlot(INPUT).isEmpty();
    }

    @Override
    protected boolean emptyForRecovery() {
        return inventory.getStackInSlot(INPUT).isEmpty() && freeOutput() >= 0;
    }

    /** The preparation profile for the bowl's reagent, or null when it is unprofiled or excluded. */
    @Nullable
    public ReagentProfile profileFor(ItemStack stack) {
        if (stack.isEmpty()) return null;
        ProfileSelector.Choice<ReagentProfile> choice = BrewingDefinitions.server().reagentProfile(stack.getItem(), null, null);
        return choice != null && choice.value().preparation() != null ? choice.value() : null;
    }

    @Override
    public void handleAction(ServerPlayer player, StationActionC2S.Action action, int argument) {
        switch (action) {
            case START_PREPARATION -> start(player, argument);
            case RESUME -> resume(player);
            default -> super.handleAction(player, action, argument);
        }
    }

    public void start(ServerPlayer player, int kits) {
        if (level == null) return;
        if (ServerConfig.disabled()) {
            notice(player, Component.translatable("notice.immersive_brewing.disabled"), true);
            return;
        }
        if (ledger != null || SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        ItemStack input = inventory.getStackInSlot(INPUT);
        ReagentProfile profile = profileFor(input);
        if (profile == null) {
            notice(player, Component.translatable(input.isEmpty() ? "notice.immersive_brewing.mortar.empty" : "notice.immersive_brewing.mortar.unprofiled"), true);
            return;
        }
        int count = Math.max(1, Math.min(PreparationParams.MAX_KITS, StationActionC2S.base(kits)));
        double[] assist = approvedAssist(kits);
        if (input.getCount() < count) {
            notice(player, Component.translatable("notice.immersive_brewing.mortar.not_enough", count), true);
            return;
        }
        if (freeOutput() < 0) {
            notice(player, Component.translatable("notice.immersive_brewing.mortar.shelf_full"), true);
            return;
        }
        ReagentProfile.Preparation prep = profile.preparation();
        long[] params = PreparationParams.of(prep.mode(), prep.durationTicks(), prep.endpointLow(), prep.endpointHigh(), prep.pressureLimit(),
                prep.overworkThreshold(), count, assist[0]).encode();
        ItemStack taken = input.copyWithCount(count);
        putDirect(INPUT, input.copyWithCount(input.getCount() - count));
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("profile", profile.id().toString());
        snapshot.putInt("kits", count);
        long seed = ((ServerLevel) level).getRandom().nextLong();
        ledger = new JobLedger(UUID.randomUUID(), Operation.PREPARE, JobState.RESERVED, List.of(taken), List.of(), 0, snapshot,
                new KitData(taken, 1, profile.id(), 0).toStack(count), params, new long[0], seed, 0, player.getUUID(),
                player.getGameProfile().getName(), List.of(), null, 0, BrewingDefinitions.server().generation(), 0);
        changed();
        SimHandle sim = SimHandles.create(SessionKind.PREPARATION, params, null);
        player.closeContainer();
        SessionManager.begin(player, this, sim, false, display(taken, profile.id(), count));
    }

    private CompoundTag display(ItemStack reagent, ResourceLocation profile, int kits) {
        CompoundTag tag = new CompoundTag();
        tag.put("reagent", LedgerCodec.saveStack(reagent.copyWithCount(1)));
        tag.putString("profile", profile.toString());
        tag.putInt("kits", kits);
        return tag;
    }

    public void resume(ServerPlayer player) {
        revalidateRecovery();
        if (ledger == null || ledger.state() != JobState.SEALED || ledger.operation() != Operation.PREPARE) {
            notice(player, Component.translatable("notice.immersive_brewing.nothing_to_resume"), true);
            return;
        }
        if (SessionManager.stationBusy(this) || SessionManager.playerBusy(player)) {
            notice(player, Component.translatable("notice.immersive_brewing.busy"), true);
            return;
        }
        if (freeOutput() < 0) {
            notice(player, Component.translatable("notice.immersive_brewing.mortar.shelf_full"), true);
            return;
        }
        SimHandle sim;
        try {
            sim = SimHandles.create(SessionKind.PREPARATION, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            ledger.hold("recovery.immersive_brewing.state_unreadable");
            changed();
            return;
        }
        player.closeContainer();
        ItemStack reagent = ledger.inputs().isEmpty() ? ItemStack.EMPTY : ledger.inputs().get(0);
        SessionManager.begin(player, this, sim, true, display(reagent, new ResourceLocation(ledger.snapshot().getString("profile")),
                ledger.snapshot().getInt("kits")));
    }

    private int freeOutput() {
        for (int i = 0; i < OUTPUT_SIZE; i++) if (inventory.getStackInSlot(OUTPUT + i).isEmpty()) return OUTPUT + i;
        return -1;
    }

    /** Releases old completed jobs held only by a full shelf; unreadable jobs remain safely held. */
    @Override
    public void revalidateRecovery() {
        if (ledger == null || ledger.unknownSchema() != null || ledger.operation() != Operation.PREPARE
                || ledger.state() != JobState.RECOVERY || !"recovery.immersive_brewing.no_room".equals(ledger.recoveryReason())
                || freeOutput() < 0) return;
        if (ledger.inputs().isEmpty() || ResourceLocation.tryParse(ledger.snapshot().getString("profile")) == null) return;
        try {
            SimHandles.create(SessionKind.PREPARATION, ledger.params(), ledger.simState());
        } catch (IllegalArgumentException e) {
            return;
        }
        ledger.clearRecovery();
        ledger.setState(JobState.SEALED);
        changed();
    }

    @Override
    protected void onInventoryChanged(int slot) {
        super.onInventoryChanged(slot);
        if (slot >= OUTPUT && slot < OUTPUT + OUTPUT_SIZE && level != null && !level.isClientSide) revalidateRecovery();
    }

    @Override
    protected void cancelReservation() {
        if (ledger == null) return;
        JobLedger l = ledger;
        ledger = null;
        for (ItemStack s : l.inputs()) {
            ItemStack live = inventory.getStackInSlot(INPUT);
            if (live.isEmpty()) putDirect(INPUT, s.copy());
            else if (ItemStack.isSameItemSameTags(live, s)) putDirect(INPUT, live.copyWithCount(live.getCount() + s.getCount()));
            else if (level != null) net.minecraft.world.Containers.dropItemStack(level, worldPosition.getX() + 0.5, worldPosition.getY() + 1, worldPosition.getZ() + 0.5, s.copy());
        }
        changed();
    }

    @Override
    @Nullable
    public CompoundTag completeJob(@Nullable ServerPlayer player, SimHandle sim) {
        if (ledger == null || ledger.operation() != Operation.PREPARE || ledger.state() != JobState.RUNNING) return null;
        if (!(sim instanceof SimHandles.Preparation prep)) return null;
        PreparationScore score = prep.sim().score();
        int slot = freeOutput();
        ResourceLocation profile = ResourceLocation.tryParse(ledger.snapshot().getString("profile"));
        if (slot < 0 || profile == null || ledger.inputs().isEmpty()) {
            ledger.setSimState(sim.saveState());
            ledger.hold(slot < 0 ? "recovery.immersive_brewing.no_room" : "recovery.immersive_brewing.state_unreadable");
            changed();
            return null;
        }
        JobLedger l = ledger;
        ledger = null;
        ItemStack source = l.inputs().get(0);
        int kits = source.getCount();
        ItemStack output = new KitData(source.copyWithCount(1), 1, profile, score.extraction()).toStack(kits);
        putDirect(slot, output);
        finished[slot] = true;
        lastResult = new StationResult(Operation.PREPARE, output.copyWithCount(1), kits, score.extraction(), score.extraction(), 0,
                score.tipKey(), List.of(), l.brewerName());
        changed();
        if (level != null) level.playSound(null, worldPosition, ModSounds.PESTLE_LIFT.get(), SoundSource.BLOCKS, 0.8F, 1.0F);
        MinecraftForge.EVENT_BUS.post(new ReagentPreparedEvent(player, worldPosition, output, score.extraction()));
        if (player != null) ModAdvancements.milestone(player, Milestone.PREPARE_KIT);
        CompoundTag result = new CompoundTag();
        result.putString("kind", "preparation");
        result.put("output", LedgerCodec.saveStack(output.copyWithCount(1)));
        result.putInt("count", kits);
        result.putInt("E", score.extraction());
        result.putDouble("accuracy", score.accuracy());
        result.putDouble("restraint", score.restraint());
        result.putString("texture", "texture.immersive_brewing." + score.texture().name().toLowerCase(java.util.Locale.ROOT));
        result.putInt("folds", score.folds());
        result.putString("tip", score.tipKey());
        return result;
    }

    @Override
    protected Component status() {
        if (ledger != null) {
            return switch (ledger.state()) {
                case RESERVED, RUNNING -> Component.translatable("status.immersive_brewing.mortar.running");
                case SEALED -> Component.translatable("status.immersive_brewing.mortar.sealed");
                case RECOVERY -> Component.translatable("status.immersive_brewing.recovery", Component.translatable(
                        ledger.recoveryReason() == null ? "recovery.immersive_brewing.unknown" : ledger.recoveryReason()));
            };
        }
        ItemStack input = inventory.getStackInSlot(INPUT);
        if (input.isEmpty()) return anyFinished() ? Component.translatable("status.immersive_brewing.mortar.kits_ready")
                : Component.translatable("status.immersive_brewing.mortar.empty");
        if (profileFor(input) == null) return Component.translatable("status.immersive_brewing.mortar.unprofiled", input.getHoverName());
        return Component.translatable("status.immersive_brewing.mortar.loaded", input.getCount(), input.getHoverName());
    }

    @Override
    protected boolean litNow() {
        return false;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.immersive_brewing.apothecary_mortar");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new MortarMenu(id, inventory, worldPosition);
    }
}
