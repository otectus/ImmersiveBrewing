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

    static int results(GameTestHelper h, BlockPos relative) {
        AtomicInteger n = RESULTS.get(h.absolutePos(relative));
        return n == null ? 0 : n.get();
    }

    static ItemStack water() {
        return PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER);
    }

    static BrewersKettleBlockEntity kettle(GameTestHelper h) {
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

    static void openAlembic(FakePlayer player, com.otectus.immersivebrewing.blockentity.AlembicBlockEntity alembic) {
        player.containerMenu = new com.otectus.immersivebrewing.menu.AlembicMenu(0, player.getInventory(), alembic.getBlockPos());
    }

    static void driveAlembic(GameTestHelper h, FakePlayer player, com.otectus.immersivebrewing.testing.DistillBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Distillation d)) return;
            int step = d.sim().step();
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {bot.decide(d.sim()).pack()}));
        }));
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

    /**
     * Each tick, sends the bot's frames for the next steps through the real input packet path. Frames are decided from
     * the server's current state a few steps ahead of use, like a client playing with ordinary latency.
     */
    static void driveKettle(GameTestHelper h, FakePlayer player, KettleBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Brewing brewing)) return;
            int step = brewing.sim().step();
            KettleInput frame = bot.decide(brewing.sim());
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {frame.pack()}));
        }));
    }

    static void driveMortar(GameTestHelper h, FakePlayer player, MortarBot bot) {
        int[] sequence = {0};
        h.onEachTick(() -> session(player).ifPresent(s -> {
            if (!(s.sim() instanceof SimHandles.Preparation prep)) return;
            int step = prep.sim().step();
            SessionManager.receive(player, new InputFramesC2S(s.id(), sequence[0]++, step + s.bufferTicks(), new int[] {bot.decide(prep.sim()).pack()}));
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
