package com.otectus.immersivebrewing.test;

import com.mojang.authlib.GameProfile;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.menu.MortarMenu;
import com.otectus.immersivebrewing.minigame.brewing.KettleInput;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.ActiveSession;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Shared GameTest plumbing: players with a station menu open, loaded stations, and bots that play through the real input path. */
final class TestSupport {
    static final BlockPos STATION = new BlockPos(4, 1, 4);
    /** Every server-to-client message sent to each fake player, in order. */
    private static final Map<UUID, List<Object>> MESSAGES = new ConcurrentHashMap<>();
    /** BrewResultEvent.Post count per absolute station position. */
    private static final Map<BlockPos, AtomicInteger> RESULTS = new ConcurrentHashMap<>();

    static {
        com.otectus.immersivebrewing.network.ModNetwork.fakePlayerSink = (player, message) ->
                MESSAGES.computeIfAbsent(player.getUUID(), id -> new CopyOnWriteArrayList<>()).add(message);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((com.otectus.immersivebrewing.api.event.BrewResultEvent.Post e) ->
                RESULTS.computeIfAbsent(e.station().immutable(), pos -> new AtomicInteger()).incrementAndGet());
    }

    static FakePlayer player(GameTestHelper h, String name) {
        return player(h, name, () -> false);
    }

