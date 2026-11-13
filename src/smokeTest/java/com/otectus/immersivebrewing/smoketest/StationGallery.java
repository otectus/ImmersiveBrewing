package com.otectus.immersivebrewing.smoketest;

import com.mojang.authlib.GameProfile;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.interaction.WorkshopPresence;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.testing.KettleTender;
import com.otectus.immersivebrewing.testing.WorkshopHand;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The station gallery for the screenshot review (spec 26.6): a second row of stations in states the main walkthrough
 * does not leave on screen, one per horizontal facing. Stand-in players (server-side fake players, never in the player
 * list) set out real hands-on batches and tend them with the reference {@link KettleTender} through the real intent
 * path: an empty Kettle (east), a Kettle being tended (west), a sealed Kettle (south), a Kettle held for recovery
 * (north) and an Alembic reclaiming on its own (east). Everything runs through the normal station and workshop code;
 * nothing is drawn specially.
 */
@Mod.EventBusSubscriber(modid = SmokeTestAgent.MOD_ID, value = Dist.CLIENT)
public final class StationGallery {
    static BlockPos row;
    private static final Map<UUID, FakePlayer> DRIVEN = new ConcurrentHashMap<>();
    private static final Map<UUID, KettleTender> TENDERS = new ConcurrentHashMap<>();
    private static FakePlayer sealer;
    private static FakePlayer holder;
    static BlockPos denseBase;
    private static final Map<UUID, BlockPos> DENSE = new ConcurrentHashMap<>();
    public static volatile int denseWorking, denseWorkingMin, denseWorkingMax, denseRestarts;

    static BlockPos at(int dx) {
        return row.offset(dx, 0, 0);
    }

    /** Server thread: places the row and starts its jobs. */
    static void build(ServerLevel level, BlockPos base) {
        row = base.offset(0, 0, -6);
        for (int dx = -4; dx <= 4; dx += 2) level.setBlock(at(dx).below(), Blocks.STONE.defaultBlockState(), 3);
        kettle(level, at(-4), Direction.EAST);
        drive(start(level, loaded(kettle(level, at(-2), Direction.WEST)), "Gallery Brewer"));
        sealer = start(level, loaded(kettle(level, at(0), Direction.SOUTH)), "Gallery Sealer");
        drive(sealer);
        holder = start(level, loaded(kettle(level, at(2), Direction.NORTH)), "Gallery Keeper");
        drive(holder);
        AlembicBlockEntity alembic = (AlembicBlockEntity) place(level, at(4), ModBlocks.ALEMBIC.get(), Direction.EAST);
        for (int i = 0; i < 3; i++) alembic.inventory().setStackInSlot(AlembicBlockEntity.INPUT + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
        alembic.inventory().setStackInSlot(AlembicBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER));
        alembic.startReclamation(stand(level, at(4), "Gallery Recycler"));
    }

    /** Real dense fixture: every kettle is set out through the normal plan path and tended by a FakePlayer's hands. */
    static int denseKettles(ServerLevel level, BlockPos base, int count) {
        clearDense(level);
        denseBase = base.offset(128, 0, 0);
        denseWorkingMin = Integer.MAX_VALUE; denseWorkingMax = denseWorking = denseRestarts = 0;
        int started = 0;
        for (int i = 0; i < count; i++) {
            BlockPos pos = denseBase.offset((i % 8) * 2, 0, -(i / 8) * 2);
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            FakePlayer player = start(level, loaded(kettle(level, pos, Direction.NORTH)), "Dense Brewer " + i);
            if (player != null && WorkshopPresence.of(player.getUUID()) != null && level.getBlockEntity(pos) instanceof BrewersKettleBlockEntity k
                    && k.ledger() != null) { drive(player); DENSE.put(player.getUUID(), pos); started++; }
        }
        return started;
    }

