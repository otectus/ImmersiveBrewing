package com.otectus.immersivebrewing.network.packet;

import com.otectus.immersivebrewing.client.ClientPacketHandler;
import com.otectus.immersivebrewing.minigame.brewing.KettlePhase;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.recipe.PlanKey;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * The plans a Kettle can brew from the bottles in its rack, validated by the server, with exact costs, fuel, returned
 * containers and what is missing (spec 13: show every step and let the player choose among alternatives). Plans whose
 * reagents are loaded come first; the list is bounded.
 */
public record PlanListS2C(BlockPos pos, ItemStack input, int servings, @Nullable Component problem, List<Entry> entries,
                          int generation, int request, int page, int total, boolean incomplete, int omitted, String query) {
    public static final int MAX_ENTRIES = 256;
    /** A deliberate favorite/JEI/staging selection must not be replaced by a remembered search on opening. */
    public static final int SELECTED_ROUTE = -1;
    public PlanListS2C(BlockPos pos, ItemStack input, int servings, Component problem, List<Entry> entries) {
        this(pos,input,servings,problem,entries,0,0,0,entries.size(),false,0,"");
    }

    public record Step(Item reagent, KettlePhase phase, KettleSetup.Role role, ItemStack after) {}

    /** One reagent line: needed for this batch, loaded raw, and serving groups covered by prepared kits. */
    public record Cost(Item item, int needed, int loaded, int kitGroups, boolean preparable) {}

    public record Entry(PlanKey key, ItemStack output, List<Step> steps, List<Cost> costs, int fuelNeeded, int fuelAvailable,
                        List<ItemStack> remainders, boolean available, @Nullable Component missing, boolean contextual, int tier,
                        int seconds, boolean qualityAffectsEffects, int servingLimit, int expectedExtraction,
                        int finishingSeconds, boolean finishReady) {
        public Entry(PlanKey key, ItemStack output, List<Step> steps, List<Cost> costs, int fuelNeeded, int fuelAvailable,
                     List<ItemStack> remainders, boolean available, Component missing, boolean contextual, int tier,
                     int seconds, boolean qualityAffectsEffects, int servingLimit, int expectedExtraction) {
            this(key, output, steps, costs, fuelNeeded, fuelAvailable, remainders, available, missing, contextual,
                    tier, seconds, qualityAffectsEffects, servingLimit, expectedExtraction, 0, false);
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(generation); buf.writeVarInt(request); buf.writeVarInt(page); buf.writeVarInt(total);
        buf.writeBoolean(incomplete); buf.writeVarInt(omitted); buf.writeUtf(query,128);
        buf.writeBlockPos(pos);
        buf.writeItem(input);
        buf.writeByte(servings);
        buf.writeBoolean(problem != null);
        if (problem != null) buf.writeComponent(problem);
        buf.writeVarInt(Math.min(entries.size(), MAX_ENTRIES));
        for (int i = 0; i < Math.min(entries.size(), MAX_ENTRIES); i++) {
            Entry e = entries.get(i);
            e.key().write(buf);
            buf.writeItem(e.output());
            buf.writeByte(e.steps().size());
            for (Step s : e.steps()) {
                buf.writeId(BuiltInRegistries.ITEM, s.reagent());
                buf.writeEnum(s.phase());
                buf.writeEnum(s.role());
                buf.writeItem(s.after());
            }
            buf.writeByte(e.costs().size());
            for (Cost c : e.costs()) {
                buf.writeId(BuiltInRegistries.ITEM, c.item());
                buf.writeVarInt(c.needed());
                buf.writeVarInt(c.loaded());
                buf.writeVarInt(c.kitGroups());
                buf.writeBoolean(c.preparable());
            }
            buf.writeVarInt(e.fuelNeeded());
            buf.writeVarInt(e.fuelAvailable());
            buf.writeByte(e.remainders().size());
            for (ItemStack r : e.remainders()) buf.writeItem(r);
            buf.writeBoolean(e.available());
            buf.writeBoolean(e.missing() != null);
            if (e.missing() != null) buf.writeComponent(e.missing());
            buf.writeBoolean(e.contextual());
            buf.writeByte(e.tier());
            buf.writeVarInt(e.seconds());
            buf.writeBoolean(e.qualityAffectsEffects());
            buf.writeByte(e.servingLimit());
            buf.writeByte(e.expectedExtraction());
            buf.writeByte(e.finishingSeconds());
            buf.writeBoolean(e.finishReady());
        }
    }

    public static PlanListS2C decode(FriendlyByteBuf buf) {
        int generation=buf.readVarInt(), request=buf.readVarInt(), page=buf.readVarInt(), total=buf.readVarInt();
        boolean incomplete=buf.readBoolean(); int omitted=buf.readVarInt(); String query=buf.readUtf(128);
        BlockPos pos = buf.readBlockPos();
        ItemStack input = buf.readItem();
        int servings = buf.readByte();
        Component problem = buf.readBoolean() ? buf.readComponent() : null;
        int n = buf.readVarInt();
        if (n < 0 || n > MAX_ENTRIES) throw new IllegalArgumentException("plan page length");
        List<Entry> entries = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            PlanKey key = PlanKey.read(buf);
            ItemStack output = buf.readItem();
            int stepCount = buf.readUnsignedByte();
            if (stepCount > 8) throw new IllegalArgumentException("plan stepCount bounds");
            List<Step> steps = new ArrayList<>(stepCount);
            for (int j = 0; j < stepCount; j++) {
                steps.add(new Step(buf.readById(BuiltInRegistries.ITEM), buf.readEnum(KettlePhase.class), buf.readEnum(KettleSetup.Role.class), buf.readItem()));
            }
            int costCount = buf.readUnsignedByte();
            if (costCount > 8) throw new IllegalArgumentException("plan costCount bounds");
            List<Cost> costs = new ArrayList<>(costCount);
            for (int j = 0; j < costCount; j++) {
                costs.add(new Cost(buf.readById(BuiltInRegistries.ITEM), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean()));
            }
            int fuelNeeded = buf.readVarInt();
            int fuelAvailable = buf.readVarInt();
            int remCount = buf.readUnsignedByte();
            if (remCount > 32) throw new IllegalArgumentException("plan remCount bounds");
            List<ItemStack> remainders = new ArrayList<>(remCount);
            for (int j = 0; j < remCount; j++) remainders.add(buf.readItem());
            boolean available = buf.readBoolean();
            Component missing = buf.readBoolean() ? buf.readComponent() : null;
            entries.add(new Entry(key, output, steps, costs, fuelNeeded, fuelAvailable, remainders, available, missing, buf.readBoolean(),
                    buf.readByte(), buf.readVarInt(), buf.readBoolean(), buf.readByte(), buf.readByte(), buf.readUnsignedByte(), buf.readBoolean()));
        }
        return new PlanListS2C(pos, input, servings, problem, entries,generation,request,page,total,incomplete,omitted,query);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.planList(this));
    }
}
