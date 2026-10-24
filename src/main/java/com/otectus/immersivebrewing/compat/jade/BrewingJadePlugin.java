package com.otectus.immersivebrewing.compat.jade;

import com.otectus.immersivebrewing.ImmersiveBrewing;
import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.block.StationBlock;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationResult;
import com.otectus.immersivebrewing.event.BrewingStandGuard;
import com.otectus.immersivebrewing.potion.Grade;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaCommonRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

/**
 * Jade overlays (spec 18): concise, server-owned station state (status, servings, fuel charges, controller while active,
 * brewer, last grade, recovery reason) and, in replacement mode, why a brewing stand will not start. Never sends a
 * ledger or nested NBT. Jade finds this class by annotation; nothing else references it.
 */
@WailaPlugin
public final class BrewingJadePlugin implements IWailaPlugin {
    static final ResourceLocation STATION = ImmersiveBrewing.id("station");
    static final ResourceLocation STAND = ImmersiveBrewing.id("stand");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(Station.INSTANCE, StationBlockEntity.class);
        registration.registerBlockDataProvider(Stand.INSTANCE, BrewingStandBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(Station.INSTANCE, StationBlock.class);
        registration.registerBlockComponent(Stand.INSTANCE, BrewingStandBlock.class);
    }

    enum Station implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof StationBlockEntity station)) return;
            CompoundTag tag = new CompoundTag();
            tag.putInt("fuel", station.fuelCharges());
            if (station.controllerName() != null) tag.putString("controller", station.controllerName());
            JobLedger ledger = station.ledger();
            if (ledger != null) {
                tag.putInt("servings", ledger.inputs().stream().mapToInt(ItemStack::getCount).sum());
                if (ledger.brewerName() != null) tag.putString("brewer", ledger.brewerName());
            }
            StationResult last = station.lastResult();
            if (last != null && ledger == null) tag.putByte("grade", (byte) last.grade().ordinal());
            tag.putFloat("progress", station.visual().active() ? station.visual().progress() : -1);
            data.put("immersive_brewing", tag);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof StationBlockEntity station)) return;
            tooltip.add(station.view().status());
            CompoundTag tag = accessor.getServerData().getCompound("immersive_brewing");
            if (tag.contains("servings")) tooltip.add(Component.translatable("jade.immersive_brewing.servings", tag.getInt("servings")));
            if (tag.contains("controller")) tooltip.add(Component.translatable("jade.immersive_brewing.controller", tag.getString("controller")));
            if (tag.contains("brewer")) tooltip.add(Component.translatable("jade.immersive_brewing.brewer", tag.getString("brewer")));
            float progress = tag.getFloat("progress");
            if (progress >= 0 && tag.contains("progress")) tooltip.add(Component.translatable("jade.immersive_brewing.progress", Math.round(progress * 100)));
            if (tag.contains("fuel")) tooltip.add(Component.translatable("jade.immersive_brewing.fuel", tag.getInt("fuel")));
            if (tag.contains("grade")) {
                Grade grade = Grade.values()[Math.max(0, Math.min(Grade.values().length - 1, tag.getByte("grade")))];
                tooltip.add(Component.literal(grade.symbol() + " ").append(Component.translatable(grade.key())));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return STATION;
        }
    }

    enum Stand implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;

        @Override
        public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            if (!(accessor.getBlockEntity() instanceof BrewingStandBlockEntity stand)) return;
            NonNullList<ItemStack> items = NonNullList.withSize(4, ItemStack.EMPTY);
            for (int i = 0; i < 4; i++) items.set(i, stand.getItem(i));
            if (BrewingStandGuard.blocks(items)) data.putBoolean("immersive_brewing_blocked", true);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (accessor.getServerData().getBoolean("immersive_brewing_blocked")) {
                tooltip.add(Component.translatable("jade.immersive_brewing.stand_blocked"));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return STAND;
        }
    }
}
