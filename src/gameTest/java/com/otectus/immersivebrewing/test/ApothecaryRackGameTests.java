package com.otectus.immersivebrewing.test;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity;
import com.otectus.immersivebrewing.item.CarrierContents;
import com.otectus.immersivebrewing.menu.ApothecaryRackMenu;
import com.otectus.immersivebrewing.network.packet.RackLabelC2S;
import com.otectus.immersivebrewing.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraftforge.items.IItemHandler;

import java.util.List;

/** IB-110-07: exact-stack persistence, conservation, once-only drops, automation simulation and menu security. */
@GameTestHolder(ImmersiveBrewing.MOD_ID)
@PrefixGameTestTemplate(false)
public final class ApothecaryRackGameTests {
    private static ApothecaryRackBlockEntity rack(GameTestHelper h) {
        h.setBlock(TestSupport.STATION, ModBlocks.APOTHECARY_RACK.get());
        return (ApothecaryRackBlockEntity) h.getLevel().getBlockEntity(h.absolutePos(TestSupport.STATION));
    }

    @GameTest(template = "empty")
    public static void sixMixedExactStacksSurviveSaveLoad(GameTestHelper h) {
        ApothecaryRackBlockEntity rack = rack(h);
        ItemStack[] expected = new ItemStack[ApothecaryRackBlockEntity.SLOTS];
        for (int i = 0; i < expected.length; i++) {
            expected[i] = CarrierGameTests.gradedSwiftness("Shelf " + i);
            expected[i].getOrCreateTag().putInt("distinct", i);
            rack.inventory().setStackInSlot(i, expected[i].copy());
            rack.setFilter(i, expected[i]);
            rack.setLabel(i, "Dose " + i);
        }
        CompoundTag saved = rack.saveWithFullMetadata();
        ApothecaryRackBlockEntity restored = new ApothecaryRackBlockEntity(rack.getBlockPos(), rack.getBlockState());
        restored.load(saved);
        for (int i = 0; i < expected.length; i++) {
            h.assertTrue(ItemStack.matches(expected[i], restored.inventory().getStackInSlot(i)), "exact stack " + i + " survived");
            h.assertTrue(ItemStack.matches(expected[i], restored.filter(i)) && restored.label(i).equals("Dose " + i), "ghost and label " + i + " survived");
        }
        h.assertTrue(restored.comparatorSignal() == 15, "a full rack gives full comparator output");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void carrierPackingConservesAtCapacityAndFiltersRestock(GameTestHelper h) {
        ApothecaryRackBlockEntity rack = rack(h);
        ItemStack wanted = CarrierGameTests.gradedSwiftness("Wanted");
        ItemStack other = PotionUtils.setPotion(new ItemStack(Items.POTION), Potions.STRENGTH);
        rack.inventory().setStackInSlot(0, wanted.copy());
        ItemStack full = CarrierGameTests.carrier(other.copy(), other.copy(), other.copy(), other.copy(), other.copy(), other.copy());
        h.assertTrue(rack.packInto(full) == 0 && ItemStack.matches(rack.inventory().getStackInSlot(0), wanted)
                && CarrierContents.count(full) == 6, "a full carrier causes no loss or partial overwrite");

        ItemStack carrier = CarrierGameTests.carrier();
        h.assertTrue(rack.packInto(carrier) == 1 && rack.inventory().getStackInSlot(0).isEmpty()
                && ItemStack.matches(CarrierContents.read(carrier).get(0), wanted), "packing moves the exact real stack once");
        rack.setFilter(4, wanted);
        h.assertTrue(rack.restockFrom(carrier) == 1 && ItemStack.matches(rack.inventory().getStackInSlot(4), wanted)
                && CarrierContents.isEmpty(carrier), "an exact ghost restocks its dedicated slot");
        h.succeed();
    }

    @GameTest(template = "empty")
    public static void automationSimulationSecurityAndDropsAreReliable(GameTestHelper h) {
        ApothecaryRackBlockEntity rack = rack(h);
        IItemHandler handler = rack.getCapability(ForgeCapabilities.ITEM_HANDLER, Direction.UP).orElseThrow(IllegalStateException::new);
        ItemStack exact = CarrierGameTests.gradedSwiftness("Automation");
        h.assertTrue(handler.insertItem(0, exact, true).isEmpty() && rack.inventory().getStackInSlot(0).isEmpty(), "simulated insert does not mutate");
        h.assertTrue(handler.insertItem(0, exact, false).isEmpty() && ItemStack.matches(rack.inventory().getStackInSlot(0), exact), "executed insert is exact");
        h.assertTrue(ItemStack.matches(handler.extractItem(0, 1, true), exact) && ItemStack.matches(rack.inventory().getStackInSlot(0), exact),
                "simulated extraction does not mutate");

        FakePlayer player = TestSupport.player(h, "Keeper");
        player.containerMenu = new ApothecaryRackMenu(0, player.getInventory(), rack.getBlockPos());
        h.assertTrue(new RackLabelC2S(rack.getBlockPos(), 0, "Ready").apply(player) && rack.label(0).equals("Ready"), "an open nearby menu may label");
        h.assertTrue(!new RackLabelC2S(rack.getBlockPos().offset(1, 0, 0), 0, "Forged").apply(player) && rack.label(0).equals("Ready"),
                "a forged position cannot edit the rack");

        rack.dropContentsOnce();
        rack.dropContentsOnce();
        List<net.minecraft.world.entity.item.ItemEntity> drops = TestSupport.drops(h, TestSupport.STATION);
        h.assertTrue(drops.stream().filter(e -> ItemStack.matches(e.getItem(), exact)).mapToInt(e -> e.getItem().getCount()).sum() == 1,
                "repeated removal drops contents exactly once");

        CompoundTag future = new CompoundTag();
        future.putInt("schema", ApothecaryRackBlockEntity.SCHEMA + 1);
        future.putString("future", "preserve");
        ApothecaryRackBlockEntity unknown = new ApothecaryRackBlockEntity(rack.getBlockPos(), rack.getBlockState());
        unknown.load(future);
        CompoundTag resaved = unknown.saveWithFullMetadata();
        h.assertTrue(!unknown.readable() && resaved.getString("future").equals("preserve")
                && resaved.getInt("schema") == ApothecaryRackBlockEntity.SCHEMA + 1, "unknown newer rack data is preserved");
        h.succeed();
    }

    private ApothecaryRackGameTests() {}
}
