package com.otectus.immersivebrewing.test;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.guide.PlayerGuide;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Migration checks backed by immutable 1.0.2 world payloads; see the adjacent provenance manifest. */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class Migration110GameTests {
    private static final String ROOT = "/migration/1_0_2/";

    @GameTest(template = "empty")
    public static void actualPotionAndPreparedKitSurviveFirstResave(GameTestHelper h) {
        CompoundTag potionFixture = fixture("showcase_graded_potion.snbt");
        ItemStack potion = ItemStack.of(potionFixture);
        BrewingMetadata metadata = BrewingMetadata.read(potion).orElseThrow();
        h.assertTrue(potion.is(Items.POTION) && PotionUtils.getPotion(potion) == Potions.SWIFTNESS, "captured potion identity loads");
        h.assertTrue(metadata.extraction() == 50 && metadata.control() == 99 && metadata.quality() == 87,
                "captured 1.0.2 quality scores load exactly");
        h.assertTrue(metadata.lineage() == -5986715922347762876L && "Dev".equals(metadata.brewerName()),
                "captured lineage and brewer survive");
        CompoundTag potionResaved = potion.save(new CompoundTag());
        h.assertTrue(potionResaved.getCompound("tag").equals(potionFixture.getCompound("tag")),
                "first 1.1 save preserves the complete 1.0.2 potion tag");

        CompoundTag kitFixture = fixture("smoketest_prepared_kit.snbt");
        ItemStack kitStack = LedgerCodec.loadStack(kitFixture);
        KitData kit = KitData.read(kitStack).orElseThrow();
        h.assertTrue(kit.source().is(Items.SUGAR) && kit.groups() == 1 && kit.extraction() == 100,
                "captured prepared kit keeps source, serving groups and extraction");
        h.assertTrue(kit.profile().equals(ImmersiveBrewing.id("sugar")), "captured preparation profile survives");
        h.assertTrue(LedgerCodec.saveStack(kitStack).equals(kitFixture), "first re-save is byte-typed NBT equivalent");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void actualGuideMigratesOnceWithoutDiscardingLegacyResult(GameTestHelper h) {
        CompoundTag guide = fixture("showcase_guide.snbt");
        ItemStack notebook = ItemStack.of(fixture("showcase_notebook.snbt"));
        h.assertTrue(notebook.is(ModItems.BREWERS_NOTEBOOK.get()) && !notebook.hasTag(),
                "the captured dropped 1.0.2 Notebook item remains an ordinary untagged Notebook");
        CompoundTag legacyResult = guide.getCompound("last").copy();
        h.assertTrue(!guide.contains("dataVersion") && !guide.contains("history") && !guide.contains("filter"),
                "fixture is the unmigrated 1.0.2 guide payload");
        PlayerGuide.migrate(guide);
        h.assertTrue(guide.getInt("dataVersion") == PlayerGuide.DATA_VERSION && guide.getByte("servings") == 3,
                "migration installs the current version and the historical three-serving default");
        h.assertTrue(guide.contains("history", Tag.TAG_LIST) && guide.getList("history", Tag.TAG_COMPOUND).isEmpty()
                        && "all".equals(guide.getString("filter")),
                "new journal fields receive safe defaults");
        h.assertTrue(guide.getCompound("last").equals(legacyResult),
                "the complete old result explanation, including phase/addition fields unknown to migration, is unchanged");
        CompoundTag firstSave = parse(guide.toString(), "first guide save");
        PlayerGuide.migrate(firstSave);
        h.assertTrue(firstSave.equals(guide), "a reload and second migration are idempotent after the first save");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void legacyCarrierContentsAndUnrelatedDataSurvivePreferences(GameTestHelper h) {
        ItemStack capturedEmpty = ItemStack.of(fixture("smoketest_empty_carrier.snbt"));
        h.assertTrue(CarrierContents.isEmpty(capturedEmpty), "the retained runtime carrier is accurately represented as empty");
        h.assertTrue(CarrierContents.meta(capturedEmpty).selected() == 0, "a 1.0.2 carrier receives non-destructive preference defaults");

        // No retained 1.0.2 world had a non-empty carrier. This second fixture is explicitly baseline-codec-derived,
        // so it exercises the real 1.0.2 list shape without misrepresenting it as captured runtime inventory.
        ItemStack carrier = ItemStack.of(fixture("baseline_legacy_carrier.snbt"));
        CompoundTag rootBefore = carrier.getTag().copy();
        h.assertTrue(CarrierContents.count(carrier) == 2 && PotionUtils.getPotion(CarrierContents.read(carrier).get(0)) == Potions.SWIFTNESS
                        && PotionUtils.getPotion(CarrierContents.read(carrier).get(4)) == Potions.STRENGTH,
                "the baseline 1.0.2 slot list loads with exact positions and potion tags");
        h.assertTrue(CarrierContents.setSelected(carrier, 4), "current preferences can be added to an old carrier");
        h.assertTrue(carrier.getTag().get(CarrierContents.KEY).equals(rootBefore.get(CarrierContents.KEY))
                        && "preserve".equals(carrier.getTag().getString("baseline_1_0_2_sentinel")),
                "adding preferences does not rewrite contents or unrelated item data");
        ItemStack reloaded = ItemStack.of(carrier.save(new CompoundTag()));
        h.assertTrue(CarrierContents.meta(reloaded).selected() == 4 && CarrierContents.count(reloaded) == 2,
                "the first current-version save reloads with preferences and all real items");
        h.assertTrue(ItemStack.matches(CarrierContents.read(reloaded).get(4), CarrierContents.read(carrier).get(4)),
                "graded carrier contents retain exact 1.0.2 metadata");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void actualSchemaOneLedgerMigratesWithExactMaterialAccounting(GameTestHelper h) {
        CompoundTag old = fixture("smoketest_kettle_ledger.snbt");
        h.assertTrue(old.getInt("schema") == 1, "fixture is the captured 1.0.2 ledger schema");
        JobLedger ledger = LedgerCodec.load(old);
        h.assertTrue(ledger.operation() == Operation.BREW && ledger.state() == JobState.RECOVERY,
                "captured operation and recovery state load");
        h.assertTrue(ledger.inputs().size() == 3 && ledger.inputs().stream().allMatch(s -> s.is(Items.POTION) && s.getCount() == 1),
                "all three owned Water bottles load once");
        h.assertTrue(ledger.reserved().size() == 2 && ledger.reserved().get(0).stack().is(Items.NETHER_WART)
                        && ledger.reserved().get(1).stack().is(Items.SUGAR) && ledger.fuelCharges() == 2,
                "wart, sugar and two fuel charges remain ledger-owned");
        h.assertTrue(ledger.snapshot().getInt("servings") == 3 && ledger.snapshot().getList("steps", Tag.TAG_COMPOUND).size() == 2,
                "plan authority and serving count survive");

        CompoundTag resaved = LedgerCodec.save(ledger);
        h.assertTrue(resaved.getInt("schema") == LedgerCodec.SCHEMA, "first save advances only the ledger schema");
        for (String key : old.getAllKeys()) {
            if (key.equals("schema")) continue;
            h.assertTrue(resaved.contains(key) && resaved.get(key).equals(old.get(key)), "first save preserves captured field " + key);
        }
        h.assertTrue(totalItems(resaved.getList("inputs", Tag.TAG_COMPOUND)) == 3
                        && totalReserved(resaved.getList("reserved", Tag.TAG_COMPOUND)) == 2 && resaved.getInt("fuel") == 2,
                "first save conserves three bottles, two reagents and two fuel charges");
        h.assertTrue(Arrays.equals(resaved.getLongArray("params"), old.getLongArray("params"))
                        && Arrays.equals(resaved.getLongArray("sim"), old.getLongArray("sim")),
                "simulation parameters and earned progress are unchanged");

        CompoundTag future = old.copy();
        future.putInt("schema", LedgerCodec.SCHEMA + 1);
        CompoundTag extension = new CompoundTag();
        extension.putString("owner", "future-version");
        extension.putLong("materialProof", 6L);
        future.put("futureExtension", extension);
        CompoundTag futureResaved = LedgerCodec.save(LedgerCodec.load(future));
        h.assertTrue(futureResaved.equals(future), "an unknown newer ledger and all of its fields are preserved byte-for-byte");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void migrationFixtureProvenanceShipsWithTests(GameTestHelper h) {
        String provenance = resource("provenance.json");
        h.assertTrue(provenance.contains("ModVersion=1.0.2") && provenance.contains("53bcb5b27f2cd123eca5d426bc9bbbdccdbbbb48"),
                "fixtures record runtime version evidence and the baseline commit");
        h.assertTrue(provenance.contains("No retained 1.0.2 world contained a non-empty carrier"),
                "known gaps are stated rather than represented as runtime captures");
        h.succeed();
    }

    private static int totalItems(ListTag stacks) {
        int total = 0;
        for (int i = 0; i < stacks.size(); i++) total += LedgerCodec.loadStack(stacks.getCompound(i)).getCount();
        return total;
    }

    private static int totalReserved(ListTag reserved) {
        int total = 0;
        for (int i = 0; i < reserved.size(); i++) total += LedgerCodec.loadStack(reserved.getCompound(i).getCompound("stack")).getCount();
        return total;
    }

    private static CompoundTag fixture(String name) {
        return parse(resource(name), name);
    }

    private static CompoundTag parse(String text, String description) {
        try {
            return TagParser.parseTag(text);
        } catch (CommandSyntaxException e) {
            throw new IllegalStateException("invalid migration fixture " + description, e);
        }
    }

    private static String resource(String name) {
        try (InputStream in = Migration110GameTests.class.getResourceAsStream(ROOT + name)) {
            if (in == null) throw new IllegalStateException("missing migration fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("could not read migration fixture " + name, e);
        }
    }

    private Migration110GameTests() {}
}
