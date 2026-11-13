package com.otectus.immersivebrewing.testing;

import com.otectus.immersivebrewing.batch.JobLedger;
import com.otectus.immersivebrewing.batch.JobState;
import com.otectus.immersivebrewing.batch.LedgerCodec;
import com.otectus.immersivebrewing.batch.Operation;
import com.otectus.immersivebrewing.batch.SlotStack;
import com.otectus.immersivebrewing.blockentity.AlembicBlockEntity;
import com.otectus.immersivebrewing.blockentity.ApothecaryMortarBlockEntity;
import com.otectus.immersivebrewing.blockentity.BrewersKettleBlockEntity;
import com.otectus.immersivebrewing.blockentity.StationBlockEntity;
import com.otectus.immersivebrewing.config.ServerConfig;
import com.otectus.immersivebrewing.data.BrewingDefinitions;
import com.otectus.immersivebrewing.data.ReagentProfile;
import com.otectus.immersivebrewing.item.KitData;
import com.otectus.immersivebrewing.minigame.brewing.KettleSetup;
import com.otectus.immersivebrewing.minigame.distillation.DistillationParams;
import com.otectus.immersivebrewing.minigame.preparation.PreparationParams;
import com.otectus.immersivebrewing.potion.BrewingMetadata;
import com.otectus.immersivebrewing.recipe.BrewPlan;
import com.otectus.immersivebrewing.session.SessionKind;
import com.otectus.immersivebrewing.session.SimHandle;
import com.otectus.immersivebrewing.session.SimHandles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Batches exactly as Immersive Brewing 1.1 saved them (legacy simulation parameter and state arrays, ledger schema 2,
 * no physical payload). Each builder moves the batch's materials out of the station exactly as the 1.1 start did and
 * returns the ledger; callers write it into the station's NBT and reload it the way a world from 1.1 arrives. New
 * batches can no longer create these; the migration tests prove the legacy-resume path still finishes them
 * (handoff 17). Never shipped.
 */
public final class LegacyBatches {
    /** A 1.1 kettle brew of {@code plan}, its simulation advanced {@code steps} steps by the reference bot, sealed. */
    public static JobLedger brew(BrewersKettleBlockEntity kettle, net.minecraft.server.level.ServerPlayer player, BrewPlan plan, int steps) {
        BrewingDefinitions defs = BrewingDefinitions.server();
        List<ItemStack> bottles = new ArrayList<>();
        for (int i = 0; i < BrewersKettleBlockEntity.RACK_SIZE; i++) {
            ItemStack s = kettle.inventory().getStackInSlot(BrewersKettleBlockEntity.RACK + i);
            if (s.isEmpty()) continue;
            bottles.add(s.copy());
            kettle.inventory().setStackInSlot(BrewersKettleBlockEntity.RACK + i, ItemStack.EMPTY);
        }
        int servings = bottles.size();
        List<SlotStack> reserved = new ArrayList<>();
        ListTag stepTags = new ListTag();
        for (BrewPlan.Step step : plan.steps()) {
            int need = step.batch().reagentCost(servings);
            int units = 0;
            for (int i = 0; i < BrewersKettleBlockEntity.TRAY_SIZE && need > 0; i++) {
                int slot = BrewersKettleBlockEntity.TRAY + i;
                ItemStack s = kettle.inventory().getStackInSlot(slot);
                boolean matches = s.is(step.reagent()) || KitData.read(s).map(k -> k.source().is(step.reagent())).orElse(false);
                if (!matches) continue;
                int take = Math.min(need, s.getCount());
                reserved.add(new SlotStack(slot, s.copyWithCount(take)));
                kettle.inventory().setStackInSlot(slot, s.copyWithCount(s.getCount() - take));
                need -= take;
                units++;
            }
            CompoundTag st = new CompoundTag();
            st.putString("source", step.source().toString());
            st.putString("reagent", String.valueOf(ForgeRegistries.ITEMS.getKey(step.reagent())));
            st.putString("adapter", step.adapter().toString());
            st.putInt("adapterVersion", step.adapterVersion());
            st.put("before", LedgerCodec.saveStack(step.before()));
            st.put("after", LedgerCodec.saveStack(step.after()));
            st.putInt("units", units);
            st.putBoolean("effect", step.spec().createsEffect());
            st.putBoolean("container", step.spec().role() == KettleSetup.Role.CONTAINER);
            st.putInt("applications", step.batch().applications(servings));
            stepTags.add(st);
        }
        CompoundTag snapshot = new CompoundTag();
        snapshot.put("steps", stepTags);
        snapshot.putString("plan", plan.key().serialize());
        snapshot.putString("profile", plan.brewProfile().toString());
        snapshot.putInt("servings", servings);
        snapshot.putBoolean("contextual", plan.contextual());
        snapshot.putFloat("assistTime", 1F);
        snapshot.putFloat("assistBand", 1F);
        BrewingMetadata.read(bottles.get(0)).ifPresent(meta -> snapshot.put("inputMeta", meta.toTag()));
        long seed = 77L;
        long[] params = KettleSetup.build(BrewersKettleBlockEntity.setupPlan(plan, servings, defs), seed,
                ServerConfig.get(ServerConfig.MAX_BREW_DURATION_SECONDS), KettleSetup.Assist.NONE).encode();
        SimHandle sim = SimHandles.create(SessionKind.BREWING, params, null);
        KettleBot bot = KettleBot.competent();
        for (int i = 0; i < steps && !sim.finished(); i++) sim.step(bot.decide(((SimHandles.Brewing) sim).sim()).pack());
        JobLedger ledger = new JobLedger(UUID.randomUUID(), Operation.BREW, JobState.SEALED, bottles, reserved, plan.fuelCharges(servings), snapshot,
                plan.output().copy(), params, sim.saveState(), seed, 50, player.getUUID(), player.getGameProfile().getName(), List.of(), null, 0,
                defs.generation(), 0x5EED1L);
        return ledger;
    }