    /** A fake player that reports itself disconnected whenever {@code disconnected} says so. */
    static FakePlayer player(GameTestHelper h, String name, BooleanSupplier disconnected) {
        FakePlayer player = new FakePlayer(h.getLevel(), new GameProfile(UUID.randomUUID(), name)) {
            @Override
            public boolean hasDisconnected() {
                return disconnected.getAsBoolean();
            }
        };
        BlockPos pos = h.absolutePos(STATION);
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 2.5);
        return player;
    }

    static List<Object> messages(FakePlayer player) {
        return MESSAGES.getOrDefault(player.getUUID(), List.of());
    }

    /** The reason of the last session end sent to the player, or null. */
    static String lastEndReason(FakePlayer player) {
        String reason = null;
        for (Object m : messages(player)) {
            if (m instanceof com.otectus.immersivebrewing.network.packet.SessionEndS2C end) reason = end.reason();
        }
        return reason;
    }

    /** The reason of the last working-view close sent to the player ("" for a plain close), or null. */
    static String lastFocusEnd(FakePlayer player) {
        String reason = null;
        for (Object m : messages(player)) {
            if (m instanceof com.otectus.immersivebrewing.network.packet.WorkshopFocusS2C focus && !focus.open()) reason = focus.reason();
        }
        return reason;
    }

    static int results(GameTestHelper h, BlockPos relative) {
        AtomicInteger n = RESULTS.get(h.absolutePos(relative));
        return n == null ? 0 : n.get();
    }

    static ItemStack water() {
        return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
    }

    static BrewersKettleBlockEntity kettle(GameTestHelper h) {
        // Batches reuse structure positions: a count left by an earlier test at this spot must not leak in.
        RESULTS.remove(h.absolutePos(STATION));
        h.setBlock(STATION, ModBlocks.BREWERS_KETTLE.get());
        return (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
    }

    static ApothecaryMortarBlockEntity mortar(GameTestHelper h, BlockPos at) {
        h.setBlock(at, ModBlocks.APOTHECARY_MORTAR.get());
        return (ApothecaryMortarBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(at));
    }

    /** Loads bottles, tray stacks, blaze powder and a paddle. */
    static void load(BrewersKettleBlockEntity kettle, int bottles, int powder, ItemStack... tray) {
        for (int i = 0; i < bottles; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, water());
        for (int i = 0; i < tray.length; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY + i, tray[i]);
        if (powder > 0) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER, powder));
        kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
    }

    static com.otectus.immersivebrewing.blockentity.AlembicBlockEntity alembic(GameTestHelper h) {
        h.setBlock(STATION, ModBlocks.ALEMBIC.get());
        return (com.otectus.immersivebrewing.blockentity.AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
    }

    /** Fills the Alembic's coolant jacket through its fluid capability (as a pipe or bucket would). */
    static int coolant(com.otectus.immersivebrewing.blockentity.AlembicBlockEntity alembic, int mb) {
        return alembic.getCapability(net.minecraftforge.common.capabilities.ForgeCapabilities.FLUID_HANDLER, net.minecraft.core.Direction.UP)
                .map(handler -> handler.fill(new net.minecraftforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER, mb),
                        net.minecraftforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)).orElse(0);
    }

    static void openAlembic(FakePlayer player, com.otectus.immersivebrewing.blockentity.AlembicBlockEntity alembic) {
        player.containerMenu = new com.otectus.immersivebrewing.menu.AlembicMenu(0, player.getInventory(), alembic.getBlockPos());
    }

    /** Each tick, tends the player's open Alembic through the real intent path. */
    static void driveAlembic(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.AlembicTender tender) {
        h.onEachTick(() -> {
            BlockPos at = workingAt(player);
            if (at != null && h.getLevel().getBlockEntity(at) instanceof com.otectus.immersivebrewing.blockentity.AlembicBlockEntity alembic) {
                tender.tick(alembic, new com.otectus.immersivebrewing.testing.WorkshopHand(player, at));
            }
        });
    }

    /** The station whose working view the player has open, or null. */
    @org.jetbrains.annotations.Nullable
    static BlockPos workingAt(FakePlayer player) {
        var presence = com.otectus.immersivebrewing.interaction.WorkshopPresence.of(player.getUUID());
        return presence == null ? null : presence.pos();
    }

    /** The station whose working view the player has open, or null. */
    @org.jetbrains.annotations.Nullable
    static StationBlockEntity station(FakePlayer player) {
        BlockPos at = workingAt(player);
        return at != null && player.level().getBlockEntity(at) instanceof StationBlockEntity be ? be : null;
    }

    /** Whether the player's open station holds a running hands-on job (what "the session started" used to assert). */
    static boolean working(FakePlayer player) {
        StationBlockEntity be = station(player);
        return be != null && be.ledger() != null && be.ledger().physical()
                && be.ledger().state() == com.otectus.immersivebrewing.batch.JobState.RUNNING;
    }

    /** Seals the job at the player's open station by hand (the lid, clamp or bowl), as closing the minigame used to. */
    static void seal(FakePlayer player) {
        StationBlockEntity be = station(player);
        if (be == null) throw new IllegalStateException("no open station to seal");
        int part = switch (be.workshopKind()) {
            case KETTLE -> com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID;
            case ALEMBIC -> com.otectus.immersivebrewing.workshop.WorkshopParts.A_CLAMP;
            default -> com.otectus.immersivebrewing.workshop.WorkshopParts.M_BOWL;
        };
        hand(player, be).act(part, com.otectus.immersivebrewing.workshop.WorkshopAction.SEAL, 0);
    }

    /** Reopens a sealed hands-on job at a station by hand (opening the view first, as a returning player does). */
    static void unseal(FakePlayer player, StationBlockEntity be) {
        be.openWorkingView(player);
        int part = switch (be.workshopKind()) {
            case KETTLE -> com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID;
            case ALEMBIC -> com.otectus.immersivebrewing.workshop.WorkshopParts.A_CLAMP;
            default -> com.otectus.immersivebrewing.workshop.WorkshopParts.M_BOWL;
        };
        hand(player, be).act(part, com.otectus.immersivebrewing.workshop.WorkshopAction.UNSEAL, 0);
    }

    /** A one-line account of a kettle's batch for assertion messages. */
    static String describe(BrewersKettleBlockEntity kettle) {
        if (kettle.ledger() == null) {
            StringBuilder rack = new StringBuilder("no batch; rack");
            for (int i = 0; i < 6; i++) rack.append(' ').append(kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i));
            return rack.toString();
        }
        var p = kettle.liveProcess();
        String process = p == null ? "process unreadable" : String.format(java.util.Locale.ROOT,
                "%s temp %.2f burner %d circ %.2f doses %d/%d progress %.2f undissolved %.2f trouble %.2f rest %.2f portions %d",
                p.stage(), p.temperature(), p.burner(), p.circulation(), p.doses(), p.setup().steps(), p.progress(), p.undissolved(), p.trouble(),
                p.restProgress(), p.portions());
        return kettle.ledger().state() + " " + kettle.ledger().operation() + (kettle.ledger().recoveryReason() == null ? "" : " (" + kettle.ledger().recoveryReason() + ")")
                + ": " + process;
    }

    /** Withdraws an untouched batch at the player's open station (no dose or condensate yet): exact return. */
    static com.otectus.immersivebrewing.interaction.WorkshopResult withdraw(FakePlayer player) {
        StationBlockEntity be = station(player);
        if (be == null) throw new IllegalStateException("no open station");
        int part = be.workshopKind() == com.otectus.immersivebrewing.workshop.WorkshopKind.ALEMBIC
                ? com.otectus.immersivebrewing.workshop.WorkshopParts.A_CLAMP : com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID;
        return hand(player, be).act(part, com.otectus.immersivebrewing.workshop.WorkshopAction.CANCEL, 0);
    }

    /** Container finishing by hand: every modifier tipped into the fitting in order, then the fitting closed. */
    static void finishByHand(FakePlayer player, BrewersKettleBlockEntity kettle) {
        kettle.openWorkingView(player);
        var hand = hand(player, kettle);
        int steps = kettle.buildWorkshopSnapshot().getCompound("finishing").getByte("steps");
        for (int i = 0; i < steps; i++) {
            var r = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_CUP + i, com.otectus.immersivebrewing.workshop.WorkshopAction.DOSE, i);
            if (!r.accepted()) throw new IllegalStateException("finishing dose " + i + " refused: " + r.reason());
        }
        var closed = hand.act(com.otectus.immersivebrewing.workshop.WorkshopParts.K_LID, com.otectus.immersivebrewing.workshop.WorkshopAction.SET_CONTROL, 1);
        if (!closed.accepted()) throw new IllegalStateException("closing the fitting was refused: " + closed.reason());
    }

    /** A hand for intents at a station (the player must have its working view open). */
    static com.otectus.immersivebrewing.testing.WorkshopHand hand(FakePlayer player, StationBlockEntity station) {
        return new com.otectus.immersivebrewing.testing.WorkshopHand(player, station.getBlockPos());
    }

    /** Opens the working view the way an ordinary use does (presence registered on the server). */
    static void openView(FakePlayer player, StationBlockEntity station) {
        station.openWorkingView(player);
    }

    static void openKettle(FakePlayer player, BrewersKettleBlockEntity kettle) {
        player.containerMenu = new KettleMenu(0, player.getInventory(), kettle.getBlockPos());
    }

    static void openMortar(FakePlayer player, ApothecaryMortarBlockEntity mortar) {
        player.containerMenu = new MortarMenu(0, player.getInventory(), mortar.getBlockPos());
    }

    /** The plan the Kettle offers for its rack and tray whose output is {@code output} with the fewest steps. */
    static BrewPlan plan(GameTestHelper h, BrewersKettleBlockEntity kettle, ItemStack output) {
        return kettle.plans().stream()
                .filter(p -> ItemStack.isSameItemSameTags(p.output(), output))
                .min(java.util.Comparator.comparingInt(p -> p.steps().size()))
                .orElseThrow(() -> new IllegalStateException("no plan to " + output));
    }

    static Optional<ActiveSession> session(FakePlayer player) {
        return SessionManager.byPlayer(player.getUUID());
    }

    /** Each tick, tends the player's open Kettle through the real intent path (envelope, budget, reach, sight). */
    static void driveKettle(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.KettleTender tender) {
        driveKettle(h, player, tender, () -> true);
    }

    /**
     * Tends only while {@code when} holds. Register drivers at the start of a test: GameTest's own scheduler can not take
     * new per-tick work from inside a delayed callback.
     */
    static void driveKettle(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.KettleTender tender,
                            java.util.function.BooleanSupplier when) {
        h.onEachTick(() -> {
            if (!when.getAsBoolean()) return;
            BlockPos at = workingAt(player);
            if (at != null && h.getLevel().getBlockEntity(at) instanceof BrewersKettleBlockEntity kettle) {
                tender.tick(kettle, new com.otectus.immersivebrewing.testing.WorkshopHand(player, at));
            }
        });
    }

    static void driveMortar(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.MortarTender tender) {
        h.onEachTick(() -> {
            BlockPos at = workingAt(player);
            if (at != null && h.getLevel().getBlockEntity(at) instanceof ApothecaryMortarBlockEntity mortar) {
                tender.tick(mortar, new com.otectus.immersivebrewing.testing.WorkshopHand(player, at));
            }
        });
    }

    /** Legacy-path driver: sends a 1.1 bot's frames for a session resumed from an old saved batch (migration tests). */
    static void driveLegacyKettle(GameTestHelper h, FakePlayer player, KettleBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Brewing brewing)) return;
            int step = brewing.sim().step();
            KettleInput frame = bot.decide(brewing.sim());
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {frame.pack()}));
        }));
    }

    static void driveLegacyMortar(GameTestHelper h, FakePlayer player, MortarBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Preparation prep)) return;
            int step = prep.sim().step();
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {bot.decide(prep.sim()).pack()}));
        }));
    }

    static void driveLegacyAlembic(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.DistillBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Distillation d)) return;
            int step = d.sim().step();
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {bot.decide(d.sim()).pack()}));
        }));
    }

    /** Item entities within three blocks of a relative position. */
    static List<net.minecraft.world.entity.item.ItemEntity> drops(GameTestHelper h, BlockPos relative) {
        return h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                new net.minecraft.world.phys.AABB(h.absolutePos(relative)).inflate(3));
    }

    static int dropped(List<net.minecraft.world.entity.item.ItemEntity> drops, net.minecraft.world.item.Item item) {
        int n = 0;
        for (var e : drops) if (e.getItem().is(item)) n += e.getItem().getCount();
        return n;
    }

    static List<ItemStack> droppedStacks(List<net.minecraft.world.entity.item.ItemEntity> drops, net.minecraft.world.item.Item item) {
        List<ItemStack> out = new ArrayList<>();
        for (var e : drops) if (e.getItem().is(item)) out.add(e.getItem().copy());
        return out;
    }

    /** Right-clicks a station with {@code held} in the main hand; returns what is left in the hand. */
    static ItemStack use(FakePlayer player, StationBlockEntity station, ItemStack held) {
        player.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, held);
        BlockPos pos = station.getBlockPos();
        station.use(player, net.minecraft.world.InteractionHand.MAIN_HAND,
                new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(pos), net.minecraft.core.Direction.UP, pos, false));
        return player.getMainHandItem();
    }

    static int count(StationBlockEntity station, int from, int size, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < size; i++) {
            ItemStack s = station.inventory().getStackInSlot(from + i);
            if (s.is(item)) n += s.getCount();
        }
        return n;
    }

    private TestSupport() {}
}
