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
        boolean any = false;
        for (ItemStack s : contents) {
            if (s.isEmpty()) continue;
            any = true;
            Component grade = BrewingMetadata.read(s).<Component>map(m -> Component.translatable(m.grade().key()))
                    .orElse(Component.translatable("grade.immersive_brewing.ordinary"));
            tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.line", s.getCount(), s.getHoverName(), grade)
                    .withStyle(ChatFormatting.GRAY));
        }
        if (!any) tooltip.add(Component.translatable("tooltip.immersive_brewing.carrier.empty").withStyle(ChatFormatting.DARK_GRAY));
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