    /** A 1.1 mortar preparation of the bowl's first {@code kits} units, simulated {@code steps} steps, sealed. */
    public static JobLedger preparation(ApothecaryMortarBlockEntity mortar, net.minecraft.server.level.ServerPlayer player, int kits, int steps) {
        ItemStack input = mortar.inventory().getStackInSlot(ApothecaryMortarBlockEntity.INPUT);
        ReagentProfile profile = mortar.profileFor(input);
        if (profile == null || profile.preparation() == null) throw new IllegalStateException("the fixture needs a profiled reagent");
        ReagentProfile.Preparation prep = profile.preparation();
        long[] params = PreparationParams.of(prep.mode(), prep.durationTicks(), prep.endpointLow(), prep.endpointHigh(), prep.pressureLimit(),
                prep.overworkThreshold(), kits, 1.0).encode();
        ItemStack taken = input.copyWithCount(kits);
        mortar.inventory().setStackInSlot(ApothecaryMortarBlockEntity.INPUT, input.copyWithCount(input.getCount() - kits));
        SimHandle sim = SimHandles.create(SessionKind.PREPARATION, params, null);
        MortarBot bot = MortarBot.competent();
        for (int i = 0; i < steps && !sim.finished(); i++) sim.step(bot.decide(((SimHandles.Preparation) sim).sim()).pack());
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("profile", profile.id().toString());
        snapshot.putInt("kits", kits);
        snapshot.putFloat("assistTime", 1F);
        snapshot.putFloat("assistBand", 1F);
        JobLedger ledger = new JobLedger(UUID.randomUUID(), Operation.PREPARE, JobState.SEALED, List.of(taken), List.of(), 0, snapshot,
                new KitData(taken, 1, profile.id(), 0).toStack(kits), params, sim.saveState(), 9L, 0, player.getUUID(),
                player.getGameProfile().getName(), List.of(), null, 0, BrewingDefinitions.server().generation(), 0);
        return ledger;
    }

    /** A 1.1 Alembic purification of the loaded inputs (they must form a valid group), simulated, sealed. */
    public static JobLedger purification(AlembicBlockEntity alembic, net.minecraft.server.level.ServerPlayer player, int steps) {
        AlembicBlockEntity.PurifyPlan plan = alembic.purifyPlan();
        if (plan.problem() != null || plan.recipe() == null) throw new IllegalStateException("the fixture needs a valid purification: " + plan.problem());
        List<ItemStack> taken = new ArrayList<>();
        for (int i = 0; i < AlembicBlockEntity.INPUT_SIZE; i++) {
            ItemStack s = alembic.inventory().getStackInSlot(AlembicBlockEntity.INPUT + i);
            if (s.isEmpty()) continue;
            taken.add(s.copy());
            alembic.inventory().setStackInSlot(AlembicBlockEntity.INPUT + i, ItemStack.EMPTY);
        }
        long seed = 5L;
        long[] params = DistillationParams.create(seed, 1.0, 1.0).encode();
        SimHandle sim = SimHandles.create(SessionKind.DISTILLATION, params, null);
        DistillBot bot = DistillBot.competent();
        for (int i = 0; i < steps && !sim.finished(); i++) sim.step(bot.decide(((SimHandles.Distillation) sim).sim()).pack());
        CompoundTag snapshot = new CompoundTag();
        snapshot.putString("recipe", plan.recipe().getId().toString());
        snapshot.putInt("groups", plan.groups());
        snapshot.putInt("groupSize", plan.recipe().groupSize());
        snapshot.putInt("yield", plan.recipe().yield());
        snapshot.putInt("cap", Math.min(plan.recipe().qualityCap(), ServerConfig.get(ServerConfig.PURIFICATION_QUALITY_CAP)));
        snapshot.putString("container", String.valueOf(ForgeRegistries.ITEMS.getKey(plan.recipe().container())));
        snapshot.putInt("qualityIn", plan.qualityIn());
        JobLedger ledger = new JobLedger(UUID.randomUUID(), Operation.PURIFY, JobState.SEALED, taken, List.of(),
                plan.recipe().fuelCharges() * plan.groups(), snapshot, plan.sample().copyWithCount(1), params, sim.saveState(), seed, 0,
                player.getUUID(), player.getGameProfile().getName(), List.of(), null, 0, BrewingDefinitions.server().generation(), 0);
        return ledger;
    }

    /** The station's saved NBT with this ledger written as 1.1 wrote it (schema 2). */
    public static CompoundTag withLedger(StationBlockEntity station, JobLedger ledger) {
        CompoundTag saved = station.saveWithFullMetadata();
        CompoundTag ledgerTag = LedgerCodec.save(ledger);
        if (ledgerTag.getInt("schema") != LedgerCodec.LEGACY_SCHEMA) throw new IllegalStateException("a legacy batch must save as schema 2");
        saved.put("Ledger", ledgerTag);
        return saved;
    }

    private LegacyBatches() {}
}
