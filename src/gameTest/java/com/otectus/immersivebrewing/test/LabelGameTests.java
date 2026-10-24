package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.network.packet.SetLabelC2S;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.potion.PotionContentsService;
import com.otectus.immersivebrewing.testing.KettleBot;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
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
 * Milestone 4 (spec 10): labels and notes are literal text of at most 48 and 120 code points with no control or
 * formatting characters; the server rejects (never edits) anything else; labels never change identity, quality or
 * power, and clearing one keeps the result and attribution. The batch just finished in a Kettle can be labelled whole.
 */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LabelGameTests {
    static ItemStack graded() {
        ItemStack stack = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS);
        BrewingMetadata.write(stack, new BrewingMetadata(80, 84, 83, 0x9L, 0, null, "Jade", null, null, null));
        return stack;
    }

    static void label(FakePlayer player, String label, String note) {
        new SetLabelC2S(false, BlockPos.ZERO, InteractionHand.MAIN_HAND, label, note).apply(player);
    }

    @GameTest(template = "empty")
    public static void textIsValidatedNotEdited(GameTestHelper h) {
        h.assertTrue(BrewingMetadata.Text.valid("Roadwalker's Reserve", BrewingMetadata.MAX_LABEL), "an ordinary label");
        h.assertTrue(BrewingMetadata.Text.valid("x".repeat(48), BrewingMetadata.MAX_LABEL), "48 code points");
        h.assertTrue(!BrewingMetadata.Text.valid("x".repeat(49), BrewingMetadata.MAX_LABEL), "not 49");
        h.assertTrue(BrewingMetadata.Text.valid("🍵".repeat(48), BrewingMetadata.MAX_LABEL), "48 astral code points (96 chars) are 48");
        h.assertTrue(!BrewingMetadata.Text.valid("bell\u0007", BrewingMetadata.MAX_LABEL), "no control characters");
        h.assertTrue(!BrewingMetadata.Text.valid("line\nbreak", BrewingMetadata.MAX_LABEL), "no line breaks");
        h.assertTrue(!BrewingMetadata.Text.valid("§cRed", BrewingMetadata.MAX_LABEL), "no legacy formatting codes");
        h.assertTrue(BrewingMetadata.Text.valid("n".repeat(120), BrewingMetadata.MAX_NOTE) && !BrewingMetadata.Text.valid("n".repeat(121), BrewingMetadata.MAX_NOTE),
                "notes stop at 120");

        FakePlayer player = TestSupport.player(h, "Labeller");
        ItemStack potion = graded();
        List<MobEffectInstance> before = PotionContentsService.effectiveEffects(potion, BrewingDefinitions.server());
        player.setItemInHand(InteractionHand.MAIN_HAND, potion);
        label(player, "Roadwalker's Reserve", "for the long road");
        ItemStack held = player.getMainHandItem();
        BrewingMetadata meta = BrewingMetadata.read(held).orElseThrow();
        h.assertTrue("Roadwalker's Reserve".equals(meta.label()) && "for the long road".equals(meta.note()), "label and note set");
        h.assertTrue(meta.quality() == 83 && "Jade".equals(meta.brewerName()) && meta.lineage() == 0x9L, "quality and attribution untouched");
        h.assertTrue(PotionContentsService.sameCanonical(held, graded()), "recipe identity untouched");
        List<MobEffectInstance> after = PotionContentsService.effectiveEffects(held, BrewingDefinitions.server());
        h.assertTrue(after.size() == before.size() && after.get(0).getDuration() == before.get(0).getDuration(), "power untouched");

        label(player, "{\"text\":\"x\",\"clickEvent\":{}}", "");
        h.assertTrue(BrewingMetadata.read(player.getMainHandItem()).orElseThrow().label().startsWith("{\"text\""), "JSON is stored as literal text");

        label(player, "x".repeat(49), "");
        h.assertTrue(BrewingMetadata.read(player.getMainHandItem()).orElseThrow().label().startsWith("{\"text\""), "an over-long label is rejected whole");
        label(player, "bad\u0007", "");
        h.assertTrue(BrewingMetadata.read(player.getMainHandItem()).orElseThrow().label().startsWith("{\"text\""), "so is a control character");

        label(player, "", "");
        BrewingMetadata cleared = BrewingMetadata.read(player.getMainHandItem()).orElseThrow();
        h.assertTrue(cleared.label() == null && cleared.note() == null && cleared.quality() == 83 && "Jade".equals(cleared.brewerName()),
                "clearing keeps the result and attribution");

        player.setItemInHand(InteractionHand.MAIN_HAND, PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.SWIFTNESS));
        label(player, "Nope", "");
        h.assertTrue(BrewingMetadata.read(player.getMainHandItem()).isEmpty(), "an ordinary potion gains no brewing data from a label");
        h.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 1600)
    public static void theLastBatchCanBeLabelledWhole(GameTestHelper h) {
        BrewersKettleBlockEntity kettle = TestSupport.kettle(h);
        TestSupport.load(kettle, 3, 1, new ItemStack(Items.NETHER_WART), new ItemStack(Items.SUGAR));
        FakePlayer player = TestSupport.player(h, "Namer");
        TestSupport.openKettle(player, kettle);
        kettle.startBrew(player, TestSupport.plan(h, kettle, KettleGameTests.SWIFTNESS).key(), 1F, 1F);
        TestSupport.driveKettle(h, player, KettleBot.competent());
        h.startSequence().thenWaitUntil(() -> h.assertTrue(kettle.ledger() == null, "still brewing")).thenExecute(() -> {
            int quality = BrewingMetadata.read(kettle.inventory().getStackInSlot(RACK)).orElseThrow().quality();
            TestSupport.openKettle(player, kettle);
            new SetLabelC2S(true, kettle.getBlockPos(), InteractionHand.MAIN_HAND, "House Swift", "").apply(player);
            for (int i = 0; i < 3; i++) {
                BrewingMetadata meta = BrewingMetadata.read(kettle.inventory().getStackInSlot(RACK + i)).orElseThrow();
                h.assertTrue("House Swift".equals(meta.label()) && meta.quality() == quality, "bottle " + i + " labelled, quality unchanged");
            }
            h.assertTrue(kettle.comparatorSignal() > 0, "still finished output after labelling");
        }).thenSucceed();
    }

    private LabelGameTests() {}
}
