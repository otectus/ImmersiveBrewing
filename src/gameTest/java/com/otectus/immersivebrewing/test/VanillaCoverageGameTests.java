package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.CoverageReport;
import com.otectus.immersivebrewing.recipe.DiscoveryIndex;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.BYPRODUCT;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.FUEL;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;

/**
 * Milestone 3 gate (spec 13, 15.3): every shipped plan, chained step by step through
 * {@code BrewingRecipeRegistry.getOutput}, gives exactly the registry's potion (identity, effects, amplifiers) for 1-6
 * servings, with costs, fuel and remainders matching the batch rules; every transformation the registry can make from
 * the vanilla potions is covered by a plan; and real brews check the special cases: blaze powder as reagent and fuel,
 * dragon's breath returning one bottle, Turtle Master's recorded-only quality, Weakness from water and quality
 * inheritance through modifiers and corruptions.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VanillaCoverageGameTests {
    private static final List<Item> CONTAINERS = List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);

    private static boolean vanilla(ItemStack stack) {
        return "minecraft".equals(ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(stack)).getNamespace());
    }

    private static String describe(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) + "[" + ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(stack)) + "]";
    }

    private static boolean sameEffects(ItemStack a, ItemStack b) {
        List<MobEffectInstance> x = PotionUtils.getMobEffects(a);
        List<MobEffectInstance> y = PotionUtils.getMobEffects(b);
        if (x.size() != y.size()) return false;
        for (int i = 0; i < x.size(); i++) {
            MobEffectInstance p = x.get(i);
            MobEffectInstance q = y.get(i);
            if (p.getEffect() != q.getEffect() || p.getAmplifier() != q.getAmplifier() || p.getDuration() != q.getDuration()) return false;
        }
        return true;
    }

    @GameTest(template = "empty")
    public static void everyPlanMatchesTheRegistry(GameTestHelper h) {
        PlanIndex index = PlanIndex.get(h.getLevel().getServer().getRecipeManager());
        int plans = 0;
        int checks = 0;
        Set<String> families = new HashSet<>();
        List<String> failures = new ArrayList<>();
        for (Item container : CONTAINERS) {
            for (Potion potion : ForgeRegistries.POTIONS) {
                ItemStack input = PotionUtils.setPotion(new ItemStack(container), potion);
                for (BrewPlan plan : index.plansFrom(input)) {
                    plans++;
                    String name = describe(input) + " -> " + describe(plan.output());
                    ItemStack state = input.copy();
                    for (BrewPlan.Step step : plan.steps()) {
                        if (!PotionContentsService.sameCanonical(step.before(), state)) failures.add(name + ": step input " + describe(step.before()));
                        ItemStack next = BrewingRecipeRegistry.getOutput(state.copy(), new ItemStack(step.reagent()));
                        if (next.isEmpty() || !PotionContentsService.sameCanonical(next, step.after()) || !sameEffects(next, step.after())) {
                            failures.add(name + ": " + ForgeRegistries.ITEMS.getKey(step.reagent()) + " gives " + (next.isEmpty() ? "nothing" : describe(next))
                                    + ", plan says " + describe(step.after()));
                            break;
                        }
                        state = next;
                    }
                    if (!PotionContentsService.sameCanonical(state, plan.output()) || !sameEffects(state, plan.output())) {
                        failures.add(name + ": chain ends at " + describe(state));
                    }
                    families.add(String.valueOf(ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(plan.output()))));
                    for (int n = 1; n <= Math.min(6, plan.servingLimit()); n++) {
                        int applications = (n + 2) / 3;
                        Map<Item, Integer> expected = new HashMap<>();
                        int remainders = 0;
                        for (BrewPlan.Step step : plan.steps()) {
                            expected.merge(step.reagent(), applications, Integer::sum);
                            if (step.reagent() == Items.DRAGON_BREATH) remainders += applications;
                        }
                        Map<Item, Integer> actual = new HashMap<>();
                        for (BrewPlan.Cost c : plan.costs(n)) actual.merge(c.item(), c.count(), Integer::sum);
                        if (!expected.equals(actual)) failures.add(name + " x" + n + ": costs " + actual + ", expected " + expected);
                        if (plan.fuelCharges(n) != applications * plan.steps().size()) failures.add(name + " x" + n + ": fuel " + plan.fuelCharges(n));
                        List<ItemStack> rem = plan.remainders(n);
                        if (rem.size() != remainders || rem.stream().anyMatch(r -> !r.is(Items.GLASS_BOTTLE))) {
                            failures.add(name + " x" + n + ": remainders " + rem);
                        }
                        checks++;
                    }
                }
            }
        }
        h.assertTrue(failures.isEmpty(), failures.size() + " plan mismatches, first: " + (failures.isEmpty() ? "" : failures.get(0)));
        h.assertTrue(plans >= 150, "the vanilla plan set is complete enough: " + plans + " plans");
        h.assertTrue(checks >= plans * 6, "every plan checked at 1-6 servings: " + checks);
        ImmersiveBrewing.LOGGER.info("[coverage] {} plans from vanilla potions, {} serving checks, {} output families", plans, checks, families.size());
        h.succeed();
    }

    /**
     * With only vanilla brewing the default budget probes the whole registry. A potion mod in the runtime multiplies the
     * potions and reagents past it, so then the probe gets a budget large enough to finish, and only transformations
     * between vanilla potions with vanilla reagents are checked here (CompatGameTests checks the mods' own).
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void everyVanillaTransformationIsPlanned(GameTestHelper h) {
        var server = h.getLevel().getServer();
        boolean modded = CompatGameTests.anyLoaded();
        DiscoveryIndex discovery = modded ? DiscoveryIndex.withBudget(server.getRecipeManager(), 1_000_000) : DiscoveryIndex.get(server.getRecipeManager());
        h.assertTrue(!discovery.truncated(), "the discovery budget covered the vanilla registry: " + discovery.queries() + " queries");
        h.assertTrue(discovery.found().size() > 100, "discovery found the vanilla transformations: " + discovery.found().size());
        List<String> report = CoverageReport.build(server);
        String dir = System.getProperty("immersive_brewing.reportDir");
        if (dir != null) {
            try {
                Files.createDirectories(Path.of(dir));
                // The GameTest world also loads the ib_test example pack; the release report comes from /immersivebrewing report.
                // A run with potion mods writes its own file, so the default run's report is not overwritten.
                Files.write(Path.of(dir, modded ? "gametest-coverage-report-potion-mods.txt" : "gametest-coverage-report.txt"), report, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("could not write the coverage report", e);
            }
        }
        List<String> uncovered = new ArrayList<>();
        for (DiscoveryIndex.Found f : discovery.found()) {
            if (modded && !(vanilla(f.input()) && vanilla(f.output()) && "minecraft".equals(ForgeRegistries.ITEMS.getKey(f.ingredient()).getNamespace()))) continue;
            if (!f.covered() && !f.excluded()) uncovered.add(describe(f.input()) + " + " + ForgeRegistries.ITEMS.getKey(f.ingredient()) + " -> " + describe(f.output()));
        }
        h.assertTrue(uncovered.isEmpty(), uncovered.size() + " vanilla transformations have no plan, first: " + (uncovered.isEmpty() ? "" : uncovered.get(0)));
        h.succeed();
    }

    // ---- real brews through the station ----

    /** Loads a kettle with {@code bottles} of {@code input}, the tray stacks and fuel, and brews toward {@code output}. */
    private static BrewersKettleBlockEntity brew(GameTestHelper h, String name, ItemStack input, int bottles, int powder, ItemStack output, ItemStack... tray) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 0, powder, tray);
        for (int i = 0; i < bottles; i++) kettle.inventory().setStackInSlot(RACK + i, input.copyWithCount(1));
        FakePlayer player = TestSupport.player(h, name);
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, output).key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the brew started");
        TestSupport.driveKettle(h, player, KettleBot.competent());
        return kettle;
    }

    private static boolean rackIs(BrewersKettleBlockEntity kettle, int n, ItemStack expected) {
        for (int i = 0; i < 6; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(RACK + i);
            if (i < n != (!s.isEmpty() && PotionContentsService.sameCanonical(s, expected) && BrewingMetadata.read(s).isPresent())) return false;
        }
        return true;
    }

    private static ItemStack potion(Item item, Potion potion) {
        return PotionUtils.setPotion(new ItemStack(item), potion);
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void blazePowderIsReagentOrFuelNeverBoth(GameTestHelper h) {
        h.setBlock(new net.minecraft.core.BlockPos(7, 1, 7), com.otectus.immersivebrewing.registry.ModBlocks.BREWERS_KETTLE.get());
        BrewersKettleBlockEntity probe = (BrewersKettleBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(new net.minecraft.core.BlockPos(7, 1, 7)));
        for (int i = 0; i < 3; i++) probe.inventory().setStackInSlot(RACK + i, potion(Items.POTION, Potions.AWKWARD));
        probe.inventory().setStackInSlot(TRAY, new ItemStack(Items.BLAZE_POWDER, 2));
        probe.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(com.otectus.immersivebrewing.registry.ModItems.WOODEN_BREWING_PADDLE.get()));
        BrewPlan strength = TestSupport.plan(h, probe, potion(Items.POTION, Potions.STRENGTH));
        h.assertTrue(probe.check(strength, 3).missing() != null, "powder in the tray is not fuel: the burner is empty");

        BrewersKettleBlockEntity kettle = brew(h, "Strongarm", potion(Items.POTION, Potions.AWKWARD), 3, 1, potion(Items.POTION, Potions.STRENGTH),
                new ItemStack(Items.BLAZE_POWDER));
        h.assertTrue(kettle.inventory().getStackInSlot(FUEL).isEmpty() && kettle.fuelCharges() == 19, "the burner's powder became 20 charges, one spent");
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.BLAZE_POWDER) == 0, "the tray's powder is the reagent");
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, potion(Items.POTION, Potions.STRENGTH)), "three Strength potions");
            h.assertTrue(kettle.fuelCharges() == 19, "no further fuel");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void dragonsBreathReturnsOneBottle(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = brew(h, "Lingerer", potion(Items.SPLASH_POTION, Potions.SWIFTNESS), 3, 1,
                potion(Items.LINGERING_POTION, Potions.SWIFTNESS), new ItemStack(Items.DRAGON_BREATH, 2));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, potion(Items.LINGERING_POTION, Potions.SWIFTNESS)), "three lingering Swiftness");
            h.assertTrue(TestSupport.count(kettle, BYPRODUCT, 3, Items.GLASS_BOTTLE) == 1, "exactly one bottle back from one dragon's breath");
            h.assertTrue(TestSupport.count(kettle, TRAY, 6, Items.DRAGON_BREATH) == 1, "one breath spent");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void turtleMasterRecordsQualityOnly(GameTestHelper h) {
        ItemStack turtle = potion(Items.POTION, Potions.TURTLE_MASTER);
        BrewersKettleBlockEntity kettle = brew(h, "Shellback", potion(Items.POTION, Potions.AWKWARD), 3, 1, turtle, new ItemStack(Items.TURTLE_HELMET));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, turtle), "three Turtle Master potions");
            ItemStack out = kettle.inventory().getStackInSlot(RACK);
            List<MobEffectInstance> effective = PotionContentsService.effectiveEffects(out, BrewingDefinitions.server());
            List<MobEffectInstance> nativeEffects = PotionUtils.getMobEffects(turtle);
            h.assertTrue(effective.size() == nativeEffects.size(), "same effects");
            for (int i = 0; i < effective.size(); i++) {
                h.assertTrue(effective.get(i).getDuration() == nativeEffects.get(i).getDuration()
                        && effective.get(i).getAmplifier() == nativeEffects.get(i).getAmplifier(), "a mixed bundle keeps its native effects exactly");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void weaknessFromWater(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = brew(h, "Weakener", TestSupport.water(), 3, 1, potion(Items.POTION, Potions.WEAKNESS),
                new ItemStack(Items.FERMENTED_SPIDER_EYE));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, potion(Items.POTION, Potions.WEAKNESS)), "three Weakness potions from water in one step");
            BrewingMetadata meta = BrewingMetadata.read(kettle.inventory().getStackInSlot(RACK)).orElseThrow();
            h.assertTrue(meta.lineage() != 0 && meta.purifications() == 0, "a fresh lineage");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aModifierCanNotRaiseQuality(GameTestHelper h) {
        ItemStack graded = potion(Items.POTION, Potions.SWIFTNESS);
        java.util.UUID brewer = java.util.UUID.randomUUID();
        BrewingMetadata in = new BrewingMetadata(90, 40, 52, 0xBEEFL, 1, brewer, "Original", "Refiner", "My label", null);
        BrewingMetadata.write(graded, in);
        BrewersKettleBlockEntity kettle = brew(h, "Modifier", graded, 3, 1, potion(Items.POTION, Potions.LONG_SWIFTNESS), new ItemStack(Items.REDSTONE));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, potion(Items.POTION, Potions.LONG_SWIFTNESS)), "three long Swiftness");
            BrewingMetadata out = BrewingMetadata.read(kettle.inventory().getStackInSlot(RACK)).orElseThrow();
            h.assertTrue(out.quality() <= in.quality(), "a modifier never raises quality: " + out.quality());
            h.assertTrue(out.extraction() == in.extraction(), "a modifier keeps the input's extraction");
            h.assertTrue(out.lineage() == in.lineage() && out.purifications() == 1, "lineage and purification count carry through");
            h.assertTrue("Original".equals(out.brewerName()) && "Refiner".equals(out.refinerName()) && "My label".equals(out.label()),
                    "attribution and label carry through a modifier");
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aCorruptionKeepsLineageButNotTheLabel(GameTestHelper h) {
        ItemStack graded = potion(Items.POTION, Potions.SWIFTNESS);
        BrewingMetadata in = new BrewingMetadata(80, 95, 91, 0xCAFEL, 0, java.util.UUID.randomUUID(), "Original", null, "Fast", null);
        BrewingMetadata.write(graded, in);
        BrewersKettleBlockEntity kettle = brew(h, "Corrupter", graded, 3, 1, potion(Items.POTION, Potions.SLOWNESS), new ItemStack(Items.FERMENTED_SPIDER_EYE));
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            h.assertTrue(rackIs(kettle, 3, potion(Items.POTION, Potions.SLOWNESS)), "three Slowness");
            BrewingMetadata out = BrewingMetadata.read(kettle.inventory().getStackInSlot(RACK)).orElseThrow();
            h.assertTrue(out.quality() <= in.quality(), "capped by the input: " + out.quality());
            h.assertTrue(out.lineage() == in.lineage(), "same lineage");
            h.assertTrue("Corrupter".equals(out.brewerName()) && out.label() == null, "a new effect records its own brewer and drops the label");
        });
    }

    private VanillaCoverageGameTests() {}
}
