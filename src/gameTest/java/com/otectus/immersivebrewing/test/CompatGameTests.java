package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.integration.PotionMods;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.minigame.preparation.PreparationMode;
import com.otectus.immersivebrewing.network.packet.StationActionC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.EffectPolicies;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.recipe.DiscoveryIndex;
import com.otectus.immersivebrewing.recipe.InfusionRecipe;
import com.otectus.immersivebrewing.recipe.PlanIndex;
import com.otectus.immersivebrewing.recipe.adapter.ForgeRegistryAdapter;
import com.otectus.immersivebrewing.registry.ModItems;
import com.otectus.immersivebrewing.registry.ModRecipeTypes;
import com.otectus.immersivebrewing.testing.DistillBot;
import com.otectus.immersivebrewing.testing.KettleBot;
import com.otectus.immersivebrewing.testing.MortarBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.Potion;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.brewing.BrewingRecipeRegistry;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.RACK;
import static com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity.TRAY;

/**
 * The optional potion mods (spec 18, 26.5 "a real potion-content mod"): Apothecary 1.5.2, Potions, Potions And More
 * Potions 1.0.4, PotionsMaster 0.6.0 and RPG Potions 1.2.0, each joined to the dev runtime by its Gradle flag. Every test
 * reaches a mod only through registry ids after a {@code ModList} check and passes trivially without it, so the suite
 * runs in every configuration of the compatibility matrix; {@link #compatDataFollowsLoadedMods} checks the absent side.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompatGameTests {
    static final boolean CHANGED_APOTHECARY = Boolean.getBoolean("immersive_brewing.apothecaryConfigFixture");
    static final List<String> MODS = PotionMods.IDS;

    static boolean loaded(String mod) {
        return ModList.get().isLoaded(mod);
    }

    static boolean anyLoaded() {
        return MODS.stream().anyMatch(CompatGameTests::loaded);
    }

    static Potion potion(String id) {
        Potion potion = ForgeRegistries.POTIONS.getValue(new ResourceLocation(id));
        if (potion == null || potion == Potions.EMPTY) throw new IllegalStateException("no potion " + id);
        return potion;
    }

    static ItemStack bottle(Item item, String potion) {
        return PotionUtils.setPotion(new ItemStack(item), potion(potion));
    }

    static Item item(String id) {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        if (item == null || item == Items.AIR) throw new IllegalStateException("no item " + id);
        return item;
    }

    static PlanIndex index(GameTestHelper h) {
        return PlanIndex.get(h.getLevel().getServer().getRecipeManager());
    }

    static String describe(ItemStack stack) {
        return ForgeRegistries.ITEMS.getKey(stack.getItem()) + "[" + ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(stack)) + "]";
    }

    // ---- data follows the installed mods ----

    @GameTest(template = "empty")
    public static void compatDataFollowsLoadedMods(GameTestHelper h) {
        PlanIndex index = index(h);
        BrewingDefinitions defs = BrewingDefinitions.server();
        List<InfusionRecipe> infusions = h.getLevel().getServer().getRecipeManager().getAllRecipesFor(ModRecipeTypes.INFUSION.get());
        List<String> problems = new ArrayList<>();
        Map<String, Integer> plans = new LinkedHashMap<>();
        for (String mod : MODS) {
            String prefix = "compat/" + mod + "/";
            List<InfusionRecipe> own = infusions.stream().filter(r -> r.getId().getNamespace().equals(ImmersiveBrewing.MOD_ID)
                    && r.getId().getPath().startsWith(prefix)).toList();
            boolean profiles = defs.reagentProfiles().stream().anyMatch(p -> p.id().getPath().startsWith(prefix));
            boolean policy = defs.effectPolicies().stream().anyMatch(p -> p.id().getPath().equals("compat/" + mod));
            boolean alembic = h.getLevel().getServer().getRecipeManager().byKey(ImmersiveBrewing.id(prefix + "distillation")).isPresent();
            if (!loaded(mod)) {
                if (!own.isEmpty() || profiles || policy || alembic) problems.add(mod + " is absent but its compat data loaded");
                continue;
            }
            if (own.isEmpty() || !profiles || !policy || !alembic) problems.add(mod + " is present but compat data is missing");
            int supported = 0;
            for (InfusionRecipe r : own) {
                PlanIndex.Entry e = index.entry(r.getId());
                boolean shadowed = r.getId().getPath().equals("compat/apothecary/potion/awkward__magma_block") && loaded("morepotions");
                if (shadowed) continue;
                if (CHANGED_APOTHECARY && r.getId().getPath().equals("compat/apothecary/potion/awkward__iron_block")) {
                    h.assertTrue(e != null && e.status() == PlanIndex.Status.UNSUPPORTED,
                            "old configured Iron Skin reagent is explicitly unavailable, not stale authority");
                    continue;
                }
                if (e == null || (e.status() != PlanIndex.Status.SUPPORTED && e.status() != PlanIndex.Status.METADATA_ONLY)) {
                    problems.add(r.getId() + ": " + (e == null ? "not indexed" : e.status() + " " + e.reason()));
                } else {
                    supported++;
                }
            }
            plans.put(mod, supported);
        }
        boolean sharedProfile = defs.reagentProfiles().stream().anyMatch(p -> p.id().getPath().equals("compat/shared/magma_block"));
        if (sharedProfile != (loaded("apothecary") || loaded("morepotions"))) problems.add("the shared magma block profile follows Apothecary or More Potions");
        h.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + (problems.isEmpty() ? "" : problems.get(0)));
        ImmersiveBrewing.LOGGER.info("[compat] validated plans per loaded mod: {}", plans);
        h.succeed();
    }

    /**
     * Every transformation the registry can make, probed completely (not the default 4096-query budget), is covered by a
     * plan, excluded, or loose. The one documented exception: PotionsMaster's recipes test only the Mundane potion, so a
     * splash or lingering Mundane also becomes a drinkable sight potion; that stays a contextual query, not a catalog plan.
     */
    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void everyCompatTransformationIsPlanned(GameTestHelper h) {
        if (!anyLoaded()) {
            h.succeed();
            return;
        }
        long start = System.nanoTime();
        DiscoveryIndex discovery = DiscoveryIndex.withBudget(h.getLevel().getServer().getRecipeManager(), 1_000_000);
        long millis = (System.nanoTime() - start) / 1_000_000;
        h.assertTrue(!discovery.truncated(), "the full probe finished: " + discovery.queries() + " queries");
        List<String> uncovered = new ArrayList<>();
        int contextual = 0;
        for (DiscoveryIndex.Found f : discovery.found()) {
            if (f.covered() || f.excluded() || f.loose()) continue;
            boolean pmContainer = "potionsmaster".equals(ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(f.output())).getNamespace())
                    && !f.input().is(Items.POTION);
            boolean changedIngredient = CHANGED_APOTHECARY && f.ingredient() == Items.IRON_INGOT
                    && PotionUtils.getPotion(f.output()) == potion("apothecary:iron_skin");
            if (changedIngredient) h.assertTrue(index(h).contextualPlans(f.input(), List.of(new ItemStack(Items.IRON_INGOT)), List.of())
                    .stream().anyMatch(p -> PotionContentsService.sameCanonical(p.output(), f.output())), "configured ingredient remains usable via exact tray context");
            if (pmContainer || changedIngredient) {
                contextual++;
                continue;
            }
            uncovered.add(describe(f.input()) + " + " + ForgeRegistries.ITEMS.getKey(f.ingredient()) + " -> " + describe(f.output()));
        }
        h.assertTrue(uncovered.isEmpty(), uncovered.size() + " transformations have no plan, first: " + (uncovered.isEmpty() ? "" : uncovered.get(0)));
        for (DiscoveryIndex.Found f : discovery.found()) {
            if (!f.loose()) continue;
            String ns = ForgeRegistries.POTIONS.getKey(PotionUtils.getPotion(f.output())).getNamespace();
            h.assertTrue(ns.equals("rpg_potions"), "only RPG Potions' recipes match loosely: " + describe(f.input()) + " -> " + describe(f.output()));
        }
        h.assertTrue(discovery.loose() > 0 == loaded("rpg_potions"), "RPG Potions' loose matches are found exactly when it is loaded");
        ImmersiveBrewing.LOGGER.info("[compat] full discovery: {} queries in {} ms, {} transformations, {} loose, {} PotionsMaster container queries",
                discovery.queries(), millis, discovery.found().size(), discovery.loose(), contextual);
        h.succeed();
    }

    // ---- real brews through the Kettle ----

    /** Brews three servings of {@code output} from three {@code input} bottles at a Kettle with the competent bot. */
    static BrewersKettleBlockEntity brew(GameTestHelper h, String name, ItemStack input, ItemStack output, ItemStack... tray) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 0, 1, tray);
        for (int i = 0; i < 3; i++) kettle.inventory().setStackInSlot(RACK + i, input.copyWithCount(1));
        FakePlayer player = TestSupport.player(h, name);
        TestSupport.openKettle(player, kettle);
        BrewPlan plan = TestSupport.plan(h, kettle, output);
        kettle.startBrew(player, plan.key(), 1F, 1F);
        h.assertTrue(TestSupport.session(player).isPresent(), "the brew started: " + describe(input) + " -> " + describe(output));
        TestSupport.driveKettle(h, player, KettleBot.competent());
        return kettle;
    }

    static void expectBrewed(GameTestHelper h, BrewersKettleBlockEntity kettle, ItemStack output, ItemStack registryResult) {
        h.succeedWhen(() -> {
            h.assertTrue(kettle.ledger() == null, "still brewing");
            for (int i = 0; i < 3; i++) {
                ItemStack out = kettle.inventory().getStackInSlot(RACK + i);
                h.assertTrue(PotionContentsService.sameCanonical(out, output), "serving " + i + " is " + describe(output) + ": " + describe(out));
                h.assertTrue(PotionContentsService.sameCanonical(out, registryResult), "and exactly what the registry makes");
                h.assertTrue(BrewingMetadata.read(out).isPresent(), "graded");
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void apothecaryIronSkinFromWater(GameTestHelper h) {
        if (!loaded("apothecary")) {
            h.succeed();
            return;
        }
        ItemStack ironSkin = bottle(Items.POTION, "apothecary:iron_skin");
        ItemStack awkward = BrewingRecipeRegistry.getOutput(TestSupport.water(), new ItemStack(Items.NETHER_WART));
        Item reagent = CHANGED_APOTHECARY ? Items.IRON_INGOT : Items.IRON_BLOCK;
        ItemStack registry = BrewingRecipeRegistry.getOutput(awkward, new ItemStack(reagent));
        h.assertTrue(PotionContentsService.sameCanonical(registry, ironSkin), "live Apothecary config produces Iron Skin");
        BrewersKettleBlockEntity kettle = CHANGED_APOTHECARY
                ? brew(h, "Ironbrewer", awkward, ironSkin, new ItemStack(reagent))
                : brew(h, "Ironbrewer", TestSupport.water(), ironSkin, new ItemStack(Items.NETHER_WART), new ItemStack(reagent));
        h.assertTrue(TestSupport.count(kettle, TRAY, 6, reagent) == 0 && TestSupport.count(kettle, TRAY, 6, Items.NETHER_WART) == 0,
                "the actual configured reagent is reserved once for three servings");
        expectBrewed(h, kettle, ironSkin, registry);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void morePotionsSplashWeight(GameTestHelper h) {
        if (!loaded("morepotions")) {
            h.succeed();
            return;
        }
        ItemStack splashAwkward = PotionUtils.setPotion(new ItemStack(Items.SPLASH_POTION), Potions.AWKWARD);
        ItemStack weight = bottle(Items.SPLASH_POTION, "morepotions:weight_potion");
        ItemStack registry = BrewingRecipeRegistry.getOutput(splashAwkward.copy(), new ItemStack(Items.IRON_NUGGET));
        expectBrewed(h, brew(h, "Splasher", splashAwkward, weight, new ItemStack(Items.IRON_NUGGET)), weight, registry);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void potionsMasterCoalSight(GameTestHelper h) {
        if (!loaded("potionsmaster")) {
            h.succeed();
            return;
        }
        ItemStack mundane = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.MUNDANE);
        ItemStack sight = bottle(Items.POTION, "potionsmaster:coal_sight");
        Item powder = item("potionsmaster:calcinatedcoal_powder");
        ItemStack registry = BrewingRecipeRegistry.getOutput(mundane.copy(), new ItemStack(powder));
        expectBrewed(h, brew(h, "Prospector", mundane, sight, new ItemStack(powder)), sight, registry);
    }

    @GameTest(template = "empty", timeoutTicks = 1800)
    public static void rpgPotionsSmoke(GameTestHelper h) {
        if (!loaded("rpg_potions")) {
            h.succeed();
            return;
        }
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        ItemStack smoke = bottle(Items.POTION, "rpg_potions:smoke");
        ItemStack registry = BrewingRecipeRegistry.getOutput(awkward.copy(), new ItemStack(Items.CHARCOAL));
        expectBrewed(h, brew(h, "Smoker", awkward, smoke, new ItemStack(Items.CHARCOAL)), smoke, registry);
    }

    // ---- quality on the mods' effects ----

    static ItemStack graded(Potion potion, int quality) {
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.POTION), potion);
        BrewingMetadata.write(stack, BrewingMetadata.brewed(50, quality, quality, 0xC0FFEEL, UUID.randomUUID(), "Tester"));
        return stack;
    }

    static int expected(int base, int quality) {
        return (int) Math.round(base * (200 + quality) / 250.0);
    }

    /**
     * Every effect a compat policy lists scales on drinking, at quality 0 and 100, exactly once and with its amplifier
     * kept; every other effect of those mods keeps its own duration (Leap) or is instant and never scales (instant ones
     * are not drunk here: More Potions' Lightning summons a bolt). Vanilla effects in a mod's potions follow the vanilla
     * policy.
     */
    @GameTest(template = "empty")
    public static void compatEffectsFollowTheirPolicies(GameTestHelper h) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        List<String> problems = new ArrayList<>();
        int scaled = 0;
        List<ResourceLocation> kept = new ArrayList<>();
        for (String mod : MODS) {
            if (!loaded(mod)) continue;
            Set<ResourceLocation> listed = defs.effectPolicies().stream().filter(p -> p.id().getPath().equals("compat/" + mod))
                    .flatMap(p -> p.selector().ids().stream()).collect(java.util.stream.Collectors.toSet());
            Map<MobEffect, MobEffectInstance> sample = new HashMap<>();
            Map<MobEffect, Potion> source = new HashMap<>();
            for (Potion potion : ForgeRegistries.POTIONS) {
                ResourceLocation id = ForgeRegistries.POTIONS.getKey(potion);
                if (id == null || !id.getNamespace().equals(mod) || potion.getEffects().size() != 1) continue;
                MobEffectInstance e = potion.getEffects().get(0);
                if (e.getDuration() > 20 && !sample.containsKey(e.getEffect())) {
                    sample.put(e.getEffect(), e);
                    source.put(e.getEffect(), potion);
                }
            }
            for (ResourceLocation id : listed) {
                MobEffect effect = ForgeRegistries.MOB_EFFECTS.getValue(id);
                if (effect == null) {
                    problems.add(id + " is listed but not registered");
                    continue;
                }
                if (!EffectPolicies.treatment(effect, defs).scales()) problems.add(id + " is listed but does not scale");
                if (!sample.containsKey(effect)) problems.add(id + " has no potion of " + mod + " to drink");
            }
            for (MobEffect effect : ForgeRegistries.MOB_EFFECTS) {
                ResourceLocation id = ForgeRegistries.MOB_EFFECTS.getKey(effect);
                if (id != null && id.getNamespace().equals(mod) && !listed.contains(id) && EffectPolicies.treatment(effect, defs).scales()) {
                    problems.add(id + " is not listed but scales");
                }
            }
            for (Map.Entry<MobEffect, MobEffectInstance> e : sample.entrySet()) {
                ResourceLocation id = ForgeRegistries.MOB_EFFECTS.getKey(e.getKey());
                if (e.getKey().isInstantenous()) {
                    if (EffectPolicies.treatment(e.getKey(), defs).scales()) problems.add(id + " is instant but scales");
                    kept.add(id);
                    continue;
                }
                boolean scales = EffectPolicies.treatment(e.getKey(), defs).scales();
                for (int q : new int[] {0, 100}) {
                    Player player = h.makeMockPlayer();
                    Items.POTION.finishUsingItem(graded(source.get(e.getKey()), q), h.getLevel(), player);
                    MobEffectInstance got = player.getEffect(e.getKey());
                    int want = scales ? expected(e.getValue().getDuration(), q) : e.getValue().getDuration();
                    if (got == null || got.getDuration() != want) {
                        problems.add(id + " at quality " + q + ": " + (got == null ? "not applied" : got.getDuration()) + " ticks, expected " + want);
                    }
                    if (got != null && got.getAmplifier() != e.getValue().getAmplifier()) problems.add(id + ": quality changed the amplifier");
                }
                if (scales) scaled++;
                else kept.add(id);
            }
        }
        h.assertTrue(problems.isEmpty(), problems.size() + " problems, first: " + (problems.isEmpty() ? "" : problems.get(0)));
        ImmersiveBrewing.LOGGER.info("[compat] drinking: {} effects scale with quality; kept as brewed: {}", scaled, kept);
        h.succeed();
    }

    // ---- the Mortar and the Alembic ----

    static void prepares(GameTestHelper h, String reagent, PreparationMode mode, String raw) {
        BlockPos at = new BlockPos(1, 1, 1);
        ApothecaryMortarBlockEntity mortar = TestSupport.mortar(h, at);
        Item item = item(reagent);
        ReagentProfile profile = mortar.profileFor(new ItemStack(item));
        h.assertTrue(profile != null && profile.preparation().mode() == mode, reagent + " prepares as " + mode + ": " + (profile == null ? null : profile.id()));
        h.assertTrue(mortar.profileFor(new ItemStack(item(raw))) == null, raw + " is used raw: the Mortar refuses it");
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, new ItemStack(item, 2));
        FakePlayer player = TestSupport.player(h, "Grinder");
        player.setPos(h.absoluteVec(new Vec3(2.5, 1, 2.5)));
        TestSupport.openMortar(player, mortar);
        mortar.start(player, StationActionC2S.withAssist(1, 1, 1));
        h.assertTrue(mortar.ledger() != null, "the preparation started");
        TestSupport.driveMortar(h, player, MortarBot.competent());
        h.succeedWhen(() -> {
            h.assertTrue(mortar.ledger() == null, "still preparing");
            ItemStack kit = ItemStack.EMPTY;
            for (int i = 0; i < ApothecaryMortarBlockEntity.OUTPUT_SIZE; i++) {
                ItemStack s = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.OUTPUT + i);
                if (s.is(ModItems.PREPARED_REAGENT_KIT.get())) kit = s;
            }
            KitData data = KitData.read(kit).orElseThrow(() -> new IllegalStateException("no kit"));
            h.assertTrue(data.source().is(item) && data.profile().equals(profile.id()), "a kit of " + reagent + " under " + profile.id());
        });
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void apothecaryReagentPrepares(GameTestHelper h) {
        if (loaded("apothecary")) prepares(h, "minecraft:lapis_lazuli", PreparationMode.CRYSTALLINE, "minecraft:iron_block");
        else h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void morePotionsReagentPrepares(GameTestHelper h) {
        if (loaded("morepotions")) prepares(h, "minecraft:feather", PreparationMode.FIBROUS, "minecraft:iron_ingot");
        else h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void potionsMasterReagentPrepares(GameTestHelper h) {
        if (loaded("potionsmaster")) prepares(h, "potionsmaster:calcinatediron_powder", PreparationMode.CRYSTALLINE, "potionsmaster:iron_powder");
        else h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void rpgPotionsReagentPrepares(GameTestHelper h) {
        if (loaded("rpg_potions")) prepares(h, "minecraft:slime_ball", PreparationMode.LIQUID, "minecraft:tnt");
        else h.succeed();
    }

    /** Each mod's potions purify through its own Alembic recipe; one purification runs end to end. */
    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void compatPotionsPurify(GameTestHelper h) {
        Map<String, String> samples = Map.of("apothecary", "apothecary:iron_skin", "morepotions", "morepotions:weight_potion",
                "potionsmaster", "potionsmaster:gold_sight", "rpg_potions", "rpg_potions:phantom_armor");
        String first = null;
        for (String mod : MODS) {
            if (!loaded(mod)) continue;
            AlembicBlockEntity alembic = AlembicGameTests.load(h, 1, AlembicGameTests.copies(graded(potion(samples.get(mod)), 40), 3));
            AlembicBlockEntity.PurifyPlan plan = alembic.purifyPlan();
            h.assertTrue(plan.problem() == null && plan.recipe() != null && plan.recipe().getId().equals(ImmersiveBrewing.id("compat/" + mod + "/distillation")),
                    samples.get(mod) + " purifies through compat/" + mod + "/distillation: " + plan.problem());
            if (first == null) first = mod;
        }
        if (first == null) {
            h.succeed();
            return;
        }
        String mod = first;
        AlembicBlockEntity alembic = AlembicGameTests.load(h, 1, AlembicGameTests.copies(graded(potion(samples.get(mod)), 40), 3));
        FakePlayer player = AlembicGameTests.purify(h, alembic, "Refiner");
        h.succeedWhen(() -> {
            h.assertTrue(alembic.ledger() == null, "still distilling");
            List<ItemStack> potions = AlembicGameTests.outputs(alembic, Items.POTION);
            h.assertTrue(AlembicGameTests.count(potions) == 2, "two potions out of three");
            BrewingMetadata meta = BrewingMetadata.read(potions.get(0)).orElseThrow();
            h.assertTrue(PotionContentsService.sameCanonical(potions.get(0), bottle(Items.POTION, samples.get(mod))), "the same " + samples.get(mod));
            h.assertTrue(meta.purified() && meta.quality() > 40, "purified to a higher quality: " + meta.quality());
            h.assertTrue(AlembicGameTests.distillation(player) >= 0, "the result reached the player");
        });
    }

    // ---- registry quirks ----

    /**
     * RPG Potions' recipes are plain Forge recipes with a vanilla ingredient: the registry also answers for Water or any
     * other drinkable potion. The Kettle brews only what they declare (Awkward), as JEI shows them.
     */
    @GameTest(template = "empty")
    public static void rpgLooseMatchesAreNotOffered(GameTestHelper h) {
        if (!loaded("rpg_potions")) {
            h.succeed();
            return;
        }
        ItemStack smoke = bottle(Items.POTION, "rpg_potions:smoke");
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        ItemStack charcoal = new ItemStack(Items.CHARCOAL);
        ForgeRegistryAdapter adapter = new ForgeRegistryAdapter();
        h.assertTrue(PotionContentsService.sameCanonical(adapter.preview(awkward, charcoal).output(), smoke), "Awkward + Charcoal makes Smoke");
        for (ItemStack other : List.of(TestSupport.water(), PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS))) {
            h.assertTrue(PotionContentsService.sameCanonical(BrewingRecipeRegistry.getOutput(other.copy(), charcoal), smoke),
                    "a stand would make Smoke from " + describe(other));
            var preview = adapter.preview(other, charcoal);
            h.assertTrue(!preview.supported() && "plan.immersive_brewing.unsupported.loose_input".equals(preview.unsupportedReason()),
                    "the Kettle refuses " + describe(other) + " + Charcoal as a loose match");
            List<BrewPlan> catalog = index(h).plansFrom(other);
            h.assertTrue(index(h).contextualPlans(other, List.of(charcoal), catalog).isEmpty(), "and offers no contextual plan for it");
        }
        h.assertTrue(index(h).plansFrom(awkward).stream().anyMatch(p -> p.steps().size() == 1 && PotionContentsService.sameCanonical(p.output(), smoke)),
                "the declared recipe is a catalog plan");
        h.succeed();
    }

    /** Apothecary's Burning and More Potions' Fire both use a magma block on Awkward; the registry's first answer wins. */
    @GameTest(template = "empty")
    public static void sharedReagentFollowsTheRegistry(GameTestHelper h) {
        if (!loaded("apothecary") || !loaded("morepotions")) {
            h.succeed();
            return;
        }
        ItemStack awkward = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD);
        ItemStack registry = BrewingRecipeRegistry.getOutput(awkward.copy(), new ItemStack(Items.MAGMA_BLOCK));
        List<BrewPlan> plans = index(h).plansFrom(awkward);
        h.assertTrue(plans.stream().anyMatch(p -> p.steps().size() == 1 && p.steps().get(0).reagent() == Items.MAGMA_BLOCK
                && PotionContentsService.sameCanonical(p.output(), registry)), "the actual live registry winner is offered");
        for (String id : List.of("apothecary:burning", "morepotions:fire_potion")) {
            ItemStack expected = bottle(Items.POTION, id);
            String path = id.startsWith("apothecary") ? "compat/apothecary/potion/awkward__magma_block" : "compat/morepotions/potion/awkward__magma_block";
            PlanIndex.Entry entry = index(h).entry(ImmersiveBrewing.id(path));
            if (!PotionContentsService.sameCanonical(registry, expected)) {
                h.assertTrue(entry != null && entry.status() == PlanIndex.Status.UNSUPPORTED
                        && entry.reason().startsWith("plan.immersive_brewing.unsupported.result_differs"), "shadowed recipe reports the actual mismatch: " + entry);
                h.assertTrue(plans.stream().noneMatch(p -> p.steps().size() == 1 && p.steps().get(0).reagent() == Items.MAGMA_BLOCK
                        && PotionContentsService.sameCanonical(p.output(), expected)), "the losing transformation is not offered");
            }
        }
        ImmersiveBrewing.LOGGER.info("[compat] actual shared magma-block recipe winner: {}", describe(registry));
        h.succeed();
    }

    // ---- inventory environments ----

    static void setMaxStackSize(Item item, int size) {
        ObfuscationReflectionHelper.setPrivateValue(Item.class, item, size, "f_41370_");
    }

    /**
     * Apothecary's Stack_Size option makes potions stack (spec 26.5, "a changed potion stack-size environment"). Rack
     * and Alembic slots still hold one bottle each, a carrier's stack is split, and serving costs stay per bottle. Its
     * own batch: item stack sizes are global.
     */
    @GameTest(template = "empty", batch = "potion_stack_size")
    public static void stackablePotionsKeepServingEconomics(GameTestHelper h) {
        List<Item> potions = List.of(Items.POTION, Items.SPLASH_POTION, Items.LINGERING_POTION);
        Map<Item, Integer> previous = new HashMap<>();
        for (Item item : potions) previous.put(item, item.getMaxStackSize());
        try {
            if (CHANGED_APOTHECARY) {
                h.assertTrue(loaded("apothecary"), "real stack fixture requires Apothecary");
                for (Item item : potions) h.assertTrue(item.getMaxStackSize() == 16, "Apothecary Stack_Size=16 reached the actual item: " + item);
            } else for (Item item : potions) setMaxStackSize(item, 16);
            h.assertTrue(TestSupport.water().getMaxStackSize() == 16, "potions stack to 16 for this test");
            BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
            for (int i = 0; i < kettle.inventory().getSlots(); i++) kettle.inventory().setStackInSlot(i, ItemStack.EMPTY);
            ItemStack left = kettle.inventory().insertItem(RACK, TestSupport.water().copyWithCount(16), false);
            h.assertTrue(left.getCount() == 15 && kettle.inventory().getStackInSlot(RACK).getCount() == 1, "a rack slot takes one bottle of a stack");
            ItemStack carrier = new ItemStack(ModItems.BATCH_CARRIER.get());
            List<ItemStack> contents = new ArrayList<>();
            contents.add(TestSupport.water().copyWithCount(7));
            for (int i = 1; i < CarrierContents.SLOTS; i++) contents.add(ItemStack.EMPTY);
            CarrierContents.write(carrier, contents);
            FakePlayer player = TestSupport.player(h, "Stacker");
            ItemStack after = TestSupport.use(player, kettle, carrier);
            h.assertTrue(TestSupport.count(kettle, RACK, 6, Items.POTION) == 6, "the carrier's stack filled the five free slots one bottle each");
            for (int i = 0; i < 6; i++) h.assertTrue(kettle.inventory().getStackInSlot(RACK + i).getCount() == 1, "slot " + i + " holds one bottle");
            h.assertTrue(CarrierContents.count(after) == 2, "two bottles stay in the carrier: " + CarrierContents.read(after));
            h.assertTrue(kettle.rack().servings() == 6, "six servings, one per bottle");
            kettle.inventory().setStackInSlot(TRAY, new ItemStack(Items.NETHER_WART, 2));
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.PADDLE, new ItemStack(ModItems.WOODEN_BREWING_PADDLE.get()));
            BrewPlan awkward = TestSupport.plan(h, kettle, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.AWKWARD));
            h.assertTrue(awkward.costs(6).size() == 1 && awkward.costs(6).get(0).count() == 2 && awkward.fuelCharges(6) == 2,
                    "six servings still cost two nether wart and two fuel charges");
            AlembicBlockEntity alembic = AlembicGameTests.load(h, 0);
            ItemStack rest = alembic.inventory().insertItem(AlembicBlockEntity.INPUT, graded(Potions.STRENGTH, 40).copyWithCount(5), false);
            h.assertTrue(rest.getCount() == 4, "an Alembic input slot takes one bottle of a stack");
        } finally {
            if (!CHANGED_APOTHECARY) previous.forEach(CompatGameTests::setMaxStackSize);
        }
        h.succeed();
    }

    // ---- the plan list with a large registry ----

    /**
     * The catalog from one input stops at {@link PlanIndex#MAX_PLANS}; with several potion mods Water reaches it (every
     * Mundane-based potion multiplies by each Mundane reagent and container). A path the tray can pay for is still
     * offered and resolves when the brew starts.
     */
    @GameTest(template = "empty")
    public static void loadedPlansSurviveTheCatalogBound(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR), new ItemStack(Items.REDSTONE),
                new ItemStack(Items.GUNPOWDER), new ItemStack(Items.DRAGON_BREATH));
        ItemStack target = PotionUtils.setPotion(new ItemStack(Items.LINGERING_POTION), Potions.LONG_SWIFTNESS);
        long t0 = System.nanoTime();
        PlanIndex fresh = PlanIndex.forClient(h.getLevel().getServer().getRecipeManager(), BrewingDefinitions.server());
        long t1 = System.nanoTime();
        List<BrewPlan> catalog = fresh.plansFrom(TestSupport.water());
        long t2 = System.nanoTime();
        List<BrewPlan> offered = kettle.plans();
        FakePlayer viewer = TestSupport.player(h, "Viewer");
        TestSupport.openKettle(viewer, kettle);
        long t3 = System.nanoTime();
        kettle.sendPlans(viewer);
        long t4 = System.nanoTime();
        long[] warm = new long[5];
        for (int i = 0; i < warm.length; i++) {
            long a = System.nanoTime();
            kettle.sendPlans(viewer);
            warm[i] = (System.nanoTime() - a) / 1000;
        }
        BrewPlan plan = offered.stream().filter(p -> PotionContentsService.sameCanonical(p.output(), target)).findFirst().orElse(null);
        h.assertTrue(plan != null && plan.steps().size() == 5, "Water to lingering Long Swiftness in five loaded steps is offered");
        Set<Item> tray = Set.of(Items.NETHER_WART, Items.SUGAR, Items.REDSTONE, Items.GUNPOWDER, Items.DRAGON_BREATH);
        for (BrewPlan p : index(h).plansUsing(TestSupport.water(), tray)) {
            h.assertTrue(p.steps().stream().allMatch(s -> tray.contains(s.reagent())), "a loaded-only plan uses only tray reagents");
        }
        BrewPlan resolved = index(h).resolve(plan.key(), TestSupport.water(), kettle.trayItems());
        h.assertTrue(resolved != null && resolved.key().equals(plan.key()), "and it resolves when the brew starts");
        ImmersiveBrewing.LOGGER.info("[compat] plans from Water: catalog {} (bound {}), offered with this tray {}; index build {} ms ({} recipes),"
                        + " catalog search {} ms, plan list packet {} ms first, then {} us", catalog.size(), PlanIndex.MAX_PLANS, offered.size(),
                (t1 - t0) / 1_000_000, fresh.report().size(), (t2 - t1) / 1_000_000, (t4 - t3) / 1_000_000, java.util.Arrays.toString(warm));
        h.succeed();
    }
}
