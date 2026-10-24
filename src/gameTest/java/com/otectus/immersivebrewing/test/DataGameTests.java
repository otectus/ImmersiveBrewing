package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDataLoader;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ProfileSelector;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.DiscoveryIndex;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;

/**
 * Milestone 5 gate (spec 16): the example pack {@code data/ib_test} (GameTest resources only) adds, without Java, a
 * working infusion plan with its own brew profile and a reagent profile; its invalid recipe and invalid definition are
 * reported and never offered; two equal profiles resolve deterministically with a tie diagnostic; and the item it
 * excludes stays out of every path: catalog, contextual query, preparation, discovery.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class DataGameTests {
    static final ResourceLocation PACK_PLAN = new ResourceLocation("ib_test", "infusion/mundane_from_redstone");
    static final ResourceLocation WRONG_RESULT = new ResourceLocation("ib_test", "infusion/wrong_result");
    static final ResourceLocation STILL_WATER = new ResourceLocation("ib_test", "still_water");

    private static PlanIndex index(GameTestHelper h) {
        return PlanIndex.get(h.getLevel().getServer().getRecipeManager());
    }

    private static BrewPlan packPlan(GameTestHelper h) {
        return index(h).plansFrom(TestSupport.water()).stream()
                .filter(p -> p.steps().size() == 1 && p.steps().get(0).source().equals(PACK_PLAN))
                .findFirst().orElseThrow(() -> new IllegalStateException("the ib_test plan is not offered"));
    }

    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void aDatapackPlanAndProfileWorkWithoutJava(GameTestHelper h) {
        PlanIndex.Entry entry = index(h).entry(PACK_PLAN);
        h.assertTrue(entry != null && (entry.status() == PlanIndex.Status.SUPPORTED || entry.status() == PlanIndex.Status.METADATA_ONLY),
                "the pack's recipe validated: " + entry);
        BrewPlan plan = packPlan(h);
        h.assertTrue(plan.steps().get(0).reagent() == Items.REDSTONE && PotionUtils.getPotion(plan.output()) == Potions.MUNDANE, "water + redstone -> mundane");
        h.assertTrue(plan.brewProfile().equals(STILL_WATER), "with the pack's own brew profile: " + plan.brewProfile());
        h.assertTrue(BrewingDefinitions.server().hasBrewProfile(STILL_WATER), "which loaded");

        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.REDSTONE));
        FakePlayer player = TestSupport.player(h, "Packer");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, plan.key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the datapack plan brews at the Kettle");
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(RACK + i);
                h.assertTrue(PotionUtils.getPotion(out) == Potions.MUNDANE && BrewingMetadata.read(out).isPresent(), "graded mundane " + i);
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void aDatapackReagentProfilePrepares(GameTestHelper h) {
        ProfileSelector.Choice<ReagentProfile> choice = BrewingDefinitions.server().reagentProfile(Items.RABBIT_HIDE, null, null);
        h.assertTrue(choice != null && choice.id().equals(new ResourceLocation("ib_test", "hide_tie_a")),
                "equal priority resolves to the smallest id: " + (choice == null ? null : choice.id()));
        h.assertTrue(new ResourceLocation("ib_test", "hide_tie_b").equals(choice.tiedWith()), "and records what it tied with");
        BlockPos at = new BlockPos(1, 1, 1);
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, at);
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.RABBIT_HIDE, 2));
        FakePlayer player = TestSupport.player(h, "Tanner");
        player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        TestSupport.openMortar(player, mortar);
        mortar.start(player, StationActionC2S.withAssist(1, 1, 1));
        h.assertTrue(mortar.ledger() != null, "the Mortar prepares a reagent only the pack profiles");
        TestSupport.driveMortar(h, player, MortarBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(mortar.ledger() == null, "still preparing");
            ItemStack kit = ItemStack.EMPTY;
            for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
                ItemStack s = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
                if (s.is(ModItems.PREPARED_REAGENT_KIT.get())) kit = s;
            }
            KitData data = KitData.read(kit).orElseThrow(() -> new IllegalStateException("no kit"));
            h.assertTrue(data.source().is(Items.RABBIT_HIDE) && data.profile().equals(new ResourceLocation("ib_test", "hide_tie_a")),
                    "a rabbit-hide kit under the pack's profile");
        });
    }

    @GameTest(template = "empty")
    public static void invalidDataIsReportedAndNeverOffered(GameTestHelper h) {
        PlanIndex.Entry wrong = index(h).entry(WRONG_RESULT);
        h.assertTrue(wrong != null && wrong.status() == PlanIndex.Status.UNSUPPORTED && wrong.reason().startsWith("plan.immersive_brewing.unsupported.result_differs"),
                "a recipe whose registry result differs is reported: " + wrong);
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        h.assertTrue(index(h).plansFrom(awkward).stream().flatMap(p -> p.steps().stream()).noneMatch(s -> s.source().equals(WRONG_RESULT)),
                "and never offered");
        BrewingDataLoader.Report report = BrewingDataLoader.lastReport();
        h.assertTrue(report.errors().stream().anyMatch(e -> e.contains("ib_test:inverted_endpoint") && e.contains("endpoint")),
                "the invalid definition is named in the report: " + report.errors());
        h.assertTrue(!report.keptPrevious() && report.generation() > 0, "on first load it is skipped and the rest activates");
        h.assertTrue(BrewingDefinitions.server().reagentProfiles().stream().noneMatch(p -> p.id().getPath().equals("inverted_endpoint")),
                "the invalid profile is not active");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void anExcludedItemStaysExcluded(GameTestHelper h) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        h.assertTrue(defs.exclusions().excludesItem(Items.PHANTOM_MEMBRANE), "the pack excludes phantom membrane");
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        List<BrewPlan> catalog = index(h).plansFrom(awkward);
        h.assertTrue(catalog.stream().flatMap(p -> p.steps().stream()).noneMatch(s -> s.reagent() == Items.PHANTOM_MEMBRANE), "no catalog plan uses it");
        h.assertTrue(index(h).contextualPlans(awkward, List.of(new ItemStack(Items.PHANTOM_MEMBRANE)), catalog).isEmpty(), "no contextual query offers it");
        h.assertTrue(defs.reagentProfile(Items.PHANTOM_MEMBRANE, null, null) == null, "its built-in profile no longer applies");
        PlanIndex.Entry recipe = index(h).entry(ImmersiveBrewing.id("infusion/potion/awkward__phantom_membrane"));
        h.assertTrue(recipe != null && recipe.status() == PlanIndex.Status.EXCLUDED, "the built-in recipe using it reports as excluded: " + recipe);

        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, new BlockPos(1, 1, 1));
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(Items.PHANTOM_MEMBRANE));
        FakePlayer player = TestSupport.player(h, "Excluded");
        player.setPos(h.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 1, 2.5)));
        TestSupport.openMortar(player, mortar);
        mortar.start(player, StationActionC2S.withAssist(1, 1, 1));
        h.assertTrue(mortar.ledger() == null, "the Mortar will not prepare it");

        DiscoveryIndex discovery = DiscoveryIndex.get(h.getLevel().getServer().getRecipeManager());
        h.assertTrue(discovery.found().stream().anyMatch(f -> f.ingredient() == Items.PHANTOM_MEMBRANE && f.excluded() && !f.covered()),
                "discovery still sees the registry transformation, marked excluded");
        h.succeed();
    }

    private DataGameTests() {}
}
