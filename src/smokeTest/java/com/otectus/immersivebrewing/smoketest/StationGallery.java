package com.otectus.immersivebrewing.smoketest;

import com.mojang.authlib.GameProfile;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.menu.KettleMenu;
import com.otectus.immersivebrewing.network.packet.InputFramesC2S;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.session.SimHandles;
import com.otectus.immersivebrewing.testing.KettleBot;
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
 * list) start real jobs: an empty Kettle (east), a Kettle brewing with live input (west), a sealed Kettle (south), a
 * Kettle held for recovery (north) and an Alembic reclaiming on its own (east). Everything runs through the normal
 * station and session code; nothing is drawn specially.
 */
@Mod.EventBusSubscriber(modid = SmokeTestAgent.MOD_ID, value = Dist.CLIENT)
public final class StationGallery {
    static BlockPos row;
    private static final Map<UUID, FakePlayer> DRIVEN = new ConcurrentHashMap<>();
    private static final Map<UUID, KettleBot> BOTS = new ConcurrentHashMap<>();
    private static FakePlayer sealer;
    private static FakePlayer holder;
    private static int sequence;

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

    /** Server thread, after the count-in: seals one Kettle, and seals another then holds it for recovery. */
    static void settle(ServerLevel level) {
        for (FakePlayer p : new FakePlayer[] {sealer, holder}) {
            DRIVEN.remove(p.getUUID());
            SessionManager.byPlayer(p.getUUID()).ifPresent(s -> SessionManager.leave(p, s.id(), "session.immersive_brewing.end.closed"));
        }
        BrewersKettleBlockEntity held = (BrewersKettleBlockEntity) level.getBlockEntity(at(2));
        if (held != null && held.ledger() != null) {
            held.ledger().hold("recovery.immersive_brewing.adapter_missing");
            held.changed();
        }
    }

    /** Live input for the gallery's brewing stand-ins, sent before the session clock runs each tick. */
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START || DRIVEN.isEmpty()) return;
        for (FakePlayer p : DRIVEN.values()) {
            SessionManager.byPlayer(p.getUUID()).ifPresent(s -> {
                if (!(s.sim() instanceof SimHandles.Brewing brewing)) return;
                KettleBot bot = BOTS.computeIfAbsent(p.getUUID(), id -> KettleBot.competent());
                SessionManager.receive(p, new InputFramesC2S(s.id(), sequence++, brewing.sim().step() + s.bufferTicks(),
                        new int[] {bot.decide(brewing.sim()).pack()}));
            });
        }
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
        player.setPos(station.getX() + 0.5, station.getY(), station.getZ() + 1.5);
        return player;
    }

    /** Starts the Swiftness plan (water, wart, sugar) for a stand-in at the Kettle; returns the stand-in. */
    private static FakePlayer start(ServerLevel level, BrewersKettleBlockEntity kettle, String name) {
        FakePlayer player = stand(level, kettle.getBlockPos(), name);
        player.containerMenu = new KettleMenu(0, player.getInventory(), kettle.getBlockPos());
        ItemStack swiftness = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        BrewPlan plan = PlanIndex.get(level.getServer().getRecipeManager()).plansFrom(kettle.rack().input()).stream()
                .filter(p -> ItemStack.isSameItemSameTags(p.output(), swiftness))
                .min(Comparator.comparingInt(p -> p.steps().size())).orElse(null);
        if (plan != null) kettle.startBrew(player, plan.key(), 1F, 1F);
        return player;
    }

    private StationGallery() {}
}
