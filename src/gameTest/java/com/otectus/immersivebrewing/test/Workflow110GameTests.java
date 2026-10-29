package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.blockentity.BatchStaging;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.PlanKey;
import com.otectus.immersivebrewing.recipe.QuickFinishing;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.session.SessionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.FUEL;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.PADDLE;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;
import static com.otectus.immersivebrewing.test.TestSupport.STATION;

/** Focused regressions for the 1.1 batch transaction, passive finishing and incremental plan catalog. */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Workflow110GameTests {
    private static final int CATALOG_WORK_CAP = 32_768;

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stagingPreviewPrepareCarrierAndReplayAreAtomic(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "BatchStager");
        BrewPlan plan = catalogPlan(h, TestSupport.water(), p -> samePotion(p.output(), Items.POTION, Potions.SWIFTNESS));

        int[] servings = {1, 3, 4, 6};
        int[] reagentCosts = {1, 1, 2, 2};
        int[] fuelCosts = {2, 2, 4, 4};
        for (int i = 0; i < servings.length; i++) {
            int expectedReagentCost = reagentCosts[i];
            List<BrewPlan.Cost> costs = plan.costs(servings[i]);
            h.assertTrue(costs.size() == 2, "Swiftness has wart and sugar costs at " + servings[i]);
            h.assertTrue(costs.stream().allMatch(c -> c.count() == expectedReagentCost),
                    "actual reagent cost at " + servings[i] + " servings is " + expectedReagentCost + ": " + costs);
            h.assertTrue(plan.fuelCharges(servings[i]) == fuelCosts[i],
                    "actual fuel cost at " + servings[i] + " servings is " + fuelCosts[i]);
        }

        ItemStack carrier = new ItemStack(ModItems.BATCH_CARRIER.get());
        List<ItemStack> carried = CarrierContents.read(carrier);
        carried.set(0, TestSupport.water());
        carried.set(1, TestSupport.water());
        CarrierContents.write(carrier, carried);
        player.getInventory().setItem(0, carrier);
        for (int i = 1; i <= 4; i++) player.getInventory().setItem(i, TestSupport.water());
        player.getInventory().setItem(5, new ItemStack(Items.NETHER_WART, 2));
        player.getInventory().setItem(6, new ItemStack(Items.SUGAR, 2));
        player.getInventory().setItem(7, new ItemStack(Items.BLAZE_POWDER));
        player.getInventory().setItem(8, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
        var profile = plan.costs(6).stream().map(BrewPlan.Cost::preparationProfile).filter(java.util.Objects::nonNull)
                .findFirst().orElse(BrewingDefinitions.GENERIC_PROFILE);
        ItemStack untouchedKit = new KitData(new ItemStack(Items.SUGAR), 1, profile, 91).toStack(1);
        player.getInventory().setItem(9, untouchedKit.copy());

        String before = ownership(kettle, player);
        long revision = BatchStaging.revision(kettle, player);
        BatchStaging.Preview preview = BatchStaging.preview(kettle, player, plan, 6, BatchStaging.Kits.RAW, 0);
        h.assertTrue(preview.ready(), "six-bottle preview is ready: " + preview.problem());
        h.assertTrue(revision == BatchStaging.revision(kettle, player) && before.equals(ownership(kettle, player)),
                "preview does not mutate station, player, carrier, fuel, or tool ownership");

        BatchStaging.Preview prepared = BatchStaging.prepare(kettle, player, plan, 6, BatchStaging.Kits.RAW, 0, revision);
        h.assertTrue(prepared.ready(), "prepare commits the preview");
        h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 6, "all six exact bottles moved to the rack");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 2
                        && TestSupport.count(kettle, TRAY, 6, Items.SUGAR) == 2,
                "six servings stage the exact two wart and two sugar cost");
        h.assertTrue(kettle.inventory().getStackInSlot(FUEL).is(Items.BLAZE_POWDER)
                        && kettle.inventory().getStackInSlot(PADDLE).is(ModItems.WOODEN_BREWING_PADDLE.get()),
                "fuel and paddle move to their direct slots");
        h.assertTrue(CarrierContents.read(player.getInventory().getItem(0)).stream().allMatch(ItemStack::isEmpty),
                "the explicitly selected carrier supplied exactly its two bottles");
        h.assertTrue(ItemStack.matches(player.getInventory().getItem(9), untouchedKit), "RAW mode preserves prepared kits exactly");

        String committed = ownership(kettle, player);
        long committedRevision = BatchStaging.revision(kettle, player);
        BatchStaging.Preview replay = BatchStaging.prepare(kettle, player, plan, 6, BatchStaging.Kits.RAW, 0, revision);
        h.assertTrue(!replay.ready(), "a replay with the spent revision is rejected");
        h.assertTrue(committedRevision == BatchStaging.revision(kettle, player) && committed.equals(ownership(kettle, player)),
                "stale prepare is a complete no-op");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stagingFullInventoryFailureIsAtomic(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "FullBatchStager");
        BrewPlan plan = catalogPlan(h, TestSupport.water(), p -> samePotion(p.output(), Items.POTION, Potions.SWIFTNESS));
        for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.DIAMOND_SWORD));
        for (int i = 0; i < 6; i++) player.getInventory().setItem(i, TestSupport.water());
        player.getInventory().setItem(6, new ItemStack(Items.NETHER_WART, 2));
        player.getInventory().setItem(7, new ItemStack(Items.SUGAR, 2));
        player.getInventory().setItem(8, new ItemStack(Items.BLAZE_POWDER));
        player.getInventory().setItem(9, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
        // Fourteen unrelated, unstackable station-owned items must be returned, but consuming the requested player
        // inputs only frees ten ordinary inventory slots.
        for (int i = 0; i <= PADDLE; i++) kettle.inventory().setStackInSlot(i, new ItemStack(Items.DIAMOND_SWORD));

        String before = ownership(kettle, player);
        long revision = BatchStaging.revision(kettle, player);
        BatchStaging.Preview preview = BatchStaging.preview(kettle, player, plan, 6, BatchStaging.Kits.RAW, -1);
        h.assertTrue(!preview.ready(), "full destination inventory refuses the draft");
        h.assertTrue(before.equals(ownership(kettle, player)) && revision == BatchStaging.revision(kettle, player),
                "failed preview changes no owner");
        BatchStaging.Preview prepare = BatchStaging.prepare(kettle, player, plan, 6, BatchStaging.Kits.RAW, -1, revision);
        h.assertTrue(!prepare.ready() && before.equals(ownership(kettle, player)), "failed prepare is also atomic");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void stagingDoesNotSplitOneMultiGroupKitAcrossRepeatedSteps(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "RepeatedKitStager");
        BrewPlan base = catalogPlan(h, TestSupport.water(), p -> samePotion(p.output(), Items.POTION, Potions.SWIFTNESS));
        BrewPlan.Step step = base.steps().get(0);
        BrewPlan repeated = new BrewPlan(base.key(), base.input(), List.of(step, step), step.after(),
                base.brewProfile(), base.maxServings(), base.contextual());
        var profile = step.spec().preparationProfile() == null ? BrewingDefinitions.GENERIC_PROFILE : step.spec().preparationProfile();
        player.getInventory().setItem(0, TestSupport.water());
        player.getInventory().setItem(1, new KitData(new ItemStack(step.reagent()), 2, profile, 80).toStack(1));
        player.getInventory().setItem(2, new ItemStack(Items.BLAZE_POWDER));
        player.getInventory().setItem(3, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));

        BatchStaging.Preview shortPreview = BatchStaging.preview(kettle, player, repeated, 1, BatchStaging.Kits.PRESERVE_BEST, -1);
        h.assertTrue(!shortPreview.ready(), "one groups-2 kit cannot split across two independent one-cost steps");
        h.assertTrue(kettle.inventory().getStackInSlot(TRAY).isEmpty() && player.getInventory().getItem(1).getCount() == 1,
                "the rejected repeated-step preview is non-mutating");

        player.getInventory().setItem(1, new KitData(new ItemStack(step.reagent()), 2, profile, 80).toStack(2));
        long revision = BatchStaging.revision(kettle, player);
        BatchStaging.Preview exact = BatchStaging.preview(kettle, player, repeated, 1, BatchStaging.Kits.PRESERVE_BEST, -1);
        h.assertTrue(exact.ready(), "two groups-2 kits satisfy two independent repeated steps");
        BatchStaging.Preview committed = BatchStaging.prepare(kettle, player, repeated, 1,
                BatchStaging.Kits.PRESERVE_BEST, -1, revision);
        h.assertTrue(committed.ready() && TestSupport.count(kettle, TRAY, 6, ModItems.PREPARED_REAGENT_KIT.get()) == 2,
                "prepare stages one indivisible kit for each repeated step");
        h.assertTrue(kettle.check(repeated, 1).missing() == null, "reservation agrees with the staged allocation");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void quickFinishingGroupedReturnsBlockAndRecoverExactly(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "GroupedQuickFinisher");
        ItemStack splash = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.SWIFTNESS);
        kettle.inventory().setStackInSlot(RACK, splash);
        kettle.inventory().setStackInSlot(TRAY, new KitData(new ItemStack(Items.DRAGON_BREATH), 4,
                ImmersiveBrewing.id("dragon_breath"), 77).toStack(1));
        kettle.inventory().setStackInSlot(FUEL, new ItemStack(Items.BLAZE_POWDER));
        BrewPlan lingering = catalogPlan(h, splash, p -> samePotion(p.output(), Items.LINGERING_POTION, Potions.SWIFTNESS));
        BatchStaging.Preview transaction = BatchStaging.preview(kettle, player, lingering, 1,
                BatchStaging.Kits.PRESERVE_BEST, -1);
        int stagedReturns = transaction.remainders().stream().filter(s -> s.is(Items.GLASS_BOTTLE))
                .mapToInt(ItemStack::getCount).sum();
        h.assertTrue(transaction.ready() && stagedReturns == 4,
                "transaction preview and eventual commit agree on four grouped-kit returns");

        for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i,
                new ItemStack(Items.GLASS_BOTTLE, 63));
        var blocked = kettle.describePlan(player, lingering, 1);
        h.assertTrue(blocked != null && blocked.missing() != null && !blocked.finishReady(),
                "four owed bottles fail preflight when only three shelf spaces remain");
        kettle.startFinishing(player, lingering.key());
        h.assertTrue(kettle.ledger() == null, "failed preflight reserves no potion, kit, or fuel");

        for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i, ItemStack.EMPTY);
        var ready = kettle.describePlan(player, lingering, 1);
        int previewReturns = ready == null ? 0 : ready.remainders().stream().filter(s -> s.is(Items.GLASS_BOTTLE))
                .mapToInt(ItemStack::getCount).sum();
        h.assertTrue(ready != null && ready.finishReady() && previewReturns == 4,
                "preview exposes the exact four returns represented by the groups-4 kit");
        kettle.startFinishing(player, lingering.key());
        h.assertTrue(kettle.ledger() != null && kettle.ledger().reserved().size() == 1,
                "finishing owns the one indivisible groups-4 kit");

        for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i,
                new ItemStack(Items.DIAMOND_SWORD));
        for (int i = 0; i < 2_000 && kettle.ledger() != null && kettle.ledger().state() == JobState.RUNNING; i++) kettle.serverTick();
        h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.RECOVERY
                        && "recovery.immersive_brewing.output_blocked".equals(kettle.ledger().recoveryReason()),
                "a shelf filled after reservation holds every owned item");
        for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.BYPRODUCT + i, ItemStack.EMPTY);
        kettle.revalidateRecovery();
        h.assertTrue(kettle.ledger().state() == JobState.SEALED, "freeing shelf room revalidates the held ledger");
        kettle.resume(player);
        kettle.serverTick();
        h.assertTrue(kettle.ledger() == null
                        && TestSupport.count(kettle, BrewersKettleBlockEntity.BYPRODUCT, 3, Items.GLASS_BOTTLE) == 4,
                "resumed finishing commits exactly four owed bottles once");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void manualBrewReturnsEveryContainerOwnedByGroupedKit(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "GroupedManualBrewer");
        ItemStack splash = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.SWIFTNESS);
        kettle.inventory().setStackInSlot(RACK, splash);
        kettle.inventory().setStackInSlot(TRAY, new KitData(new ItemStack(Items.DRAGON_BREATH), 2,
                ImmersiveBrewing.id("dragon_breath"), 73).toStack(1));
        kettle.inventory().setStackInSlot(FUEL, new ItemStack(Items.BLAZE_POWDER));
        kettle.inventory().setStackInSlot(PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
        BrewPlan lingering = catalogPlan(h, splash, p -> samePotion(p.output(), Items.LINGERING_POTION, Potions.SWIFTNESS));
        int preflightReturns = kettle.check(lingering, 1).remainders().stream().filter(s -> s.is(Items.GLASS_BOTTLE))
                .mapToInt(ItemStack::getCount).sum();
        h.assertTrue(preflightReturns == 2 && kettle.check(lingering, 1).missing() == null,
                "manual preflight accounts for both containers owned by a groups-2 kit");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, lingering.key(), 1, 1);
        h.assertTrue(kettle.ledger() != null && kettle.ledger().reserved().size() == 1,
                "manual brew reserves the one indivisible kit");
        TestSupport.driveKettle(h, player, com.otectus.immersivebrewing.testing.KettleBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "manual grouped-kit brew still running");
            h.assertTrue(kettle.inventory().getStackInSlot(RACK).is(Items.LINGERING_POTION),
                    "manual grouped-kit brew produces its validated output");
            h.assertTrue(TestSupport.count(kettle, BrewersKettleBlockEntity.BYPRODUCT, 3, Items.GLASS_BOTTLE) == 2,
                    "manual completion returns both accounted containers exactly once");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void schemaOneContainerStepDerivesPreservationProofAfterReload(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "LegacyContainerBrewer");
        ItemStack splash = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.SWIFTNESS);
        PotionUtils.setCustomEffects(splash, List.of(new MobEffectInstance(MobEffects.NIGHT_VISION, 777, 1)));
        splash.getOrCreateTag().putString("foreign_mod_payload", "legacy-keep");
        BrewingMetadata originalMeta = new BrewingMetadata(88, 76, 82, 0x55AA7711L, 2, UUID.randomUUID(),
                "Legacy Brewer", "Legacy Refiner", "Legacy Label", "Legacy Note");
        BrewingMetadata.write(splash, originalMeta);
        kettle.inventory().setStackInSlot(RACK, splash);
        kettle.inventory().setStackInSlot(TRAY, new ItemStack(Items.DRAGON_BREATH));
        kettle.inventory().setStackInSlot(FUEL, new ItemStack(Items.BLAZE_POWDER));
        kettle.inventory().setStackInSlot(PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
        BrewPlan lingering = catalogPlan(h, splash, p -> samePotion(p.output(), Items.LINGERING_POTION, Potions.SWIFTNESS));
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, lingering.key(), 1, 1);

        BrewersKettleBlockEntity[] resumed = {null};
        h.runAfterDelay(100, () -> {
            var active = TestSupport.session(player).orElseThrow();
            h.assertTrue(active.sim().step() > 0, "legacy fixture has real saved simulation progress");
            long[] progress = active.sim().saveState();
            SessionManager.leave(player, active.id(), "session.immersive_brewing.end.closed");
            h.assertTrue(kettle.ledger() != null && kettle.ledger().state() == JobState.SEALED,
                    "running container job seals before migration reload");
            CompoundTag step = kettle.ledger().snapshot().getList("steps", Tag.TAG_COMPOUND).getCompound(0);
            step.remove("container");
            CompoundTag saved = kettle.saveWithFullMetadata();
            saved.getCompound("Ledger").putInt("schema", 1);
            BlockPos pos = kettle.getBlockPos();
            h.getLevel().setBlockEntity(BlockEntity.loadStatic(pos, kettle.getBlockState(), saved));
            BrewersKettleBlockEntity loaded = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(pos);
            h.assertTrue(loaded != null && loaded.ledger() != null && loaded.ledger().state() == JobState.SEALED,
                    "schema-1 step without the role marker reloads sealed");
            h.assertTrue(java.util.Arrays.equals(progress, loaded.ledger().simState()),
                    "migration does not reroll or alter saved simulation state");
            TestSupport.openKettle(player, loaded);
            loaded.resume(player);
            resumed[0] = loaded;
        });
        int[] sequence = {0};
        var bot = com.otectus.immersivebrewing.testing.KettleBot.competent();
        h.onEachTick(() -> {
            if (resumed[0] == null) return;
            TestSupport.session(player).ifPresent(session -> {
                var sim = ((com.otectus.immersivebrewing.session.SimHandles.Brewing) session.sim()).sim();
                SessionManager.receive(player, new com.otectus.immersivebrewing.network.packet.InputFramesC2S(
                        session.id(), sequence[0]++, sim.step() + session.bufferTicks(), new int[]{bot.decide(sim).pack()}));
            });
        });
        h.succeedWhen(() -> {
                BrewersKettleBlockEntity loaded = resumed[0];
                h.assertTrue(loaded != null, "legacy container job has not resumed yet");
                h.assertTrue(loaded.ledger() == null, "legacy container job still running");
                ItemStack result = loaded.inventory().getStackInSlot(RACK);
                h.assertTrue(result.is(Items.LINGERING_POTION)
                                && "legacy-keep".equals(result.getOrCreateTag().getString("foreign_mod_payload"))
                                && PotionUtils.getCustomEffects(result).size() == 1,
                        "derived legacy container proof preserves foreign NBT and custom effects");
                BrewingMetadata metadata = BrewingMetadata.read(result).orElseThrow();
                h.assertTrue(metadata.lineage() == originalMeta.lineage() && metadata.purifications() == 2
                                && "Legacy Label".equals(metadata.label()) && "Legacy Note".equals(metadata.note()),
                        "legacy lineage, purification history and labels survive completion");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 800)
    public static void quickFinishingPreservesPotionAndCompletesOnceAcrossRecovery(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        FakePlayer player = TestSupport.player(h, "QuickFinisher");
        ItemStack original = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        PotionUtils.setCustomEffects(original, List.of(new MobEffectInstance(MobEffects.NIGHT_VISION, 1_234, 2)));
        BrewingMetadata metadata = new BrewingMetadata(100, 100, 100, 0x1A2B3C4DL, 2, UUID.randomUUID(),
                "Brewer", "Refiner", "Expedition", "foreign effect kept");
        BrewingMetadata.write(original, metadata);
        original.getOrCreateTag().putString("foreign_mod_payload", "keep-me");
        CompoundTag expectedTag = original.getTag().copy();
        int resultsBefore = TestSupport.results(h, STATION);
        int experienceBefore = player.totalExperience;

        kettle.inventory().setStackInSlot(RACK, original.copy());
        kettle.inventory().setStackInSlot(TRAY, new ItemStack(Items.GUNPOWDER));
        kettle.inventory().setStackInSlot(FUEL, new ItemStack(Items.BLAZE_POWDER));
        BrewPlan splash = catalogPlan(h, original, p -> samePotion(p.output(), Items.SPLASH_POTION, Potions.SWIFTNESS));
        h.assertTrue(QuickFinishing.eligible(splash) && oneCost(splash, Items.GUNPOWDER), "splash is an eligible one-gunpowder finishing plan");
        kettle.startFinishing(player, splash.key());
        h.assertTrue(kettle.ledger() != null && kettle.ledger().operation() == Operation.FINISH
                        && kettle.ledger().reserved().size() == 1 && kettle.ledger().fuelCharges() == 1,
                "splash finishing owns one reagent and one fuel charge");
        runPassive(kettle, 2_000);
        ItemStack splashed = kettle.inventory().getStackInSlot(RACK);
        h.assertTrue(splashed.is(Items.SPLASH_POTION) && expectedTag.equals(splashed.getTag()),
                "Q100 schema-1 metadata, custom effects, foreign NBT, lineage, purification and label survive splash conversion");

        kettle.inventory().setStackInSlot(TRAY, new ItemStack(Items.DRAGON_BREATH));
        BrewPlan lingering = catalogPlan(h, splashed, p -> samePotion(p.output(), Items.LINGERING_POTION, Potions.SWIFTNESS));
        h.assertTrue(QuickFinishing.eligible(lingering) && oneCost(lingering, Items.DRAGON_BREATH),
                "lingering is an eligible one-breath finishing plan");
        kettle.startFinishing(player, lingering.key());
        h.assertTrue(kettle.ledger() != null && kettle.ledger().reserved().size() == 1 && kettle.ledger().fuelCharges() == 1,
                "lingering finishing owns one reagent and one fuel charge");
        for (int i = 0; i < 4; i++) kettle.serverTick();
        long progress = kettle.ledger().simState()[0];
        kettle.handleAction(player, StationActionC2S.Action.CANCEL_PASSIVE, 0);
        h.assertTrue(kettle.ledger().state() == JobState.SEALED, "passive work seals on request");
        kettle.serverTick();
        h.assertTrue(kettle.ledger().simState()[0] == progress, "sealed finishing does not advance");

        BlockPos absolute = h.absolutePos(STATION);
        CompoundTag saved = kettle.saveWithFullMetadata();
        h.getLevel().setBlockEntity(BlockEntity.loadStatic(absolute, kettle.getBlockState(), saved));
        BrewersKettleBlockEntity loaded = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(absolute);
        h.assertTrue(loaded != kettle && loaded.ledger() != null && loaded.ledger().state() == JobState.SEALED,
                "sealed finishing reloads with its exact ledger");
        loaded.resume(player);
        h.assertTrue(loaded.ledger().state() == JobState.RUNNING, "reloaded passive work resumes");
        for (int i = 0; i < 3; i++) loaded.serverTick();

        h.getLevel().destroyBlock(absolute, true);
        List<net.minecraft.world.entity.item.ItemEntity> drops = TestSupport.drops(h, STATION);
        List<ItemStack> recoveries = TestSupport.droppedStacks(drops, ModItems.RECOVERY_BATCH.get());
        h.assertTrue(recoveries.size() == 1, "running finishing is packaged in exactly one recovery batch");
        ItemStack recovery = recoveries.get(0).copy();
        drops.forEach(Entity::discard);
        BrewersKettleBlockEntity recovered = TestSupport.kettle(h);
        h.assertTrue(TestSupport.use(player, recovered, recovery).isEmpty(), "the recovery batch installs once");
        h.assertTrue(recovered.ledger() != null && recovered.ledger().operation() == Operation.FINISH
                        && recovered.ledger().state() == JobState.RUNNING,
                "passive finishing continues after recovery without an interactive session");
        runPassive(recovered, 2_000);

        ItemStack result = recovered.inventory().getStackInSlot(RACK);
        h.assertTrue(result.is(Items.LINGERING_POTION) && expectedTag.equals(result.getTag()),
                "the recovered lingering potion retains the complete source payload");
        h.assertTrue(BrewingMetadata.read(result).orElseThrow().equals(metadata), "schema-1 quality and provenance are byte-stable");
        h.assertTrue(TestSupport.count(recovered, BrewersKettleBlockEntity.BYPRODUCT, 3, Items.GLASS_BOTTLE) == 1,
                "dragon breath returns exactly one glass bottle");
        String completed = ownership(recovered, player);
        for (int i = 0; i < 40; i++) recovered.serverTick();
        h.assertTrue(completed.equals(ownership(recovered, player)), "duplicate passive ticks issue no extra output or remainder");
        h.assertTrue(TestSupport.results(h, STATION) == resultsBefore && player.totalExperience == experienceBefore,
                "quick finishing grants no brew-result reward or experience, including after replay ticks");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void catalogBudgetsRejectedGenericCandidatesOneAtATime(GameTestHelper h) {
        PlanIndex index = PlanIndex.get(h.getLevel().getServer().getRecipeManager());
        PlanIndex.Catalog rejected = index.catalog(new ItemStack(Items.STICK));
        rejected.advance(1);
        h.assertTrue(rejected.work() == 1 && !rejected.done(),
                "one rejected generic candidate consumes one work unit and leaves the cursor resumable");
        for (int i = 0; i < 64 && !rejected.done(); i++) rejected.advance(1);
        h.assertTrue(rejected.done() && rejected.plans().isEmpty(),
                "all non-applicable generic candidates finish without inventing a plan");
        h.assertTrue(rejected.work() >= 2 && rejected.work() <= 64,
                "rejected candidates are individually counted inside the explicit budget: " + rejected.work());
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void directResolutionFetchesLateAuthoredSourceByStableId(GameTestHelper h) {
        var manager = h.getLevel().getServer().getRecipeManager();
        PlanIndex index = PlanIndex.get(manager);
        InfusionRecipe late = manager.getAllRecipesFor(ModRecipeTypes.INFUSION.get()).stream()
                .filter(recipe -> !recipe.generic() && index.isValidated(recipe.getId()))
                .max(java.util.Comparator.comparing(recipe -> recipe.getId().toString()))
                .orElseThrow();
        ItemStack input = late.input().stack(null);
        PlanKey.Part part = new PlanKey.Part(late.getId(), 0, late.steps().size(), late.getId());
        PlanKey key = new PlanKey(List.of(part));
        BrewPlan resolved = index.resolve(key, input, List.of());
        h.assertTrue(resolved != null && resolved.key().equals(key) && resolved.steps().size() == late.steps().size(),
                "the lexically late authored source resolves directly by stable ID and exact segment bounds");
        PlanKey wrongBounds = new PlanKey(List.of(new PlanKey.Part(late.getId(), 0, late.steps().size() - 1, late.getId())));
        h.assertTrue(index.resolve(wrongBounds, input, List.of()) == null,
                "direct resolution rejects a forged partial segment instead of finding a path equivalent");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 600)
    public static void catalogFindsAndReplaysLongLingeringSwiftnessWithoutTray(GameTestHelper h) {
        ItemStack water = TestSupport.water();
        PlanIndex index = PlanIndex.get(h.getLevel().getServer().getRecipeManager());
        PlanIndex.Catalog catalog = index.catalog(water);
        BrewPlan found = null;
        for (int slice = 0; slice < 1_024 && found == null && !catalog.done(); slice++) {
            catalog.advance(32);
            found = catalog.plans().stream()
                    .filter(p -> samePotion(p.output(), Items.LINGERING_POTION, Potions.LONG_SWIFTNESS))
                    .findFirst().orElse(null);
        }
        h.assertTrue(found != null, "bounded catalog reaches long lingering Swiftness from Water without a loaded tray");
        h.assertTrue(catalog.work() <= CATALOG_WORK_CAP, "incremental search stays inside its explicit edge-work cap");
        h.assertTrue(found.steps().size() >= 5, "the destination exercises a long multi-segment path: " + found.steps().size());
        BrewPlan replayed = index.resolve(found.key(), water, List.of());
        h.assertTrue(replayed != null && samePotion(replayed.output(), Items.LINGERING_POTION, Potions.LONG_SWIFTNESS),
                "direct key replay resolves without tray authority or the legacy 512-result page");
        h.assertTrue(replayed.key().equals(found.key()), "replay preserves the catalog's stable plan key");
        h.succeed();
    }

    private static boolean oneCost(BrewPlan plan, net.minecraft.world.item.Item item) {
        List<BrewPlan.Cost> costs = plan.costs(1);
        return plan.fuelCharges(1) == 1 && costs.size() == 1 && costs.get(0).item() == item && costs.get(0).count() == 1;
    }

    private static boolean samePotion(ItemStack stack, net.minecraft.world.item.Item item, net.minecraft.world.item.alchemy.Potion potion) {
        return stack.is(item) && PotionUtils.getPotion(stack) == potion;
    }

    private static BrewPlan catalogPlan(GameTestHelper h, ItemStack input, Predicate<BrewPlan> wanted) {
        PlanIndex.Catalog catalog = PlanIndex.get(h.getLevel().getServer().getRecipeManager()).catalog(input);
        for (int slice = 0; slice < 1_024; slice++) {
            BrewPlan found = catalog.plans().stream().filter(wanted).findFirst().orElse(null);
            if (found != null) return found;
            if (catalog.done()) break;
            catalog.advance(32);
        }
        throw new IllegalStateException("catalog did not contain the requested potion path; work=" + catalog.work());
    }

    private static void runPassive(BrewersKettleBlockEntity kettle, int limit) {
        for (int i = 0; i < limit && kettle.ledger() != null; i++) kettle.serverTick();
        if (kettle.ledger() != null) throw new IllegalStateException("passive finishing did not complete: " + kettle.ledger().state());
    }

    private static String ownership(BrewersKettleBlockEntity kettle, FakePlayer player) {
        List<String> stacks = new ArrayList<>();
        for (int i = 0; i < kettle.inventory().getSlots(); i++) stacks.add(save(kettle.inventory().getStackInSlot(i)));
        stacks.add("fuel=" + kettle.fuelCharges());
        stacks.add("ledger=" + (kettle.ledger() == null ? "none" : kettle.ledger().jobId()));
        for (int i = 0; i < 36; i++) stacks.add(save(player.getInventory().getItem(i)));
        return String.join("\n", stacks);
    }

    private static String save(ItemStack stack) {
        return stack.save(new CompoundTag()).toString();
    }

    private Workflow110GameTests() {}
}
