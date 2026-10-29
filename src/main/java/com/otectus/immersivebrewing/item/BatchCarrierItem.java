package com.otectus.immersivebrewing.item;

import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.menu.BatchCarrierMenu;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.item.alchemy.PotionUtils;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.network.NetworkHooks;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Reusable transport for up to six potion stacks (spec 10). It holds exact stacks, has no effect of its own and can not
 * enter a recipe. Use it in the air to open it; use it on a Kettle or Alembic to load an idle rack or collect finished
 * bottles. Hoppers see a narrow six-slot handler when automation is enabled.
 */
public class BatchCarrierItem extends Item {
    /** Root tag flag of the leather-strapped look (Immersive Tanning recipe): presentation only, never behaviour. */
    public static final String STRAPPED = "Strapped";

    public BatchCarrierItem(Properties properties) {
        super(properties);
    }

    public static boolean strapped(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.getBoolean(STRAPPED);
    }

    public enum EquipResult { EQUIPPED, EMPTY_SELECTION, NO_SAFE_HOTBAR_SLOT, UNREADABLE_DATA, NOT_A_CARRIER }

    /**
     * Moves one real selected item into the hotbar. A full hotbar is changed only by a lossless swap with another
     * carrier-storable stack; arbitrary items are never deleted or pushed into a full inventory.
     */
    public static EquipResult equipSelected(Player player, InteractionHand hand) {
        ItemStack carrier = player.getItemInHand(hand);
        if (!(carrier.getItem() instanceof BatchCarrierItem)) return EquipResult.NOT_A_CARRIER;
        if (!CarrierContents.metadataWritable(carrier)) return EquipResult.UNREADABLE_DATA;
        CarrierContents.Meta meta = CarrierContents.meta(carrier);
        List<ItemStack> contents = CarrierContents.read(carrier);
        int selected = meta.selected();
        ItemStack source = contents.get(selected);
        if (source.isEmpty() && meta.advanceOnEmpty()) {
            selected = nextSafe(contents, selected);
            if (selected >= 0) source = contents.get(selected);
        }
        if (source.isEmpty()) return EquipResult.EMPTY_SELECTION;

        int carrierHotbar = hand == InteractionHand.MAIN_HAND ? player.getInventory().selected : -1;
        int target = -1;
        for (int i = 0; i < 9; i++) {
            if (i != carrierHotbar && player.getInventory().getItem(i).isEmpty()) {
                target = i;
                break;
            }
        }
        ItemStack replacement = ItemStack.EMPTY;
        if (target < 0 && source.getCount() == 1) {
            int preferred = player.getInventory().selected;
            if (preferred != carrierHotbar && CarrierContents.accepts(player.getInventory().getItem(preferred))) target = preferred;
            if (target < 0) {
                for (int i = 0; i < 9; i++) {
                    if (i != carrierHotbar && CarrierContents.accepts(player.getInventory().getItem(i))) {
                        target = i;
                        break;
                    }
                }
            }
            if (target >= 0) replacement = player.getInventory().getItem(target).copy();
        }
        if (target < 0) return EquipResult.NO_SAFE_HOTBAR_SLOT;

        ItemStack equipped = source.copyWithCount(1);
        contents.set(selected, replacement.isEmpty() ? source.copyWithCount(source.getCount() - 1) : replacement);
        CarrierContents.write(carrier, contents);
        player.getInventory().setItem(target, equipped);
        player.getInventory().selected = target;
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.send(new net.minecraft.network.protocol.game.ClientboundSetCarriedItemPacket(target));
        }
        player.getInventory().setChanged();

        int nextSelected = selected;
        if (contents.get(selected).isEmpty() && meta.advanceOnEmpty()) {
            int next = nextSafe(contents, selected);
            if (next >= 0) nextSelected = next;
        }
        if (nextSelected != meta.selected()) CarrierContents.setSelected(carrier, nextSelected);
        return EquipResult.EQUIPPED;
    }

    /** Auto-advance never chooses a stack carrying a harmful potion effect. Manual selection remains explicit. */
    private static int nextSafe(List<ItemStack> contents, int after) {
        for (int step = 1; step <= CarrierContents.SLOTS; step++) {
            int slot = (after + step) % CarrierContents.SLOTS;
            ItemStack stack = contents.get(slot);
            if (!stack.isEmpty() && PotionUtils.getMobEffects(stack).stream()
                    .noneMatch(effect -> effect.getEffect().getCategory() == MobEffectCategory.HARMFUL)) return slot;
        }
        return -1;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer sp) {
            NetworkHooks.openScreen(sp, new SimpleMenuProvider((id, inventory, p) -> new BatchCarrierMenu(id, inventory, hand),
                    stack.getHoverName()), buf -> buf.writeEnum(hand));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        List<ItemStack> contents = CarrierContents.read(stack);
        CarrierContents.Meta meta = CarrierContents.meta(stack);
        if (meta.loadoutName() != null) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.loadout", meta.loadoutName())
                .withStyle(ChatFormatting.GOLD));
        boolean any = false;
        int shown = 0;
        int missing = 0;
        for (int i = 0; i < contents.size(); i++) {
            ItemStack s = contents.get(i);
            if (CarrierContents.isMissing(stack, i)) missing++;
            if (s.isEmpty()) continue;
            any = true;
            if (shown++ >= 3) continue;
            Component grade = BrewingMetadata.read(s).<Component>map(m -> Component.translatable(m.grade().key()))
                    .orElse(Component.translatable("grade.immersive_brewing.ordinary"));
            tooltip.add(Component.translatable(i == meta.selected() ? "tooltip.immersive_brewing.carrier.selected_line" : "tooltip.immersive_brewing.carrier.line",
                            s.getCount(), s.getHoverName(), grade)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!any) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.empty").withStyle(ChatFormatting.DARK_GRAY));
        else if (shown > 3) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.more", shown - 3).withStyle(ChatFormatting.DARK_GRAY));
        if (missing > 0) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.missing", missing).withStyle(ChatFormatting.RED));
        if (strapped(stack)) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.strapped").withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.use").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public boolean canFitInsideContainerItems() {
        return false;
    }

    @Override
    public @Nullable ICapabilityProvider initCapabilities(ItemStack stack, @Nullable CompoundTag nbt) {
        return new ICapabilityProvider() {
            private final LazyOptional<CarrierItemHandler> handler = LazyOptional.of(() -> new CarrierItemHandler(stack));

            @Override
            public <T> @NotNull LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
                if (cap == ForgeCapabilities.ITEM_HANDLER && ServerConfig.get(ServerConfig.ALLOW_AUTOMATION_INPUTS)) return handler.cast();
                return LazyOptional.empty();
            }
        };
    }
}