    static int denseRacks(ServerLevel level, BlockPos base, int count) {
        clearDense(level); denseBase = base.offset(128, 0, 0);
        denseWorking = denseWorkingMax = denseRestarts = 0; denseWorkingMin = Integer.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            BlockPos pos = denseBase.offset((i % 16), 0, -(i / 16));
            level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), 3);
            var rack = (com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity) place(level, pos, ModBlocks.APOTHECARY_RACK.get(), Direction.NORTH);
            if ((i & 1) == 0) rack.inventory().setStackInSlot(0, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
            else rack.setFilter(0, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
        }
        return count;
    }

    /** Server thread, a few seconds in: one stand-in closes the lid and walks away, another does and the batch is held. */
    static void settle(ServerLevel level) {
        for (FakePlayer p : new FakePlayer[] {sealer, holder}) {
            if (p == null) continue;
            DRIVEN.remove(p.getUUID());
            TENDERS.remove(p.getUUID());
            var presence = WorkshopPresence.of(p.getUUID());
            if (presence != null && level.getBlockEntity(presence.pos()) instanceof StationBlockEntity station) {
                station.sealPhysical(p, "workshop.immersive_brewing.seal.manual");
            }
            WorkshopPresence.leave(p, "");
        }
        BrewersKettleBlockEntity held = (BrewersKettleBlockEntity) level.getBlockEntity(at(2));
        if (held != null && held.ledger() != null) {
            // A reason the periodic re-check can not clear on its own (a faked missing adapter is released at once).
            held.ledger().hold("recovery.immersive_brewing.source_changed");
            held.changed();
        }
    }

    /** The stand-ins' hands, before the stations tick: each tends the batch at the station whose working view it has open. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || DRIVEN.isEmpty()) return;
        for (FakePlayer p : DRIVEN.values()) {
            var presence = WorkshopPresence.of(p.getUUID());
            if (presence != null && p.serverLevel().getBlockEntity(presence.pos()) instanceof BrewersKettleBlockEntity kettle) {
                TENDERS.computeIfAbsent(p.getUUID(), id -> KettleTender.competent()).tick(kettle, new WorkshopHand(p, presence.pos()));
            }
        }
        int working = 0;
        for (var entry : DENSE.entrySet()) {
            FakePlayer p = DRIVEN.get(entry.getKey());
            if (p == null) continue;
            ServerLevel level = p.serverLevel();
            if (!(level.getBlockEntity(entry.getValue()) instanceof BrewersKettleBlockEntity kettle)) continue;
            if (kettle.ledger() != null) {
                if (kettle.ledger().state() == JobState.RUNNING && kettle.ledger().physical()) working++;
            } else {
                // The fixture discards completed outputs between measured runs; production inventory/recipe paths are unchanged.
                for (int slot = 0; slot < kettle.inventory().getSlots(); slot++) kettle.inventory().setStackInSlot(slot, ItemStack.EMPTY);
                loaded(kettle);
                TENDERS.remove(p.getUUID());
                setOut(level, kettle, p);
                if (kettle.ledger() != null) denseRestarts++;
            }
        }
        denseWorking = working; denseWorkingMin = Math.min(denseWorkingMin, working); denseWorkingMax = Math.max(denseWorkingMax, working);
    }

    static void clearDense(ServerLevel level) {
        for (UUID id : DENSE.keySet()) {
            FakePlayer p = DRIVEN.remove(id); TENDERS.remove(id);
            if (p != null) WorkshopPresence.leave(p, "");
        }
        if (denseBase != null) for (int z=0; z<16; z++) for (int x=0; x<16; x++) level.removeBlock(denseBase.offset(x,0,-z), false);
        DENSE.clear();
    }

    private static void drive(FakePlayer player) {
        if (player != null) DRIVEN.put(player.getUUID(), player);
    }

    private static Object place(ServerLevel level, BlockPos pos, Block block, Direction facing) {
        level.setBlock(pos, block.defaultBlockState().setValue(StationBlock.FACING, facing), 3);
        return level.getBlockEntity(pos);
    }

    private static BrewersKettleBlockEntity kettle(ServerLevel level, BlockPos pos, Direction facing) {
        return (BrewersKettleBlockEntity) place(level, pos, ModBlocks.BREWERS_KETTLE.get(), facing);
    }

    private static BrewersKettleBlockEntity loaded(BrewersKettleBlockEntity kettle) {
        for (int i = 0; i < 3; i++) {
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.WATER));
        }
        kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY, new ItemStack(Items.NETHER_WART));
        kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.TRAY + 1, new ItemStack(Items.SUGAR));
        kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.FUEL, new ItemStack(Items.BLAZE_POWDER));
        kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
        return kettle;
    }

    private static FakePlayer stand(ServerLevel level, BlockPos station, String name) {
        FakePlayer player = new FakePlayer(level, new GameProfile(UUID.nameUUIDFromBytes(name.getBytes()), name)) {
            @Override
            public boolean hasDisconnected() {
                return false;
            }
        };
        player.setPos(station.getX() + 0.5, station.getY(), station.getZ() + 2.5);
        return player;
    }

    /** Sets out the Swiftness plan (water, wart, sugar) for a stand-in at the Kettle; returns the stand-in. */
    private static FakePlayer start(ServerLevel level, BrewersKettleBlockEntity kettle, String name) {
        FakePlayer player = stand(level, kettle.getBlockPos(), name);
        setOut(level, kettle, player);
        return player;
    }

    /** The route book's Swiftness plan, set out the way the plan screen's button does (it opens the working view). */
    private static void setOut(ServerLevel level, BrewersKettleBlockEntity kettle, FakePlayer player) {
        ItemStack swiftness = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        BrewPlan plan = PlanIndex.get(level.getServer().getRecipeManager()).plansFrom(kettle.rack().input()).stream()
                .filter(p -> ItemStack.isSameItemSameTags(p.output(), swiftness))
                .min(Comparator.comparingInt(p -> p.steps().size())).orElse(null);
        if (plan != null) kettle.startBrew(player, plan.key(), 1F, 1F);
    }

    private StationGallery() {}
}
