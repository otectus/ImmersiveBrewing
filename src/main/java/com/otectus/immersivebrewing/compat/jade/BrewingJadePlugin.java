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
    static final ResourceLocation RACK = ImmersiveBrewing.id("rack");
    static final ResourceLocation STAND = ImmersiveBrewing.id("stand");

    @Override
    public void register(IWailaCommonRegistration registration) {
        registration.registerBlockDataProvider(Station.INSTANCE, StationBlockEntity.class);
        registration.registerBlockDataProvider(Rack.INSTANCE, com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity.class);
        registration.registerBlockDataProvider(Stand.INSTANCE, BrewingStandBlockEntity.class);
    }

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(Station.INSTANCE, StationBlock.class);
        registration.registerBlockComponent(Rack.INSTANCE, com.otectus.immersivebrewing.block.ApothecaryRackBlock.class);
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
            // Hands-on state: what the apparatus shows now, its heat in words, who holds which tool, what is ready.
            CompoundTag workshop = station.buildWorkshopSnapshot();
            for (String kind : new String[] {"kettle", "mortar", "alembic", "finishing"}) {
                if (workshop.contains(kind)) {
                    CompoundTag process = workshop.getCompound(kind);
                    if (process.contains("cue")) tag.putString("cue", process.getString("cue"));
                    if (process.contains("temp")) tag.putInt("heat", com.otectus.immersivebrewing.workshop.KettleProcess.heatBand(process.getByte("temp") / 100.0));
                    if (kind.equals("kettle")) {
                        int ready = 0;
                        for (byte b : process.getByteArray("portions")) if (b == com.otectus.immersivebrewing.workshop.KettleProcess.Portion.READY.ordinal()) ready++;
                        if (ready > 0) tag.putInt("ready", ready);
                    }
                }
            }
            net.minecraft.nbt.ListTag held = workshop.getList("held", net.minecraft.nbt.Tag.TAG_COMPOUND);
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < held.size() && i < 3; i++) {
                String name = held.getCompound(i).getString("name");
                if (!names.toString().contains(name)) names.append(names.length() == 0 ? "" : ", ").append(name);
            }
            if (names.length() > 0) tag.putString("working", names.toString());
            data.put("immersive_brewing", tag);
        }

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof StationBlockEntity station)) return;
            CompoundTag tag = accessor.getServerData().getCompound("immersive_brewing");
            if(!accessor.getPlayer().isShiftKeyDown()) {
                if(tag.contains("cue")) tooltip.add(Component.translatable(tag.getString("cue")));
                else com.otectus.immersivebrewing.blockentity.StationSummary.lines(station.view()).stream().limit(2).forEach(tooltip::add);
                if(tag.contains("heat")) tooltip.add(Component.translatable("jade.immersive_brewing.heat",Component.translatable("heat.immersive_brewing.band."+tag.getInt("heat"))));
                if(tag.contains("ready")) tooltip.add(Component.translatable("jade.immersive_brewing.ready",tag.getInt("ready")));
                if(tag.contains("working")) tooltip.add(Component.translatable("jade.immersive_brewing.working",tag.getString("working")));
                tooltip.add(Component.translatable("tooltip.immersive_brewing.details_hint"));
                return;
            }
            com.otectus.immersivebrewing.blockentity.StationSummary.lines(station.view()).forEach(tooltip::add);
            if (tag.contains("cue")) tooltip.add(Component.translatable(tag.getString("cue")));
            if (tag.contains("heat")) tooltip.add(Component.translatable("jade.immersive_brewing.heat",
                    Component.translatable("heat.immersive_brewing.band." + tag.getInt("heat"))));
            if (tag.contains("working")) tooltip.add(Component.translatable("jade.immersive_brewing.working", tag.getString("working")));
            if (tag.contains("ready")) tooltip.add(Component.translatable("jade.immersive_brewing.ready", tag.getInt("ready")));
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

    enum Rack implements IServerDataProvider<BlockAccessor>, IBlockComponentProvider {
        INSTANCE;
        @Override public void appendServerData(CompoundTag data, BlockAccessor accessor) {
            // Rack contents, reservations and labels already have an authoritative block-entity baseline.
        }
        @Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!(accessor.getBlockEntity() instanceof com.otectus.immersivebrewing.blockentity.ApothecaryRackBlockEntity rack)) return;
            int slot = com.otectus.immersivebrewing.block.ApothecaryRackBlock.targetSlot(rack.getBlockState(), rack.getBlockPos(), accessor.getHitResult());
            tooltip.add(Component.translatable("screen.immersive_brewing.carrier.slot", slot + 1, rack.label(slot)));
            String label = rack.label(slot);
            if (!label.isEmpty()) tooltip.add(Component.literal(label));
            ItemStack stack = rack.inventory().getStackInSlot(slot);
            if (stack.isEmpty()) tooltip.add(Component.translatable(rack.filter(slot).isEmpty()
                    ? "screen.immersive_brewing.carrier.empty" : "state.immersive_brewing.reserved"));
            else {
                tooltip.add(Component.translatable("screen.immersive_brewing.result.made", stack.getCount(), stack.getHoverName()));
                com.otectus.immersivebrewing.potion.BrewingMetadata.read(stack).ifPresent(meta ->
                        tooltip.add(Component.translatable(Grade.of(meta.quality()).key())));
            }
        }
        @Override public ResourceLocation getUid() { return RACK; }
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
