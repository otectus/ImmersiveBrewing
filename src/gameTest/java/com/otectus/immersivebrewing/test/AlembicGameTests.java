package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.item.RecoveryBatchItem;
import com.otectus.immersivebrewing.network.packet.SessionResultS2C;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.QualityMath;
import com.otectus.immersivebrewing.registry.ModBlocks;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.session.SessionManager;
import com.otectus.immersivebrewing.testing.DistillBot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.otectus.immersivebrewing.blockentity.AlembicBlockEntity.FUEL;
import static com.otectus.immersivebrewing.blockentity.AlembicBlockEntity.INPUT;
import static com.otectus.immersivebrewing.blockentity.AlembicBlockEntity.OUTPUT;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/**
 * Milestone 4 gate (spec 11): purification turns three graded potions into two (six into four) with one bottle back,
 * raises quality only up to its cap and only once per lineage, refuses what it can not improve, and survives sealing,
 * resuming and a broken Alembic with no duplicated material; reclamation turns three potions into one Spent Alchemical
 * Salt and three bottles, can be cancelled for a full refund, and keeps running inside a Recovery Batch.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class AlembicGameTests {
    static final long LINEAGE = 0xA1EB1CL;

    static ItemStack graded(Potion potion, int quality, int purifications) {
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.POTION), potion);
        BrewingMetadata.write(stack, new BrewingMetadata(60, 40, quality, LINEAGE, purifications, UUID.nameUUIDFromBytes(new byte[] {1}), "Brewer",
                null, null, null));
        return stack;
    }

    static AlembicBlockEntity load(GameTestHelper h, int powder, ItemStack... inputs) {
        AlembicBlockEntity alembic = TestSupport.alembic(h);
        // Placing the same block again keeps the existing block entity: start from empty slots.
        for (int i = 0; i < alembic.inventory().getSlots(); i++) alembic.inventory().setStackInSlot(i, ItemStack.EMPTY);
        for (int i = 0; i < inputs.length; i++) alembic.inventory().setStackInSlot(INPUT + i, inputs[i]);
        if (powder > 0) alembic.inventory().setStackInSlot(FUEL, new ItemStack(Items.BLAZE_POWDER, powder));
        return alembic;
    }

    static ItemStack[] copies(ItemStack stack, int n) {
        ItemStack[] out = new ItemStack[n];
        for (int i = 0; i < n; i++) out[i] = stack.copy();
        return out;
    }

    static List<ItemStack> outputs(AlembicBlockEntity alembic, Item item) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < AlembicBlockEntity.OUTPUT_SIZE; i++) {
            ItemStack s = alembic.inventory().getStackInSlot(OUTPUT + i);
            if (s.is(item)) out.add(s);
        }
        return out;
    }

    static int count(List<ItemStack> stacks) {
        return stacks.stream().mapToInt(ItemStack::getCount).sum();
    }

    static String problemKey(AlembicBlockEntity alembic) {
        Component problem = alembic.purifyPlan().problem();
        return problem != null && problem.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    static FakePlayer purify(GameTestHelper h, AlembicBlockEntity alembic, String name) {
        FakePlayer player = TestSupport.player(h, name);
        TestSupport.openAlembic(player, alembic);
        String problem = problemKey(alembic);
        alembic.startPurification(player, 0);
        h.assertTrue(TestSupport.session(player).isPresent(), "purification started (" + problem + ")");
        TestSupport.driveAlembic(h, player, DistillBot.competent());
        return player;
    }

    /** The D score the server reported to the player for the last purification. */
    static int distillation(FakePlayer player) {
        int d = -1;
        for (Object m : TestSupport.messages(player)) if (m instanceof SessionResultS2C r) d = r.result().getInt("D");
        return d;
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void purifyThreeIntoTwo(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(graded(Potions.STRENGTH, 40, 0), 3));
        FakePlayer player = purify(h, alembic, "Refiner");
        JobLedger ledger = alembic.ledger();
        h.assertTrue(ledger != null && ledger.operation() == Operation.PURIFY && ledger.inputs().size() == 3, "the job owns the three bottles");
        h.assertTrue(alembic.fuelCharges() == 19 && alembic.inventory().getStackInSlot(FUEL).isEmpty(), "one charge reserved from one powder");
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still distilling");
            List<ItemStack> potions = outputs(alembic, Items.POTION);
            h.assertTrue(count(potions) == 2, "two potions out of three: " + count(potions));
            h.assertTrue(count(outputs(alembic, Items.GLASS_BOTTLE)) == 1, "the third bottle returns empty");
            int d = distillation(player);
            int expected = QualityMath.purified(40, d, 70);
            for (ItemStack p : potions) {
                BrewingMetadata meta = BrewingMetadata.read(p).orElseThrow();
                h.assertTrue(PotionUtils.getPotion(p) == Potions.STRENGTH, "still Strength");
                h.assertTrue(meta.quality() == expected && meta.quality() > 40, "quality " + meta.quality() + " = purified(40, " + d + ", 70)");
                h.assertTrue(meta.purifications() == 1 && meta.lineage() == LINEAGE, "one purification on the same lineage");
                h.assertTrue("Refiner".equals(meta.refinerName()) && "Brewer".equals(meta.brewerName()), "refiner recorded, brewer kept");
            }
            h.assertTrue(alembic.fuelCharges() == 19, "one charge spent");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void purifySixIntoFour(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(graded(Potions.SWIFTNESS, 30, 0), 6));
        purify(h, alembic, "Refiner6");
        h.assertTrue(alembic.fuelCharges() == 18, "two groups, two charges");
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still distilling");
            h.assertTrue(count(outputs(alembic, Items.POTION)) == 4 && count(outputs(alembic, Items.GLASS_BOTTLE)) == 2, "six into four plus two bottles");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void ordinaryPotionsStartFromTheBaseline(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH), 3));
        purify(h, alembic, "Plain");
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still distilling");
            List<ItemStack> batch = outputs(alembic, Items.POTION);
            h.assertTrue(batch.size() == 2, "three ordinary bottles yield two potions");
            long lineage = BrewingMetadata.read(batch.get(0)).orElseThrow().lineage();
            for (ItemStack p : batch) {
                BrewingMetadata meta = BrewingMetadata.read(p).orElseThrow();
                h.assertTrue(meta.purifications() == 1 && meta.quality() >= QualityMath.BASELINE && meta.lineage() != 0, "graded from 50 with a new lineage");
                h.assertTrue(meta.lineage() == lineage, "one lineage for the whole ordinary batch");
            }
        });
    }

    @GameTest(template = "empty")
    public static void whatCanNotImproveIsRefused(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(graded(Potions.STRENGTH, 75, 0), 3));
        h.assertTrue("alembic.immersive_brewing.purify.too_good".equals(problemKey(alembic)), "at or above the cap: " + problemKey(alembic));
        alembic = load(h, 1, copies(graded(Potions.STRENGTH, 40, 1), 3));
        h.assertTrue("alembic.immersive_brewing.purify.once".equals(problemKey(alembic)), "once per lineage: " + problemKey(alembic));
        alembic = load(h, 1, graded(Potions.STRENGTH, 40, 0), graded(Potions.STRENGTH, 40, 0), graded(Potions.STRENGTH, 41, 0));
        h.assertTrue("alembic.immersive_brewing.purify.mixed".equals(problemKey(alembic)), "identical bottles only: " + problemKey(alembic));
        alembic = load(h, 1, copies(graded(Potions.STRENGTH, 40, 0), 4));
        h.assertTrue("alembic.immersive_brewing.purify.group".equals(problemKey(alembic)), "groups of three: " + problemKey(alembic));
        alembic = load(h, 0, copies(graded(Potions.STRENGTH, 40, 0), 3));
        h.assertTrue("plan.immersive_brewing.missing.fuel".equals(problemKey(alembic)), "fuel needed: " + problemKey(alembic));
        alembic = load(h, 1, copies(TestSupport.water(), 3));
        h.assertTrue("alembic.immersive_brewing.purify.unsupported".equals(problemKey(alembic)), "water has nothing to purify: " + problemKey(alembic));
        FakePlayer player = TestSupport.player(h, "Refused");
        TestSupport.openAlembic(player, alembic);
        alembic.startPurification(player, 0);
        h.assertTrue(alembic.ledger() == null && TestSupport.session(player).isEmpty(), "a refused start reserves nothing");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void anInterruptedPurificationResumesWithoutDuplicates(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(graded(Potions.STRENGTH, 40, 0), 3));
        FakePlayer player = purify(h, alembic, "Paused");
        h.runAfterDelay(140, () -> {
            SessionManager.leave(player, TestSupport.session(player).orElseThrow().id(), "session.immersive_brewing.end.closed");
            h.assertTrue(alembic.ledger() != null && alembic.ledger().state() == JobState.SEALED, "sealed");
            h.assertTrue(count(outputs(alembic, Items.POTION)) == 0 && alembic.ledger().inputs().size() == 3, "nothing issued, all three held");
            alembic.handleAction(player, StationActionC2S.Action.START_RECLAMATION, 0);
            h.assertTrue(alembic.ledger().operation() == Operation.PURIFY, "no other job can start on a sealed Alembic");
            TestSupport.openAlembic(player, alembic);
            alembic.resume(player);
            h.assertTrue(TestSupport.session(player).isPresent(), "resumed");
        });
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still distilling");
            h.assertTrue(count(outputs(alembic, Items.POTION)) == 2 && count(outputs(alembic, Items.GLASS_BOTTLE)) == 1, "exactly two and one");
            for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) h.assertTrue(alembic.inventory().getStackInSlot(INPUT + i).isEmpty(), "no input reappeared");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void breakingAPurificationPackagesIt(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(graded(Potions.STRENGTH, 40, 0), 3));
        FakePlayer player = purify(h, alembic, "Clumsy");
        h.runAfterDelay(120, () -> {
            h.getLevel().destroyBlock(h.absolutePos(STATION), true);
            List<ItemEntity> drops = TestSupport.drops(h, STATION);
            List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
            h.assertTrue(batches.size() == 1, "one Recovery Batch");
            h.assertTrue(TestSupport.dropped(drops, Items.POTION) == 0, "no potion dropped loose");
            JobLedger ledger = RecoveryBatchItem.ledger(batches.get(0)).orElseThrow();
            h.assertTrue(ledger.state() == JobState.SEALED && ledger.inputs().size() == 3, "the sealed purification with its three bottles");
            drops.forEach(Entity::discard);
            h.setBlock(STATION, ModBlocks.ALEMBIC.get());
            AlembicBlockEntity fresh = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
            h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installed");
            TestSupport.openAlembic(player, fresh);
            fresh.resume(player);
            h.assertTrue(TestSupport.session(player).isPresent(), "resumed at the new Alembic");
        });
        h.succeedWhen(() -> {
            AlembicBlockEntity fresh = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
            h.assertTrue(fresh != null && fresh != alembic && fresh.ledger() == null, "still distilling");
            h.assertTrue(count(outputs(fresh, Items.POTION)) == 2 && count(outputs(fresh, Items.GLASS_BOTTLE)) == 1, "two and one, once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void reclamationConservesMaterial(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD), 4));
        FakePlayer player = TestSupport.player(h, "Recycler");
        TestSupport.openAlembic(player, alembic);
        alembic.startReclamation(player);
        h.assertTrue(alembic.ledger() != null && alembic.ledger().operation() == Operation.RECLAIM && alembic.ledger().inputs().size() == 3,
                "one group of three taken");
        h.assertTrue(TestSupport.count(alembic, INPUT, AlembicBlockEntity.INPUT_SIZE, Items.POTION) == 1, "the fourth potion waits");
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still reclaiming");
            h.assertTrue(count(outputs(alembic, ModItems.SPENT_ALCHEMICAL_SALT.get())) == 1, "one Spent Alchemical Salt");
            h.assertTrue(count(outputs(alembic, Items.GLASS_BOTTLE)) == 3, "three bottles back");
            h.assertTrue(TestSupport.count(alembic, INPUT, AlembicBlockEntity.INPUT_SIZE, Items.POTION) == 1, "the fourth potion untouched");
            h.assertTrue(alembic.fuelCharges() == 19, "one charge");
        });
    }

    @GameTest(template = "empty")
    public static void reclamationCanBeCancelled(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD), 3));
        FakePlayer player = TestSupport.player(h, "Undecided");
        TestSupport.openAlembic(player, alembic);
        alembic.startReclamation(player);
        h.assertTrue(alembic.ledger() != null, "started");
        alembic.handleAction(player, StationActionC2S.Action.CANCEL_PASSIVE, 0);
        h.assertTrue(alembic.ledger() == null, "cancelled");
        h.assertTrue(TestSupport.count(alembic, INPUT, AlembicBlockEntity.INPUT_SIZE, Items.POTION) == 3, "all three potions back");
        h.assertTrue(alembic.fuelCharges() == 20, "and the charge refunded");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void aReclamationKeepsRunningInsideARecoveryBatch(GameTestHelper h) {
        AlembicBlockEntity alembic = load(h, 1, copies(PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD), 3));
        FakePlayer player = TestSupport.player(h, "Mover");
        TestSupport.openAlembic(player, alembic);
        alembic.startReclamation(player);
        h.runAfterDelay(60, () -> {
            h.getLevel().destroyBlock(h.absolutePos(STATION), true);
            List<ItemEntity> drops = TestSupport.drops(h, STATION);
            List<ItemStack> batches = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
            h.assertTrue(batches.size() == 1 && TestSupport.dropped(drops, Items.POTION) == 0, "one batch, no loose potions");
            drops.forEach(Entity::discard);
            h.setBlock(STATION, ModBlocks.ALEMBIC.get());
            AlembicBlockEntity fresh = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
            h.assertTrue(TestSupport.use(player, fresh, batches.get(0).copy()).isEmpty(), "installed");
            h.assertTrue(fresh.ledger() != null && fresh.ledger().state() == JobState.RUNNING, "a passive job carries on where it was");
        });
        h.succeedWhen(() -> {
            AlembicBlockEntity fresh = (AlembicBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(STATION));
            h.assertTrue(fresh != null && fresh != alembic && fresh.ledger() == null, "still reclaiming");
            h.assertTrue(count(outputs(fresh, ModItems.SPENT_ALCHEMICAL_SALT.get())) == 1 && count(outputs(fresh, Items.GLASS_BOTTLE)) == 3,
                    "finished once at the new Alembic");
        });
    }

    private AlembicGameTests() {}
}
